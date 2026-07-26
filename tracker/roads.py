"""Road snapping and route reconstruction, with three interchangeable providers.

Two public entry points, both taking a UserProfile so provider choice is
per-user:

    snap_points(profile, pts)       -> {location_id: (lng, lat)}
    route_between(profile, a, b)    -> [(lng, lat), ...] along real roads

Snapping is used for *display only* — the caller never writes the result back to
Location. Routing is used by the gap filler, which does persist its output, but
into InferredLocation rather than Location.

Providers, in the order "auto" resolves them (see
UserProfile.road_provider_resolved):

  local   Roads downloaded into the RoadSegment table by road_download_tasks.
          No external calls at query time at all, which is the same reasoning
          that moved reverse geocoding offline (offline_geocode.py): a tracker
          that runs continuously cannot lean on a public API. Snapping is a
          PostGIS KNN probe plus a Viterbi smoothing pass; routing is A* over a
          graph rebuilt from the ways' shared node ids. PostGIS-only.
  mapbox  Map Matching + Directions, using the token the user already has for
          basemaps. Capped at 100 coordinates per matching request.
  osrm    A configurable OSRM instance (self-hosted, or the public demo).
          /match/v1 and /route/v1.

Every function degrades rather than raising at the caller: a failed snap returns
the input unchanged, and a failed route lets the gap filler fall back to
straight-line interpolation. RoadProviderError carries a message fit to show a
user, in the style of ai_tasks.AIProviderError.
"""

import heapq
import logging
import math

import requests
from django.core.cache import cache

logger = logging.getLogger(__name__)

_REQUEST_TIMEOUT = 20

# --- Snapping ---------------------------------------------------------------
# Candidates per point. More than a handful is wasted work: the right road is
# essentially always in the nearest few.
_SNAP_CANDIDATES = 4
# Never move a point further than this. A fix that is genuinely 80m from any
# road (a car park, a field, a private drive) must stay where it was recorded —
# snapping is meant to remove GPS scatter, not to relocate real positions.
_SNAP_MIN_MAX_M = 30.0
_SNAP_ACC_MULT = 2.0
_SNAP_HARD_MAX_M = 120.0
# Cost of moving to a different way between consecutive points. Nearest-road
# snapping alone ping-pongs between a highway and its frontage road; this makes
# the smoother prefer staying on one road unless the evidence is strong.
_SNAP_SWITCH_PENALTY_M = 40.0
# How much to punish distorting the distance between consecutive points.
_SNAP_STRETCH_WEIGHT = 0.5
_SNAP_CHUNK = 400          # points per SQL round-trip
_SNAP_CACHE_TTL = 604800   # 7 days — a snapped fix never changes

# --- Routing ----------------------------------------------------------------
_MAX_GRAPH_SEGMENTS = 60000   # ways in the search bbox before we give up
_ROUTE_MAX_KM = 150.0         # longer than this is not a plausible road gap
_BBOX_PAD_FRAC = 0.30
_BBOX_MIN_PAD_DEG = 0.02
# Prefer the fast road when several routes are similar in length. Weights
# multiply true metres, so 0.6 means "a motorway kilometre costs 600m".
_HIGHWAY_WEIGHT = {
    'motorway': 0.6, 'trunk': 0.7, 'primary': 0.8,
    'secondary': 0.9, 'tertiary': 1.0,
}
_DEFAULT_HIGHWAY_WEIGHT = 1.15


class RoadProviderError(Exception):
    """A road provider failed in a way worth showing the user."""


# ---------------------------------------------------------------------------
# Geometry helpers
# ---------------------------------------------------------------------------

def _haversine_m(lng1, lat1, lng2, lat2):
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def _polyline_length_m(coords):
    return sum(
        _haversine_m(coords[i][0], coords[i][1], coords[i + 1][0], coords[i + 1][1])
        for i in range(len(coords) - 1)
    )


def _snap_limit_m(accuracy):
    """How far this particular fix may be moved, from its reported accuracy."""
    if accuracy is None:
        return _SNAP_MIN_MAX_M
    return min(_SNAP_HARD_MAX_M, max(_SNAP_MIN_MAX_M, float(accuracy) * _SNAP_ACC_MULT))


def _profile_for(provider, mode):
    """Map a Location.transport_mode to the provider's routing profile."""
    if provider == 'mapbox':
        return {'walk': 'walking', 'cycle': 'cycling'}.get(mode, 'driving')
    # OSRM's stock profile names.
    return {'walk': 'foot', 'cycle': 'bike'}.get(mode, 'driving')


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def snap_points(profile, pts):
    """Snap points to the road network. Display only — nothing is persisted.

    `pts` is a list of {'id', 'lat', 'lng', 'accuracy'} in **timestamp order**;
    the local smoother relies on that order to reason about continuity. Returns
    {id: (lng, lat)} containing only the points that actually moved, so a caller
    can leave the rest untouched.

    Results are cached per point id — a recorded fix and the road beside it are
    both immutable, so this is the one place a snapped coordinate lives. Panning
    the map therefore re-snaps almost nothing.
    """
    provider = profile.road_provider_resolved
    if not provider or not pts:
        return {}

    out = {}
    todo = []
    for p in pts:
        key = f"snap:{provider}:{p['id']}"
        hit = cache.get(key)
        if hit is None:
            todo.append(p)
        elif hit:                      # falsy sentinel = known-unsnappable
            out[p['id']] = (hit[0], hit[1])

    if todo:
        # Only the cache misses are re-snapped, so `todo` can be a sparse slice of
        # the sequence and the smoother has less continuity to work with. That's
        # fine: the cached points were computed with full context at the time, and
        # a sparse run still yields the right road far more often than nearest-only.
        try:
            if provider == 'local':
                fresh = _local_snap(todo)
            elif provider == 'mapbox':
                fresh = _mapbox_snap(profile, todo)
            else:
                fresh = _osrm_snap(profile, todo)
        except RoadProviderError:
            raise
        except Exception:
            logger.exception('Road snap failed (provider=%s)', provider)
            return out

        for p in todo:
            coord = fresh.get(p['id'])
            # Cache the misses too, as a falsy sentinel: a point with no road
            # within tolerance will still have none next time.
            cache.set(f"snap:{provider}:{p['id']}", list(coord) if coord else 0,
                      _SNAP_CACHE_TTL)
            if coord:
                out[p['id']] = coord

    return out


def route_between(profile, a, b, mode=''):
    """Road route between two (lng, lat) anchors.

    Returns the full path including both anchors, or None when no road route
    could be found — the caller then decides whether to interpolate a straight
    line instead. Raises RoadProviderError only for configuration/service
    problems worth surfacing, never for "no route exists".
    """
    provider = profile.road_provider_resolved
    if not provider:
        raise RoadProviderError('No road data provider is configured.')

    if _haversine_m(a[0], a[1], b[0], b[1]) / 1000.0 > _ROUTE_MAX_KM:
        return None

    if provider == 'local':
        return _local_route(a, b, mode)
    if provider == 'mapbox':
        return _mapbox_route(profile, a, b, mode)
    return _osrm_route(profile, a, b, mode)


# ---------------------------------------------------------------------------
# Local provider — PostGIS RoadSegment
# ---------------------------------------------------------------------------

def _local_candidates(pts):
    """Nearest few ways per point, in one query per chunk rather than per point.

    A LATERAL join lets the KNN `<->` operator run against the GiST index once
    per point inside a single statement, which is the difference between one
    round-trip and several hundred.
    """
    from django.db import connection

    rows_by_pt = {p['id']: [] for p in pts}
    with connection.cursor() as cur:
        for i in range(0, len(pts), _SNAP_CHUNK):
            chunk = pts[i:i + _SNAP_CHUNK]
            values = ','.join(
                ['(%s::bigint, %s::double precision, %s::double precision)'] * len(chunk)
            )
            params = []
            for p in chunk:
                params.extend([p['id'], p['lng'], p['lat']])
            params.append(_SNAP_CANDIDATES)
            cur.execute(f"""
                SELECT p.pid, c.way_id, ST_X(c.g), ST_Y(c.g),
                       ST_Distance(
                           c.g::geography,
                           ST_SetSRID(ST_MakePoint(p.lng, p.lat), 4326)::geography
                       )
                FROM (VALUES {values}) AS p(pid, lng, lat)
                CROSS JOIN LATERAL (
                    SELECT r.way_id,
                           ST_ClosestPoint(
                               r.geom, ST_SetSRID(ST_MakePoint(p.lng, p.lat), 4326)
                           ) AS g
                    FROM tracker_roadsegment r
                    ORDER BY r.geom <-> ST_SetSRID(ST_MakePoint(p.lng, p.lat), 4326)
                    LIMIT %s
                ) c
            """, params)
            for pid, way_id, lng, lat, dist_m in cur.fetchall():
                rows_by_pt[pid].append((way_id, lng, lat, dist_m))
    return rows_by_pt


def _local_snap(pts):
    """Nearest-road candidates, then a Viterbi pass for continuity.

    Snapping each point to its own nearest road independently is what produces
    the classic artefact of a track hopping between a motorway and the service
    road beside it. Treating the sequence as a hidden-state problem — which road
    was I on — and paying a penalty to change road removes almost all of it, for
    about the cost of one extra pass over the candidates.
    """
    cands = _local_candidates(pts)
    out = {}

    # Viterbi over runs of consecutive points that have candidates at all.
    prev_costs = None     # [(cost, cand_index)] aligned with prev_cands
    prev_cands = None
    prev_raw = None
    back = []             # per step: (cands, [(cost, prev_index), ...])
    run = []              # point ids in the current run

    def _emit_run():
        """Walk the best path back through the run and record its coordinates."""
        if not run or not prev_costs:
            return
        best_i = min(range(len(prev_costs)), key=lambda i: prev_costs[i])
        for step in range(len(run) - 1, -1, -1):
            step_cands, step_back = back[step]
            way_id, lng, lat, _d = step_cands[best_i]
            out[run[step]] = (lng, lat)
            best_i = step_back[best_i][1]
            if best_i < 0:
                break

    for p in pts:
        limit = _snap_limit_m(p.get('accuracy'))
        usable = [c for c in cands.get(p['id'], []) if c[3] <= limit]
        if not usable:
            # No road within tolerance: close the run, leave this point alone.
            _emit_run()
            prev_costs = prev_cands = prev_raw = None
            back = []
            run = []
            continue

        if prev_costs is None:
            costs = [c[3] for c in usable]
            back = [(usable, [(costs[i], -1) for i in range(len(usable))])]
            run = [p['id']]
        else:
            raw_d = _haversine_m(prev_raw[0], prev_raw[1], p['lng'], p['lat'])
            costs, links = [], []
            for j, cand in enumerate(usable):
                best_cost, best_prev = None, -1
                for i, prev_cand in enumerate(prev_cands):
                    step = prev_costs[i]
                    if prev_cand[0] != cand[0]:
                        step += _SNAP_SWITCH_PENALTY_M
                    snapped_d = _haversine_m(prev_cand[1], prev_cand[2], cand[1], cand[2])
                    step += abs(snapped_d - raw_d) * _SNAP_STRETCH_WEIGHT
                    if best_cost is None or step < best_cost:
                        best_cost, best_prev = step, i
                costs.append(best_cost + cand[3])
                links.append((best_cost + cand[3], best_prev))
            back.append((usable, links))
            run.append(p['id'])

        prev_costs = costs
        prev_cands = usable
        prev_raw = (p['lng'], p['lat'])

    _emit_run()
    return out


def _load_graph(a, b, mode=''):
    """Build a routing graph from the RoadSegment rows around the two anchors.

    Ways are split at nodes they share with another way — that shared node id is
    exactly what an intersection is — giving edges between junctions. This is why
    RoadSegment stores node_ids: without them the rows are drawable but not
    routable, and reconstructing intersections geometrically would mean an
    ST_Intersects storm over the biggest table in the database.
    """
    from django.contrib.gis.geos import Polygon
    from .models import RoadSegment

    min_lng, max_lng = min(a[0], b[0]), max(a[0], b[0])
    min_lat, max_lat = min(a[1], b[1]), max(a[1], b[1])
    pad_lng = max((max_lng - min_lng) * _BBOX_PAD_FRAC, _BBOX_MIN_PAD_DEG)
    pad_lat = max((max_lat - min_lat) * _BBOX_PAD_FRAC, _BBOX_MIN_PAD_DEG)
    bbox = Polygon.from_bbox((min_lng - pad_lng, min_lat - pad_lat,
                             max_lng + pad_lng, max_lat + pad_lat))

    segs = list(
        RoadSegment.objects.filter(geom__bboverlaps=bbox)
        .values_list('way_id', 'node_ids', 'highway', 'oneway', 'geom')[:_MAX_GRAPH_SEGMENTS + 1]
    )
    if len(segs) > _MAX_GRAPH_SEGMENTS:
        logger.info('Local route: %d ways in bbox, above cap — bailing', len(segs))
        return None, None

    # A node shared by two or more ways is a junction. Way endpoints always are.
    seen, junctions = set(), set()
    for _wid, node_ids, _hw, _ow, _geom in segs:
        for n in (node_ids or ()):
            if n in seen:
                junctions.add(n)
            else:
                seen.add(n)
        if node_ids:
            junctions.add(node_ids[0])
            junctions.add(node_ids[-1])

    adj = {}
    node_pos = {}
    # One-way restrictions are a driving concept; on foot both directions are
    # walkable, so honouring them would refuse perfectly ordinary routes.
    honour_oneway = mode not in ('walk', 'cycle')
    for _wid, node_ids, highway, oneway, geom in segs:
        coords = list(geom.coords)
        if not node_ids or len(node_ids) != len(coords) or len(coords) < 2:
            # Overpass gives node ids and geometry in lockstep; if a row doesn't
            # line up it's unusable for routing (still fine for snapping).
            continue
        weight_mult = _HIGHWAY_WEIGHT.get(highway, _DEFAULT_HIGHWAY_WEIGHT)
        cut = [i for i, n in enumerate(node_ids) if n in junctions]
        for k in range(len(cut) - 1):
            i, j = cut[k], cut[k + 1]
            n_from, n_to = node_ids[i], node_ids[j]
            if n_from == n_to:
                continue
            sub = coords[i:j + 1]
            cost = _polyline_length_m(sub) * weight_mult
            node_pos[n_from] = coords[i]
            node_pos[n_to] = coords[j]
            adj.setdefault(n_from, []).append((n_to, cost, sub))
            if not (oneway and honour_oneway):
                adj.setdefault(n_to, []).append((n_from, cost, sub[::-1]))
    return adj, node_pos


def _nearest_node(node_pos, pt):
    best, best_d = None, None
    for n, c in node_pos.items():
        d = _haversine_m(pt[0], pt[1], c[0], c[1])
        if best_d is None or d < best_d:
            best, best_d = n, d
    return best, best_d


def _local_route(a, b, mode=''):
    adj, node_pos = _load_graph(a, b, mode)
    if not adj:
        return None

    start, _ = _nearest_node(node_pos, a)
    goal, _ = _nearest_node(node_pos, b)
    if start is None or goal is None or start == goal:
        return None

    goal_pos = node_pos[goal]

    def h(n):
        c = node_pos[n]
        return _haversine_m(c[0], c[1], goal_pos[0], goal_pos[1])

    # A* — the haversine heuristic is admissible for the unweighted case and
    # near enough with the class multipliers to keep the search tight.
    open_heap = [(h(start), 0.0, start)]
    came = {start: (None, None)}
    best_g = {start: 0.0}
    while open_heap:
        _f, g, node = heapq.heappop(open_heap)
        if node == goal:
            break
        if g > best_g.get(node, float('inf')):
            continue
        for nxt, cost, sub in adj.get(node, ()):
            ng = g + cost
            if ng < best_g.get(nxt, float('inf')):
                best_g[nxt] = ng
                came[nxt] = (node, sub)
                heapq.heappush(open_heap, (ng + h(nxt), ng, nxt))

    if goal not in came:
        return None

    parts, node = [], goal
    while came[node][0] is not None:
        prev, sub = came[node]
        parts.append(sub)
        node = prev
    parts.reverse()

    path = [a]
    for sub in parts:
        for c in sub:
            if not path or (c[0], c[1]) != (path[-1][0], path[-1][1]):
                path.append((c[0], c[1]))
    path.append(b)
    return path if len(path) > 2 else None


# ---------------------------------------------------------------------------
# Mapbox provider
# ---------------------------------------------------------------------------

_MAPBOX_MATCH_MAX = 100   # hard API limit on coordinates per matching request


def _mapbox_snap(profile, pts):
    out = {}
    for i in range(0, len(pts), _MAPBOX_MATCH_MAX):
        chunk = pts[i:i + _MAPBOX_MATCH_MAX]
        coords = ';'.join(f"{p['lng']},{p['lat']}" for p in chunk)
        radii = ';'.join(str(int(_snap_limit_m(p.get('accuracy')))) for p in chunk)
        url = f'https://api.mapbox.com/matching/v5/mapbox/driving/{coords}'
        data = _get_json(url, {
            'access_token': profile.mapbox_token,
            'geometries': 'geojson',
            'radiuses': radii,
            'tidy': 'true',
        })
        for p, tp in zip(chunk, data.get('tracepoints') or []):
            if tp and tp.get('location'):
                lng, lat = tp['location'][0], tp['location'][1]
                if _haversine_m(p['lng'], p['lat'], lng, lat) <= _snap_limit_m(p.get('accuracy')):
                    out[p['id']] = (lng, lat)
    return out


def _mapbox_route(profile, a, b, mode=''):
    prof = _profile_for('mapbox', mode)
    url = (f'https://api.mapbox.com/directions/v5/mapbox/{prof}/'
           f'{a[0]},{a[1]};{b[0]},{b[1]}')
    data = _get_json(url, {
        'access_token': profile.mapbox_token,
        'geometries': 'geojson',
        'overview': 'full',
    })
    routes = data.get('routes') or []
    if not routes:
        return None
    coords = routes[0].get('geometry', {}).get('coordinates') or []
    return [(c[0], c[1]) for c in coords] or None


# ---------------------------------------------------------------------------
# OSRM provider
# ---------------------------------------------------------------------------

def _osrm_base(profile):
    url = (profile.osrm_url or '').strip().rstrip('/')
    if not url:
        raise RoadProviderError('No OSRM URL is configured.')
    return url


def _osrm_snap(profile, pts):
    base = _osrm_base(profile)
    prof = _profile_for('osrm', '')
    out = {}
    # OSRM has no documented coordinate cap, but keep requests modest so a
    # single failure doesn't cost the whole viewport.
    for i in range(0, len(pts), _MAPBOX_MATCH_MAX):
        chunk = pts[i:i + _MAPBOX_MATCH_MAX]
        coords = ';'.join(f"{p['lng']},{p['lat']}" for p in chunk)
        radii = ';'.join(str(int(_snap_limit_m(p.get('accuracy')))) for p in chunk)
        data = _get_json(f'{base}/match/v1/{prof}/{coords}', {
            'geometries': 'geojson',
            'overview': 'false',
            'radiuses': radii,
            'tidy': 'true',
        })
        if data.get('code') not in (None, 'Ok'):
            continue           # NoMatch on a stretch is normal, not an error
        for p, tp in zip(chunk, data.get('tracepoints') or []):
            if tp and tp.get('location'):
                lng, lat = tp['location'][0], tp['location'][1]
                if _haversine_m(p['lng'], p['lat'], lng, lat) <= _snap_limit_m(p.get('accuracy')):
                    out[p['id']] = (lng, lat)
    return out


def _osrm_route(profile, a, b, mode=''):
    base = _osrm_base(profile)
    prof = _profile_for('osrm', mode)
    path = f'{base}/route/v1/{prof}/{a[0]},{a[1]};{b[0]},{b[1]}'
    try:
        data = _get_json(path, {'geometries': 'geojson', 'overview': 'full'})
    except RoadProviderError:
        if prof == 'driving':
            raise
        # The public demo server only serves `driving`; a non-driving profile
        # 400s there. Retry rather than lose the gap.
        data = _get_json(f'{base}/route/v1/driving/{a[0]},{a[1]};{b[0]},{b[1]}',
                         {'geometries': 'geojson', 'overview': 'full'})
    if data.get('code') not in (None, 'Ok'):
        return None
    routes = data.get('routes') or []
    if not routes:
        return None
    coords = routes[0].get('geometry', {}).get('coordinates') or []
    return [(c[0], c[1]) for c in coords] or None


# ---------------------------------------------------------------------------
# Shared HTTP
# ---------------------------------------------------------------------------

def _get_json(url, params):
    """GET returning parsed JSON, with errors translated for the user.

    Mirrors ai_tasks._provider_chat's error handling: the message ends up in a
    Settings status line or an audit row, so it has to read as English rather
    than as a stack trace.
    """
    try:
        resp = requests.get(url, params=params, timeout=_REQUEST_TIMEOUT)
    except requests.exceptions.Timeout:
        raise RoadProviderError('The road service timed out.')
    except requests.exceptions.RequestException:
        raise RoadProviderError('The road service is unreachable. Check the URL.')

    if resp.status_code in (401, 403):
        raise RoadProviderError('The road service rejected the credentials '
                                '(check your Mapbox token or URL restrictions).')
    if resp.status_code == 429:
        raise RoadProviderError('The road service is rate-limiting requests. Try again later.')
    if resp.status_code >= 400:
        raise RoadProviderError(f'The road service returned HTTP {resp.status_code}.')

    try:
        return resp.json()
    except ValueError:
        raise RoadProviderError('The road service returned an unreadable response.')
