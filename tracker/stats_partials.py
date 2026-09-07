"""Per-day partial aggregates behind the Stats / Visits / Places snapshot.

``StatsSnapshot`` serves five all-time payloads. Recomputing them used to mean
streaming the user's entire location history through Python on every run — four
separate full passes (gated distance, the visits dwell walk, the per-mode
transport breakdown, and a scan per custom place). On a real history that is
minutes of CPU to answer a question whose answer barely moved since yesterday.

Those four passes are the only expensive part. Everything else in the snapshot is
a handful of indexed DB aggregates, so this module leaves those exactly where
they are — computed live on every roll-up, byte for byte what they always were —
and caches *only* the streaming work, bucketed per UTC day:

    partials = {
        'v': PARTIALS_VERSION,
        'days': {'2026-09-01': {…}, …},   # see _DAY_SHAPE below
        'places': {'<place id>': '<geometry fingerprint>'},
    }

A recalculation then re-reads only the days in its window and rolls the whole
all-time payload up from the map. The numbers stay all-time and stay exact.

Two rules make the per-day split exact rather than approximate:

* **A quantity that spans midnight is attributed to the day of the *earlier*
  point.** A visit dwell gap, a transport run's elapsed seconds and a custom
  place's dwell are therefore each a sum over consecutive pairs rather than one
  end-to-end subtraction — the pairwise sum equals the whole, and it partitions.
  Gated distance already buckets each credited segment by its own timestamp, so
  it partitions with no help.

* **The window always extends back to include the newest day already in the
  map.** That day was computed when it was the *trailing* day, so its last point
  had no successor yet and its closing gap was never recorded. Recomputing it now
  that later data exists is what keeps a gap from being silently dropped at every
  window boundary. Without this, an overnight stay would lose its overnight.

`covered_from` on the row is the honest half of the deal: NULL means the map is
not a whole-history record, so the next run is a full one regardless of the
window asked for. A version bump here has the same effect.
"""

import logging
from collections import defaultdict
from datetime import timedelta, timezone as dt_timezone
from itertools import groupby

logger = logging.getLogger(__name__)

# Bump to force every snapshot to rebuild its map from scratch on the next run.
PARTIALS_VERSION = 1

# Key separator inside a composite day-map key. \x1f (ASCII unit separator) can't
# occur in a geocoded place name, so split() round-trips the tuple exactly.
SEP = '\x1f'

# How far before the window a device's stream is read for distance/transport. The
# distance gate resets across a >2h gap (_DIST_GAP_RESET_S), so six hours of lead
# is enough for the window's first segment to start from the same anchor an
# all-time pass would have had, instead of a cold one. Contributions from before
# the window are discarded — those days keep the partials they already have.
SEED_S = 6 * 3600

# Days of overlap a default "since the last run" window reaches back beyond the
# last completed run. Tracking that arrives late, a clock skew, or an import
# backdated into yesterday all land inside it.
DEFAULT_OVERLAP_DAYS = 2

# Matches _calc_dwell_time's max_gap: a longer hole means the user left and came
# back, so it isn't time spent in the place.
PLACE_DWELL_MAX_GAP_S = 600

# A day entry. Sub-maps are omitted entirely when empty to keep the blob small.
#   'km' : float   gated distance credited to this day (absent = no segment,
#                  which is NOT the same as 0.0 — an absent day is left out of
#                  the distance series exactly as the live pass leaves it out)
#   'dc' : {city|state|country|cc : seconds}   visit dwell
#   'ds' : {state|country : seconds}
#   'dn' : {country : seconds}
#   'tm' : {mode : [km, points, seconds]}      transport breakdown
#   'tu' : int                                 unclassified points
#   'pl' : {place id : [points, last_iso, dwell_seconds]}   custom places
_DAY_SHAPE = None  # documentation only


def blank_partials():
    return {'v': PARTIALS_VERSION, 'days': {}, 'places': {}}


def is_usable(partials):
    """True if this map was written by the current version of this module."""
    return bool(partials) and partials.get('v') == PARTIALS_VERSION


def day_key(ts):
    """The day bucket for a timestamp.

    Deliberately the same expression `_compute_distance_from_qs` uses on the
    datetime the ORM hands back, so the buckets here and the buckets in the live
    pass can never disagree about which day a point belongs to.
    """
    return ts.strftime('%Y-%m-%d')


def day_start(key):
    """UTC midnight opening the given 'YYYY-MM-DD' bucket."""
    from datetime import datetime
    return datetime.strptime(key, '%Y-%m-%d').replace(tzinfo=dt_timezone.utc)


def floor_to_day(dt):
    """Round an instant back to the midnight opening its bucket."""
    return day_start(day_key(dt))


def newest_day_start(partials):
    """Midnight of the newest day present in the map, or None if it's empty.

    A window must always reach back this far — see the module docstring.
    """
    days = (partials or {}).get('days') or {}
    if not days:
        return None
    return day_start(max(days))


# ---------------------------------------------------------------------------
# Window computation
# ---------------------------------------------------------------------------

def refresh(user, partials, window_start):
    """Recompute every day from ``window_start`` (a UTC midnight) onward, in place.

    The window always runs to now, so the days it covers are simply dropped and
    rebuilt — there is no partial-day merge to get wrong.
    """
    days = partials.setdefault('days', {})
    start_day = day_key(window_start)
    for k in [k for k in days if k >= start_day]:
        del days[k]

    _scan_distance_and_transport(user, window_start, days)
    _scan_dwell(user, window_start, days)
    _scan_places(user, partials, window_start, days)

    # Drop days that ended up with nothing worth storing (e.g. a day whose only
    # points were unlabelled and stationary), so the map doesn't accrete blanks.
    for k in [k for k, d in days.items() if not d]:
        del days[k]
    return partials


def _bump(day, sub, key, amount):
    m = day.get(sub)
    if m is None:
        m = day[sub] = {}
    m[key] = m.get(key, 0.0) + amount


def _scan_distance_and_transport(user, start, days):
    """Gated distance per day, and the transport breakdown per day, per device.

    Both walk one device's track in time order, so they are done together here
    even though they are two queries — a single buffered pass would have to hold
    a whole device's history in memory on a full rebuild.
    """
    from .models import Location, Device
    from .views import _gated_distance_segments

    start_day = day_key(start)
    seed = start - timedelta(seconds=SEED_S)

    for dev_id in Device.objects.filter(user=user).values_list('id', flat=True):
        base = Location.objects.filter(device_id=dev_id, timestamp__gte=seed)

        # ── distance ────────────────────────────────────────────────────────
        rows = (base.order_by('timestamp')
                .values_list('latitude', 'longitude', 'timestamp', 'accuracy')
                .iterator(chunk_size=10000))
        for cdt, km in _gated_distance_segments(rows):
            k = day_key(cdt)
            if k < start_day:
                continue          # seed lead-in: that day is already recorded
            d = days.setdefault(k, {})
            d['km'] = d.get('km', 0.0) + km

        # ── transport ───────────────────────────────────────────────────────
        rows = (base.order_by('timestamp', 'id')
                .values('latitude', 'longitude', 'timestamp', 'accuracy', 'transport_mode')
                .iterator(chunk_size=5000))
        for mode, group in groupby(rows, key=lambda r: r['transport_mode']):
            g = list(group)
            if not mode:
                for r in g:
                    k = day_key(r['timestamp'])
                    if k >= start_day:
                        d = days.setdefault(k, {})
                        d['tu'] = d.get('tu', 0) + 1
                continue

            def _mode_entry(k):
                tm = days.setdefault(k, {}).setdefault('tm', {})
                e = tm.get(mode)
                if e is None:
                    e = tm[mode] = [0.0, 0, 0.0]
                return e

            for r in g:
                k = day_key(r['timestamp'])
                if k >= start_day:
                    _mode_entry(k)[1] += 1

            # Elapsed time as a sum over consecutive pairs rather than
            # last-minus-first: the two are equal, but only the pairwise form
            # splits correctly across a run that straddles midnight.
            for i in range(1, len(g)):
                k = day_key(g[i - 1]['timestamp'])
                if k >= start_day:
                    _mode_entry(k)[2] += (g[i]['timestamp'] - g[i - 1]['timestamp']).total_seconds()

            # These runs are already classified motion, so start MOVING — the same
            # reasoning (and the same argument) as the live pass.
            pts = [(r['latitude'], r['longitude'], r['timestamp'], r['accuracy']) for r in g]
            for ts, km in _gated_distance_segments(pts, initial_state='MOVING'):
                k = day_key(ts)
                if k >= start_day:
                    _mode_entry(k)[0] += km


def _scan_dwell(user, start, days):
    """Visit dwell seconds per city/state/country, per day.

    Mirrors the walk in `_compute_visits_from_qs`: one global time-ordered pass
    over the city-labelled points (not per device — that is what the live pass
    does, and the totals have to agree), attributing each gap to the earlier
    point's place *and* to the earlier point's day.
    """
    from .models import Location
    from .views import _visits_dwell_gap

    rows = (Location.objects.filter(device__user=user, timestamp__gte=start)
            .exclude(city='')
            .order_by('timestamp')
            .values_list('timestamp', 'city', 'state', 'country', 'country_code',
                         'latitude', 'longitude'))

    prev = None
    for ts, city, state_val, country_val, cc, lat, lon in rows.iterator(chunk_size=10000):
        cur = (ts, city, state_val, country_val, cc, lat, lon)
        if prev:
            d = days.setdefault(day_key(prev[0]), {})
            if prev[1]:
                gap = _visits_dwell_gap(prev, cur, 'city')
                if gap > 0:
                    _bump(d, 'dc', SEP.join((prev[1], prev[2], prev[3], prev[4])), gap)
            if prev[2]:
                gap = _visits_dwell_gap(prev, cur, 'state')
                if gap > 0:
                    _bump(d, 'ds', SEP.join((prev[2], prev[3])), gap)
            if prev[3]:
                gap = _visits_dwell_gap(prev, cur, 'country')
                if gap > 0:
                    _bump(d, 'dn', prev[3], gap)
        prev = cur


def _place_fingerprint(place):
    """Identifies the circle a place's stored counts were measured against."""
    return f"{place.latitude:.6f},{place.longitude:.6f},{place.radius_m:.1f}"


def _scan_places(user, partials, start, days):
    """Points, last-seen and dwell per custom place, per day.

    A place whose circle moved or resized is rescanned over the whole history
    rather than the window: its stored counts describe a different circle, and
    no amount of recent data repairs that.
    """
    from .models import Location, CustomPlace
    from .views import _find_nearby_locations

    base = Location.objects.filter(device__user=user)
    stored = partials.get('places') or {}
    live = {}

    for place in CustomPlace.objects.filter(user=user):
        pid = str(place.id)
        fp = _place_fingerprint(place)
        live[pid] = fp
        if stored.get(pid) == fp:
            since = start
        else:
            _forget_place(days, pid)
            since = None

        qs = _find_nearby_locations(base, place.latitude, place.longitude, place.radius_m)
        if since is not None:
            qs = qs.filter(timestamp__gte=since)

        prev = None
        for ts in (qs.order_by('timestamp').values_list('timestamp', flat=True)
                     .iterator(chunk_size=10000)):
            entry = _place_entry(days, day_key(ts), pid)
            entry[0] += 1
            entry[1] = ts.isoformat()      # time-ordered, so the last write wins
            if prev is not None:
                gap = (ts - prev).total_seconds()
                if gap <= PLACE_DWELL_MAX_GAP_S:
                    _place_entry(days, day_key(prev), pid)[2] += gap
            prev = ts

    # A deleted place leaves counts behind in every day it appeared in.
    for pid in stored:
        if pid not in live:
            _forget_place(days, pid)
    partials['places'] = live


def _place_entry(days, key, pid):
    pl = days.setdefault(key, {}).setdefault('pl', {})
    e = pl.get(pid)
    if e is None:
        e = pl[pid] = [0, None, 0.0]
    return e


def _forget_place(days, pid):
    for d in days.values():
        pl = d.get('pl')
        if pl:
            pl.pop(pid, None)
            if not pl:
                d.pop('pl', None)


# ---------------------------------------------------------------------------
# Roll-up
# ---------------------------------------------------------------------------

def roll_up(user, partials):
    """Build the five snapshot payloads from the day map plus live DB aggregates.

    Returns ``(overview, visits, yearly, places, transport)`` — the same five
    objects, in the same shapes, that a whole-history pass produces.
    """
    from .models import Location
    from .transport_tasks import MODES
    from .views import (
        _compute_overview_from_qs, _compute_visits_from_qs,
        _compute_yearly_payload, _compute_places_payload, _transport_payload,
    )

    days = (partials or {}).get('days') or {}
    ordered = sorted(days.items())     # deterministic accumulation order

    # ── distance ────────────────────────────────────────────────────────────
    dist_keys = [k for k, d in ordered if 'km' in d]
    distance = {
        'days': dist_keys,
        'distances': [round(days[k]['km'], 2) for k in dist_keys],
        'total_km': round(sum(days[k]['km'] for k in dist_keys), 2),
    }

    # ── visit dwell, keyed exactly as _compute_visits_from_qs looks it up ────
    time_city = defaultdict(float)
    time_state = defaultdict(float)
    time_country = defaultdict(float)
    for _k, d in ordered:
        for key, secs in (d.get('dc') or {}).items():
            time_city[tuple(key.split(SEP))] += secs
        for key, secs in (d.get('ds') or {}).items():
            time_state[tuple(key.split(SEP))] += secs
        for key, secs in (d.get('dn') or {}).items():
            time_country[key] += secs

    # ── custom places ───────────────────────────────────────────────────────
    pl_points, pl_last, pl_days, pl_dwell = {}, {}, {}, {}
    for _k, d in ordered:
        for pid, (n, last, dwell) in (d.get('pl') or {}).items():
            i = int(pid)
            if n:
                pl_points[i] = pl_points.get(i, 0) + n
                pl_days[i] = pl_days.get(i, 0) + 1
                if last and (pl_last.get(i) is None or last > pl_last[i]):
                    pl_last[i] = last
            if dwell:
                pl_dwell[i] = pl_dwell.get(i, 0.0) + dwell
    ids = set(pl_points) | set(pl_dwell)
    place_totals = {i: {'n': pl_points.get(i, 0), 'last': pl_last.get(i)} for i in ids}
    place_yearly = {i: {'days': pl_days.get(i, 0), 'dwell': int(pl_dwell.get(i, 0.0))}
                    for i in ids}

    # ── transport ───────────────────────────────────────────────────────────
    per_mode = {m: {'km': 0.0, 'points': 0, 'seconds': 0.0} for m in MODES}
    unclassified = 0
    for _k, d in ordered:
        unclassified += d.get('tu', 0)
        for mode, (km, pts, secs) in (d.get('tm') or {}).items():
            b = per_mode.setdefault(mode, {'km': 0.0, 'points': 0, 'seconds': 0.0})
            b['km'] += km
            b['points'] += pts
            b['seconds'] += secs

    all_qs = Location.objects.filter(device__user=user)
    overview = _compute_overview_from_qs(all_qs, user)
    overview['distance'] = distance
    visits = _compute_visits_from_qs(
        all_qs.exclude(city=''), dwell=(time_city, time_state, time_country))
    yearly = _compute_yearly_payload(user, place_stats=place_yearly)
    places = _compute_places_payload(user, place_stats=place_totals)
    transport = _transport_payload(per_mode, unclassified)

    return overview, visits, yearly, places, transport
