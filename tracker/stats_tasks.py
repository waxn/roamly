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

from django.utils import timezone

logger = logging.getLogger(__name__)

_threads = {}            # user_id -> on-demand compute thread (in-process guard)
_scheduler_thread = None
SCHEDULER_CHECK_INTERVAL = 900   # 15 min between nightly sweeps


def compute_snapshot(user_id):
    """Recompute and store the whole snapshot for one user. Atomically claims the
    row first so concurrent workers / the button can't double-run."""
    from django.contrib.auth.models import User
    from .models import Location, StatsSnapshot
    from .views import (
        _compute_overview_from_qs, _compute_distance_from_qs,
        _compute_visits_from_qs, _compute_yearly_payload, _compute_places_payload,
    )

    snap, _ = StatsSnapshot.objects.get_or_create(user_id=user_id)
    claimed = StatsSnapshot.objects.filter(pk=snap.pk).exclude(status='running').update(
        status='running', started_at=timezone.now(), error='',
    )
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

        StatsSnapshot.objects.filter(pk=snap.pk).update(
            stats_json=overview, visits_json=visits, yearly_json=yearly,
            places_json=places, status='done', error='',
            computed_at=timezone.now(),
        )
        logger.info(f"Stats snapshot computed for user {user_id}")
    except Exception as e:
        logger.exception(f"Stats snapshot failed for user {user_id}")
        StatsSnapshot.objects.filter(pk=snap.pk).update(status='error', error=str(e)[:500])


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
    snap = StatsSnapshot.objects.filter(user_id=user_id).first()
    if not snap:
        return {'status': 'idle', 'computed_at': None, 'error': ''}
    return {
        'status': snap.status,
        'computed_at': snap.computed_at.isoformat() if snap.computed_at else None,
        'error': snap.error or '',
    }


def _stats_scheduler_loop():
    """Refresh every user's snapshot once per local day. The first sweep after
    midnight recomputes everyone; later sweeps skip users already done today, so
    it's restart-safe (no missed-cron, no duplicate runs)."""
    from .models import StatsSnapshot, Location

    while True:
        try:
            today = timezone.localdate()
            user_ids = list(
                Location.objects.values_list('device__user_id', flat=True).distinct()
            )
            for uid in user_ids:
                if uid is None:
                    continue
                snap = StatsSnapshot.objects.filter(user_id=uid).first()
                if snap and snap.status == 'running':
                    continue
                if snap and snap.computed_at and timezone.localtime(snap.computed_at).date() >= today:
                    continue
                compute_snapshot(uid)  # sequential; atomic claim dedupes across workers
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
