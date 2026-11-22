"""Background task: label every GPS point with how the user was moving.

A full run is triggered from Settings → Background Jobs; the nightly stats pass
labels the backlog (``classify_backlog``) and the map labels the last 24 hours on
demand (``classify_since``) when its transport filter is narrowed. Writes one of MODES onto
``Location.transport_mode`` so the map can colour tracks by mode and Stats can
break distance down by it.

Why segments and not points
---------------------------
A single fix's speed lies constantly: a car at a red light reads 0 m/s, and one
bad fix reads 900. So this doesn't threshold per point. It splits each device's
track into *journeys* — runs of movement bounded by stops — scores each journey
from its whole speed profile, and stamps every point in the journey with the
result. A red light stays 'vehicle' because the journey around it is one.

Speed source
------------
``Location.speed`` is the chip's Doppler reading. It stays trustworthy even when
the position estimate wanders (the same property DriftAnchor leans on), so it's
preferred. Imported history (CSV/GPX/Takeout) has no Doppler, so speed is
derived from consecutive fixes as a fallback.
"""

import math
import logging
import threading
from .scheduler_utils import db_thread
import time

from django.db import close_old_connections
from django.db.models import Q
from django.utils import timezone

logger = logging.getLogger(__name__)

_running_threads = {}

# Mode codes stored on Location.transport_mode ('' = unclassified).
MODES = ('still', 'walk', 'cycle', 'vehicle', 'plane')

# Upper speed bound (m/s) for each mode, tested in order. Deliberately no
# car/train split: their speed profiles overlap far too much to call reliably.
_WALK_MAX_MPS = 2.2       # ~8 km/h — brisk walk / slow jog
_CYCLE_MAX_MPS = 8.0      # ~29 km/h — above this you're not pedalling for long
_VEHICLE_MAX_MPS = 90.0   # ~324 km/h — beyond this only aircraft sustain it

# A journey is "moving" while speed stays above this.
_MOVE_MPS = 0.7
# Stops shorter than this don't end a journey (red lights, junctions, platforms).
_STOP_GAP_S = 180
# A time gap this large means we lost the track — never bridge a journey across it.
_MAX_BRIDGE_S = 900
# Journeys shorter than this are noise, not travel.
_MIN_JOURNEY_S = 60
# Discard fixes worse than this (m) — matches the distance gate's tolerance.
_MAX_ACCURACY_M = 100.0
# Derived speeds above this are GPS teleports, not travel.
_MAX_SANE_MPS = 300.0

BATCH_SIZE = 20000        # points loaded per device chunk


def _haversine_m(lat1, lon1, lat2, lon2):
    R = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlam / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def _percentile(sorted_vals, q):
    """Linear-interpolated percentile of an already-sorted list."""
    if not sorted_vals:
        return 0.0
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    pos = q * (len(sorted_vals) - 1)
    lo = int(math.floor(pos))
    hi = min(lo + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (pos - lo)


def _point_speeds(rows):
    """Yield ``(row, speed_mps_or_None)`` — Doppler where present, else derived.

    `rows` is one device's fixes in time order, each a dict with id/latitude/
    longitude/timestamp/speed/accuracy.
    """
    prev = None
    for r in rows:
        acc = r['accuracy']
        if acc is not None and acc > _MAX_ACCURACY_M:
            # Too vague to say anything about; leave it for the journey it lands in.
            yield r, None
            continue

        spd = r['speed']
        if spd is None or spd < 0:
            spd = None
            if prev is not None:
                dt = (r['timestamp'] - prev['timestamp']).total_seconds()
                if 0 < dt <= _MAX_BRIDGE_S:
                    d = _haversine_m(prev['latitude'], prev['longitude'],
                                     r['latitude'], r['longitude'])
                    derived = d / dt
                    if derived <= _MAX_SANE_MPS:
                        spd = derived
        elif spd > _MAX_SANE_MPS:
            spd = None

        prev = r
        yield r, spd


def _split_journeys(scored):
    """Group ``(row, speed)`` pairs into journeys.

    A journey runs from first movement to the last fix before a stop that lasts
    longer than _STOP_GAP_S. Short stops stay inside the journey so a red light
    doesn't shatter one drive into three. Track gaps over _MAX_BRIDGE_S always
    break, since we have no idea what happened in between.

    Yields ``(rows, speeds)`` where speeds excludes the Nones.
    """
    cur_rows, cur_speeds = [], []
    stop_since = None
    prev_ts = None

    def _flush():
        if not cur_rows:
            return None
        span = (cur_rows[-1]['timestamp'] - cur_rows[0]['timestamp']).total_seconds()
        out = (list(cur_rows), list(cur_speeds)) if span >= _MIN_JOURNEY_S else None
        cur_rows.clear()
        cur_speeds.clear()
        return out

    for r, spd in scored:
        ts = r['timestamp']
        if prev_ts is not None and (ts - prev_ts).total_seconds() > _MAX_BRIDGE_S:
            done = _flush()
            if done:
                yield done
            stop_since = None
        prev_ts = ts

        moving = spd is not None and spd >= _MOVE_MPS
        if moving:
            stop_since = None
        else:
            if stop_since is None:
                stop_since = ts
            elif (ts - stop_since).total_seconds() > _STOP_GAP_S:
                # Long stop: close the journey and leave this fix out of it.
                done = _flush()
                if done:
                    yield done
                stop_since = ts
                continue

        cur_rows.append(r)
        if spd is not None:
            cur_speeds.append(spd)

    done = _flush()
    if done:
        yield done


def _classify(speeds):
    """Pick a mode for one journey from its speed profile.

    Uses the 85th percentile rather than the max (one bad fix shouldn't promote a
    walk to a flight) and the median to catch journeys that are mostly stationary.
    """
    if not speeds:
        return 'still'
    s = sorted(speeds)
    median = _percentile(s, 0.5)
    p85 = _percentile(s, 0.85)

    if p85 < _MOVE_MPS:
        return 'still'
    # Median guards against a fast journey being called by a handful of spikes:
    # a real drive spends most of its time moving, a walk with a GPS jump doesn't.
    if p85 <= _WALK_MAX_MPS or median <= _WALK_MAX_MPS * 0.6:
        return 'walk'
    if p85 <= _CYCLE_MAX_MPS:
        return 'cycle'
    if p85 <= _VEHICLE_MAX_MPS:
        return 'vehicle'
    return 'plane'


def _classify_device(dev_id, start=None, write_from=None, should_stop=None, on_chunk=None):
    """Label one device's points with how it was moving, oldest first.

    `start` (None = the whole history) is where reading begins; `write_from`
    (None = everything read) is where writing begins. Reading earlier than
    writing gives a journey that runs across `write_from` its whole speed
    profile, while points before it keep the label they already had rather
    than being rescored from half a journey.

    `should_stop()` aborts between chunks; `on_chunk(processed, classified)`
    reports progress. Returns ``(processed, classified)``.
    """
    from .models import Location

    processed = 0
    classified = 0
    # Journeys are per device and must be walked in time order — one device's
    # track says nothing about another's.
    cursor = None       # (timestamp, id) of the last row consumed
    carry = []
    while True:
        if should_stop is not None and should_stop():
            break

        qs = Location.objects.filter(device_id=dev_id)
        if start is not None:
            qs = qs.filter(timestamp__gte=start)
        if cursor is not None:
            # Paginate on (timestamp, id), not id alone: imported history gets
            # its ids in file order, which need not match time order, so an id
            # cursor would silently skip points.
            last_ts, last_id = cursor
            qs = qs.filter(
                Q(timestamp__gt=last_ts) | Q(timestamp=last_ts, id__gt=last_id)
            )
        chunk = list(
            qs.order_by('timestamp', 'id')
            .values('id', 'latitude', 'longitude', 'timestamp', 'speed', 'accuracy')[:BATCH_SIZE]
        )
        if not chunk:
            break
        cursor = (chunk[-1]['timestamp'], chunk[-1]['id'])
        more = len(chunk) == BATCH_SIZE

        rows = carry + chunk
        journeys = list(_split_journeys(_point_speeds(rows)))
        # The last journey may run on into the next chunk, so hold it back and
        # re-score it there rather than classifying half a drive.
        if more and journeys:
            carry = journeys.pop()[0]
        elif more:
            carry = rows[-1:]
        else:
            carry = []

        assigned = {}
        for j_rows, j_speeds in journeys:
            mode = _classify(j_speeds)
            for r in j_rows:
                assigned[r['id']] = mode

        # Everything not inside a journey is the user parked somewhere.
        # Labelling it explicitly (rather than leaving it be) keeps the whole
        # history classified and makes re-runs idempotent instead of leaving
        # stale modes behind.
        held = {r['id'] for r in carry}
        by_mode = {}
        for r in rows:
            if r['id'] in held:
                continue
            if write_from is not None and r['timestamp'] < write_from:
                continue
            by_mode.setdefault(assigned.get(r['id'], 'still'), []).append(r['id'])

        for mode, ids in by_mode.items():
            for i in range(0, len(ids), 5000):
                Location.objects.filter(id__in=ids[i:i + 5000]).update(transport_mode=mode)
            classified += len(ids)

        processed += len(chunk)
        if on_chunk is not None:
            on_chunk(processed, classified)

        if not more:
            break
    return processed, classified


def _transport_worker(user_id):
    from .models import Location, Device, TransportJob

    totals = {'processed': 0, 'classified': 0}
    job = None

    try:
        try:
            job = TransportJob.objects.get(user_id=user_id)
        except TransportJob.DoesNotExist:
            return

        total = Location.objects.filter(device__user_id=user_id).count()
        job.total = total
        job.processed = 0
        job.classified = 0
        job.save(update_fields=['total', 'processed', 'classified', 'updated_at'])
        logger.info(f"Transport detection for user {user_id}: {total} points")

        def should_stop():
            try:
                job.refresh_from_db()
            except TransportJob.DoesNotExist:
                return True
            return job.status != 'running'

        device_ids = list(Device.objects.filter(user_id=user_id).values_list('id', flat=True))
        for dev_id in device_ids:
            base = dict(totals)

            def on_chunk(processed, classified):
                job.processed = base['processed'] + processed
                job.classified = base['classified'] + classified
                job.save(update_fields=['processed', 'classified', 'updated_at'])
                time.sleep(0.02)

            processed, classified = _classify_device(
                dev_id, should_stop=should_stop, on_chunk=on_chunk)
            totals['processed'] += processed
            totals['classified'] += classified
            if should_stop():
                return

    except Exception:
        logger.exception(f"Transport detection failed for user {user_id}")
    finally:
        try:
            job = TransportJob.objects.get(user_id=user_id)
            if job.status == 'running':
                job.status = 'completed'
            job.save(update_fields=['status', 'updated_at'])
        except Exception:
            pass
        if totals['classified']:
            _labels_changed(user_id)
        _running_threads.pop(user_id, None)
        close_old_connections()
        logger.info(
            f"Transport detection done for user {user_id}: "
            f"{totals['processed']} points, {totals['classified']} classified"
        )


# ── Incremental detection: nightly backlog + the map's on-demand last 24h ──
# The full run above is manual, so without these everything recorded since it
# last ran stays unclassified — which is exactly the data a map filter for
# "today" asks about.

# Read this much earlier than the first point being labelled, so a journey
# under way at that moment is scored from more than its tail.
_CONTEXT_S = 3600
# The nightly pass always rescores at least this much, even when nothing is
# unclassified: the map's on-demand pass labels a journey still in progress
# from what had arrived so far, and this is what finishes it properly.
_NIGHTLY_MIN_WINDOW_S = 48 * 3600
# The map's on-demand window.
RECENT_WINDOW_S = 24 * 3600


def transport_gen(user_id):
    """Bumped whenever this user's labels change; caches of mode-filtered
    aggregates (tiles, overview cells) key on it."""
    from django.core.cache import cache
    return cache.get(f"transport_gen:{user_id}", 0)


def _labels_changed(user_id):
    from django.core.cache import cache
    if not cache.add(f"transport_gen:{user_id}", 1, None):
        try:
            cache.incr(f"transport_gen:{user_id}")
        except ValueError:
            cache.set(f"transport_gen:{user_id}", 1, None)
    # Track and Trip Lines key on cache_gen.
    from .views import _bust_user_cache
    _bust_user_cache(user_id)


def classify_since(user_id, since):
    """Label every point of the user's from `since` on, synchronously.
    Returns how many points were labelled."""
    from datetime import timedelta
    from .models import Device

    classified = 0
    for dev_id in Device.objects.filter(user_id=user_id).values_list('id', flat=True):
        _, n = _classify_device(dev_id, start=since - timedelta(seconds=_CONTEXT_S), write_from=since)
        classified += n
    if classified:
        _labels_changed(user_id)
    return classified


def classify_backlog(user_id):
    """The nightly pass: everything still unclassified, and at least the last
    48 hours. The first night after an upgrade this is the whole unlabelled
    history; after that it is about a day."""
    from datetime import timedelta
    from .models import Location

    since = timezone.now() - timedelta(seconds=_NIGHTLY_MIN_WINDOW_S)
    oldest = (Location.objects.filter(device__user_id=user_id, transport_mode='')
              .order_by('timestamp').values_list('timestamp', flat=True).first())
    if oldest is not None and oldest < since:
        since = oldest
    return classify_since(user_id, since)


def _is_thread_alive(user_id):
    t = _running_threads.get(user_id)
    return t is not None and t.is_alive()


def _start_thread(user_id):
    t = db_thread(_transport_worker, args=(user_id,))
    _running_threads[user_id] = t
    t.start()


def start_transport_detect(user_id):
    from .models import TransportJob

    job, created = TransportJob.objects.get_or_create(
        user_id=user_id, defaults={'status': 'running', 'total': 0},
    )
    if not created:
        if job.status == 'running' and _is_thread_alive(user_id):
            return job
        job.status = 'running'
        job.total = 0
        job.processed = 0
        job.classified = 0
        job.save(update_fields=['status', 'total', 'processed', 'classified', 'updated_at'])
    _start_thread(user_id)
    return job


def stop_transport_detect(user_id):
    from .models import TransportJob
    try:
        job = TransportJob.objects.get(user_id=user_id, status='running')
        job.status = 'stopped'
        job.save(update_fields=['status', 'updated_at'])
        return True
    except TransportJob.DoesNotExist:
        return False


def get_transport_status(user_id):
    from .models import TransportJob

    try:
        job = TransportJob.objects.get(user_id=user_id)
    except TransportJob.DoesNotExist:
        return {'status': 'idle'}

    # Restart a thread that died mid-run (e.g. a worker recycle).
    if job.status == 'running' and not _is_thread_alive(user_id):
        if (timezone.now() - job.updated_at).total_seconds() > 30:
            _start_thread(user_id)

    return {
        'status': job.status,
        'processed': job.processed,
        'total': job.total,
        'classified': job.classified,
    }
