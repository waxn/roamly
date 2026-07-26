"""Fill holes in a device's track with routed, clearly-marked inferred points.

Two entry points sharing one implementation:

  * a manual job (Settings → Background Jobs), mirroring transport_tasks.py
  * an opt-in nightly sweep, mirroring summary_email_tasks.py

A "gap" is two consecutive fixes far enough apart in *both* time and space that
the track visibly jumps. For each one we ask tracker.roads for the road route
between the anchors and lay points along it at constant speed — the average speed
the gap implies — so a multi-minute hole becomes the drive it probably was rather
than a straight line through the countryside.

Everything written goes to InferredLocation, never Location, so no aggregate in
the app (distance, visits, stats snapshots, fog, geocoding, transport, backups)
sees these points. They exist for the map and the audit view only.

Idempotency and user intent both live on InferredGap: the (device, start, end)
anchor triple is unique, so re-runs are no-ops, and a gap the user deletes is
kept as status='dismissed' so the nightly sweep never refills it.
"""

import logging
import threading
import time
from contextlib import contextmanager
from datetime import timedelta

from django.db import close_old_connections, connection
from django.utils import timezone

from . import roads

logger = logging.getLogger(__name__)

_running_threads = {}
_scheduler_thread = None
SCHEDULER_CHECK_INTERVAL = 3600   # 1 hour between sweeps

# Advisory-lock namespace (first int4 key). Deliberately DIFFERENT from
# stats_tasks ('RAML'), summary_email_tasks ('RAMS') and log_cleanup_tasks
# ('RAMG') so gap-fill locks can never collide with theirs.
_LOCK_NAMESPACE = 0x52414d46      # 'RAMF'

# Upper bound on a fillable gap. Beyond a few hours the "route I probably took"
# premise stops holding — you may have driven anywhere, or not driven at all.
_GAP_MAX_S = 6 * 3600
# Below this the anchors are effectively the same place: a stationary phone
# whose fixes simply stopped for a while is not a journey to reconstruct.
_GAP_MIN_DIST_M = 250.0
# Above this implied speed it is a flight, not a road journey. ~216 km/h.
_GAP_MAX_MPS = 60.0
# Spacing of generated points, and a hard ceiling per gap so one long hole can't
# dump thousands of rows.
_FILL_INTERVAL_S = 60
_MAX_POINTS_PER_GAP = 500
_BATCH_SIZE = 20000


# ---------------------------------------------------------------------------
# Gap detection
# ---------------------------------------------------------------------------

def _detect_gaps(user_id, min_gap_s, since=None):
    """Yield fillable gaps for every device belonging to the user.

    Paginates on (timestamp, id) rather than id alone — imported history (CSV,
    GPX, Takeout) gets ids in file order, which need not match time order, so an
    id cursor would silently skip points. Same reasoning as transport_tasks.
    """
    from django.db.models import Q
    from .models import Device, InferredGap, Location

    for device in Device.objects.filter(user_id=user_id):
        # Every gap already on record — including dismissed ones, which must
        # stay dismissed.
        known = set(
            InferredGap.objects.filter(device=device)
            .values_list('start_location_id', 'end_location_id')
        )

        base = Location.objects.filter(device=device)
        if since is not None:
            base = base.filter(timestamp__gte=since)

        last = None
        last_ts = last_id = None
        while True:
            qs = base
            if last_ts is not None:
                qs = qs.filter(Q(timestamp__gt=last_ts)
                               | Q(timestamp=last_ts, id__gt=last_id))
            chunk = list(
                qs.order_by('timestamp', 'id')
                .values('id', 'latitude', 'longitude', 'timestamp', 'transport_mode')[:_BATCH_SIZE]
            )
            if not chunk:
                break

            for cur in chunk:
                if last is not None:
                    gap = _classify_pair(last, cur, min_gap_s)
                    if gap and (last['id'], cur['id']) not in known:
                        gap['device'] = device
                        yield gap
                last = cur

            last_ts, last_id = chunk[-1]['timestamp'], chunk[-1]['id']
            if len(chunk) < _BATCH_SIZE:
                break


def _classify_pair(a, b, min_gap_s):
    """Decide whether a consecutive pair is a gap worth filling."""
    if a['latitude'] is None or b['latitude'] is None:
        return None
    dt = (b['timestamp'] - a['timestamp']).total_seconds()
    if dt < min_gap_s or dt > _GAP_MAX_S:
        return None
    dist = roads._haversine_m(a['longitude'], a['latitude'],
                              b['longitude'], b['latitude'])
    if dist < _GAP_MIN_DIST_M:
        return None

    gap = {
        'start_location_id': a['id'],
        'end_location_id': b['id'],
        'start_time': a['timestamp'],
        'end_time': b['timestamp'],
        'a': (a['longitude'], a['latitude']),
        'b': (b['longitude'], b['latitude']),
        'distance_m': dist,
        # Reuse the transport mode the detection job already worked out so the
        # route uses the right profile (a walk shouldn't be routed as a car).
        'mode': a.get('transport_mode') or b.get('transport_mode') or '',
    }
    if dist / dt > _GAP_MAX_MPS or gap['mode'] == 'plane':
        gap['skip'] = 'implied speed too high for a road journey'
    return gap


# ---------------------------------------------------------------------------
# Filling
# ---------------------------------------------------------------------------

def _points_along(route, start_dt, end_dt, interval_s=_FILL_INTERVAL_S):
    """Space points along `route` so the traversal is at constant average speed.

    The user's actual request: work out how long the trip took, and lay the
    points out along the road accordingly. Endpoints are excluded — the real
    fixes already sit there.
    """
    cum = [0.0]
    for i in range(len(route) - 1):
        cum.append(cum[-1] + roads._haversine_m(
            route[i][0], route[i][1], route[i + 1][0], route[i + 1][1]))
    total = cum[-1]
    if total <= 0:
        return [], 0.0

    dt_total = (end_dt - start_dt).total_seconds()
    n = int(dt_total // interval_s) - 1
    if n < 1:
        return [], total
    if n > _MAX_POINTS_PER_GAP:
        n = _MAX_POINTS_PER_GAP
    step = dt_total / (n + 1)

    out = []
    seg = 0
    for k in range(1, n + 1):
        elapsed = step * k
        target = (elapsed / dt_total) * total
        while seg < len(cum) - 2 and cum[seg + 1] < target:
            seg += 1
        span = cum[seg + 1] - cum[seg]
        f = 0.0 if span <= 0 else (target - cum[seg]) / span
        lng = route[seg][0] + (route[seg + 1][0] - route[seg][0]) * f
        lat = route[seg][1] + (route[seg + 1][1] - route[seg][1]) * f
        out.append((lng, lat, start_dt + timedelta(seconds=elapsed)))
    return out, total


def _fill_gap(profile, gap):
    """Route one gap and persist it. Returns True when road-routed."""
    from .models import InferredGap, InferredLocation

    record = {
        'start_time': gap['start_time'],
        'end_time': gap['end_time'],
        'distance_m': gap['distance_m'],
        'provider': profile.road_provider_resolved,
    }

    if gap.get('skip'):
        InferredGap.objects.update_or_create(
            device=gap['device'],
            start_location_id=gap['start_location_id'],
            end_location_id=gap['end_location_id'],
            defaults={**record, 'status': 'skipped', 'detail': gap['skip'],
                      'point_count': 0, 'route_distance_m': 0},
        )
        return False

    route = None
    status = 'straight'
    detail = ''
    try:
        route = roads.route_between(profile, gap['a'], gap['b'], gap['mode'])
        if route:
            status = 'filled'
        else:
            detail = 'no road route found; interpolated a straight line'
    except roads.RoadProviderError as exc:
        status = 'failed'
        detail = str(exc)[:200]
    except Exception:
        logger.exception('Gap routing crashed')
        status = 'failed'
        detail = 'routing failed unexpectedly'

    if status == 'failed':
        InferredGap.objects.update_or_create(
            device=gap['device'],
            start_location_id=gap['start_location_id'],
            end_location_id=gap['end_location_id'],
            defaults={**record, 'status': status, 'detail': detail,
                      'point_count': 0, 'route_distance_m': 0},
        )
        return False

    # A straight line is still better than a track that teleports, and the row
    # is labelled 'straight' so the audit view can show why.
    if not route:
        route = [gap['a'], gap['b']]

    pts, route_len = _points_along(route, gap['start_time'], gap['end_time'])

    obj, _created = InferredGap.objects.update_or_create(
        device=gap['device'],
        start_location_id=gap['start_location_id'],
        end_location_id=gap['end_location_id'],
        defaults={**record, 'status': status, 'detail': detail,
                  'point_count': len(pts), 'route_distance_m': route_len},
    )
    # update_or_create on a re-run could leave stale children behind.
    obj.points.all().delete()
    if pts:
        InferredLocation.objects.bulk_create([
            InferredLocation(gap=obj, device=gap['device'],
                             latitude=lat, longitude=lng, timestamp=ts)
            for (lng, lat, ts) in pts
        ])
    return status == 'filled'


# ---------------------------------------------------------------------------
# Shared run
# ---------------------------------------------------------------------------

@contextmanager
def _user_lock(user_id):
    """PostgreSQL session advisory lock — one gap fill per user across workers.

    Postgres releases these on process death, so a killed worker can never wedge
    a user permanently. SQLite has no equivalent; there the job row's 'running'
    status is the only guard, which is fine for a single-process dev setup.
    """
    got = False
    if connection.vendor != 'postgresql':
        yield True
        return
    try:
        with connection.cursor() as cur:
            cur.execute("SELECT pg_try_advisory_lock(%s, %s)", [_LOCK_NAMESPACE, user_id])
            got = bool(cur.fetchone()[0])
        yield got
    finally:
        if got:
            with connection.cursor() as cur:
                cur.execute("SELECT pg_advisory_unlock(%s, %s)", [_LOCK_NAMESPACE, user_id])


def _run_fill(user_id, job=None, since=None):
    """Detect and fill gaps for one user. Returns (examined, filled)."""
    from .models import UserProfile

    try:
        profile = UserProfile.objects.get(user_id=user_id)
    except UserProfile.DoesNotExist:
        return 0, 0
    if not profile.road_provider_resolved:
        return 0, 0

    min_gap_s = max(30, int(profile.gap_fill_min_minutes or 2) * 60)
    examined = filled = 0

    for gap in _detect_gaps(user_id, min_gap_s, since=since):
        if job is not None:
            try:
                job.refresh_from_db()
                if job.status != 'running':
                    break
            except Exception:
                break

        if _fill_gap(profile, gap):
            filled += 1
        examined += 1

        if job is not None and examined % 5 == 0:
            try:
                job.processed = examined
                job.total = examined
                job.filled = filled
                job.save(update_fields=['processed', 'total', 'filled', 'updated_at'])
            except Exception:
                pass
        # Remote providers are rate-limited and the local one is CPU-heavy;
        # either way, yield to the web workers between gaps.
        time.sleep(0.05)

    return examined, filled


def _gap_fill_worker(user_id):
    from .models import GapFillJob

    examined = filled = 0
    try:
        with _user_lock(user_id) as got:
            if not got:
                logger.info('Gap fill already running elsewhere for user %s', user_id)
                return
            try:
                job = GapFillJob.objects.get(user_id=user_id)
            except GapFillJob.DoesNotExist:
                return
            examined, filled = _run_fill(user_id, job=job)
    except Exception:
        logger.exception('Gap fill crashed for user %s', user_id)
    finally:
        try:
            job = GapFillJob.objects.get(user_id=user_id)
            job.processed = examined
            job.total = examined
            job.filled = filled
            if job.status == 'running':
                job.status = 'completed'
            job.save(update_fields=['processed', 'total', 'filled', 'status', 'updated_at'])
        except Exception:
            pass
        try:
            from .views import _bust_user_cache
            _bust_user_cache(user_id)
        except Exception:
            pass
        _running_threads.pop(user_id, None)
        close_old_connections()
        logger.info('Gap fill done for user %s: %s gaps examined, %s filled',
                    user_id, examined, filled)


def _is_thread_alive(user_id):
    t = _running_threads.get(user_id)
    return t is not None and t.is_alive()


def _start_thread(user_id):
    t = threading.Thread(target=_gap_fill_worker, args=(user_id,), daemon=True)
    _running_threads[user_id] = t
    t.start()


def start_gap_fill(user_id):
    from .models import GapFillJob

    job, created = GapFillJob.objects.get_or_create(
        user_id=user_id, defaults={'status': 'running', 'total': 0},
    )
    if not created:
        if job.status == 'running' and _is_thread_alive(user_id):
            return job
        job.status = 'running'
        job.total = 0
        job.processed = 0
        job.filled = 0
        job.save(update_fields=['status', 'total', 'processed', 'filled', 'updated_at'])
    _start_thread(user_id)
    return job


def stop_gap_fill(user_id):
    from .models import GapFillJob
    try:
        job = GapFillJob.objects.get(user_id=user_id, status='running')
        job.status = 'stopped'
        job.save(update_fields=['status', 'updated_at'])
        return True
    except GapFillJob.DoesNotExist:
        return False


def get_gap_fill_status(user_id):
    from .models import GapFillJob, InferredGap, InferredLocation

    totals = {
        'total_gaps': InferredGap.objects.filter(
            device__user_id=user_id).exclude(status='dismissed').count(),
        'total_points': InferredLocation.objects.filter(device__user_id=user_id).count(),
    }
    try:
        job = GapFillJob.objects.get(user_id=user_id)
    except GapFillJob.DoesNotExist:
        return {'status': 'idle', **totals}

    if job.status == 'running' and not _is_thread_alive(user_id):
        if (timezone.now() - job.updated_at).total_seconds() > 60:
            _start_thread(user_id)

    return {
        'status': job.status,
        'processed': job.processed,
        'total': job.total,
        'filled': job.filled,
        **totals,
    }


# ---------------------------------------------------------------------------
# Nightly sweep
# ---------------------------------------------------------------------------

def _sweep_user(user_id):
    """Fill gaps created since this user's last sweep, at most once a day."""
    from .models import UserProfile

    try:
        profile = UserProfile.objects.get(user_id=user_id)
    except UserProfile.DoesNotExist:
        return
    if not (profile.gap_fill_enabled and profile.gap_fill_auto):
        return

    now = timezone.now()
    last = profile.gap_fill_last_run
    # Due once per local day, the same freshness rule stats snapshots use.
    if last is not None and timezone.localtime(last).date() >= timezone.localtime(now).date():
        return

    with _user_lock(user_id) as got:
        if not got:
            return
        # Overlap the window by a day so a gap straddling the cursor isn't
        # missed; the unique anchor triple makes re-examination free.
        since = (last - timedelta(days=1)) if last else None
        examined, filled = _run_fill(user_id, since=since)

    profile.gap_fill_last_run = now
    profile.save(update_fields=['gap_fill_last_run'])
    if examined:
        try:
            from .views import _bust_user_cache
            _bust_user_cache(user_id)
        except Exception:
            pass
        logger.info('Gap fill sweep for user %s: %s examined, %s filled',
                    user_id, examined, filled)


def _gapfill_scheduler_loop():
    from .models import UserProfile
    while True:
        try:
            # Long-lived daemon thread: no request cycle enforces CONN_MAX_AGE
            # here, so force a fresh connection each sweep or a stale one can
            # silently freeze the loop.
            close_old_connections()
            user_ids = list(
                UserProfile.objects.filter(gap_fill_enabled=True, gap_fill_auto=True)
                .values_list('user_id', flat=True)
            )
            for uid in user_ids:
                try:
                    _sweep_user(uid)
                except Exception:
                    logger.exception('Gap fill sweep failed for user %s', uid)
        except Exception:
            logger.exception('Gap fill scheduler error')
        time.sleep(SCHEDULER_CHECK_INTERVAL)


def start_gapfill_scheduler():
    """Start the hourly gap-fill sweep thread (called once on app startup)."""
    global _scheduler_thread
    if _scheduler_thread is not None and _scheduler_thread.is_alive():
        return
    _scheduler_thread = threading.Thread(target=_gapfill_scheduler_loop, daemon=True)
    _scheduler_thread.start()
    logger.info('Gap fill scheduler started')
