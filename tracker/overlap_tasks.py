"""Device-overlap detection — two devices recording the same track at once.

With a hardware tracker in the pocket and the phone still tracking, the same
trip lands twice: once per device. Every per-device aggregate then counts it
twice — distance, visit time, point counts. This finds those stretches and
records each as a ``DeviceOverlap`` for the notification bell, where the user
picks which device's points to hide for the window.

**Incremental, by Location id.** Each sweep reads only points with an id above
``UserProfile.overlap_scan_last_id`` — but compares them against *every* point
the user's other devices have in the same time window, whatever its id. That is
what catches the tracker, which uploads hours of history in one go while
charging: its points are new, the phone's matching points are old, and an
id-only comparison would never pair them. The cursor is an id rather than a
timestamp for the same reason: a late upload lands in the past.

**Matching.** A point is *near* when the other device has a fix within
``MATCH_WINDOW_S`` of it and within ``max(MATCH_RADIUS_M, acc_a + acc_b)``
(capped). It is *far* when such a fix is more than ``DIVERGE_FACTOR`` times that
away — the devices are demonstrably in different places, which ends a run. A
fix with no counterpart in time says nothing either way and neither extends nor
breaks a run, so a tracker with a sparser cadence than the phone still pairs.
A run qualifies at ``MIN_RUN_S`` and ``MIN_RUN_MATCHES``.

**One record per episode.** A run within ``MERGE_GAP_S`` of an existing record
for the same pair extends it rather than creating another, whatever its status:
a pending overlap simply grows; a dismissed one absorbs it silently (the user
already said "keep both" for this episode); a hidden one also hides the newly
covered points of its hidden device. The last case is a late upload landing in
a window the user already decided on, and the first is how unhidden points —
re-inserted under fresh ids and therefore re-scanned — never re-prompt.

Mirrors the other schedulers: a daemon thread from ``apps.ready()``, hourly,
``close_old_connections()`` each pass, a per-user PostgreSQL advisory lock
(namespace 'RAMO') so two gunicorn workers never scan one user at once.
"""

import logging
import math
import threading
import time
from bisect import bisect_left
from collections import defaultdict
from contextlib import contextmanager
from datetime import timedelta

from django.db import close_old_connections, connection, transaction
from django.utils import timezone

logger = logging.getLogger(__name__)

SCHEDULER_CHECK_INTERVAL = 3600

# Distinct from stats 'RAML', summary 'RAMS', log-cleanup 'RAMG', auto-download
# 'RAMD', alerts 'RAMA' and family 'RAMF'.
_LOCK_NAMESPACE = 0x52414d4f       # 'RAMO'

MATCH_WINDOW_S = 90         # max time apart for two fixes to be compared
MATCH_RADIUS_M = 75         # base "same place" radius
MATCH_RADIUS_MAX_M = 250    # ceiling once accuracies are added in
DIVERGE_FACTOR = 3          # this many radii apart = provably separate
RUN_BREAK_S = 600           # a run ends after this long without a near pair
MIN_RUN_S = 600             # shortest overlap worth asking about
MIN_RUN_MATCHES = 10
MERGE_GAP_S = 900           # runs this close to a record belong to it
SCAN_CHUNK = 20000          # new points read per pass
SEGMENT_GAP_S = 1800        # split a device's new points at gaps this long...
SEGMENT_MAX_S = 86400       # ...and at most a day each, bounding each fetch

_scheduler_thread = None


def _haversine_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(min(1.0, a)))


@contextmanager
def _user_lock(user_id):
    """Yield True iff this thread holds the overlap-scan lock for ``user_id``."""
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


# ── Matching ────────────────────────────────────────────────────────────────

def _segments(points):
    """Split time-sorted ``(ts, lat, lon, acc)`` points into contiguous runs."""
    seg = []
    for p in points:
        if seg and ((p[0] - seg[-1][0]).total_seconds() > SEGMENT_GAP_S
                    or (p[0] - seg[0][0]).total_seconds() > SEGMENT_MAX_S):
            yield seg
            seg = []
        seg.append(p)
    if seg:
        yield seg


def find_runs(points, other):
    """Overlap runs between two time-sorted point lists.

    ``points`` are the new fixes being checked, ``other`` the other device's
    fixes over the same window (both ``(ts, lat, lon, acc)``). Returns every
    run as ``(start, end, matches)``, short ones included: a run too short to
    open a record on its own may still extend an adjacent one — typically the
    tail of an episode split across two scan chunks. ``_record_run`` decides.
    """
    if not points or not other:
        return []
    other_ts = [o[0] for o in other]
    runs = []
    cur = None   # [start, last_near, matches]

    def close():
        if cur:
            runs.append((cur[0], cur[1], cur[2]))

    for ts, lat, lon, acc in points:
        i = bisect_left(other_ts, ts)
        best = None
        for j in (i - 1, i):
            if 0 <= j < len(other):
                dt = abs((other[j][0] - ts).total_seconds())
                if dt <= MATCH_WINDOW_S and (best is None or dt < best[0]):
                    best = (dt, other[j])
        if best is None:
            continue   # no counterpart in time: says nothing either way
        o = best[1]
        radius = min(MATCH_RADIUS_MAX_M,
                     max(MATCH_RADIUS_M, (acc or 0) + (o[3] or 0)))
        d = _haversine_m(lat, lon, o[1], o[2])
        if d <= radius:
            if cur and (ts - cur[1]).total_seconds() > RUN_BREAK_S:
                close()
                cur = None
            if cur is None:
                cur = [ts, ts, 0]
            cur[1] = ts
            cur[2] += 1
        elif d > DIVERGE_FACTOR * radius:
            close()
            cur = None
    close()
    return runs


def _qualifies(start, end, matches):
    return matches >= MIN_RUN_MATCHES and (end - start).total_seconds() >= MIN_RUN_S


# ── Recording ───────────────────────────────────────────────────────────────

def _record_run(user, dev_x, dev_y, start, end, matches):
    """Merge one run into the user's DeviceOverlap records.

    Returns ``(created, moved)``: whether a new pending overlap was opened, and
    how many points were hidden because the run grew an already-hidden one."""
    from .models import DeviceOverlap

    a, b = (dev_x, dev_y) if dev_x < dev_y else (dev_y, dev_x)
    gap = timedelta(seconds=MERGE_GAP_S)
    with transaction.atomic():
        existing = (DeviceOverlap.objects.select_for_update()
                    .filter(user=user, device_a_id=a, device_b_id=b,
                            start_time__lte=end + gap, end_time__gte=start - gap)
                    .order_by('start_time').first())
        if existing is None:
            if not _qualifies(start, end, matches):
                return False, 0
            DeviceOverlap.objects.create(user=user, device_a_id=a, device_b_id=b,
                                         start_time=start, end_time=end)
            return True, 0
        grew = False
        if start < existing.start_time:
            existing.start_time, grew = start, True
        if end > existing.end_time:
            existing.end_time, grew = end, True
        if grew:
            existing.save(update_fields=['start_time', 'end_time'])
    if grew and existing.status == 'hidden' and existing.hidden_device_id:
        # The user already chose a device for this episode; keep it consistent.
        return False, _trash_window(user, existing, existing.hidden_device_id)
    return False, 0


def _trash_window(user, overlap, device_id):
    """Move device_id's points inside the overlap window into the trash."""
    from . import editor_tasks
    from .models import Location, Visit

    qs = Location.objects.filter(device_id=device_id,
                                 timestamp__gte=overlap.start_time,
                                 timestamp__lte=overlap.end_time)
    n = editor_tasks.trash_locations(user, qs, reason='overlap', overlap=overlap)
    if n:
        # Visits built from those points would keep counting the time. Drop the
        # ones that touch the window and let the visit job rebuild whatever of
        # them lay outside it from the device's remaining points.
        stale = Visit.objects.filter(device_id=device_id,
                                     start_time__lte=overlap.end_time,
                                     end_time__gte=overlap.start_time)
        span = [(v.start_time, v.end_time) for v in stale.only('start_time', 'end_time')]
        stale.delete()
        for vs, ve in span:
            Location.objects.filter(device_id=device_id, timestamp__gte=vs,
                                    timestamp__lte=ve).update(processed_for_visits=False)
    return n


def _after_change(user_id):
    """Invalidate everything that summarised the moved points."""
    from .views import _bust_user_cache
    from .stats_tasks import start_stats_compute
    from .visit_tasks import ensure_auto_visits
    _bust_user_cache(user_id)
    try:
        start_stats_compute(user_id)
    except Exception:
        logger.exception("Stats recompute after overlap change failed for %s", user_id)
    try:
        ensure_auto_visits(user_id)
    except Exception:
        logger.exception("Visit rebuild after overlap change failed for %s", user_id)


def hide_overlap(user, overlap, device_id):
    """Hide ``device_id``'s points for the overlap window. Returns the count."""
    if device_id not in (overlap.device_a_id, overlap.device_b_id):
        raise ValueError('device not part of this overlap')
    with transaction.atomic():
        n = _trash_window(user, overlap, device_id)
        overlap.status = 'hidden'
        overlap.hidden_device_id = device_id
        overlap.resolved_at = timezone.now()
        overlap.save(update_fields=['status', 'hidden_device', 'resolved_at'])
    _after_change(user.id)
    return n


def unhide_overlap(user, overlap):
    """Put an overlap's hidden points back; the overlap becomes 'dismissed' so
    the re-scanned points (fresh ids) merge into it instead of re-prompting."""
    from . import editor_tasks
    from .models import TrashedLocation

    with transaction.atomic():
        ids = list(TrashedLocation.objects.filter(user=user, overlap=overlap)
                   .values_list('id', flat=True))
        n = editor_tasks.restore_trashed(user, ids) if ids else 0
        overlap.status = 'dismissed'
        overlap.hidden_device = None
        overlap.resolved_at = timezone.now()
        overlap.save(update_fields=['status', 'hidden_device', 'resolved_at'])
    _after_change(user.id)
    return n


# ── Scanning ────────────────────────────────────────────────────────────────

def scan_user(user_id):
    """Compare a user's new points against their other devices. Returns the
    number of new pending overlaps."""
    from django.db.models import Max
    from .models import Device, Location, UserProfile

    profile = UserProfile.objects.filter(user_id=user_id).select_related('user').first()
    if profile is None:
        return 0
    user = profile.user
    devices = list(Device.objects.filter(user_id=user_id).values_list('id', flat=True))
    hi = Location.objects.filter(device_id__in=devices).aggregate(m=Max('id'))['m'] if devices else None
    cursor = profile.overlap_scan_last_id or 0
    if hi is None or hi <= cursor:
        return 0
    if len(devices) < 2:
        # Nothing to compare against; don't re-read this history once a second
        # device appears — its own points will be the new ones then.
        UserProfile.objects.filter(pk=profile.pk).update(overlap_scan_last_id=hi)
        return 0

    created = moved = 0
    while cursor < hi:
        rows = list(Location.objects
                    .filter(device_id__in=devices, id__gt=cursor, id__lte=hi)
                    .exclude(flag='suspect')
                    .order_by('id')
                    .values_list('id', 'device_id', 'timestamp', 'latitude',
                                 'longitude', 'accuracy')[:SCAN_CHUNK])
        if not rows:
            break
        by_dev = defaultdict(list)
        for _id, dev, ts, lat, lon, acc in rows:
            by_dev[dev].append((ts, lat, lon, acc))
        window = timedelta(seconds=MATCH_WINDOW_S)
        for dev, pts in by_dev.items():
            pts.sort(key=lambda p: p[0])
            for seg in _segments(pts):
                lo_t, hi_t = seg[0][0] - window, seg[-1][0] + window
                for other_dev in devices:
                    if other_dev == dev:
                        continue
                    other = list(Location.objects
                                 .filter(device_id=other_dev, timestamp__gte=lo_t,
                                         timestamp__lte=hi_t)
                                 .exclude(flag='suspect')
                                 .order_by('timestamp')
                                 .values_list('timestamp', 'latitude', 'longitude', 'accuracy'))
                    for start, end, matches in find_runs(seg, other):
                        new, n = _record_run(user, dev, other_dev, start, end, matches)
                        created += new
                        moved += n
        cursor = rows[-1][0]
        # Per chunk, so a killed sweep resumes rather than starting over.
        UserProfile.objects.filter(pk=profile.pk).update(overlap_scan_last_id=cursor)
        if len(rows) < SCAN_CHUNK:
            break
    UserProfile.objects.filter(pk=profile.pk).update(overlap_scan_last_id=hi)
    if moved:
        _after_change(user_id)
    if created:
        logger.info("Found %d new device overlap(s) for user %s", created, user_id)
    return created


def _scheduler_loop():
    from .models import Device
    from .scheduler_utils import wait_for_app_registry
    wait_for_app_registry()
    # A short settle after boot, then hourly — same shape as log cleanup.
    time.sleep(90)
    while True:
        try:
            close_old_connections()
            # Single-device accounts still go through scan_user, once, so their
            # cursor tracks the history and a second device later starts fresh.
            user_ids = list(Device.objects.order_by().values_list('user_id', flat=True)
                            .distinct())
            for uid in user_ids:
                try:
                    with _user_lock(uid) as got:
                        if got:
                            scan_user(uid)
                except Exception:
                    logger.exception("Device overlap scan failed for user %s", uid)
        except Exception:
            logger.exception("Device overlap scheduler error")
        finally:
            close_old_connections()
        time.sleep(SCHEDULER_CHECK_INTERVAL)


def start_overlap_scheduler():
    """Start the hourly device-overlap sweep thread (called once on startup)."""
    global _scheduler_thread
    if _scheduler_thread is not None and _scheduler_thread.is_alive():
        return
    _scheduler_thread = threading.Thread(target=_scheduler_loop, daemon=True)
    _scheduler_thread.start()
    logger.info("Device overlap scheduler started")
