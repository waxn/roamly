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
