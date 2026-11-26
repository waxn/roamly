"""Data maps for the Ask chat — read-only.

A *data map* is a card (``kind: "data"``) the assistant attaches to an answer
when the user wants their history summed up on a map rather than drawn point by
point: a number per US state or country ("how many Hannafords have I been in,
by state"), a ranking of how much of each state's towns they have covered, or
the roads they have driven most.

The assistant never writes a query. It picks a **metric** from the fixed list
below and a few validated parameters; the queries are ours, every one scoped to
``user`` and none of them writes. The card holds only those parameters — the
numbers are recomputed from the signed-in user's own data whenever the card is
opened, so a stored chat never contains a copy of the history.
"""
import datetime
import hashlib
import json
import logging
from collections import Counter, defaultdict

from django.core.cache import cache
from django.db import connection
from django.db.models import Count, Q

logger = logging.getLogger(__name__)

METRICS = {
    # metric: (needs a name filter, label for the numbers)
    'place_visits': 'distinct places',
    'days': 'days',
    'town_coverage': '% of towns',
    'top_roads': '% of driving',
}
LEVELS = ('state', 'country')
_MAX_REGIONS = 250
_ROAD_SAMPLE = 2500          # driving fixes looked at, at most
_ROAD_MAX_M = 40.0           # a fix further than this from a road isn't on it
_ROAD_TOP = 15
_ROAD_MAX_WAYS = 900         # ways of geometry sent to the browser
_ROAD_SPEED_MPS = 5.0        # same threshold roads.snappable() falls back to
_CACHE_S = 600
_CHUNK = 400


class DataMapError(ValueError):
    """The card parameters can't be turned into a query."""


def _day(value):
    try:
        return datetime.date.fromisoformat(str(value).strip()[:10])
    except (ValueError, TypeError):
        raise DataMapError('dates must be YYYY-MM-DD')


def clean_card(raw):
    """Validate untrusted parameters and return only the keys we use."""
    if not isinstance(raw, dict):
        raise DataMapError('invalid card')
    metric = str(raw.get('metric') or '').strip()
    if metric not in METRICS:
        raise DataMapError('metric must be one of: ' + ', '.join(METRICS))
    card = {'kind': 'data', 'metric': metric}
    level = str(raw.get('level') or 'state').strip()
    if metric in ('place_visits', 'days'):
        if level not in LEVELS:
            raise DataMapError('level must be state or country')
        card['level'] = level
    if metric == 'place_visits':
        name = str(raw.get('name_contains') or '').strip()[:80]
        if len(name) < 2:
            raise DataMapError('name_contains is required: part of the place name, e.g. "Hannaford"')
        card['name_contains'] = name
    if metric in ('days', 'top_roads'):
        for k in ('start_date', 'end_date'):
            if raw.get(k):
                card[k] = _day(raw[k]).isoformat()
        if 'start_date' in card and 'end_date' in card and card['start_date'] > card['end_date']:
            card['start_date'], card['end_date'] = card['end_date'], card['start_date']
    if metric in ('days', 'top_roads', 'town_coverage'):
        state = str(raw.get('state') or '').strip()[:100]
        if state:
            card['state'] = state
    title = str(raw.get('title') or '').strip()[:80]
    card['title'] = title or _default_title(card)
    return card


def _default_title(card):
    m = card['metric']
    if m == 'place_visits':
        return f"{card['name_contains']} by {card.get('level', 'state')}"
    if m == 'days':
        return f"Days spent by {card.get('level', 'state')}"
    if m == 'town_coverage':
        return 'Town coverage by state'
    return 'Most travelled roads' + (f" in {card['state']}" if card.get('state') else '')


def sub_title(card):
    return {'place_visits': 'distinct places', 'days': 'days',
            'town_coverage': 'town coverage', 'top_roads': 'roads'}[card['metric']]


# ── Shared filters ─────────────────────────────────────────────────────────

def _points(user, card, tz_offset=0):
    from . import views
    from .models import Location
    qs = Location.objects.filter(device__user=user,
                                 latitude__isnull=False, longitude__isnull=False)
    if card.get('start_date'):
        qs = qs.filter(timestamp__gte=views._journal_day_bounds(_day(card['start_date']), tz_offset)[0])
    if card.get('end_date'):
        qs = qs.filter(timestamp__lte=views._journal_day_bounds(_day(card['end_date']), tz_offset)[1])
    if card.get('state'):
        qs = qs.filter(state__iexact=card['state'])
    return qs


def _region_field(card):
    return 'country' if card.get('level') == 'country' else 'state'


def _region(row_state, row_country, row_cc, level):
    """(key, display name) for a row. States key on their name, countries on the
    ISO code the world map is keyed by."""
    if level == 'country':
        cc = (row_cc or '').upper()
        return (cc, row_country or cc) if cc else (None, None)
    return (row_state, row_state) if row_state else (None, None)


# ── Metrics ────────────────────────────────────────────────────────────────

def _place_visits(user, card, tz_offset):
    """Distinct places whose name matches, per region, with how often visited."""
    from .models import Visit
    needle = card['name_contains']
    level = card['level']
    qs = Visit.objects.filter(device__user=user).filter(
        Q(place_name__icontains=needle) | Q(poi__name__icontains=needle))
    if level == 'state':
        qs = qs.filter(country_code__iexact='US')
    places = defaultdict(set)
    visits = Counter()
    names = {}
    rows = qs.order_by().values_list('state', 'country', 'country_code', 'poi_id',
                                     'place_name', 'latitude', 'longitude')[:50000]
    for state, country, cc, poi_id, pname, lat, lon in rows:
        key, name = _region(state, country, cc, level)
        if not key:
            continue
        # One store is one place however many times it was visited: its POI,
        # else its name at a ~100 m grid cell.
        places[key].add(('p', poi_id) if poi_id else ('n', pname, round(lat, 3), round(lon, 3)))
        visits[key] += 1
        names[key] = name
    return [{'key': k, 'name': names[k], 'value': len(v),
             'detail': f"{visits[k]} visit{'s' if visits[k] != 1 else ''}"}
            for k, v in places.items()]


def _days(user, card, tz_offset):
    from django.db.models.functions import TruncDate
    level = card['level']
    qs = _points(user, card, tz_offset)
    if level == 'state':
        qs = qs.filter(country_code__iexact='US')
    # Group on the level itself, so a country's days aren't summed over its states.
    fields = ('country', 'country_code') if level == 'country' else ('state',)
    rows = (qs.exclude(**{_region_field(card): ''}).annotate(d=TruncDate('timestamp')).order_by()
            .values(*fields).annotate(n=Count('d', distinct=True)))
    merged, names = Counter(), {}
    for r in rows:
        key, name = _region(r.get('state'), r.get('country'), r.get('country_code'), level)
        if key:
            merged[key] += r['n']
            names[key] = name
    return [{'key': k, 'name': names[k], 'value': n, 'detail': ''} for k, n in merged.items()]


def _town_coverage(user, card, tz_offset):
    """Towns visited ÷ towns the state has (the imported Census boundaries).

    A state whose boundaries aren't loaded has no denominator: it is listed with
    its visited count and no percentage rather than a made-up one."""
    from .models import Boundary
    visited = defaultdict(set)
    qs = _points(user, card, tz_offset).filter(country_code__iexact='US').exclude(city='').exclude(state='')
    for state, city in qs.order_by().values_list('state', 'city').distinct():
        visited[state].add(city.lower())
    total = defaultdict(set)
    bq = Boundary.objects.filter(kind__in=('place', 'cousub'), state__in=list(visited) or [''])
    for state, name in bq.values_list('state', 'name'):
        total[state].add(name.lower())
    out = []
    for state, towns in visited.items():
        have = len(towns & total[state]) if total[state] else len(towns)
        of = len(total[state])
        row = {'key': state, 'name': state, 'visited': have, 'of': of}
        if of:
            row['value'] = round(100.0 * min(have, of) / of, 1)
            row['detail'] = f"{have} of {of} towns"
        else:
            row['value'] = None
            row['detail'] = f"{have} towns (boundaries for this state aren't loaded)"
        out.append(row)
    return out


def _top_roads(user, card, tz_offset):
    """Which named roads the user's driving fixes sit on, and the roads' lines.

    Fixes are sampled (``id % k``), so the share is relative: a road with 12% of
    the sampled driving fixes accounts for roughly 12% of the driving. Each fix
    is matched to the nearest ``RoadSegment`` within ``_ROAD_MAX_M``."""
    from .models import HAS_POSTGIS, RoadSegment
    if not HAS_POSTGIS or not RoadSegment.objects.exists():
        return {'error': "Road data isn't downloaded on this server, so roads can't be ranked."}
    from django.db.models import F, Value
    qs = _points(user, card, tz_offset).filter(
        Q(transport_mode='vehicle') | Q(transport_mode='', speed__gte=_ROAD_SPEED_MPS))
    total = qs.count()
    if not total:
        return {'error': 'No driving was recorded for that.'}
    k = max(1, -(-total // _ROAD_SAMPLE))
    sample = qs.annotate(_m=F('id') % Value(k)).filter(_m=0) if k > 1 else qs
    pts = list(sample.order_by().values_list('id', 'longitude', 'latitude')[:_ROAD_SAMPLE + 500])

    ways = Counter()
    with connection.cursor() as cur:
        for i in range(0, len(pts), _CHUNK):
            chunk = pts[i:i + _CHUNK]
            values = ','.join(['(%s::bigint, %s::double precision, %s::double precision)'] * len(chunk))
            params = [v for p in chunk for v in p]
            params.append(_ROAD_MAX_M)
            cur.execute(f"""
                SELECT c.way_id
                FROM (VALUES {values}) AS p(pid, lng, lat)
                CROSS JOIN LATERAL (
                    SELECT r.way_id,
                           ST_Distance(r.geom::geography,
                                       ST_SetSRID(ST_MakePoint(p.lng, p.lat), 4326)::geography) AS d
                    FROM tracker_roadsegment r
                    ORDER BY r.geom <-> ST_SetSRID(ST_MakePoint(p.lng, p.lat), 4326)
                    LIMIT 1
                ) c
                WHERE c.d <= %s
            """, params)
            for (way_id,) in cur.fetchall():
                ways[way_id] += 1
    if not ways:
        return {'error': "None of the driving fixes sit on a downloaded road."}

    meta = {w: (n, h) for w, n, h in
            RoadSegment.objects.filter(way_id__in=list(ways)).values_list('way_id', 'name', 'highway')}
    by_name, name_ways = Counter(), defaultdict(list)
    for way_id, n in ways.items():
        name = (meta.get(way_id, ('', ''))[0] or '').strip()
        if name:                       # unnamed service roads and ramps can't be ranked by name
            by_name[name] += n
            name_ways[name].append(way_id)
    matched = sum(ways.values())
    top = by_name.most_common(_ROAD_TOP)
    if not top:
        return {'error': "The roads driven have no names in the road data."}

    keep = [w for name, _ in top for w in name_ways[name]][:_ROAD_MAX_WAYS]
    geoms = {}
    with connection.cursor() as cur:
        cur.execute("SELECT way_id, ST_AsGeoJSON(ST_SimplifyPreserveTopology(geom, 0.00003), 5) "
                    "FROM tracker_roadsegment WHERE way_id = ANY(%s)", [keep])
        for way_id, gj in cur.fetchall():
            geoms[way_id] = json.loads(gj)['coordinates']
    roads = []
    for name, n in top:
        lines = [geoms[w] for w in name_ways[name] if w in geoms]
        if lines:
            roads.append({'name': name, 'n': n, 'value': round(100.0 * n / matched, 1), 'lines': lines})
    return {'roads': roads, 'sampled': len(pts), 'matched': matched, 'thinned': k > 1}


_REGION_METRICS = {'place_visits': _place_visits, 'days': _days, 'town_coverage': _town_coverage}


# ── Public API ─────────────────────────────────────────────────────────────

def _cache_key(user, card, tz_offset):
    blob = json.dumps([card, str(tz_offset)], sort_keys=True)
    return f"ask_datamap:{user.pk}:{hashlib.sha1(blob.encode()).hexdigest()}"


def geometry(user, card, tz_offset=0):
    """What the page draws for a data card. Read-only; cached a few minutes
    because the tool call and the card opening both ask for the same numbers."""
    key = _cache_key(user, card, tz_offset)
    hit = cache.get(key)
    if hit is not None:
        return hit
    out = _compute(user, card, tz_offset)
    if 'error' not in out:
        cache.set(key, out, _CACHE_S)
    return out


def _compute(user, card, tz_offset):
    metric = card['metric']
    if metric == 'top_roads':
        res = _top_roads(user, card, tz_offset)
        if 'error' in res:
            return res
        lons, lats = [], []
        for r in res['roads']:
            for line in r['lines']:
                for lon, lat in line:
                    lons.append(lon)
                    lats.append(lat)
        return {'mode': 'roads', **res, 'unit': METRICS[metric],
                'bounds': [min(lons), min(lats), max(lons), max(lats)] if lons else None}
    rows = _REGION_METRICS[metric](user, card, tz_offset)
    rows = [r for r in rows if r.get('value') is not None or metric == 'town_coverage']
    if not rows:
        hint = {'place_visits': "No visits to a place with that name. (Visits are built from stays, so "
                                "they may need computing, and the name has to match the place's name.)",
                }.get(metric, 'Nothing recorded to show for that.')
        return {'error': hint}
    rows.sort(key=lambda r: (r['value'] is None, -(r['value'] or 0), r['name']))
    rows = rows[:_MAX_REGIONS]
    return {'mode': 'choropleth', 'level': card.get('level', 'state'), 'metric': metric,
            'unit': METRICS[metric], 'regions': rows,
            'max': max((r['value'] or 0) for r in rows)}


def summary(user, card, tz_offset=0):
    """What the model is told: the ranking, never geometry."""
    geo = geometry(user, card, tz_offset)
    if 'error' in geo:
        return geo
    if geo['mode'] == 'roads':
        return {'roads': [{'road': r['name'], 'share_pct': r['value']} for r in geo['roads']],
                'note': 'Share of sampled driving fixes, a relative ranking, not miles.'}
    out = {'unit': geo['unit'], 'regions': [
        {'name': r['name'], 'value': r['value'], 'detail': r['detail']} for r in geo['regions'][:25]]}
    if len(geo['regions']) > 25:
        out['more_regions'] = len(geo['regions']) - 25
    return out


def full_map_url(card):
    return '/stats/'
