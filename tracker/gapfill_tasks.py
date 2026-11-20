"""Auto-fill single missed fixes with a road-snapped midpoint.

A phone sometimes misses one fix, leaving a 20-45 s hole in a dense drive. This
drops one inferred point in the middle of such a gap, **on the road**, and does
nothing at all unless both neighbours are on a snapped road.

**Inferred only.** The point goes to ``InferredLocation`` (an ``EditBatch`` of
kind ``auto``), so it shows under the map's "filled gaps" toggle but is never
read by distance, visits, stats or fog, and can be undone from the editor.

**The road gate is the whole safety story.** Both neighbours must come back from
``roads.snap_points`` (which already refuses walks, stills, planes, trains and
anything with no road inside its tight tolerance), a road route must exist
between the snapped positions, and that route must not be a detour. Anything
else is left exactly as recorded.

**Incremental, by Location id** (``UserProfile.gapfill_scan_last_id``), like
``overlap_tasks``. A first scan starts ``FIRST_SCAN_DAYS`` back rather than
rewriting a whole history. A gap that already has an inferred point strictly
inside it is skipped, so re-scanning a chunk is harmless.

Daemon thread from ``apps.ready()``, hourly, per-user advisory lock (namespace
'RAMI').
"""

import logging
import threading
import time
from bisect import bisect_right
from contextlib import contextmanager
from datetime import timedelta

from django.db import close_old_connections, connection
from django.utils import timezone

logger = logging.getLogger(__name__)

SCHEDULER_CHECK_INTERVAL = 3600
_LOCK_NAMESPACE = 0x52414d49       # 'RAMI'

GAP_MIN_S = 20
GAP_MAX_S = 45
MIN_HOP_M = 25              # closer than this and there is nothing to fill
MAX_PLAUSIBLE_MPS = 45      # above any road speed (matches roads._SNAP_MAX_PLAUSIBLE_ROAD_MPS)
MAX_ACCURACY_M = 100
MAX_DETOUR = 1.5            # route length vs the straight line between the anchors
CONTEXT_ROWS = 3            # neighbours snappable() needs on each side
FIRST_SCAN_DAYS = 30
CHUNK = 5000
MAX_CHUNKS_PER_SWEEP = 20

_scheduler_thread = None


@contextmanager
def _user_lock(user_id):
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


def find_candidates(rows):
    """Indices ``i`` where ``rows[i]``→``rows[i+1]`` looks like one missed fix.

    ``rows`` are dicts (``lat``, ``lng``, ``timestamp``) in timestamp order.
    """
    from . import roads

    out = []
    for i in range(len(rows) - 1):
        a, b = rows[i], rows[i + 1]
        dt = (b['timestamp'] - a['timestamp']).total_seconds()
        if not GAP_MIN_S <= dt <= GAP_MAX_S:
            continue
        d = roads._haversine_m(a['lng'], a['lat'], b['lng'], b['lat'])
        if d < MIN_HOP_M or d > MAX_PLAUSIBLE_MPS * dt:
            continue
        out.append(i)
    return out


def midpoint_on_route(route):
    """The point halfway along a polyline's length, as ``(lng, lat)``."""
    from . import roads

    cum = [0.0]
    for i in range(len(route) - 1):
        cum.append(cum[-1] + roads._haversine_m(
            route[i][0], route[i][1], route[i + 1][0], route[i + 1][1]))
    total = cum[-1]
    if total <= 0:
        return None
    half = total / 2.0
    seg = 0
    while seg < len(cum) - 2 and cum[seg + 1] < half:
        seg += 1
    span = cum[seg + 1] - cum[seg]
    f = 0.0 if span <= 0 else (half - cum[seg]) / span
    return (route[seg][0] + (route[seg + 1][0] - route[seg][0]) * f,
            route[seg][1] + (route[seg + 1][1] - route[seg][1]) * f)


def fill_window(profile, rows, existing_ts):
    """Midpoints ``[(lng, lat, when)]`` for the fillable gaps in ``rows``.

    ``existing_ts`` is a sorted list of timestamps of inferred points already on
    this device; a gap with one strictly inside is skipped.
    """
    from . import roads

    cands = find_candidates(rows)
    if not cands:
        return []

    # Snap only the stretch around each candidate, not the whole window: the
    # smoother needs neighbours, but most of a history has no gap to fill.
    snapped = {}
    covered = set()
    for i in cands:
        lo, hi = max(0, i - CONTEXT_ROWS), min(len(rows), i + 2 + CONTEXT_ROWS)
        if all(j in covered for j in range(lo, hi)):
            continue
        covered.update(range(lo, hi))
    idxs = sorted(covered)
    # Break into contiguous slices so the Viterbi pass never bridges a skipped hole.
    slices, cur = [], []
    for j in idxs:
        if cur and j != cur[-1] + 1:
            slices.append(cur)
            cur = []
        cur.append(j)
    if cur:
        slices.append(cur)
    for sl in slices:
        snapped.update(roads.snap_points(profile, [rows[j] for j in sl]) or {})

    out = []
    for i in cands:
        a, b = rows[i], rows[i + 1]
        sa, sb = snapped.get(a['id']), snapped.get(b['id'])
        if not sa or not sb:
            continue                      # not on a snapped road: do nothing
        k = bisect_right(existing_ts, a['timestamp'])
        if k < len(existing_ts) and existing_ts[k] < b['timestamp']:
            continue                      # already filled
        try:
            route = roads.route_between(profile, sa, sb, a.get('transport_mode') or '')
        except roads.RoadProviderError:
            return out
        if not route or len(route) < 2:
            continue
        straight = roads._haversine_m(sa[0], sa[1], sb[0], sb[1])
        length = sum(roads._haversine_m(route[n][0], route[n][1], route[n + 1][0], route[n + 1][1])
                     for n in range(len(route) - 1))
        if straight <= 0 or length > straight * MAX_DETOUR:
            continue
        mid = midpoint_on_route(route)
        if mid is None:
            continue
        when = a['timestamp'] + (b['timestamp'] - a['timestamp']) / 2
        out.append((mid[0], mid[1], when))
    return out


def _initial_cursor():
    from .models import Location
    cutoff = timezone.now() - timedelta(days=FIRST_SCAN_DAYS)
    last = (Location.objects.filter(timestamp__lt=cutoff).order_by('-id')
            .values_list('id', flat=True).first())
    return last or 0


def scan_user(user_id):
    """Fill missed single fixes among a user's new points. Returns points added."""
    from django.db.models import Max
    from . import editor_tasks
    from .models import Device, InferredLocation, Location, UserProfile

    profile = UserProfile.objects.filter(user_id=user_id).select_related('user').first()
    if profile is None:
        return 0
    devices = {d.id: d for d in Device.objects.filter(user_id=user_id)}
    if not devices:
        return 0
    hi = Location.objects.filter(device_id__in=devices).aggregate(m=Max('id'))['m']
    cursor = profile.gapfill_scan_last_id
    if cursor is None:
        cursor = _initial_cursor()
    if hi is None or hi <= cursor:
        return 0
    if not profile.snap_to_roads or not profile.road_provider_resolved:
        # Nothing to snap with. Leave the cursor so enabling it later fills
        # recent gaps rather than skipping everything seen meanwhile.
        return 0

    total = 0
    chunks = 0
    while cursor < hi and chunks < MAX_CHUNKS_PER_SWEEP:
        chunks += 1
        ids = list(Location.objects.filter(device_id__in=devices, id__gt=cursor, id__lte=hi)
                   .order_by('id').values_list('id', 'device_id', 'timestamp')[:CHUNK])
        if not ids:
            break
        span = {}
        for _id, dev, ts in ids:
            s = span.setdefault(dev, [ts, ts])
            s[0] = min(s[0], ts)
            s[1] = max(s[1], ts)
        win = timedelta(seconds=GAP_MAX_S + 60)
        for dev, (t0, t1) in span.items():
            qs = (Location.objects.filter(device_id=dev, timestamp__gte=t0 - win,
                                          timestamp__lte=t1 + win)
                  .exclude(flag='suspect').order_by('timestamp', 'id')
                  .values('id', 'latitude', 'longitude', 'accuracy', 'speed',
                          'transport_mode', 'timestamp'))
            rows = [{'id': r['id'], 'lat': r['latitude'], 'lng': r['longitude'],
                     'accuracy': r['accuracy'], 'speed': r['speed'],
                     'transport_mode': r['transport_mode'], 'timestamp': r['timestamp']}
                    for r in qs if r['accuracy'] is None or r['accuracy'] <= MAX_ACCURACY_M]
            if len(rows) < 2:
                continue
            existing = sorted(InferredLocation.objects
                              .filter(device_id=dev, batch__undone=False,
                                      timestamp__gte=t0 - win, timestamp__lte=t1 + win)
                              .values_list('timestamp', flat=True))
            pts = fill_window(profile, rows, existing)
            if pts:
                editor_tasks.create_batch(profile.user, devices[dev], 'auto', pts,
                                          target='inferred', note='Auto-filled missed fixes')
                total += len(pts)
        cursor = ids[-1][0]
        UserProfile.objects.filter(pk=profile.pk).update(gapfill_scan_last_id=cursor)
    if total:
        from .views import _bust_user_cache
        _bust_user_cache(user_id)
        logger.info("Auto-filled %d missed fix(es) for user %s", total, user_id)
    return total


def _scheduler_loop():
    from .models import Device
    from .scheduler_utils import wait_for_app_registry
    wait_for_app_registry()
    time.sleep(150)
    while True:
        try:
            close_old_connections()
            user_ids = list(Device.objects.order_by().values_list('user_id', flat=True)
                            .distinct())
            for uid in user_ids:
                try:
                    with _user_lock(uid) as got:
                        if got:
                            scan_user(uid)
                except Exception:
                    logger.exception("Gap fill failed for user %s", uid)
        except Exception:
            logger.exception("Gap fill scheduler error")
        finally:
            close_old_connections()
        time.sleep(SCHEDULER_CHECK_INTERVAL)


def start_gapfill_scheduler():
    global _scheduler_thread
    if _scheduler_thread is not None and _scheduler_thread.is_alive():
        return
    _scheduler_thread = threading.Thread(target=_scheduler_loop, daemon=True)
    _scheduler_thread.start()
    logger.info("Gap fill scheduler started")
