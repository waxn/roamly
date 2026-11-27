"""Data for the Chronicle layout's day feed (/timeline/).

``GET /api/layout/timeline/`` returns the requesting user's days, newest first,
each one a summary of what that local calendar day held. Only days that hold
something are returned; the client draws the gaps between them as "quiet days".

Query parameters
  before      YYYY-MM-DD, exclusive upper bound (default: tomorrow, local).
              A date after tomorrow is clamped to tomorrow.
  limit       days *with data* to return, 1..60 (default 30).
  year_strip  '1' adds ``activity`` for the last 24 months.

Response
  {
    "days": [{
      "date": "2026-11-26",            # local calendar day
      "weekday": "Thursday",
      "distance_km": 12.4,             # gated, same rule as Stats (0.0 if none)
      "moving_seconds": 5400,          # classified non-still time + unclassified motion
      "visit_count": 3,                # Visit rows overlapping the day
      "places": [{"name": str, "id": int|null, "kind": "place"|"poi"|"stop"|"city",
                  "lat": float|null, "lon": float|null}],    # <= 8, in visit order
      "transport": {"walk": 1800, "vehicle": 3600},          # seconds, classified only
      "journal": {"title": str, "excerpt": str, "mood": str, "weather": str,
                  "favorite": bool, "photo_count": int} | null,
      "health": {"steps": int|null,
                 "workouts": [{"type": str, "title": str, "duration_s": int,
                               "url": str|null}]} | null,
      "flights": [{"id": int, "from": str, "to": str, "number": str}],
      "adventure": {"id": int, "name": str} | null,
      "notes": [str]                   # the day's quick note, if any
    }],
    "next_before": "2026-10-28" | null,  # pass as ?before= for older days
    "today": "2026-11-27",               # the server's idea of the user's local today
    "activity": {"2026-11": {"2026-11-03": 2, ...}, ...}   # only with year_strip=1
  }

``activity`` scores are 1..4 (quartiles of the non-zero daily distance, at
least 1 for any day that has a visit, journal entry or flight); days scoring 0
are omitted. Months with nothing are present as empty objects so the client can
draw the full 24-month axis.

Days are the user's LOCAL days: UserTimezoneMiddleware has activated the zone
derived from their newest fix, and every boundary here goes through
``tz_utils.aware_local`` / ``timezone.localtime`` (see docs/claude/local-time.md).

Cost is bounded per request: finding the days scans at most _SCAN_MAX_DAYS of
history (in growing windows, oldest data first known so an empty account stops
at once), and the per-day detail is one streamed pass over the points of the
returned days plus one small query per other source. The response is cached on
the coarse location generation, the health generation and the newest journal
edit, so a repeat scroll costs a cache read.
"""
import logging
from collections import defaultdict
from datetime import date, datetime, time as dt_time, timedelta
from itertools import groupby

from django.contrib.auth.decorators import login_required
from django.core.cache import cache
from django.db.models import Count, Max, Min, Q
from django.db.models.functions import TruncDate
from django.http import JsonResponse
from django.utils import timezone
from django.views.decorators.http import require_GET

from .models import (
    Activity, Adventure, CustomPlace, Device, Flight, HealthSample, HealthWorkout,
    JournalEntry, Location, StatsSnapshot, Visit,
)
from .tz_utils import aware_local

logger = logging.getLogger(__name__)

DEFAULT_LIMIT = 30
MAX_LIMIT = 60
# Growing look-back windows for finding days with data; their sum caps how much
# history one request may scan. A longer silence is crossed by the client
# following next_before (an empty page with a cursor means "keep going").
_SCAN_WINDOWS = (21, 42, 84, 168, 280)
_SCAN_MAX_DAYS = sum(_SCAN_WINDOWS)          # 595
MAX_PLACES_PER_DAY = 8
EXCERPT_CHARS = 200
# Consecutive fixes further apart than this are a tracking hole, not motion
# (matches transport_tasks._MAX_BRIDGE_S).
_MOVE_MAX_GAP_S = 900
# Unclassified fallback: a pair counts as motion above walking-ish speed and a
# step long enough that GPS jitter cannot produce it.
_MOVE_MIN_MPS = 0.7
_MOVE_MIN_STEP_M = 30.0
_MOVE_MAX_ACC_M = 100.0
_MOVING_MODES = ('walk', 'cycle', 'vehicle', 'plane')
ACTIVITY_MONTHS = 24

_ACTIVITY_SLUG = {
    'ride': 'biking', 'run': 'running', 'walk': 'walking', 'hike': 'hiking',
    'row': 'rowing', 'other': 'workout',
}


# ── Day arithmetic (local) ─────────────────────────────────────────────────

def _day_start(d):
    """Aware instant of local midnight opening day ``d`` (DST-safe)."""
    return aware_local(datetime.combine(d, dt_time.min))


def _local_date(ts):
    return timezone.localtime(ts).date()


def _parse_day(value):
    try:
        return date.fromisoformat(str(value))
    except (TypeError, ValueError):
        return None


# ── Which days hold anything ───────────────────────────────────────────────

def _earliest_day(user, device_ids):
    """The first local day any source has data for, or None for an empty account.

    Location is probed one device at a time so each probe is an index seek on
    tracker_loc_dev_ts_idx rather than a scan of the whole history.
    """
    instants = []
    for dev_id in device_ids:
        ts = (Location.objects.filter(device_id=dev_id).order_by('timestamp')
              .values_list('timestamp', flat=True).first())
        if ts:
            instants.append(ts)
    # Steps only, matching _days_with_data: a calories-only history would
    # otherwise send the client paging through empty cursors to its first row.
    for qs in (HealthSample.objects.filter(user=user, kind='steps'),
               HealthWorkout.objects.filter(user=user), Activity.objects.filter(user=user)):
        ts = qs.aggregate(m=Min('start_time'))['m']
        if ts:
            instants.append(ts)
    days = [_local_date(ts) for ts in instants]
    for model in (JournalEntry, Flight):
        d = model.objects.filter(user=user).aggregate(m=Min('date'))['m']
        if d:
            days.append(d)
    return min(days) if days else None


def _days_with_data(user, device_ids, lo, hi):
    """Local days in [lo, hi) that hold any record. A set of ``date``."""
    tz = timezone.get_current_timezone()
    start, end = _day_start(lo), _day_start(hi)
    found = set()
    if device_ids:
        found.update(
            Location.objects.filter(device_id__in=device_ids, timestamp__gte=start, timestamp__lt=end)
            .order_by().annotate(d=TruncDate('timestamp', tzinfo=tz))
            .values_list('d', flat=True).distinct())
    found.update(
        HealthSample.objects.filter(user=user, kind='steps', start_time__gte=start, start_time__lt=end)
        .order_by().annotate(d=TruncDate('start_time', tzinfo=tz))
        .values_list('d', flat=True).distinct())
    for model in (HealthWorkout, Activity):
        for ts in (model.objects.filter(user=user, start_time__gte=start, start_time__lt=end)
                   .order_by().values_list('start_time', flat=True)[:2000]):
            found.add(_local_date(ts))
    for model in (JournalEntry, Flight):
        found.update(model.objects.filter(user=user, date__gte=lo, date__lt=hi)
                     .order_by().values_list('date', flat=True))
    found.discard(None)
    return {d for d in found if lo <= d < hi}


def _pick_days(user, device_ids, before, limit, earliest):
    """Newest ``limit`` data days before ``before`` and the cursor for the next page."""
    found = set()
    hi = before
    scanned_to = before
    for width in _SCAN_WINDOWS:
        if hi <= earliest:
            break
        lo = max(earliest, hi - timedelta(days=width))
        found |= _days_with_data(user, device_ids, lo, hi)
        scanned_to = lo
        hi = lo
        if len(found) > limit:
            break
    days = sorted(found, reverse=True)
    picked = days[:limit]
    if len(days) > limit:
        next_before = picked[-1]
    elif scanned_to > earliest:
        # The scan budget ran out before the account's first day. Every day
        # found is on or after scanned_to and nothing before it was looked at,
        # so the next page starts exactly there, even if this one is empty.
        next_before = scanned_to
    else:
        next_before = None
    return picked, next_before


# ── Per-day detail ─────────────────────────────────────────────────────────

def _blank_day(d):
    return {
        'date': d.isoformat(),
        'weekday': d.strftime('%A'),
        'distance_km': 0.0,
        'moving_seconds': 0,
        'visit_count': 0,
        'places': [],
        'transport': {},
        'journal': None,
        'health': None,
        'flights': [],
        'adventure': None,
        'notes': [],
    }


def _scan_track(device_ids, wanted, start, end):
    """One streamed pass over the points of the wanted days.

    Returns per-day ``km``, ``moving`` seconds, ``modes`` {mode: seconds} and the
    ordered distinct ``cities`` (the place fallback when no Visit covers the day).
    """
    from .views import _gated_distance_segments, _haversine_km

    km = defaultdict(float)
    moving = defaultdict(float)
    modes = defaultdict(lambda: defaultdict(float))
    cities = defaultdict(list)
    seen_city = defaultdict(set)
    if not device_ids or not wanted:
        return km, moving, modes, cities

    rows = (Location.objects
            .filter(device_id__in=device_ids, timestamp__gte=start, timestamp__lt=end,
                    latitude__isnull=False, longitude__isnull=False)
            .order_by('device_id', 'timestamp')
            .values_list('device_id', 'latitude', 'longitude', 'timestamp', 'accuracy',
                         'transport_mode', 'city', 'state')
            .iterator(chunk_size=10000))

    def _walk(group):
        """Feed the distance gate while accumulating time and place per day."""
        prev = None
        for _dev, lat, lon, ts, acc, mode, city, state in group:
            day = _local_date(ts)
            if day in wanted:
                label = (city or '').strip()
                if label:
                    if state:
                        label = f'{label}, {state}'
                    key = label.lower()
                    if key not in seen_city[day] and len(cities[day]) < MAX_PLACES_PER_DAY:
                        seen_city[day].add(key)
                        cities[day].append(label)
            if prev is not None:
                plat, plon, pts, pacc, pmode, pday = prev
                dt = (ts - pts).total_seconds()
                if 0 < dt <= _MOVE_MAX_GAP_S and pday in wanted:
                    if pmode and pmode == mode:
                        if mode in _MOVING_MODES:
                            modes[pday][mode] += dt
                            moving[pday] += dt
                    elif not pmode and not mode:
                        if (acc is None or acc <= _MOVE_MAX_ACC_M) and (pacc is None or pacc <= _MOVE_MAX_ACC_M):
                            step_m = _haversine_km(plat, plon, lat, lon) * 1000.0
                            if step_m >= _MOVE_MIN_STEP_M and step_m / dt >= _MOVE_MIN_MPS:
                                moving[pday] += dt
            prev = (lat, lon, ts, acc, mode, day)
            yield lat, lon, ts, acc

    for _dev_id, group in groupby(rows, key=lambda r: r[0]):
        for cdt, seg_km in _gated_distance_segments(_walk(group)):
            day = _local_date(cdt)
            if day in wanted:
                km[day] += seg_km
    return km, moving, modes, cities


def _nearest_place(places, lat, lon):
    from .views import _haversine_km
    best, best_d = None, None
    for p in places:
        d = _haversine_km(p['latitude'], p['longitude'], lat, lon) * 1000.0
        if d <= (p['radius_m'] or 150) and (best_d is None or d < best_d):
            best, best_d = p, d
    return best


def _scan_visits(user, wanted, start, end):
    """Per-day place chips and visit counts from the Visit table."""
    places = list(CustomPlace.objects.filter(user=user)
                  .values('id', 'name', 'latitude', 'longitude', 'radius_m')[:500])
    visits = (Visit.objects
              .filter(device__user=user, start_time__lt=end, end_time__gte=start)
              .order_by('start_time')
              .values('start_time', 'end_time', 'latitude', 'longitude', 'place_name',
                      'city', 'state', 'poi_id', 'poi__name')[:3000])
    chips = defaultdict(list)
    seen = defaultdict(set)
    counts = defaultdict(int)
    for v in visits:
        first, last = _local_date(v['start_time']), _local_date(v['end_time'])
        if last < first:
            last = first
        place = _nearest_place(places, v['latitude'], v['longitude']) if places else None
        if place:
            chip = {'name': place['name'], 'id': place['id'], 'kind': 'place',
                    'lat': place['latitude'], 'lon': place['longitude']}
        elif v['poi__name']:
            chip = {'name': v['poi__name'], 'id': None, 'kind': 'poi',
                    'lat': v['latitude'], 'lon': v['longitude']}
        else:
            name = (v['place_name'] or v['city'] or '').strip()
            if not name:
                name = 'Unnamed stop'
            chip = {'name': name, 'id': None, 'kind': 'stop',
                    'lat': v['latitude'], 'lon': v['longitude']}
        key = (chip['kind'], chip['id']) if chip['id'] else (chip['kind'], chip['name'].lower())
        # A stay can span midnight (a night at home): it belongs to each day it
        # touches. Bounded at three days so a runaway row cannot fan out.
        d = first
        for _ in range(3):
            if d > last:
                break
            if d in wanted:
                counts[d] += 1
                if key not in seen[d] and len(chips[d]) < MAX_PLACES_PER_DAY:
                    seen[d].add(key)
                    chips[d].append(chip)
            d += timedelta(days=1)
    return chips, counts


def _scan_steps(user, wanted, start, end):
    """Steps per local day, one source per day (the Health page's rule)."""
    from .views import _health_preferred
    preferred = _health_preferred(user)
    per_source = defaultdict(lambda: defaultdict(float))
    for st, value, source in (HealthSample.objects
                              .filter(user=user, kind='steps', start_time__gte=start, start_time__lt=end)
                              .order_by().values_list('start_time', 'value', 'source')
                              .iterator(chunk_size=10000)):
        if value is None:
            continue
        day = _local_date(st)
        if day in wanted:
            per_source[day][source or ''] += float(value)
    steps = {}
    for day, by_source in per_source.items():
        src = preferred if preferred and preferred in by_source else max(by_source, key=by_source.get)
        steps[day] = int(round(by_source[src]))
    return steps


def _scan_workouts(user, wanted, start, end):
    out = defaultdict(list)
    for w in (HealthWorkout.objects.filter(user=user, start_time__gte=start, start_time__lt=end)
              .order_by('start_time')
              .values('start_time', 'exercise_slug', 'title', 'duration_s')[:500]):
        day = _local_date(w['start_time'])
        if day in wanted:
            out[day].append({'type': w['exercise_slug'] or 'workout', 'title': w['title'] or '',
                             'duration_s': int(w['duration_s'] or 0), 'url': None})
    for a in (Activity.objects.filter(user=user, start_time__gte=start, start_time__lt=end)
              .order_by('start_time')
              .values('id', 'start_time', 'kind', 'title', 'moving_seconds', 'elapsed_seconds')[:500]):
        day = _local_date(a['start_time'])
        if day in wanted:
            out[day].append({'type': _ACTIVITY_SLUG.get(a['kind'], 'workout'), 'title': a['title'] or '',
                             'duration_s': int(a['moving_seconds'] or a['elapsed_seconds'] or 0),
                             'url': f"/activities/{a['id']}/"})
    return out


def _excerpt(text):
    text = ' '.join((text or '').split())
    if len(text) <= EXCERPT_CHARS:
        return text
    cut = text[:EXCERPT_CHARS].rsplit(' ', 1)[0] or text[:EXCERPT_CHARS]
    return cut.rstrip(',.;:') + '…'


def _scan_journals(user, lo, hi, wanted):
    journals, notes = {}, {}
    for e in (JournalEntry.objects.filter(user=user, date__gte=lo, date__lte=hi)
              .annotate(n_photos=Count('photos'))
              .values('date', 'title', 'body', 'quick_note', 'mood', 'weather',
                      'is_favorite', 'n_photos')):
        d = e['date']
        if d not in wanted:
            continue
        note = (e['quick_note'] or '').strip()
        if note:
            notes[d] = [note]
        if e['title'] or (e['body'] or '').strip() or e['mood'] or e['weather'] or e['n_photos']:
            journals[d] = {
                'title': e['title'] or '',
                'excerpt': _excerpt(e['body']),
                'mood': e['mood'] or '',
                'weather': e['weather'] or '',
                'favorite': bool(e['is_favorite']),
                'photo_count': int(e['n_photos'] or 0),
            }
    return journals, notes


def _scan_flights(user, lo, hi, wanted):
    out = defaultdict(list)
    for f in (Flight.objects.filter(user=user, date__gte=lo, date__lte=hi)
              .order_by('date', 'sched_dep', 'id')
              .values('id', 'date', 'flight_number', 'dep_iata', 'dep_city', 'dep_name',
                      'arr_iata', 'arr_city', 'arr_name')[:500]):
        if f['date'] in wanted:
            out[f['date']].append({
                'id': f['id'],
                'from': f['dep_iata'] or f['dep_city'] or f['dep_name'] or '',
                'to': f['arr_iata'] or f['arr_city'] or f['arr_name'] or '',
                'number': f['flight_number'] or '',
            })
    return out


def _scan_adventures(user, wanted, start, end):
    """The first adventure (owned, or joined and accepted) covering each day."""
    rows = list(Adventure.objects
                .filter(Q(device__user=user) | Q(members__user=user, members__accepted_at__isnull=False))
                .filter(start_time__lt=end, end_time__gte=start)
                .distinct().order_by('start_time')
                .values('id', 'name', 'start_time', 'end_time')[:100])
    out = {}
    for d in wanted:
        ds, de = _day_start(d), _day_start(d + timedelta(days=1))
        for a in rows:
            if a['start_time'] < de and a['end_time'] >= ds:
                out[d] = {'id': a['id'], 'name': a['name']}
                break
    return out


def build_days(user, device_ids, picked):
    """Full day summaries for the picked local days (newest first)."""
    if not picked:
        return []
    wanted = set(picked)
    lo, hi = min(picked), max(picked)
    start, end = _day_start(lo), _day_start(hi + timedelta(days=1))

    km, moving, modes, cities = _scan_track(device_ids, wanted, start, end)
    chips, counts = _scan_visits(user, wanted, start, end)
    steps = _scan_steps(user, wanted, start, end)
    workouts = _scan_workouts(user, wanted, start, end)
    journals, notes = _scan_journals(user, lo, hi, wanted)
    flights = _scan_flights(user, lo, hi, wanted)
    adventures = _scan_adventures(user, wanted, start, end)

    days = []
    for d in sorted(picked, reverse=True):
        day = _blank_day(d)
        day['distance_km'] = round(km.get(d, 0.0), 2)
        day['moving_seconds'] = int(round(moving.get(d, 0.0)))
        day['transport'] = {m: int(round(s)) for m, s in sorted(modes.get(d, {}).items(),
                                                                 key=lambda kv: -kv[1]) if s >= 1}
        day['visit_count'] = counts.get(d, 0)
        day['places'] = chips.get(d) or [
            {'name': c, 'id': None, 'kind': 'city', 'lat': None, 'lon': None} for c in cities.get(d, [])]
        day['journal'] = journals.get(d)
        if d in steps or d in workouts:
            day['health'] = {'steps': steps.get(d), 'workouts': workouts.get(d, [])}
        day['flights'] = flights.get(d, [])
        day['adventure'] = adventures.get(d)
        day['notes'] = notes.get(d, [])
        days.append(day)
    return days


# ── Year strip ─────────────────────────────────────────────────────────────

def _month_keys(today, months):
    y, m = today.year, today.month
    keys = []
    for _ in range(months):
        keys.append(f'{y:04d}-{m:02d}')
        m -= 1
        if m == 0:
            y, m = y - 1, 12
    return list(reversed(keys))


def _quartile_scores(values):
    """{day: value} -> {day: 1..4} by quartile of the non-zero values."""
    nz = sorted(v for v in values.values() if v > 0)
    if not nz:
        return {}
    q = [nz[int((len(nz) - 1) * f)] for f in (0.25, 0.5, 0.75)]
    out = {}
    for d, v in values.items():
        if v <= 0:
            continue
        out[d] = 1 if v <= q[0] else 2 if v <= q[1] else 3 if v <= q[2] else 4
    return out


def build_activity(user, device_ids, today):
    keys = _month_keys(today, ACTIVITY_MONTHS)
    first = date.fromisoformat(keys[0] + '-01')
    end_day = today + timedelta(days=1)
    lo_iso, hi_iso = first.isoformat(), today.isoformat()

    values = {}
    snap = StatsSnapshot.objects.filter(user=user, computed_at__isnull=False).only('stats_json').first()
    dist = ((snap.stats_json or {}).get('distance') or {}) if snap else {}
    s_days, s_km = dist.get('days') or [], dist.get('distances') or []
    if isinstance(s_days, list) and isinstance(s_km, list) and s_days:
        for d, v in zip(s_days, s_km):
            if isinstance(d, str) and lo_iso <= d[:10] <= hi_iso:
                try:
                    values[d[:10]] = float(v or 0)
                except (TypeError, ValueError):
                    continue
    elif device_ids:
        # No snapshot yet (a new account, so a small history): points per day.
        tz = timezone.get_current_timezone()
        for d, n in (Location.objects.filter(device_id__in=device_ids,
                                             timestamp__gte=_day_start(first), timestamp__lt=_day_start(end_day))
                     .order_by().annotate(d=TruncDate('timestamp', tzinfo=tz))
                     .values('d').annotate(n=Count('id')).values_list('d', 'n')):
            if d:
                values[d.isoformat()] = float(n)
    scores = _quartile_scores(values)

    start, end = _day_start(first), _day_start(end_day)
    touched = set()
    for ts in (Visit.objects.filter(device__user=user, start_time__gte=start, start_time__lt=end)
               .order_by().values_list('start_time', flat=True)[:20000]):
        touched.add(_local_date(ts).isoformat())
    for model in (JournalEntry, Flight):
        touched.update(d.isoformat() for d in model.objects.filter(user=user, date__gte=first, date__lte=today)
                       .order_by().values_list('date', flat=True))
    for d in touched:
        if lo_iso <= d <= hi_iso:
            scores[d] = max(scores.get(d, 0), 1)

    out = {k: {} for k in keys}
    for d, s in scores.items():
        bucket = out.get(d[:7])
        if bucket is not None:
            bucket[d] = s
    return out


# ── The endpoint ───────────────────────────────────────────────────────────

def _cache_token(user):
    """What the cached feed depends on beyond the two generations: journal and
    flight edits bump neither, so their newest edit and row count go in too
    (a delete lowers the count). Two small aggregates on per-user tables."""
    from .views import _health_gen, _slow_gen
    parts = [str(_slow_gen(user.id)), str(_health_gen(user.id))]
    for model in (JournalEntry, Flight):
        agg = model.objects.filter(user=user).aggregate(n=Count('id'), m=Max('updated_at'))
        parts.append(f"{agg['n']}.{int(agg['m'].timestamp()) if agg['m'] else 0}")
    return ':'.join(parts)


@login_required
@require_GET
def timeline_api(request):
    user = request.user
    today = timezone.localdate()
    tomorrow = today + timedelta(days=1)

    before = tomorrow
    if request.GET.get('before'):
        before = _parse_day(request.GET.get('before'))
        if before is None:
            return JsonResponse({'error': 'before must be a date (YYYY-MM-DD).'}, status=400)
        before = min(before, tomorrow)
    try:
        limit = int(request.GET.get('limit') or DEFAULT_LIMIT)
    except (TypeError, ValueError):
        return JsonResponse({'error': 'limit must be a whole number.'}, status=400)
    limit = max(1, min(limit, MAX_LIMIT))
    want_strip = request.GET.get('year_strip') == '1'

    try:
        token = _cache_token(user)
        key = f'lx_tl:{user.id}:{token}:{today.isoformat()}:{before.isoformat()}:{limit}'
        payload = cache.get(key)
        if payload is None:
            device_ids = list(Device.objects.filter(user=user).values_list('id', flat=True))
            earliest = _earliest_day(user, device_ids)
            picked, next_before = ([], None) if earliest is None or earliest >= before else \
                _pick_days(user, device_ids, before, limit, earliest)
            payload = {
                'days': build_days(user, device_ids, picked),
                'next_before': next_before.isoformat() if next_before else None,
                'today': today.isoformat(),
            }
            # A page that reaches today keeps moving; older pages barely change.
            cache.set(key, payload, 60 if before >= today else 600)

        if want_strip:
            akey = f'lx_tl_act:{user.id}:{token}:{today.isoformat()}'
            activity = cache.get(akey)
            if activity is None:
                device_ids = list(Device.objects.filter(user=user).values_list('id', flat=True))
                activity = build_activity(user, device_ids, today)
                cache.set(akey, activity, 1800)
            payload = dict(payload, activity=activity)
    except Exception:
        logger.exception('timeline_api failed for user %s', user.id)
        return JsonResponse({'error': 'The timeline could not be loaded.'}, status=500)
    return JsonResponse(payload)
