"""Find flights in a user's GPS history, to suggest adding them to the log.

A flight shows up in a track one of two ways: the phone keeps logging in the
air (a run of fixes hundreds of km/h apart), or it goes quiet at one airport
and wakes up at another (a single long hop). Both reduce to the same shape —
a stretch of consecutive *fast* hops — so the scan merges fast hops into runs
and keeps a run whose two ends are each at an airport (``airports.nearest``,
large/medium fields only) and that is long and quick enough to be a flight
rather than a train.

Suggestions are not stored. The raw candidates are cached per user and
re-derived in the background (the scan walks the whole history, so it never
runs on a request); what the bell shows is that list minus flights already
in the log and suggestions the user dismissed, filtered fresh on every read
so adding the flight retires its suggestion immediately.
"""
import logging
import math
from datetime import timedelta

from django.core.cache import cache

from . import airports

logger = logging.getLogger(__name__)

CACHE_KEY = 'flight_suggest:{}'
LOCK_KEY = 'flight_suggest_lock:{}'
CACHE_TTL = 12 * 3600
LOCK_TTL = 15 * 60

FAST_MPS = 50.0          # a hop at least this quick is part of a run (180 km/h)
MAX_MPS = 340.0          # quicker than any airliner's ground speed: a glitch
MIN_DIST_KM = 100.0      # shorter "flights" are almost always ground travel
MIN_AVG_MPS = 60.0       # end to end, incl. the ground time either side
MAX_DURATION_S = 22 * 3600
MAX_ACCURACY_M = 1000


def _km(lat1, lon1, lat2, lon2):
    return airports.great_circle_km(lat1, lon1, lat2, lon2)


def _runs(points):
    """Yield (origin, dest) fixes for each maximal run of fast hops.

    `points` is (timestamp, lat, lon) in time order. The origin is the fix
    *before* the first fast hop — the last one on the ground.
    """
    prev = None
    origin = None
    last = None
    for p in points:
        if prev is not None:
            dt = (p[0] - prev[0]).total_seconds()
            fast = False
            if dt > 0:
                v = _km(prev[1], prev[2], p[1], p[2]) * 1000 / dt
                fast = FAST_MPS <= v <= MAX_MPS
            if fast:
                if origin is None:
                    origin = prev
                last = p
            elif origin is not None:
                yield origin, last
                origin = last = None
        prev = p
    if origin is not None:
        yield origin, last


def scan_user(user):
    """Every plausible flight in the user's history, newest first."""
    from .models import Device, Location
    from .tz_utils import zone_name_for_point
    from zoneinfo import ZoneInfo

    found = {}
    for dev in Device.objects.filter(user=user):
        qs = (Location.objects.filter(device=dev)
              .exclude(accuracy__gt=MAX_ACCURACY_M)
              .order_by('timestamp')
              .values_list('timestamp', 'latitude', 'longitude')
              .iterator(chunk_size=5000))
        for origin, dest in _runs(qs):
            dur = (dest[0] - origin[0]).total_seconds()
            if dur <= 0 or dur > MAX_DURATION_S:
                continue
            dist = _km(origin[1], origin[2], dest[1], dest[2])
            if dist < MIN_DIST_KM or dist * 1000 / dur < MIN_AVG_MPS:
                continue
            dep = airports.nearest(origin[1], origin[2])
            arr = airports.nearest(dest[1], dest[2])
            if not dep or not arr or dep['iata'] == arr['iata']:
                continue
            try:
                tz = ZoneInfo(zone_name_for_point(dep['lat'], dep['lon']) or 'UTC')
            except Exception:
                tz = ZoneInfo('UTC')
            day = origin[0].astimezone(tz).date().isoformat()
            key = f"{day}:{dep['iata']}:{arr['iata']}"
            if key in found:   # the same flight seen by a second device
                continue
            found[key] = {
                'key': key, 'date': day,
                'dep': {k: dep[k] for k in ('iata', 'name', 'city', 'country')},
                'arr': {k: arr[k] for k in ('iata', 'name', 'city', 'country')},
                'left_at': origin[0].isoformat(), 'arrived_at': dest[0].isoformat(),
                'distance_km': round(_km(dep['lat'], dep['lon'], arr['lat'], arr['lon'])),
            }
    return sorted(found.values(), key=lambda s: s['left_at'], reverse=True)


def _compute(user_id):
    from django.contrib.auth.models import User
    try:
        user = User.objects.get(pk=user_id)
        cache.set(CACHE_KEY.format(user_id), scan_user(user), CACHE_TTL)
    except Exception:
        logger.exception('flight suggestion scan failed for user %s', user_id)
    finally:
        cache.delete(LOCK_KEY.format(user_id))


def candidates(user):
    """The cached candidates, kicking a background scan when there are none.

    Returns [] until the first scan lands; the bell picks them up next load.
    """
    data = cache.get(CACHE_KEY.format(user.id))
    if data is None:
        if cache.add(LOCK_KEY.format(user.id), 1, LOCK_TTL):
            from .scheduler_utils import db_thread
            db_thread(_compute, args=(user.id,), name="flight-suggest").start()
        return []
    return data


def pending(user):
    """Candidates not already in the flight log and not dismissed."""
    from .models import Flight, DismissedFlightSuggestion
    from datetime import date as _date

    cands = candidates(user)
    if not cands:
        return []
    days = [_date.fromisoformat(c['date']) for c in cands]
    lo, hi = min(days) - timedelta(days=1), max(days) + timedelta(days=1)
    logged = set()
    for d, a, b in Flight.objects.filter(user=user, date__gte=lo, date__lte=hi) \
            .values_list('date', 'dep_iata', 'arr_iata'):
        for off in (-1, 0, 1):
            logged.add(f"{(d + timedelta(days=off)).isoformat()}:{a}:{b}")
    dismissed = set(DismissedFlightSuggestion.objects.filter(user=user)
                    .values_list('key', flat=True))
    return [c for c in cands if c['key'] not in logged and c['key'] not in dismissed]


# ---------------------------------------------------------------------------
# Linking a logged flight to its recorded track
# ---------------------------------------------------------------------------
#
# The reverse question to the suggestions above: given a flight the user has
# logged, is there a recording of it in their history — their phone logging
# in the air, or a Flightradar24 KML they imported? A match is stored on the
# flight as a link (device + window), never as copied points.
#
# Matching anchors on the airports rather than on fast-hop runs, because a
# recording is often broken by one duplicate fix (speed 0 between identical
# timestamps), which would split a run in two: the track is the stretch from
# the last point at (or nearest) the departure airport to the first point at
# (or nearest) the arrival airport after it. "Near" is ENDPOINT_KM, widened
# for long routes to TRACK_ENDPOINT_FRAC of the route, so a KML that starts
# already airborne still matches.

TRACK_PAD = timedelta(hours=3)
ENDPOINT_KM = 30.0
TRACK_ENDPOINT_FRAC = 0.15
TRACK_SLACK_KM = 3.0          # "as near as the nearest point, give or take"
TRACK_MIN_POINTS = 5          # fewer is a phone switched off, not a recording
TRACK_MIN_FAST_HOPS = 3
TRACK_MAX_POINTS_SCANNED = 50000
TAKEOFF_MAX_GAP_S = 300       # only infer takeoff/landing from dense sampling


def _track_window(flight):
    from datetime import datetime, time as dtime
    from zoneinfo import ZoneInfo
    from .tz_utils import zone_name_for_point

    dep = flight.actual_dep or flight.sched_dep
    arr = flight.actual_arr or flight.sched_arr
    if dep and arr and arr > dep:
        return dep - TRACK_PAD, arr + TRACK_PAD
    if dep:
        return dep - TRACK_PAD, dep + timedelta(hours=20)
    if arr:
        return arr - timedelta(hours=20), arr + TRACK_PAD
    try:
        tz = ZoneInfo(flight.dep_tz or zone_name_for_point(flight.dep_lat, flight.dep_lon) or 'UTC')
    except Exception:
        tz = ZoneInfo('UTC')
    start = datetime.combine(flight.date, dtime.min).replace(tzinfo=tz)
    # The whole local day of departure, plus an overnight arrival.
    return start - timedelta(hours=2), start + timedelta(hours=34)


def _hop_mps(a, b):
    dt = (b[0] - a[0]).total_seconds()
    return _km(a[1], a[2], b[1], b[2]) * 1000 / dt if dt > 0 else 0.0


def _match_in(points, dep, arr, route_km):
    """(i, j, score) of the flight within one device's points, or None."""
    limit = max(ENDPOINT_KM, TRACK_ENDPOINT_FRAC * route_km)
    d_dep = [_km(p[1], p[2], dep[0], dep[1]) for p in points]
    d_arr = [_km(p[1], p[2], arr[0], arr[1]) for p in points]
    near_dep = [d for d in d_dep if d <= limit]
    near_arr = [d for d in d_arr if d <= limit]
    if not near_dep or not near_arr:
        return None
    dep_ok = min(near_dep) + TRACK_SLACK_KM
    arr_ok = min(near_arr) + TRACK_SLACK_KM
    last_dep = None
    for k, p in enumerate(points):
        if d_dep[k] <= dep_ok:
            last_dep = k
        elif d_arr[k] <= arr_ok and last_dep is not None:
            i, j = last_dep, k
            if j - i + 1 < TRACK_MIN_POINTS:
                return None
            if (points[j][0] - points[i][0]).total_seconds() > MAX_DURATION_S:
                return None
            fast = sum(1 for a, b in zip(points[i:j], points[i + 1:j + 1])
                       if FAST_MPS <= _hop_mps(a, b) <= MAX_MPS)
            if fast < TRACK_MIN_FAST_HOPS:
                return None
            return i, j, d_dep[i] + d_arr[j]
    return None


def match_track(flight):
    """The best recording of `flight` in its owner's history, or None.

    Returns a dict: device, start, end, km, takeoff, landing (the last two
    None unless the sampling was dense enough to say).
    """
    from .models import Device, Location

    if None in (flight.dep_lat, flight.dep_lon, flight.arr_lat, flight.arr_lon):
        return None
    dep = (flight.dep_lat, flight.dep_lon)
    arr = (flight.arr_lat, flight.arr_lon)
    route_km = _km(dep[0], dep[1], arr[0], arr[1])
    if route_km < 20:
        return None
    lo, hi = _track_window(flight)

    best = None
    for dev in Device.objects.filter(user_id=flight.user_id):
        points = list(Location.objects.filter(device=dev, timestamp__gte=lo, timestamp__lte=hi)
                      .exclude(accuracy__gt=MAX_ACCURACY_M)
                      .order_by('timestamp')
                      .values_list('timestamp', 'latitude', 'longitude')[:TRACK_MAX_POINTS_SCANNED])
        if len(points) < TRACK_MIN_POINTS:
            continue
        m = _match_in(points, dep, arr, route_km)
        if m and (best is None or m[2] < best[3]):
            best = (dev, points, m[0], m[1], m[2])
    if best is None:
        return None

    dev, points, i, j = best[:4]
    seg = points[i:j + 1]
    km = sum(_km(a[1], a[2], b[1], b[2]) for a, b in zip(seg, seg[1:]))
    hops = list(zip(seg, seg[1:]))
    fast = [k for k, (a, b) in enumerate(hops) if FAST_MPS <= _hop_mps(a, b) <= MAX_MPS]
    takeoff = landing = None
    if fast:
        a, b = hops[fast[0]]
        if (b[0] - a[0]).total_seconds() <= TAKEOFF_MAX_GAP_S:
            takeoff = a[0]
        a, b = hops[fast[-1]]
        if (b[0] - a[0]).total_seconds() <= TAKEOFF_MAX_GAP_S:
            landing = b[0]
    return {'device': dev, 'start': seg[0][0], 'end': seg[-1][0], 'km': round(km, 1),
            'takeoff': takeoff, 'landing': landing}


def apply_track(flight, force=False):
    """Match `flight` and store the link. Returns True when a track is linked.

    Respects an unlink (track_auto False) unless `force`. Fills actual
    takeoff/landing only where the flight has none — AeroDataBox or the user
    always win over an estimate from GPS sampling.
    """
    if not flight.track_auto and not force:
        return bool(flight.track_device_id)
    m = match_track(flight)
    fields = ['track_device', 'track_start', 'track_end', 'track_distance_km']
    if m is None:
        flight.track_device = None
        flight.track_start = flight.track_end = flight.track_distance_km = None
    else:
        flight.track_device = m['device']
        flight.track_start, flight.track_end = m['start'], m['end']
        flight.track_distance_km = m['km']
        if force:
            flight.track_auto = True
            fields.append('track_auto')
        if flight.actual_dep is None and m['takeoff']:
            flight.actual_dep = m['takeoff']
            fields.append('actual_dep')
        if flight.actual_arr is None and m['landing']:
            flight.actual_arr = m['landing']
            fields.append('actual_arr')
    if flight.pk:
        type(flight).objects.filter(pk=flight.pk).update(**{f: getattr(flight, f) for f in fields})
    return m is not None


def track_points(flight, max_points=2000):
    """The linked track as [[lon, lat], ...], longitudes unwrapped so a route
    over the date line stays one continuous line, simplified to <= max_points."""
    from .models import Location
    from .activity_track import simplify

    if not (flight.track_device_id and flight.track_start and flight.track_end):
        return []
    rows = list(Location.objects.filter(device_id=flight.track_device_id,
                                        timestamp__gte=flight.track_start,
                                        timestamp__lte=flight.track_end)
                .exclude(accuracy__gt=MAX_ACCURACY_M)
                .order_by('timestamp')
                .values_list('timestamp', 'latitude', 'longitude')[:TRACK_MAX_POINTS_SCANNED])
    seg, prev = [], None
    for t, lat, lon in rows:
        if prev is not None:
            while lon - prev > 180:
                lon -= 360
            while lon - prev < -180:
                lon += 360
        prev = lon
        seg.append((t, lat, lon))
    if len(seg) <= max_points:
        keep = range(len(seg))
    else:
        tol = 50.0
        keep = simplify(seg, tol)
        while len(keep) > max_points:
            tol *= 2
            keep = simplify(seg, tol)
    return [[round(seg[k][2], 5), round(seg[k][1], 5)] for k in keep]
