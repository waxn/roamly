"""Nightly precomputation of the heavy Stats / Visits / Places payloads.

The Stats, Visits and Places pages aggregate the user's *entire* location
history (dwell-time per city, gated distance, per-place radius scans). Doing that
live on every visit is slow — and because a tracking phone pushes points
constantly, the per-user response cache (`cache_gen`) is busted continuously, so
those pages almost never hit a warm cache.

This module computes everything once and stores it on the per-user
``StatsSnapshot`` row, which the API views serve instantly. It is **decoupled
from cache_gen on purpose**: snapshots refresh once a night (the scheduler below)
or on demand (the "recalculate" button), not on every push.

No external scheduler/queue — mirrors ``backup_tasks``: a daemon thread started
from ``apps.ready()`` loops and runs due work.
"""

import time
import logging
import threading
from contextlib import contextmanager
from datetime import timedelta

from django.db import connection
from django.utils import timezone

logger = logging.getLogger(__name__)

_threads = {}            # user_id -> on-demand compute thread (in-process guard)
_scheduler_thread = None
SCHEDULER_CHECK_INTERVAL = 900   # 15 min between nightly sweeps
# A snapshot whose compute thread was killed mid-run (process restart/redeploy —
# the daemon thread dies with the process) is left status='running' in the DB
# forever. Without recovery the scheduler skips it for good and every Stats
# request falls back to a full live whole-history recompute. Treat a 'running'
# row older than this as dead and reclaimable. (SQLite-only fallback guard — on
# PostgreSQL the advisory lock below is the real single-flight guard.)
STALE_RUNNING_S = 1800           # 30 min

# Advisory-lock namespace (first of the two int4 keys) so our per-user compute
# locks never collide with other pg_advisory_lock users in the same database.
_LOCK_NAMESPACE = 0x52414d4c       # 'RAML'


@contextmanager
def _user_compute_lock(user_id):
    """Yield ``True`` iff this thread holds the exclusive compute lock for ``user_id``.

    On PostgreSQL this is a **session advisory lock** — the true single-flight
    guard. It guarantees at most one heavy snapshot compute per user across all
    gunicorn workers and threads *regardless of how long the compute takes*, and
    PostgreSQL auto-releases it if the holding connection/process dies, so a
    killed compute can never wedge it (unlike a status='running' DB row). This is
    what stops concurrent full-history recomputes from piling up into a CPU /
    web↔db-traffic storm.

    On SQLite (no advisory locks) it is a no-op that always yields ``True``; the
    atomic status claim in ``compute_snapshot`` is the dedupe guard there.
    """
    if connection.vendor != 'postgresql':
        yield True
        return
    got = False
    try:
        with connection.cursor() as cur:
            cur.execute("SELECT pg_try_advisory_lock(%s, %s)", [_LOCK_NAMESPACE, user_id])
            got = bool(cur.fetchone()[0])
        yield got
    finally:
        if got:
            with connection.cursor() as cur:
                cur.execute("SELECT pg_advisory_unlock(%s, %s)", [_LOCK_NAMESPACE, user_id])


def compute_snapshot(user_id):
    """Recompute and store the whole snapshot for one user. Holds a per-user
    single-flight lock (PostgreSQL advisory lock; SQLite atomic claim) for the
    whole compute so concurrent workers / the recalculate button can't double-run
    — overlapping full-history recomputes are what pin CPU and saturate web↔db."""
    from django.db.models import Q
    from django.contrib.auth.models import User
    from .models import Location, StatsSnapshot
    from .views import (
        _compute_overview_from_qs, _compute_distance_from_qs,
        _compute_visits_from_qs, _compute_yearly_payload, _compute_places_payload,
    )

    # Resolve the row's pk without dragging the (potentially multi-MB) JSON
    # columns over the wire — we only need it to claim the row.
    snap_id = StatsSnapshot.objects.filter(user_id=user_id).values_list('id', flat=True).first()
    if snap_id is None:
        snap_id = StatsSnapshot.objects.get_or_create(user_id=user_id)[0].pk

    # Single-flight guard. On PostgreSQL the advisory lock makes overlapping
    # full-history recomputes structurally impossible — no matter how long a
    # compute runs — which is what prevents the concurrent-recompute CPU / db
    # traffic storm. Bail instantly if another worker already holds it.
    with _user_compute_lock(user_id) as got_lock:
        if not got_lock:
            return  # another worker is already computing this user

        if connection.vendor == 'postgresql':
            # We definitively hold the exclusive lock, so just record state; no
            # need to guess at staleness. This also self-heals a row left
            # 'running' by a killed process the instant its lock frees.
            StatsSnapshot.objects.filter(pk=snap_id).update(
                status='running', started_at=timezone.now(), error='')
        else:
            # SQLite has no advisory lock: fall back to an atomic DB claim that
            # takes the row only if it isn't freshly running. This both dedupes
            # concurrent threads and self-heals a stale 'running' claim.
            stale_cutoff = timezone.now() - timedelta(seconds=STALE_RUNNING_S)
            claimed = StatsSnapshot.objects.filter(pk=snap_id).filter(
                ~Q(status='running') | Q(started_at__isnull=True) | Q(started_at__lt=stale_cutoff)
            ).update(status='running', started_at=timezone.now(), error='')
            if not claimed:
                return  # already being computed elsewhere

        try:
            user = User.objects.get(id=user_id)
            all_qs = Location.objects.filter(device__user=user)

            overview = _compute_overview_from_qs(all_qs, user)
            overview['distance'] = _compute_distance_from_qs(all_qs, 'daily')
            visits = _compute_visits_from_qs(all_qs.exclude(city=''))
            yearly = _compute_yearly_payload(user)
            places = _compute_places_payload(user)

            StatsSnapshot.objects.filter(pk=snap_id).update(
                stats_json=overview, visits_json=visits, yearly_json=yearly,
                places_json=places, status='done', error='',
                computed_at=timezone.now(),
            )
            logger.info(f"Stats snapshot computed for user {user_id}")
        except Exception as e:
            logger.exception(f"Stats snapshot failed for user {user_id}")
            StatsSnapshot.objects.filter(pk=snap_id).update(status='error', error=str(e)[:500])


def start_stats_compute(user_id):
    """Kick an on-demand recompute in the background (the recalculate button)."""
    t = _threads.get(user_id)
    if t is not None and t.is_alive():
        return 'running'
    t = threading.Thread(target=compute_snapshot, args=(user_id,), daemon=True)
    _threads[user_id] = t
    t.start()
    return 'started'


def get_status(user_id):
    from .models import StatsSnapshot
    # Only the small status columns — never pull the JSON blobs (this is polled
    # every 2s by the recalculate bar; SELECT *'ing the multi-MB payload here was
    # a major source of web↔db traffic).
    snap = (StatsSnapshot.objects
            .filter(user_id=user_id)
            .values('status', 'computed_at', 'error')
            .first())
    if not snap:
        return {'status': 'idle', 'computed_at': None, 'error': ''}
    return {
        'status': snap['status'],
        'computed_at': snap['computed_at'].isoformat() if snap['computed_at'] else None,
        'error': snap['error'] or '',
    }


def _stats_scheduler_loop():
    """Refresh every user's snapshot once per local day. The first sweep after
    midnight recomputes everyone; later sweeps skip users already done today, so
    it's restart-safe (no missed-cron, no duplicate runs)."""
    from .models import StatsSnapshot, Location

    while True:
        try:
            today = timezone.localdate()
            # NOTE the .order_by(): Location has Meta.ordering = ['-timestamp'],
            # and a values_list(...).distinct() inherits it — which forces the
            # ordering column into the SELECT and *breaks* the DISTINCT, so this
            # returned one row per timestamp (~700k rows, almost all the same
            # user) instead of one row per user. The sweep then looped hundreds of
            # thousands of times per pass, never reaching the sleep below, pinning
            # CPU across every worker and streaming the whole table over the wire.
            # order_by() clears the inherited ordering so DISTINCT is real.
            user_ids = list(
                Location.objects.order_by()
                .values_list('device__user_id', flat=True)
                .distinct()
            )
            for uid in user_ids:
                if uid is None:
                    continue
                # Only the computed_at column — never drag the JSON payload per
                # user/sweep. If it was already refreshed today, skip it.
                snap = (StatsSnapshot.objects
                        .filter(user_id=uid)
                        .values('computed_at')
                        .first())
                if snap and snap['computed_at'] and timezone.localtime(snap['computed_at']).date() >= today:
                    continue
                # compute_snapshot's advisory lock makes this a cheap no-op if a
                # compute is already in flight (this or another worker), so we no
                # longer need a fragile time-based "is it running" guess here.
                compute_snapshot(uid)
        except Exception:
            logger.exception("Stats scheduler error")

        time.sleep(SCHEDULER_CHECK_INTERVAL)


def start_stats_scheduler():
    """Start the nightly snapshot scheduler thread (called once on app startup)."""
    global _scheduler_thread
    if _scheduler_thread is not None and _scheduler_thread.is_alive():
        return
    _scheduler_thread = threading.Thread(target=_stats_scheduler_loop, daemon=True)
    _scheduler_thread.start()
    logger.info("Stats snapshot scheduler started")
