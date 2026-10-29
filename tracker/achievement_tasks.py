"""Achievements: the background scan behind Data → Achievements.

Everything on the page is precomputed here and stored on the per-user
``AchievementSnapshot`` row, which ``/api/achievements/`` serves as-is — the
same "never compute a whole-history aggregate on a request" contract Stats and
Visits keep through ``StatsSnapshot``. A separate row and job rather than more
StatsSnapshot columns because the road-coverage phase is far heavier than the
stats compute, and gets its own status, progress and advisory lock.

Phases (each reports ``stage``/``pct`` and honours Stop):

1. **Boundaries** — fetch geoBoundaries polygons for any country the user has
   spent more than a week in (``boundary_download_tasks``).
2. **History pass** — one ordered stream over each device's points that feeds
   every per-point metric at once (distance per day, corroborated top speed,
   extremes, climbing, journeys, rhythm, crossings, labels per day…), so the
   history is read exactly once however many metrics there are.
3. **Geography** (PostGIS) — ~220m cell centres joined against ``Boundary``
   for town / county / region coverage with first-visit times.
4. **Roads** (PostGIS) — mapped roads in visited towns and counties sampled
   every 25m; a sample is driven if one of the user's own hops passed within
   ~25m. Skipped when nothing it depends on changed (``roads_fingerprint``).
5. **Places** — ``Visit`` rows + POI names: regulars, top store, chains,
   commute, nature/culture.
6. **Extras** — flights, health, activities, cells, journals (each optional).
7. **Badges** — evaluate the registry, persist new unlocks (back-dated where
   the metric has a natural first-reached time), compute XP and level.

Max speed is *corroborated*, never a raw ``Max(speed)``: a parked phone that
reports a garbage 45 m/s Doppler reading (see ``_is_jitter`` in views) would
otherwise hold the record forever. A reading counts only as the median of a
5-fix window whose own displacement agrees with it.

Single-flight per user via a PostgreSQL advisory lock in namespace
``0x52414d48`` ('RAMH'), distinct from every other scheduler's.
"""
import heapq
import logging
import math
import threading
import time
from collections import Counter, defaultdict, deque
from contextlib import contextmanager
from datetime import datetime, timezone as dt_timezone

from django.core.cache import cache
from django.db import close_old_connections, connection
from django.utils import timezone

from . import achievement_defs as defs
from .scheduler_utils import db_thread

logger = logging.getLogger(__name__)

_LOCK_NAMESPACE = 0x52414d48       # 'RAMH'
SCHEDULER_CHECK_INTERVAL = 1800    # 30 min between nightly sweeps
STALE_AFTER_S = 600                # silent this long while 'running' ⇒ dead
HEARTBEAT_EVERY_S = 10
_STOP_KEY = 'achievements_stop:{uid}'

_threads = {}
_scheduler_thread = None

WORLD_COUNTRIES = 195
US_STATES_TOTAL = 51               # 50 states + DC
EARTH_KM = 40075.0
MOON_KM = 384400.0
EVEREST_M = 8849.0

# History-pass tuning.
_SPEED_WIN = 5
_GROUND_CAP_MPS = 100.0            # ~360 km/h: nothing on the ground beats it
_MODE_CAP_MPS = {'walk': 4.5, 'cycle': 22.0, 'vehicle': 100.0}
_SPEED_MAX_ACC_M = 30.0
# Airborne detection for the ground-speed record. A takeoff roll or a landing
# reads 70-90 m/s, under the ground cap, and is only labelled 'plane' once the
# transport job has run, so the record is chosen afterwards from the top
# candidates, skipping any that fall in an airborne window.
_SPEED_CANDIDATES = 400
_MODE_CANDIDATES = 60
_AIR_PAD_S = 30 * 60               # either side of airborne evidence
_AIR_DOPPLER_MPS = _GROUND_CAP_MPS  # median Doppler above this is a plane
_AIR_HOP_MPS = 50.0                # a hop this fast over _AIR_HOP_KM: flown
_AIR_HOP_KM = 100.0                # (flight_detect.FAST_MPS / MIN_DIST_KM)
_AIRPORT_ROLL_MPS = 55.0           # this fast at a large/medium airport: runway
_ALT_MAX_ACC_M = 25.0
_ALT_HYST_M = 5.0
_ALT_GAP_S = 600
_JOURNEY_STOP_S = 600
_JOURNEY_GAP_S = 900
_JOURNEY_MOVE_MPS = 1.0
_JOURNEY_MIN_S = 120
_JOURNEY_MIN_KM = 0.3
_HOP_MAX_MPS = 340.0
_HOME_CELL = 500                   # 1/500° ≈ 220m
_DWELL_CELL = 100                  # 1/100° ≈ 1.1km
_TZ_CELL = 4                       # 1/4°
_AWAY_KM = 50.0

_SEASONS = {12: 'winter', 1: 'winter', 2: 'winter', 3: 'spring', 4: 'spring', 5: 'spring',
            6: 'summer', 7: 'summer', 8: 'summer', 9: 'autumn', 10: 'autumn', 11: 'autumn'}

US_STATE_NAMES = {
    'Alabama', 'Alaska', 'Arizona', 'Arkansas', 'California', 'Colorado', 'Connecticut',
    'Delaware', 'District of Columbia', 'Florida', 'Georgia', 'Hawaii', 'Idaho', 'Illinois',
    'Indiana', 'Iowa', 'Kansas', 'Kentucky', 'Louisiana', 'Maine', 'Maryland',
    'Massachusetts', 'Michigan', 'Minnesota', 'Mississippi', 'Missouri', 'Montana',
    'Nebraska', 'Nevada', 'New Hampshire', 'New Jersey', 'New Mexico', 'New York',
    'North Carolina', 'North Dakota', 'Ohio', 'Oklahoma', 'Oregon', 'Pennsylvania',
    'Rhode Island', 'South Carolina', 'South Dakota', 'Tennessee', 'Texas', 'Utah',
    'Vermont', 'Virginia', 'Washington', 'West Virginia', 'Wisconsin', 'Wyoming',
}


class Stopped(Exception):
    pass


def _hav_km(lat1, lon1, lat2, lon2):
    r = math.radians
    dlat = r(lat2 - lat1)
    dlon = r(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(r(lat1)) * math.cos(r(lat2)) * math.sin(dlon / 2) ** 2
    return 12742.0 * math.asin(min(1.0, math.sqrt(a)))


def _iso(dt):
    return dt.isoformat() if dt else None


def _median(vals):
    s = sorted(vals)
    n = len(s)
    if not n:
        return None
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2.0


def _longest_run(dates):
    """Longest run of consecutive dates, and the date it ended on."""
    best, best_end, run, prev = 0, None, 0, None
    for d in sorted(dates):
        run = run + 1 if prev is not None and (d - prev).days == 1 else 1
        if run > best:
            best, best_end = run, d
        prev = d
    return best, best_end


def corroborated_speed(window):
    """Median Doppler speed of a window of fixes, or None if it can't be trusted.

    ``window`` holds ``(lat, lon, epoch, speed, acc)``. Every fix must carry a
    speed and decent accuracy, and the window's own displacement must account
    for at least half the speed it claims — a parked phone reporting garbage
    Doppler (the ``jitter`` case) moves metres while claiming tens of m/s.
    """
    if len(window) < 3:
        return None
    speeds = []
    for _lat, _lon, _ep, spd, acc in window:
        if spd is None or spd < 0 or acc is None or acc > _SPEED_MAX_ACC_M:
            return None
        speeds.append(spd)
    med = _median(speeds)
    if med is None or med > _GROUND_CAP_MPS:
        return None
    first, last = window[0], window[-1]
    dt = last[2] - first[2]
    if dt <= 0:
        return None
    implied = _hav_km(first[0], first[1], last[0], last[1]) * 1000.0 / dt
    if med > 2.0 and implied < 0.5 * med:
        return None
    return med


def _keep_top(heap, item, n):
    if len(heap) < n:
        heapq.heappush(heap, item)
    elif item[0] > heap[0][0]:
        heapq.heapreplace(heap, item)


def _mark_air(wins, start, end):
    """Add an airborne window, merging into the last one (fixes come in time
    order per device, so windows only ever grow at the end)."""
    s, e = start - _AIR_PAD_S, end + _AIR_PAD_S
    if wins and s <= wins[-1][1]:
        wins[-1][1] = max(wins[-1][1], e)
    else:
        wins.append([s, e])


def _flight_windows(user):
    """Logged flights as (start, end, airports) — times padded, or the
    departure date when the flight has no times, limited to fixes at either
    airport."""
    from .models import Flight
    out = []
    for f in Flight.objects.filter(user=user):
        start = f.actual_dep or f.sched_dep or f.track_start
        end = f.actual_arr or f.sched_arr or f.track_end
        ports = [(la, lo) for la, lo in ((f.dep_lat, f.dep_lon), (f.arr_lat, f.arr_lon))
                 if la is not None and lo is not None]
        if start and end:
            out.append((start.timestamp() - _AIR_PAD_S, end.timestamp() + _AIR_PAD_S, None))
        elif f.date and ports:
            day = datetime.combine(f.date, datetime.min.time()).replace(tzinfo=dt_timezone.utc)
            # The date is the departure's local day: allow the widest offsets.
            out.append((day.timestamp() - 14 * 3600, day.timestamp() + 38 * 3600, ports))
    return out


def _ground_speed_records(speed_top, mode_top, airborne, flights):
    """Pick the fastest candidates that weren't in a plane.

    Returns ``(max_speed, mode_speed)`` shaped as before:
    ``(mps, ts, lat, lon, mode)`` and ``{mode: (mps, ts, lat, lon)}``.
    """
    from bisect import bisect_right
    from . import airports
    starts = {dev: [w[0] for w in wins] for dev, wins in airborne.items()}

    def grounded(c):
        mps, _seq, dev, ep, _ts, lat, lon, _m = c
        wins = airborne.get(dev)
        if wins:
            i = bisect_right(starts[dev], ep) - 1
            if i >= 0 and ep <= wins[i][1]:
                return False
        for s, e, ports in flights:
            if s <= ep <= e and (ports is None or any(
                    _hav_km(lat, lon, pa, po) <= 10.0 for pa, po in ports)):
                return False
        if mps >= _AIRPORT_ROLL_MPS and airports.nearest(lat, lon):
            return False
        return True

    max_speed = None
    for c in sorted(speed_top, reverse=True):
        if grounded(c):
            max_speed = (c[0], c[4], c[5], c[6], c[7])
            break
    mode_speed = {}
    for m, heap in mode_top.items():
        for c in sorted(heap, reverse=True):
            if grounded(c):
                mode_speed[m] = (c[0], c[4], c[5], c[6])
                break
    return max_speed, mode_speed


def home_from_counts(night_counts):
    """The most-slept-at ~220m cell → (lat, lon), or None."""
    if not night_counts:
        return None
    (gy, gx), _n = night_counts.most_common(1)[0]
    return gy / _HOME_CELL, gx / _HOME_CELL


# ── Job plumbing ─────────────────────────────────────────────────────────────

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


class _Progress:
    def __init__(self, snap_id, user_id):
        self.snap_id = snap_id
        self.user_id = user_id
        self._last = 0.0

    def __call__(self, stage, pct, force=False):
        from .models import AchievementSnapshot
        now = time.monotonic()
        if not force and now - self._last < HEARTBEAT_EVERY_S:
            return
        self._last = now
        AchievementSnapshot.objects.filter(pk=self.snap_id).update(
            stage=stage[:120], pct=round(max(0.0, min(100.0, pct)), 1),
            heartbeat_at=timezone.now())

    def check_stop(self):
        if cache.get(_STOP_KEY.format(uid=self.user_id)):
            raise Stopped()


def start_achievement_scan(user_id):
    from .models import AchievementSnapshot
    t = _threads.get(user_id)
    if t is not None and t.is_alive():
        return 'running'
    snap, _ = AchievementSnapshot.objects.get_or_create(user_id=user_id)
    if (snap.status == 'running' and snap.heartbeat_at
            and (timezone.now() - snap.heartbeat_at).total_seconds() < STALE_AFTER_S):
        return 'running'  # running in another worker
    cache.delete(_STOP_KEY.format(uid=user_id))
    # Claim immediately so a status poll right after the click shows it.
    AchievementSnapshot.objects.filter(pk=snap.pk).update(
        status='running', stage='Starting', pct=0, error='',
        started_at=timezone.now(), heartbeat_at=timezone.now())
    t = db_thread(compute_achievements, args=(user_id,), name=f'achievements-{user_id}')
    _threads[user_id] = t
    t.start()
    return 'started'


def stop_achievement_scan(user_id):
    cache.set(_STOP_KEY.format(uid=user_id), 1, 3600)


def get_status(user_id):
    from .models import AchievementSnapshot
    row = (AchievementSnapshot.objects.filter(user_id=user_id)
           .values('status', 'stage', 'pct', 'error', 'started_at', 'heartbeat_at', 'computed_at')
           .first())
    if not row:
        return {'status': 'idle', 'stage': '', 'pct': 0, 'error': '', 'computed_at': None}
    status = row['status']
    if status == 'running' and row['heartbeat_at'] and \
            (timezone.now() - row['heartbeat_at']).total_seconds() > STALE_AFTER_S:
        # The thread died with its worker (a redeploy). Report it, and let the
        # next scan start cleanly.
        AchievementSnapshot.objects.filter(user_id=user_id, status='running').update(
            status='error', error='The scan stopped unexpectedly (server restart?). Start it again.')
        status = 'error'
        row['error'] = 'The scan stopped unexpectedly (server restart?). Start it again.'
    return {
        'status': status, 'stage': row['stage'], 'pct': row['pct'], 'error': row['error'],
        'started_at': _iso(row['started_at']), 'computed_at': _iso(row['computed_at']),
    }


def compute_achievements(user_id):
    from django.contrib.auth.models import User
    from .models import AchievementSnapshot
    from .tz_utils import user_timezone

    snap = AchievementSnapshot.objects.get_or_create(user_id=user_id)[0]
    prog = _Progress(snap.pk, user_id)
    with _user_lock(user_id) as got:
        if not got:
            return
        AchievementSnapshot.objects.filter(pk=snap.pk).update(
            status='running', error='', started_at=timezone.now(), heartbeat_at=timezone.now())
        try:
            user = User.objects.get(id=user_id)
            with user_timezone(user):
                payload, roads, fp = _compute(user, snap, prog)
            AchievementSnapshot.objects.filter(pk=snap.pk).update(
                payload_json=payload, roads_json=roads, roads_fingerprint=fp,
                status='done', stage='Done', pct=100, error='', computed_at=timezone.now(),
                heartbeat_at=timezone.now())
            logger.info('Achievements computed for user %s', user_id)
        except Stopped:
            AchievementSnapshot.objects.filter(pk=snap.pk).update(
                status='stopped', stage='Stopped', heartbeat_at=timezone.now())
        except Exception as e:
            logger.exception('Achievements failed for user %s', user_id)
            AchievementSnapshot.objects.filter(pk=snap.pk).update(
                status='error', error=str(e)[:500], heartbeat_at=timezone.now())
        finally:
            cache.delete(_STOP_KEY.format(uid=user_id))


# ── The computation ──────────────────────────────────────────────────────────

def _compute(user, snap, prog):
    from .models import HAS_POSTGIS

    prog('Checking region boundaries', 1, force=True)
    prog.check_stop()
    from . import boundary_download_tasks as bdt
    bdt.ensure_boundaries(user, progress=lambda s: prog(s, 2, force=True),
                          stop=lambda: bool(cache.get(_STOP_KEY.format(uid=user.id))))
    prog.check_stop()

    hist = _history_pass(user, prog)                 # 5 → 55
    metrics = hist.pop('_metrics')
    geo = {}
    if HAS_POSTGIS:
        prog('Mapping towns, counties and regions', 56, force=True)
        geo = _geography(user, prog, metrics, hist)   # 56 → 65
    prog.check_stop()

    roads, fp = {}, ''
    if HAS_POSTGIS:
        roads, fp = _roads(user, snap, prog, geo)     # 65 → 88
        metrics.update(roads.get('_metrics', {}))
    prog.check_stop()

    prog('Finding your regulars', 89, force=True)
    places = _places(user, metrics, hist.get('home'))
    prog('Gathering flights, health and cells', 93, force=True)
    extras = _extras(user, metrics)
    transport = _transport(user, metrics)

    prog('Awarding badges', 97, force=True)
    badges = _badges(user, metrics, hist.pop('_reach', {}))

    payload = {
        'version': 1,
        'computed_for': timezone.localdate().isoformat(),
        'metrics': metrics,
        'game': badges,
        'explorer': geo.get('explorer', {}),
        'roads': {k: v for k, v in roads.items() if not k.startswith('_')},
        'transport': transport,
        'places': places,
        'extras': extras,
        'postgis': HAS_POSTGIS,
        **{k: v for k, v in hist.items() if not k.startswith('_')},
    }
    payload['explorer'].setdefault('countries', hist.get('countries_list', []))
    return payload, roads, fp


# ── Phase 2: one pass over the history ───────────────────────────────────────

def _history_pass(user, prog):
    from .models import Device, Location
    from .views import _gated_distance_segments

    devices = list(Device.objects.filter(user=user).values_list('id', flat=True))
    from django.db.models import Count
    counts = dict(
        Location.objects.filter(device_id__in=devices).order_by()
        .values_list('device_id').annotate(n=Count('id'))
    ) if devices else {}
    grand = sum(counts.values()) or 1
    seen = 0

    days = set()
    day_km = defaultdict(float)
    hour_week_km = [[0.0] * 24 for _ in range(7)]
    early_days, night_days = set(), set()
    day_towns, day_states, day_countries = defaultdict(set), defaultdict(set), defaultdict(set)
    towns = {}            # (city, state, cc) -> set(dates)
    town_first = {}
    us_states = {}        # state -> first ts
    country_days = defaultdict(set)
    country_first = {}
    country_names = {}
    ext = {'n': None, 's': None, 'e': None, 'w': None, 'hi': None, 'lo': None}
    speed_top = []        # min-heap of (mps, seq, dev, epoch, ts, lat, lon, mode)
    mode_top = defaultdict(list)
    airborne = defaultdict(list)  # dev -> merged [start, end] epoch windows
    plane_speed = None
    climbed = 0.0
    day_climb = defaultdict(float)
    night_counts = Counter()
    night_spot = {}       # date -> (lat, lon) first fix between 01:00 and 04:59
    dwell = defaultdict(float)  # 1km cell -> seconds
    tz_cells = set()
    hemis = set()
    crossings = {}
    journeys = []
    year_km = defaultdict(float)
    month_km = defaultdict(float)

    def note_extreme(key, val, better, lat, lon, ts, extra=None):
        cur = ext[key]
        if cur is None or better(val, cur['v']):
            ext[key] = {'v': val, 'lat': lat, 'lon': lon, 'ts': _iso(ts), **(extra or {})}

    for dev_id in devices:
        rows = (Location.objects.filter(device_id=dev_id)
                .order_by('timestamp', 'id')
                .values_list('latitude', 'longitude', 'timestamp', 'altitude', 'accuracy',
                             'speed', 'transport_mode', 'flag', 'city', 'state', 'country',
                             'country_code')
                .iterator(chunk_size=5000))

        win = deque(maxlen=_SPEED_WIN)
        prev = None
        alt_ref = None
        alt_ts = None
        jr = None  # current journey

        def close_journey():
            nonlocal jr
            if jr and jr['last_move'] is not None:
                dur = (jr['last_move'] - jr['start']).total_seconds()
                if dur >= _JOURNEY_MIN_S and jr['km'] >= _JOURNEY_MIN_KM:
                    mode = (jr['modes'].most_common(1)[0][0] if jr['modes'] else '')
                    spd = sorted(jr['speeds'])
                    p85 = spd[int(len(spd) * 0.85)] if spd else 0.0
                    if not mode:
                        mode = ('walk' if p85 < 2.5 else 'cycle' if p85 < 7 else 'vehicle')
                    if mode != 'plane':
                        journeys.append({
                            'start': jr['start'], 'end': jr['last_move'], 'km': jr['km'],
                            'mode': mode, 'p85': p85,
                            'from': jr['from'], 'to': jr['to'] or jr['from'],
                            'lat': jr['lat'], 'lon': jr['lon'],
                        })
            jr = None

        def points(rows, win):
            nonlocal prev, alt_ref, alt_ts, jr, seen, plane_speed, climbed
            for (lat, lon, ts, alt, acc, spd, mode, flag, city, state, country, cc) in rows:
                seen += 1
                if seen % 20000 == 0:
                    prog(f'Reading your history ({seen:,} / {grand:,} points)',
                         5 + 50 * seen / grand)
                    prog.check_stop()
                if lat is None or lon is None:
                    continue
                local = timezone.localtime(ts)
                d = local.date()
                days.add(d)
                if flag == 'suspect':
                    continue

                # Labels.
                if city:
                    k = (city, state or '', (cc or '').upper())
                    s = towns.get(k)
                    if s is None:
                        towns[k] = s = set()
                        town_first[k] = ts
                    s.add(d)
                    day_towns[d].add(k)
                if state:
                    day_states[d].add(((cc or '').upper(), state))
                    if (cc or '').upper() == 'US' and state in US_STATE_NAMES and state not in us_states:
                        us_states[state] = ts
                if cc:
                    ccu = cc.upper()
                    day_countries[d].add(ccu)
                    country_days[ccu].add(d)
                    if ccu not in country_first:
                        country_first[ccu] = ts
                        country_names[ccu] = country or ccu

                good = acc is None or acc <= 100
                if good:
                    note_extreme('n', lat, lambda a, b: a > b, lat, lon, ts, {'place': city or ''})
                    note_extreme('s', lat, lambda a, b: a < b, lat, lon, ts, {'place': city or ''})
                    note_extreme('e', lon, lambda a, b: a > b, lat, lon, ts, {'place': city or ''})
                    note_extreme('w', lon, lambda a, b: a < b, lat, lon, ts, {'place': city or ''})
                    hemis.add('N' if lat >= 0 else 'S')
                    hemis.add('E' if lon >= 0 else 'W')
                    tz_cells.add((round(lat * _TZ_CELL), round(lon * _TZ_CELL)))
                    if local.hour < 5:
                        night_counts[(round(lat * _HOME_CELL), round(lon * _HOME_CELL))] += 1
                        if 1 <= local.hour and d not in night_spot:
                            night_spot[d] = (lat, lon)

                ep = ts.timestamp()
                # Altitude, climbing.
                if (alt is not None and acc is not None and acc <= _ALT_MAX_ACC_M
                        and -430 < alt < 8900 and mode != 'plane' and (spd is None or spd < 70)):
                    note_extreme('hi', alt, lambda a, b: a > b, lat, lon, ts, {'place': city or ''})
                    note_extreme('lo', alt, lambda a, b: a < b, lat, lon, ts, {'place': city or ''})
                    if alt_ref is None or alt_ts is None or ep - alt_ts > _ALT_GAP_S:
                        alt_ref = alt
                    elif alt > alt_ref + _ALT_HYST_M:
                        climbed += alt - alt_ref
                        day_climb[d] += alt - alt_ref
                        alt_ref = alt
                    elif alt < alt_ref - _ALT_HYST_M:
                        alt_ref = alt
                    alt_ts = ep

                # Corroborated speed.
                win.append((lat, lon, ep, spd, acc, mode, ts))
                if len(win) == _SPEED_WIN:
                    med = corroborated_speed([w[:5] for w in win])
                    mid = win[_SPEED_WIN // 2]
                    if med is not None and (mid[5] or '') != 'plane':
                        m = mid[5] or ''
                        cand = (med, seen, dev_id, mid[2], mid[6], mid[0], mid[1], m)
                        _keep_top(speed_top, cand, _SPEED_CANDIDATES)
                        cap = _MODE_CAP_MPS.get(m)
                        if cap and med <= cap:
                            _keep_top(mode_top[m], cand, _MODE_CANDIDATES)
                    dop = [w[3] for w in win]
                    if all(x is not None for x in dop) and _median(dop) > _AIR_DOPPLER_MPS:
                        _mark_air(airborne[dev_id], win[0][2], win[-1][2])
                    if (mid[5] == 'plane' and mid[3] is not None and 50 < mid[3] < 340
                            and (plane_speed is None or mid[3] > plane_speed[0])):
                        plane_speed = (mid[3], mid[6], mid[0], mid[1])
                if mode == 'plane':
                    _mark_air(airborne[dev_id], ep, ep)

                # Hop-based things: dwell, crossings, journeys.
                if prev is not None:
                    plat, plon, pep, pacc = prev
                    dt = ep - pep
                    if dt > 0:
                        cell = (round(plat * _DWELL_CELL), round(plon * _DWELL_CELL))
                        dwell[cell] += min(dt, 1800.0)
                        if dt <= 6 * 3600:
                            for name, line in (('equator', 0.0), ('tropic_cancer', 23.4365),
                                               ('tropic_capricorn', -23.4365),
                                               ('arctic', 66.5634), ('antarctic', -66.5634)):
                                if (plat - line) * (lat - line) < 0 and name not in crossings:
                                    crossings[name] = _iso(ts)
                            if abs(lon - plon) > 180 and abs(lon) > 150 and abs(plon) > 150 \
                                    and 'dateline' not in crossings:
                                crossings['dateline'] = _iso(ts)
                        hop_km = _hav_km(plat, plon, lat, lon)
                        hop_mps = hop_km * 1000.0 / dt
                        if hop_km >= _AIR_HOP_KM and _AIR_HOP_MPS <= hop_mps < _HOP_MAX_MPS:
                            _mark_air(airborne[dev_id], pep, ep)
                        v = spd if spd is not None else hop_mps
                        # The displacement has to back the speed up: a parked
                        # phone's garbage Doppler would otherwise "move".
                        moving = v >= _JOURNEY_MOVE_MPS and 0.5 <= hop_mps < _HOP_MAX_MPS
                        if dt > _JOURNEY_GAP_S:
                            # A hole in the track ends the journey, and the hop
                            # across it is not travel we saw: it must not start
                            # the next one either (it would credit the whole gap).
                            close_journey()
                            moving = False
                        elif moving:
                            if jr is None:
                                jr = {'start': timezone.localtime(datetime.fromtimestamp(pep, dt_timezone.utc)),
                                      'last_move': None, 'km': 0.0, 'modes': Counter(), 'speeds': [],
                                      'stop_since': None, 'from': city or '', 'to': '',
                                      'lat': plat, 'lon': plon}
                            if (acc is None or acc <= 50) and (pacc is None or pacc <= 50):
                                jr['km'] += hop_km
                            jr['last_move'] = local
                            jr['stop_since'] = None
                            jr['to'] = city or jr['to']
                            if mode and mode != 'still':
                                jr['modes'][mode] += 1
                            jr['speeds'].append(min(v, _HOP_MAX_MPS))
                        elif jr is not None and dt > _JOURNEY_STOP_S:
                            # Stood still with the phone silent for longer than
                            # a stop: that is a stop, even with no fixes in it.
                            close_journey()
                        elif jr is not None:
                            if jr['stop_since'] is None:
                                jr['stop_since'] = ep
                            elif ep - jr['stop_since'] > _JOURNEY_STOP_S:
                                close_journey()
                prev = (lat, lon, ep, acc)
                yield lat, lon, ts, acc

        for cdt, km in _gated_distance_segments(points(rows, win)):
            local = timezone.localtime(cdt)
            d = local.date()
            day_km[d] += km
            year_km[d.year] += km
            month_km[(d.year, d.month)] += km
            hour_week_km[local.weekday()][local.hour] += km
            if 3 <= local.hour < 6:
                early_days.add(d)
            elif local.hour < 3:
                night_days.add(d)
        close_journey()
        prog.check_stop()

    prog('Summing up your history', 55, force=True)
    max_speed, mode_speed = _ground_speed_records(
        speed_top, mode_top, airborne, _flight_windows(user))
    return _summarise_history(locals())


def _summarise_history(v):
    """Turn the raw accumulators of ``_history_pass`` into payload sections +
    flat metrics. Separate so the pass itself stays a loop."""
    days, day_km = v['days'], v['day_km']
    towns, town_first = v['towns'], v['town_first']
    country_days, country_first, country_names = v['country_days'], v['country_first'], v['country_names']
    journeys = v['journeys']
    today = timezone.localdate()
    m = {}

    # ── Distance & streaks ────────────────────────────────────────────────
    total_km = sum(day_km.values())
    m['total_km'] = round(total_km, 1)
    best_day = max(day_km.items(), key=lambda kv: kv[1]) if day_km else None
    m['best_day_km'] = round(best_day[1], 1) if best_day else None
    moving_days = {d for d, km in day_km.items() if km > 10}
    m['move_streak'], move_end = _longest_run(moving_days)
    m['days_tracked'] = len(days)
    m['track_streak'], track_end = _longest_run(days)
    m['early_bird_days'] = len(v['early_days'])
    m['night_owl_days'] = len(v['night_days'])

    # cumulative distance reach for back-dating the odometer
    reach = {}
    cum = 0.0
    km_reach = []
    for d in sorted(day_km):
        cum += day_km[d]
        km_reach.append((datetime.combine(d, datetime.min.time()).isoformat(), round(cum, 1)))
    reach['total_km'] = km_reach

    # ── Speed ─────────────────────────────────────────────────────────────
    ms = v['max_speed']
    m['max_ground_kmh'] = round(ms[0] * 3.6, 1) if ms else None
    speed = {
        'max': ({'kmh': round(ms[0] * 3.6, 1), 'ts': _iso(ms[1]), 'lat': ms[2], 'lon': ms[3],
                 'mode': ms[4]} if ms else None),
        'by_mode': {k: {'kmh': round(s[0] * 3.6, 1), 'ts': _iso(s[1]), 'lat': s[2], 'lon': s[3]}
                    for k, s in v['mode_speed'].items()},
        'plane': ({'kmh': round(v['plane_speed'][0] * 3.6), 'ts': _iso(v['plane_speed'][1])}
                  if v['plane_speed'] else None),
    }
    distance = {
        'total_km': round(total_km, 1),
        'earth_laps': round(total_km / EARTH_KM, 3),
        'moon_pct': round(total_km / MOON_KM * 100, 2),
        'best_day': {'date': best_day[0].isoformat(), 'km': round(best_day[1], 1)} if best_day else None,
        'move_streak': m['move_streak'],
        'move_streak_end': move_end.isoformat() if move_end else None,
        'avg_day_km': round(total_km / len(day_km), 1) if day_km else 0,
    }

    # ── Home & extremes ───────────────────────────────────────────────────
    home = home_from_counts(v['night_counts'])
    extremes = dict(v['ext'])
    far = None
    rings = None
    nights_away = 0
    if home:
        hl, ho = home
        total_s = sum(v['dwell'].values()) or 1.0
        within = {10: 0.0, 100: 0.0, 1000: 0.0}
        for (gy, gx), secs in v['dwell'].items():
            dkm = _hav_km(hl, ho, gy / _DWELL_CELL, gx / _DWELL_CELL)
            for r in within:
                if dkm <= r:
                    within[r] += secs
            if far is None or dkm > far[0]:
                far = (dkm, gy / _DWELL_CELL, gx / _DWELL_CELL)
        rings = {str(r): round(s / total_s * 100, 1) for r, s in within.items()}
        nights_away = sum(1 for (la, lo) in v['night_spot'].values() if _hav_km(hl, ho, la, lo) > _AWAY_KM)
    m['farthest_km'] = round(far[0]) if far else None
    m['nights_away'] = nights_away
    if far:
        extremes['far'] = {'v': round(far[0]), 'lat': far[1], 'lon': far[2]}
    if extremes.get('hi'):
        m['highest_m'] = round(extremes['hi']['v'])

    # ── Climbing ──────────────────────────────────────────────────────────
    dc = v['day_climb']
    best_climb = max(dc.items(), key=lambda kv: kv[1]) if dc else None
    m['climbed_m'] = round(v['climbed'])
    m['best_climb_day_m'] = round(best_climb[1]) if best_climb else None
    climber = {
        'climbed_m': round(v['climbed']),
        'everests': round(v['climbed'] / EVEREST_M, 2),
        'best_day': {'date': best_climb[0].isoformat(), 'm': round(best_climb[1])} if best_climb else None,
        'highest': extremes.get('hi'),
    }

    # ── Rhythm ────────────────────────────────────────────────────────────
    wk = v['hour_week_km']
    weekday_km = sum(sum(wk[i]) for i in range(5))
    weekend_km = sum(sum(wk[i]) for i in range(5, 7))
    busiest = max(((i, h) for i in range(7) for h in range(24)), key=lambda t: wk[t[0]][t[1]])
    rhythm = {
        'hour_week_km': [[round(x, 1) for x in row] for row in wk],
        'weekday_km': round(weekday_km, 1), 'weekend_km': round(weekend_km, 1),
        'busiest': {'dow': busiest[0], 'hour': busiest[1]} if total_km else None,
        'early_bird_days': m['early_bird_days'], 'night_owl_days': m['night_owl_days'],
        'days_tracked': len(days), 'track_streak': m['track_streak'],
        'track_streak_end': track_end.isoformat() if track_end else None,
        'first_day': min(days).isoformat() if days else None,
        'nights_away': nights_away,
    }

    # ── Labels: countries, states, towns ──────────────────────────────────
    countries_list = sorted(
        ({'cc': cc, 'name': country_names.get(cc, cc), 'days': len(ds),
          'first': _iso(country_first.get(cc))} for cc, ds in country_days.items()),
        key=lambda c: -c['days'])
    m['countries'] = len(countries_list)
    reach['countries'] = _count_reach(country_first.values())
    m['us_states'] = len(v['us_states'])
    reach['us_states'] = _count_reach(v['us_states'].values())
    m['towns'] = len(towns)
    reach['towns'] = _count_reach(town_first.values())

    def best_day_of(dmap, fmt):
        if not dmap:
            return None
        d, s = max(dmap.items(), key=lambda kv: len(kv[1]))
        return {'date': d.isoformat(), 'count': len(s), 'items': sorted(fmt(x) for x in s)[:40]}

    big_days = {
        'towns': best_day_of(v['day_towns'], lambda k: k[0]),
        'states': best_day_of(v['day_states'], lambda k: k[1]),
        'countries': best_day_of(v['day_countries'], lambda k: country_names.get(k, k)),
    }
    m['max_towns_day'] = big_days['towns']['count'] if big_days['towns'] else None
    m['max_states_day'] = big_days['states']['count'] if big_days['states'] else None
    m['max_countries_day'] = big_days['countries']['count'] if big_days['countries'] else None

    collector = _town_collector(towns, m)

    # ── Globe ─────────────────────────────────────────────────────────────
    from .tz_utils import zone_name_for_point
    zones = set()
    for gy, gx in v['tz_cells']:
        z = zone_name_for_point(gy / _TZ_CELL, gx / _TZ_CELL)
        if z and not z.startswith('Etc/'):
            zones.add(z)
    m['timezones'] = len(zones)
    m['hemispheres'] = len(v['hemis'])
    m['crossings'] = len(v['crossings'])
    globe = {
        'timezones': sorted(zones), 'hemispheres': sorted(v['hemis']),
        'crossings': v['crossings'], 'home_rings': rings,
        'home': {'lat': round(home[0], 3), 'lon': round(home[1], 3)} if home else None,
        'farthest': extremes.get('far'),
    }

    # ── Journeys ──────────────────────────────────────────────────────────
    m['journeys'] = len(journeys)

    def jfmt(j):
        if not j:
            return None
        return {'start': j['start'].isoformat(), 'end': j['end'].isoformat(),
                'km': round(j['km'], 1), 'hours': round((j['end'] - j['start']).total_seconds() / 3600, 2),
                'mode': j['mode'], 'from': j['from'], 'to': j['to'],
                'date': j['start'].date().isoformat()}

    def longest(pred, key=lambda j: j['km']):
        c = [j for j in journeys if pred(j)]
        return max(c, key=key) if c else None

    lj = longest(lambda j: True)
    lw = longest(lambda j: j['mode'] == 'walk' and j['km'] / max(1e-6, (j['end'] - j['start']).total_seconds() / 3600) < 9)
    lc = longest(lambda j: j['mode'] == 'cycle')
    ld = longest(lambda j: j['mode'] == 'vehicle')
    lt = longest(lambda j: j['mode'] == 'vehicle' and j['p85'] > 45 and j['km'] > 20)
    ldur = longest(lambda j: True, key=lambda j: (j['end'] - j['start']).total_seconds())
    m['longest_journey_km'] = round(lj['km'], 1) if lj else None
    m['longest_walk_km'] = round(lw['km'], 1) if lw else None
    m['longest_ride_km'] = round(lc['km'], 1) if lc else None
    journeys_out = {
        'count': len(journeys),
        'longest': jfmt(lj), 'longest_time': jfmt(ldur), 'walk': jfmt(lw), 'ride': jfmt(lc),
        'drive': jfmt(ld), 'rail_speed': jfmt(lt),
        'by_mode': dict(Counter(j['mode'] for j in journeys)),
    }

    # ── Seasons & years ───────────────────────────────────────────────────
    years = _years(v, towns, town_first, country_days, day_km, today, m)

    m['cells'] = None  # filled by geography (needs the cell scan)
    return {
        '_metrics': m, '_reach': reach,
        'home': home,
        'distance': distance, 'speed': speed, 'extremes': extremes, 'climber': climber,
        'rhythm': rhythm, 'big_days': big_days, 'collector': collector, 'globe': globe,
        'journeys': journeys_out, 'years': years, 'countries_list': countries_list,
        'us_states_visited': sorted(v['us_states']),
    }


def _count_reach(first_times):
    out = []
    for i, ts in enumerate(sorted(t for t in first_times if t), start=1):
        out.append((ts.isoformat(), i))
    return out


def _town_collector(towns, m):
    letters = defaultdict(list)
    names = defaultdict(set)
    for (city, state, cc), ds in towns.items():
        ch = city.strip()[:1].upper()
        if 'A' <= ch <= 'Z':
            letters[ch].append((len(ds), city))
        names[city.strip().lower()].add((city, state, cc))
    alphabet = {}
    for ch in 'ABCDEFGHIJKLMNOPQRSTUVWXYZ':
        lst = sorted(letters.get(ch, []), reverse=True)
        alphabet[ch] = {'count': len(lst), 'top': lst[0][1] if lst else None}
    m['alphabet_letters'] = sum(1 for a in alphabet.values() if a['count'])

    shared = sorted(((len(s), sorted(s)) for s in names.values() if len(s) > 1),
                    key=lambda t: -t[0])
    m['max_namesakes'] = shared[0][0] if shared else 1 if towns else None

    def label(k):
        return ', '.join(x for x in (k[0], k[1]) if x)

    by_days = sorted(towns.items(), key=lambda kv: -len(kv[1]))
    top = by_days[0] if by_days else None
    m['top_town_days'] = len(top[1]) if top else None
    once = [label(k) for k, ds in towns.items() if len(ds) == 1]

    comeback = None
    for k, ds in towns.items():
        if len(ds) < 2:
            continue
        s = sorted(ds)
        for a, b in zip(s, s[1:], strict=False):
            gap = (b - a).days
            if gap > 1 and (comeback is None or gap > comeback[0]):
                comeback = (gap, k, a, b)
    m['longest_comeback_days'] = comeback[0] if comeback else None

    named = [k[0] for k in towns]
    longest_name = max(named, key=len) if named else None
    shortest_name = min(named, key=len) if named else None
    return {
        'alphabet': alphabet,
        'namesakes': [{'name': s[0][0], 'count': n, 'places': [label(x) for x in s][:8],
                       'links': [{'label': label(x), 'city': x[0], 'state': x[1]} for x in s][:8]}
                      for n, s in shared[:6]],
        # city/state are the stored labels, so the page can open /map/?city=&state=.
        'top_towns': [{'name': label(k), 'city': k[0], 'state': k[1], 'days': len(ds)}
                      for k, ds in by_days[:10]],
        'one_and_done': len(once),
        'one_and_done_sample': sorted(once)[:12],
        'comeback': ({'name': label(comeback[1]), 'city': comeback[1][0], 'state': comeback[1][1],
                      'days': comeback[0],
                      'left': comeback[2].isoformat(), 'returned': comeback[3].isoformat()}
                     if comeback else None),
        'longest_name': longest_name, 'shortest_name': shortest_name,
        'total': len(towns),
    }


def _years(v, towns, town_first, country_days, day_km, today, m):
    yk = v['year_km']
    years = sorted(set(yk) | {d.year for d in v['days']})
    new_towns = Counter(timezone.localtime(ts).year for ts in town_first.values())
    countries_y = defaultdict(set)
    for cc, ds in country_days.items():
        for d in ds:
            countries_y[d.year].add(cc)
    towns_y = defaultdict(set)
    for k, ds in towns.items():
        for d in ds:
            towns_y[d.year].add(k)
    best_day_y = defaultdict(float)
    for d, km in day_km.items():
        best_day_y[d.year] = max(best_day_y[d.year], km)

    rows = [{'year': y, 'km': round(yk.get(y, 0.0), 1), 'new_towns': new_towns.get(y, 0),
             'towns': len(towns_y.get(y, ())), 'countries': len(countries_y.get(y, ())),
             'best_day_km': round(best_day_y.get(y, 0.0), 1),
             'days': sum(1 for d in v['days'] if d.year == y)} for y in years]

    seasons = defaultdict(float)
    for (_y, mo), km in v['month_km'].items():
        seasons[_SEASONS[mo]] += km
    busiest = max(v['month_km'].items(), key=lambda kv: kv[1]) if v['month_km'] else None
    m['seasons_with_travel'] = sum(1 for km in seasons.values() if km > 100)
    m['years_tracked'] = len(years)

    pbs = []
    cur = today.year
    earlier = [r for r in rows if r['year'] < cur]
    this = next((r for r in rows if r['year'] == cur), None)
    if this and earlier:
        for key, label in (('km', 'Distance'), ('towns', 'Towns visited'), ('countries', 'Countries'),
                           ('best_day_km', 'Biggest day'), ('new_towns', 'New towns')):
            if this[key] > max(r[key] for r in earlier):
                pbs.append(label)
    m['personal_bests'] = len(pbs) if earlier else None

    doy = today.timetuple().tm_yday
    ytd = sum(km for d, km in day_km.items() if d.year == cur and d.timetuple().tm_yday <= doy)
    lytd = sum(km for d, km in day_km.items() if d.year == cur - 1 and d.timetuple().tm_yday <= doy)
    return {
        'rows': rows,
        'seasons': {k: round(seasons.get(k, 0.0), 1) for k in ('spring', 'summer', 'autumn', 'winter')},
        'busiest_month': {'year': busiest[0][0], 'month': busiest[0][1], 'km': round(busiest[1], 1)} if busiest else None,
        'personal_bests': pbs,
        'ytd_km': round(ytd, 1), 'last_ytd_km': round(lytd, 1),
    }


# ── Phase 3: geography ───────────────────────────────────────────────────────

def _cells_with_first(user):
    """Distinct ~220m cells with first-visit time."""
    from django.db.models import F, IntegerField, Min
    from django.db.models.functions import Cast, Round
    from .models import Location
    rows = (Location.objects.filter(device__user=user).exclude(flag='suspect')
            .annotate(gy=Cast(Round(F('latitude') * _HOME_CELL), IntegerField()),
                      gx=Cast(Round(F('longitude') * _HOME_CELL), IntegerField()))
            .order_by().values_list('gy', 'gx').annotate(first=Min('timestamp')))
    ys, xs, ts = [], [], []
    for gy, gx, first in rows.iterator(chunk_size=20000):
        if gy is None or gx is None:
            continue
        ys.append(gy); xs.append(gx); ts.append(first)
    return ys, xs, ts


def _geography(user, prog, metrics, hist):
    from .views import _visited_cells

    try:
        metrics['cells'] = len(_visited_cells(user)['cells']) // 3
    except Exception:
        metrics['cells'] = None

    ys, xs, ts = _cells_with_first(user)
    prog.check_stop()
    visited = {}
    with connection.cursor() as cur:
        cur.execute("DROP TABLE IF EXISTS ach_cells")
        cur.execute("CREATE TEMP TABLE ach_cells (gy int, gx int, first timestamptz, pt geometry(Point, 4326))")
        for i in range(0, len(ys), 50000):
            cur.execute(
                """INSERT INTO ach_cells (gy, gx, first, pt)
                   SELECT y, x, f, ST_SetSRID(ST_MakePoint(x::float8 / %s, y::float8 / %s), 4326)
                     FROM unnest(%s::int[], %s::int[], %s::timestamptz[]) AS t(y, x, f)""",
                [_HOME_CELL, _HOME_CELL, ys[i:i + 50000], xs[i:i + 50000], ts[i:i + 50000]])
        cur.execute("CREATE INDEX ON ach_cells USING gist (pt)")
        cur.execute("ANALYZE ach_cells")
        prog('Mapping towns, counties and regions', 60, force=True)
        cur.execute(
            """SELECT b.id, b.kind, b.name, b.state, b.country_code, min(c.first)
                 FROM tracker_boundary b JOIN ach_cells c ON ST_Intersects(b.geom, c.pt)
                WHERE b.kind IN ('place', 'cousub', 'county', 'adm1', 'adm2', 'adm3')
                GROUP BY b.id, b.kind, b.name, b.state, b.country_code""")
        for bid, kind, name, state, cc, first in cur.fetchall():
            visited[bid] = {'kind': kind, 'name': name, 'state': state, 'cc': cc, 'first': first}
        cur.execute(
            """SELECT country_code, kind, state, count(*) FROM tracker_boundary
                WHERE kind IN ('place', 'cousub', 'county', 'adm1', 'adm2', 'adm3')
                GROUP BY country_code, kind, state""")
        totals = cur.fetchall()
        cur.execute("DROP TABLE IF EXISTS ach_cells")

    tot = defaultdict(int)       # (cc, kind, state) -> n
    tot_k = defaultdict(int)     # (cc, kind) -> n
    for cc, kind, state, n in totals:
        tot[(cc, kind, state or '')] += n
        tot_k[(cc, kind)] += n
    vis = defaultdict(int)
    vis_k = defaultdict(int)
    for b in visited.values():
        vis[(b['cc'], b['kind'], b['state'] or '')] += 1
        vis_k[(b['cc'], b['kind'])] += 1

    def pct(a, b):
        return round(a / b * 100, 2) if b else None

    # US.
    us_states = hist.get('us_states_visited', [])
    state_rows = []
    us_state_names = sorted({s for (cc, k, s) in tot if cc == 'US' and s} | set(us_states))
    for st in us_state_names:
        row = {'state': st, 'visited': st in us_states}
        for k in ('place', 'cousub', 'county'):
            row[f'{k}_v'] = vis.get(('US', k, st), 0)
            row[f'{k}_t'] = tot.get(('US', k, st), 0)
        if row['visited'] or row['place_v'] or row['county_v']:
            state_rows.append(row)
    state_rows.sort(key=lambda r: -(r['place_v'] + r['cousub_v']))
    us = {
        'states_v': len(us_states), 'states_t': US_STATES_TOTAL,
        'states_pct': pct(len(us_states), US_STATES_TOTAL),
        'states': us_states,
        'towns_v': vis_k.get(('US', 'place'), 0), 'towns_t': tot_k.get(('US', 'place'), 0),
        'cousub_v': vis_k.get(('US', 'cousub'), 0), 'cousub_t': tot_k.get(('US', 'cousub'), 0),
        'counties_v': vis_k.get(('US', 'county'), 0), 'counties_t': tot_k.get(('US', 'county'), 0),
        'rows': state_rows,
        'has_towns': tot_k.get(('US', 'place'), 0) > 0,
        'has_counties': tot_k.get(('US', 'county'), 0) > 0,
    }
    us['towns_pct'] = pct(us['towns_v'], us['towns_t'])
    us['counties_pct'] = pct(us['counties_v'], us['counties_t'])
    if us['has_counties']:
        metrics['us_counties'] = us['counties_v']
    home = hist.get('home')
    if home and state_rows:
        # Home state = the state with the most towns visited ≈ where you live.
        hs = state_rows[0]
        t_tot = hs['place_t'] + hs['cousub_t']
        if t_tot:
            metrics['home_state_town_pct'] = round((hs['place_v'] + hs['cousub_v']) / t_tot * 100, 1)
            us['home_state'] = hs['state']

    # Abroad.
    from . import boundary_download_tasks as bdt
    abroad = []
    covered = bdt.downloaded_countries()
    regions_abroad = 0
    for c in hist.get('countries_list', []):
        cc = c['cc']
        if cc == 'US':
            continue
        entry = {'cc': cc, 'name': c['name'], 'days': c['days'],
                 'detailed': bdt.iso3(cc) in covered and tot_k.get((cc, 'adm1'), 0) > 0,
                 'needs_days': max(0, bdt.MIN_DAYS + 1 - c['days'])}
        if entry['detailed']:
            for k in ('adm1', 'adm2', 'adm3'):
                entry[f'{k}_v'] = vis_k.get((cc, k), 0)
                entry[f'{k}_t'] = tot_k.get((cc, k), 0)
                entry[f'{k}_pct'] = pct(entry[f'{k}_v'], entry[f'{k}_t'])
            regions_abroad += entry['adm1_v']
            regs = []
            for (rcc, k, st), n in tot.items():
                if rcc == cc and k == 'adm2' and st:
                    regs.append({'name': st, 'adm2_v': vis.get((cc, 'adm2', st), 0), 'adm2_t': n})
            regs.sort(key=lambda r: (-r['adm2_v'], r['name']))
            entry['regions'] = regs[:60]
            entry['visited_regions'] = sorted(b['name'] for b in visited.values()
                                              if b['cc'] == cc and b['kind'] == 'adm1')
        abroad.append(entry)
    metrics['regions_abroad'] = regions_abroad

    # Visited-unit back-dating.
    hist['_reach']['us_counties'] = _count_reach(
        [b['first'] for b in visited.values() if b['cc'] == 'US' and b['kind'] == 'county'])

    # Map geometry: counties of every visited state (+ ADM2 of detailed
    # countries), flagged visited, simplified — the "fill in the map" view.
    county_geo = _unit_geojson(visited, [r['state'] for r in state_rows], 'county', 'US')
    abroad_geo = {}
    for e in abroad:
        if e.get('detailed'):
            kind = 'adm2' if e.get('adm2_t') else 'adm1'
            abroad_geo[e['cc']] = _unit_geojson(visited, None, kind, e['cc'])

    return {
        'explorer': {
            'countries': hist.get('countries_list', []),
            'countries_v': len(hist.get('countries_list', [])), 'countries_t': WORLD_COUNTRIES,
            'countries_pct': pct(len(hist.get('countries_list', [])), WORLD_COUNTRIES),
            'us': us, 'abroad': abroad, 'min_days': bdt.MIN_DAYS + 1,
            'county_geo': county_geo, 'abroad_geo': abroad_geo,
        },
        '_visited': visited,
    }


def _unit_geojson(visited, states, kind, cc, cap=800):
    import json
    vis_ids = {bid for bid, b in visited.items() if b['kind'] == kind and b['cc'] == cc}
    if not vis_ids:
        return None
    with connection.cursor() as cur:
        if states is not None:
            cur.execute(
                """SELECT id, name, state, ST_AsGeoJSON(ST_SimplifyPreserveTopology(geom, 0.01), 3)
                     FROM tracker_boundary
                    WHERE kind = %s AND country_code = %s AND state = ANY(%s) LIMIT %s""",
                [kind, cc, list(states), cap])
        else:
            cur.execute(
                """SELECT id, name, state, ST_AsGeoJSON(ST_SimplifyPreserveTopology(geom, 0.01), 3)
                     FROM tracker_boundary WHERE kind = %s AND country_code = %s LIMIT %s""",
                [kind, cc, cap])
        feats = []
        for bid, name, state, gj in cur.fetchall():
            if not gj:
                continue
            feats.append({'type': 'Feature', 'geometry': json.loads(gj),
                          'properties': {'name': name, 'state': state or '', 'v': 1 if bid in vis_ids else 0}})
    return {'type': 'FeatureCollection', 'features': feats}


# ── Phase 4: roads ───────────────────────────────────────────────────────────

_MOTORWAY = {'motorway', 'motorway_link', 'trunk', 'trunk_link'}
_MINOR = {'residential', 'unclassified', 'living_street'}
_ROAD_LABELS = {
    'motorway': 'Motorway', 'trunk': 'Trunk', 'primary': 'Primary', 'secondary': 'Secondary',
    'tertiary': 'Tertiary', 'unclassified': 'Unclassified', 'residential': 'Residential',
    'living_street': 'Living street',
}


def _roads(user, snap, prog, geo):
    from .models import Device, Location, RoadSegment

    visited = geo.get('_visited', {})
    towns = {bid: b for bid, b in visited.items() if b['kind'] in ('place', 'cousub', 'adm3')}
    counties = {bid: b for bid, b in visited.items() if b['kind'] in ('county', 'adm2')}
    road_count = RoadSegment.objects.count()
    if not road_count:
        return {'available': False, 'reason': 'no_roads'}, ''
    if not towns and not counties:
        return {'available': False, 'reason': 'no_boundaries'}, ''
    devices = list(Device.objects.filter(user=user).values_list('id', flat=True))
    max_id = Location.objects.filter(device_id__in=devices).order_by('-id').values_list('id', flat=True).first()
    fp = f'v1:{max_id}:{road_count}:{len(towns)}:{len(counties)}'
    if snap.roads_fingerprint == fp and snap.roads_json.get('available'):
        prog('Road coverage unchanged — reusing the last scan', 88, force=True)
        return snap.roads_json, fp

    area_ids = list(towns) + list(counties)
    with connection.cursor() as cur:
        for t in ('ach_lines', 'ach_areas', 'ach_samples'):
            cur.execute(f"DROP TABLE IF EXISTS {t}")
        prog('Tracing the roads you travelled', 66, force=True)
        # Every short, plausible hop of the user's own track, as a line. Planes,
        # suspect fixes, gaps and poor fixes are left out: a line drawn across a
        # 20-minute hole would "drive" every road it happened to cross.
        cur.execute(
            """CREATE TEMP TABLE ach_lines AS
               SELECT ST_MakeLine(pg, g) AS geom FROM (
                 SELECT l.location::geometry AS g,
                        lag(l.location::geometry) OVER w AS pg,
                        l.timestamp - lag(l.timestamp) OVER w AS dt,
                        l.accuracy AS acc, lag(l.accuracy) OVER w AS pacc,
                        l.transport_mode AS mode, l.flag AS flag
                   FROM tracker_location l
                  WHERE l.device_id = ANY(%s) AND l.location IS NOT NULL
                 WINDOW w AS (PARTITION BY l.device_id ORDER BY l.timestamp)
               ) s
               WHERE pg IS NOT NULL AND dt > interval '0' AND dt <= interval '120 seconds'
                 AND coalesce(acc, 0) <= 50 AND coalesce(pacc, 0) <= 50
                 AND mode <> 'plane' AND flag <> 'suspect'
                 AND NOT ST_Equals(pg, g)
                 AND ST_DistanceSphere(pg, g) BETWEEN 5 AND 2000""",
            [devices])
        cur.execute("CREATE INDEX ON ach_lines USING gist (geom)")
        cur.execute("ANALYZE ach_lines")
        prog.check_stop()

        cur.execute("CREATE TEMP TABLE ach_areas (id bigint, town boolean, geom geometry(MultiPolygon, 4326))")
        cur.execute(
            """INSERT INTO ach_areas SELECT id, kind IN ('place', 'cousub', 'adm3'), geom
                 FROM tracker_boundary WHERE id = ANY(%s)""", [area_ids])
        cur.execute("CREATE INDEX ON ach_areas USING gist (geom)")
        cur.execute("ANALYZE ach_areas")

        prog('Sampling mapped roads in the places you visited', 72, force=True)
        # One sample per ~25m of road, each carrying the length it stands for
        # (a road's length ÷ its sample count), so totals are true kilometres
        # however unevenly OSM placed the original vertices.
        cur.execute(
            """CREATE TEMP TABLE ach_samples AS
               SELECT r.id AS rid, r.highway,
                      ST_Length(r.geom::geography) / ST_NPoints(seg) AS w,
                      (ST_DumpPoints(seg)).geom AS pt
                 FROM (SELECT r.id, r.highway, r.geom,
                              ST_Segmentize(r.geom::geography, 25)::geometry AS seg
                         FROM tracker_roadsegment r
                        WHERE EXISTS (SELECT 1 FROM ach_areas a
                                       WHERE a.geom && r.geom AND ST_Intersects(a.geom, r.geom))) r""")
        cur.execute("ALTER TABLE ach_samples ADD COLUMN cov boolean DEFAULT false")
        cur.execute("CREATE INDEX ON ach_samples USING gist (pt)")
        cur.execute("ANALYZE ach_samples")
        prog.check_stop()

        prog('Checking which roads you have travelled', 78, force=True)
        # ~25m in degrees of latitude; a geometry DWithin keeps the GiST index
        # usable (a geography cast here would not).
        cur.execute(
            """UPDATE ach_samples s SET cov = true
                WHERE EXISTS (SELECT 1 FROM ach_lines l WHERE ST_DWithin(l.geom, s.pt, 0.00025))""")
        prog.check_stop()

        prog('Adding up road coverage', 84, force=True)
        cur.execute(
            """SELECT highway, sum(w), sum(w) FILTER (WHERE cov),
                      count(DISTINCT rid) FILTER (WHERE cov)
                 FROM ach_samples GROUP BY highway""")
        by_type = cur.fetchall()
        cur.execute(
            """SELECT a.id, sum(s.w), sum(s.w) FILTER (WHERE s.cov)
                 FROM ach_areas a JOIN ach_samples s ON ST_Intersects(a.geom, s.pt)
                GROUP BY a.id""")
        per_area = cur.fetchall()
        for t in ('ach_lines', 'ach_areas', 'ach_samples'):
            cur.execute(f"DROP TABLE IF EXISTS {t}")

    types = defaultdict(lambda: [0.0, 0.0, 0])
    for hw, tot_m, cov_m, nroads in by_type:
        base = (hw or 'other').replace('_link', '')
        t = types[base]
        t[0] += (tot_m or 0) / 1000.0
        t[1] += (cov_m or 0) / 1000.0
        t[2] += nroads or 0
    type_rows = sorted(({'type': k, 'label': _ROAD_LABELS.get(k, k.replace('_', ' ').title()),
                         'total_km': round(v[0], 1), 'km': round(v[1], 1), 'roads': v[2]}
                        for k, v in types.items()), key=lambda r: -r['km'])
    total_cov = sum(r['km'] for r in type_rows)
    total_all = sum(r['total_km'] for r in type_rows)

    areas = []
    for bid, tot_m, cov_m in per_area:
        b = visited.get(bid)
        if not b or not tot_m:
            continue
        areas.append({'name': b['name'], 'state': b['state'] or '', 'cc': b['cc'],
                      'town': bid in towns, 'kind': b['kind'],
                      'km': round((cov_m or 0) / 1000.0, 1), 'total_km': round(tot_m / 1000.0, 1),
                      'pct': round((cov_m or 0) / tot_m * 100, 1)})
    town_rows = [a for a in areas if a['town'] and a['total_km'] >= 1]
    county_rows = [a for a in areas if not a['town'] and a['total_km'] >= 5]
    town_rows.sort(key=lambda a: (-a['pct'], -a['total_km']))
    county_rows.sort(key=lambda a: (-a['pct'], -a['total_km']))
    barely = sorted((a for a in town_rows if a['pct'] < 15), key=lambda a: -a['total_km'])[:10]
    moto = sum(r['km'] for r in type_rows if r['type'] in {t.replace('_link', '') for t in _MOTORWAY})
    minor = sum(r['km'] for r in type_rows if r['type'] in _MINOR)

    return {
        'available': True,
        'km': round(total_cov, 1), 'mapped_km': round(total_all, 1),
        'pct': round(total_cov / total_all * 100, 1) if total_all else 0,
        'types': type_rows,
        'towns': town_rows[:40], 'counties': county_rows[:30], 'barely': barely,
        'completionist': [a['name'] for a in town_rows if a['pct'] >= 90][:50],
        'towns_scanned': len(town_rows), 'counties_scanned': len(county_rows),
        '_metrics': {
            'road_km': round(total_cov, 1),
            'towns_90pct': sum(1 for a in town_rows if a['pct'] >= 90),
            'motorway_km': round(moto, 1), 'minor_km': round(minor, 1),
        },
    }, fp


# ── Phase 5: places ──────────────────────────────────────────────────────────

_KW = {
    'cafe': ('coffee', 'café', 'cafe', 'espresso', 'starbucks', 'dunkin', 'tim hortons', 'roaster',
             'tea house', 'peet'),
    'bar': (' bar', 'pub', 'tavern', 'brewery', 'brewing', 'saloon', 'taproom', 'wine', 'distillery',
            'lounge'),
    'food': ('restaurant', 'pizza', 'grill', 'diner', 'burger', 'kitchen', 'taco', 'sushi', 'bbq',
             'bistro', 'bakery', 'mcdonald', 'wendy', 'chipotle', 'kfc', 'domino', 'panera', 'subway',
             'noodle', 'ramen', 'thai', 'pho', 'deli', 'sandwich', 'chicken', 'steak', 'seafood',
             'cantina', 'trattoria', 'bagel', 'donut', 'ice cream', 'creamery', 'eatery'),
    'fuel': ('shell', 'exxon', 'mobil', 'bp', 'sunoco', 'irving', 'citgo', 'chevron', 'speedway',
             'wawa', 'sheetz', 'circle k', 'gulf', 'valero', 'marathon', 'cumberland farms', 'casey',
             'texaco', 'arco', 'esso', 'gas station', 'fuel', 'petro', 'pilot', "love's", 'quiktrip'),
    'grocery': ('grocery', 'supermarket', 'market', 'hannaford', 'kroger', 'safeway', 'aldi', 'lidl',
                "trader joe", 'whole foods', 'publix', 'wegmans', "shaw's", 'stop & shop', 'food lion',
                'giant', 'h-e-b', 'heb', 'costco', "sam's club", 'walmart', 'tesco', 'sainsbury',
                'carrefour', 'co-op', 'save-a-lot', 'piggly'),
    'park': ('park', 'trail', 'preserve', 'forest', 'garden', 'reserve', 'state park', 'nature',
             'woods', 'falls', 'lake', 'pond', 'mountain', 'mount '),
    'beach': ('beach',),
    'culture': ('museum', 'gallery', 'monument', 'memorial', 'castle', 'cathedral', 'historic',
                'fort ', 'lighthouse', 'library', 'theater', 'theatre', 'opera', 'abbey', 'palace',
                'temple', 'basilica', 'zoo', 'aquarium'),
}


def classify_poi(name, category):
    """Set of fun buckets a POI belongs to. POI.category is only the OSM tag
    *key* (shop/amenity/tourism…), so the subtype comes from the name."""
    n = f' {(name or "").lower()} '
    out = set()
    for bucket, words in _KW.items():
        if any(w in n for w in words):
            out.add(bucket)
    if category in ('leisure', 'natural') and 'beach' not in out:
        out.add('park')
    if category in ('historic',) or (category == 'tourism' and 'park' not in out):
        out.add('culture')
    if category in ('shop', 'brand') and not out & {'food', 'cafe', 'bar', 'fuel'}:
        out.add('store')
    if 'grocery' in out:
        out.add('store')
    return out


def _places(user, metrics, home):
    from .models import Visit, StatsSnapshot

    rows = (Visit.objects.filter(device__user=user)
            .order_by('device_id', 'start_time')
            .values_list('device_id', 'poi_id', 'poi__name', 'poi__category', 'start_time', 'end_time',
                         'latitude', 'longitude', 'city', 'place_name'))
    poi = {}             # id -> {name, cat, visits, secs, first, buckets, city}
    pairs = Counter()
    pair_label = {}
    prev = None
    this_year = timezone.localdate().year
    for dev, pid, pname, pcat, st, en, lat, lon, city, place in rows.iterator(chunk_size=5000):
        secs = max(0.0, (en - st).total_seconds())
        key = ('p', pid) if pid else ('g', round(lat * _HOME_CELL), round(lon * _HOME_CELL))
        label = pname or place or city or 'Somewhere'
        if pid:
            p = poi.get(pid)
            if p is None:
                p = poi[pid] = {'name': pname, 'cat': pcat or '', 'visits': 0, 'secs': 0.0,
                                'first': st, 'buckets': classify_poi(pname, pcat), 'city': city or '',
                                'lat': lat, 'lon': lon}
            p['visits'] += 1
            p['secs'] += secs
        if prev and prev[0] == dev and prev[1] != key and (st - prev[2]).total_seconds() <= 3 * 3600:
            pk = (prev[1], key)
            pairs[pk] += 1
            pair_label[pk] = (prev[3], label)
        prev = (dev, key, en, label)

    def top(bucket, n=5):
        c = [p for p in poi.values() if bucket in p['buckets']]
        c.sort(key=lambda p: (-p['visits'], -p['secs']))
        return [{'name': p['name'], 'visits': p['visits'], 'hours': round(p['secs'] / 3600, 1),
                 'city': p['city'], 'lat': p['lat'], 'lon': p['lon']} for p in c[:n]]

    tops = {b: top(b) for b in ('store', 'grocery', 'cafe', 'food', 'bar', 'fuel', 'park', 'culture', 'beach')}
    overall = sorted(poi.values(), key=lambda p: (-p['visits'], -p['secs']))[:10]
    chains = defaultdict(lambda: {'branches': 0, 'visits': 0})
    for p in poi.values():
        k = (p['name'] or '').strip().lower()
        if k:
            chains[k]['branches'] += 1
            chains[k]['visits'] += p['visits']
            chains[k]['name'] = p['name']
    chain_rows = sorted((c for c in chains.values() if c['branches'] > 1),
                        key=lambda c: (-c['branches'], -c['visits']))[:8]

    metrics['distinct_pois'] = len(poi) if poi else None
    metrics['regular_places'] = sum(1 for p in poi.values() if p['visits'] >= 10) if poi else None
    metrics['top_store_visits'] = tops['store'][0]['visits'] if tops['store'] else None
    metrics['chain_branches'] = chain_rows[0]['branches'] if chain_rows else None
    metrics['new_places_this_year'] = (sum(1 for p in poi.values()
                                           if timezone.localtime(p['first']).year == this_year)
                                       if poi else None)
    metrics['parks'] = len([p for p in poi.values() if 'park' in p['buckets']]) if poi else None
    metrics['culture'] = len([p for p in poi.values() if 'culture' in p['buckets']]) if poi else None
    metrics['beaches'] = len([p for p in poi.values() if 'beach' in p['buckets']]) if poi else None
    metrics['food_places'] = (len([p for p in poi.values() if p['buckets'] & {'food', 'cafe', 'bar'}])
                              if poi else None)

    commute = None
    if pairs:
        (pk, n) = pairs.most_common(1)[0]
        a, b = pair_label[pk]
        rev = pairs.get((pk[1], pk[0]), 0)
        commute = {'from': a, 'to': b, 'count': n, 'back': rev}
        metrics['commute_count'] = n
    else:
        metrics['commute_count'] = None

    custom = []
    snap = StatsSnapshot.objects.filter(user=user).only('places_json').first()
    if snap and snap.places_json:
        cp = sorted(snap.places_json.get('places', []), key=lambda p: -(p.get('point_count') or 0))
        custom = [{'name': p.get('name'), 'points': p.get('point_count') or 0,
                   'color': p.get('color')} for p in cp[:5]]

    return {
        'tops': tops,
        'overall': [{'name': p['name'], 'visits': p['visits'], 'hours': round(p['secs'] / 3600, 1),
                     'city': p['city']} for p in overall],
        'chains': [{'name': c['name'], 'branches': c['branches'], 'visits': c['visits']} for c in chain_rows],
        'distinct': len(poi), 'regulars': metrics['regular_places'] or 0,
        'new_this_year': metrics['new_places_this_year'] or 0,
        'commute': commute, 'custom': custom,
        'counts': {b: metrics.get(k) for b, k in (('park', 'parks'), ('culture', 'culture'),
                                                  ('beach', 'beaches'), ('food', 'food_places'))},
        'has_visits': bool(poi),
    }


# ── Phase 6: extras (each optional) ──────────────────────────────────────────

def _extras(user, metrics):
    from django.db.models import Max, Sum
    from django.db.models.functions import TruncDate
    from .models import Activity, CellSample, Flight, HealthSample, JournalEntry

    out = {}
    try:
        fl = list(Flight.objects.filter(user=user).values(
            'date', 'dep_iata', 'arr_iata', 'dep_city', 'arr_city', 'distance_km', 'airline_name',
            'flight_number', 'aircraft_model'))
        if fl:
            airports = {a for f in fl for a in (f['dep_iata'], f['arr_iata']) if a}
            km = sum(f['distance_km'] or 0 for f in fl)
            longest = max(fl, key=lambda f: f['distance_km'] or 0)
            routes = Counter(tuple(sorted((f['dep_iata'], f['arr_iata']))) for f in fl if f['dep_iata'])
            out['flights'] = {
                'count': len(fl), 'airports': len(airports), 'km': round(km),
                'airlines': len({f['airline_name'] for f in fl if f['airline_name']}),
                'aircraft': len({f['aircraft_model'] for f in fl if f['aircraft_model']}),
                'longest': {'route': f"{longest['dep_iata']}–{longest['arr_iata']}",
                            'km': round(longest['distance_km'] or 0), 'date': longest['date'].isoformat(),
                            'number': longest['flight_number']},
                'top_route': ({'route': '–'.join(routes.most_common(1)[0][0]),
                               'count': routes.most_common(1)[0][1]} if routes else None),
            }
            metrics['flights'] = len(fl)
            metrics['airports'] = len(airports)
            metrics['flight_km'] = round(km)
    except Exception:
        logger.exception('achievements: flights')

    try:
        per = (HealthSample.objects.filter(user=user, kind='steps')
               .annotate(d=TruncDate('start_time')).order_by()
               .values('d', 'source').annotate(v=Sum('value')))
        best = {}
        for r in per.iterator():
            if r['v'] and r['v'] > best.get(r['d'], 0):
                best[r['d']] = r['v']
        if best:
            bd = max(best.items(), key=lambda kv: kv[1])
            total = sum(best.values())
            out['health'] = {'steps': int(total), 'best_day': {'date': bd[0].isoformat(), 'steps': int(bd[1])},
                             'days': len(best), 'avg': int(total / len(best)),
                             'days_10k': sum(1 for v in best.values() if v >= 10000)}
            metrics['steps_total'] = int(total)
            metrics['best_step_day'] = int(bd[1])
    except Exception:
        logger.exception('achievements: health')

    try:
        acts = Activity.objects.filter(user=user)
        n = acts.count()
        if n:
            lg = acts.exclude(distance_km__isnull=True).order_by('-distance_km').values(
                'kind', 'title', 'distance_km', 'start_time').first()
            out['activities'] = {
                'count': n,
                'by_kind': dict(Counter(acts.values_list('kind', flat=True))),
                'longest': ({'kind': lg['kind'], 'title': lg['title'], 'km': round(lg['distance_km'], 1),
                             'date': timezone.localtime(lg['start_time']).date().isoformat()} if lg else None),
                'fastest_kmh': round(acts.aggregate(m=Max('max_speed_kmh'))['m'] or 0, 1),
            }
            metrics['activities'] = n
    except Exception:
        logger.exception('achievements: activities')

    try:
        from . import cell_utils
        cs = CellSample.objects.filter(user=user, cid__isnull=False)
        if cs.exists():
            sites = set()
            for rat, mcc, mnc, tac, cid in (cs.order_by().values_list('rat', 'mcc', 'mnc', 'tac', 'cid')
                                            .distinct().iterator()):
                sites.add(cell_utils.site_key(rat, mcc, mnc, tac, cid)[0])
            carriers = sorted({c for c in cs.order_by().values_list('carrier', flat=True).distinct() if c})
            out['cells'] = {'towers': len(sites), 'carriers': carriers,
                            'best_dbm': cs.aggregate(m=Max('dbm'))['m']}
            metrics['towers'] = len(sites)
    except Exception:
        logger.exception('achievements: cells')

    try:
        from .views import _journal_compute_streaks
        dates = list(JournalEntry.objects.filter(user=user).values_list('date', flat=True))
        if dates:
            st = _journal_compute_streaks(dates)
            out['journal'] = st
            metrics['journal_streak'] = st['longest']
    except Exception:
        logger.exception('achievements: journals')
    return out


def _transport(user, metrics):
    from .models import Location, StatsSnapshot
    data = None
    snap = StatsSnapshot.objects.filter(user=user).only('transport_json').first()
    if snap and snap.transport_json and snap.transport_json.get('modes') is not None:
        data = snap.transport_json
    else:
        try:
            from .views import _compute_transport_breakdown_from_qs
            data = _compute_transport_breakdown_from_qs(Location.objects.filter(device__user=user))
        except Exception:
            logger.exception('achievements: transport')
            data = {'modes': [], 'unclassified': 0}
    by = {m['mode']: m for m in data.get('modes', [])}
    metrics['walk_km'] = round(by['walk']['km'], 1) if 'walk' in by else None
    metrics['cycle_km'] = round(by['cycle']['km'], 1) if 'cycle' in by else None
    return data


# ── Phase 7: badges ──────────────────────────────────────────────────────────

def _badges(user, metrics, reach):
    from .models import AchievementUnlock

    rows, xp = defs.evaluate(metrics)
    have = {(u.badge_id, u.tier): u for u in AchievementUnlock.objects.filter(user=user)}
    now = timezone.now()
    new = []
    for r in rows:
        b = defs.BADGE_BY_ID[r['id']]
        for t in range(1, r['tier'] + 1):
            if (b.id, t) in have:
                continue
            when = defs.reached_at(reach.get(b.metric), b.tiers[t - 1])
            when_dt = None
            if when:
                try:
                    when_dt = datetime.fromisoformat(when)
                    if timezone.is_naive(when_dt):
                        when_dt = timezone.make_aware(when_dt)
                except ValueError:
                    when_dt = None
            u = AchievementUnlock(user=user, badge_id=b.id, tier=t,
                                  unlocked_at=when_dt or now, value=float(r['value'] or 0))
            new.append(u)
            have[(b.id, t)] = u
    if new:
        AchievementUnlock.objects.bulk_create(new, ignore_conflicts=True)

    for r in rows:
        got = [have[(r['id'], t)].unlocked_at for t in range(1, 5) if (r['id'], t) in have]
        r['unlocked_at'] = _iso(max(got)) if got else None
        r['first_unlocked_at'] = _iso(min(got)) if got else None

    timeline = sorted(
        ({'badge': k[0], 'tier': k[1], 'at': _iso(u.unlocked_at),
          'name': defs.BADGE_BY_ID[k[0]].name, 'emoji': defs.BADGE_BY_ID[k[0]].emoji}
         for k, u in have.items() if k[0] in defs.BADGE_BY_ID),
        key=lambda e: e['at'] or '', reverse=True)
    almost = sorted((r for r in rows if r['available'] and r['next'] is not None and r['progress'] > 0),
                    key=lambda r: -r['progress'])[:4]
    level = defs.level_for_xp(xp)
    return {
        'badges': rows, 'level': level,
        'unlocked': sum(1 for r in rows if r['tier'] > 0),
        'tiers_earned': sum(r['tier'] for r in rows),
        'total': len(rows), 'max_tiers': len(rows) * 4,
        'recent': timeline[:8], 'timeline': timeline[:120],
        'almost': [r['id'] for r in almost],
        'groups': [{'id': g, 'name': n} for g, n in defs.GROUPS],
        'tier_names': list(defs.TIER_NAMES), 'tier_xp': list(defs.TIER_XP),
    }


# ── Scheduler ────────────────────────────────────────────────────────────────

def _scheduler_loop():
    from .models import AchievementSnapshot, Device
    from .tz_utils import user_timezone
    from .scheduler_utils import wait_for_app_registry
    wait_for_app_registry()
    time.sleep(120)  # let the stats scheduler's first sweep go first
    while True:
        try:
            close_old_connections()
            for uid in list(Device.objects.order_by().values_list('user_id', flat=True).distinct()):
                if uid is None:
                    continue
                row = AchievementSnapshot.objects.filter(user_id=uid).values('computed_at', 'status').first()
                with user_timezone(uid):
                    fresh = bool(row and row['computed_at']
                                 and timezone.localtime(row['computed_at']).date() >= timezone.localdate())
                if fresh or (row and row['status'] == 'running'):
                    continue
                compute_achievements(uid)
                close_old_connections()
        except Exception:
            logger.exception('Achievements scheduler error')
        time.sleep(SCHEDULER_CHECK_INTERVAL)


def start_achievement_scheduler():
    global _scheduler_thread
    if _scheduler_thread is not None and _scheduler_thread.is_alive():
        return
    _scheduler_thread = threading.Thread(target=_scheduler_loop, daemon=True, name='achievements-scheduler')
    _scheduler_thread.start()
    logger.info('Achievements scheduler started')
