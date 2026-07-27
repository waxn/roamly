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
# Ways in the search bbox before we give up. Lower than it looks because the
# graph is keyed on coordinates, so a way contributes a vertex per shape point,
# not one per junction — 25k ways is already ~500k transient dict entries.
_MAX_GRAPH_SEGMENTS = 25000
_ROUTE_MAX_KM = 150.0         # longer than this is not a plausible road gap
_BBOX_PAD_FRAC = 0.60         # generous: a detour outside the box is unroutable
_BBOX_MIN_PAD_DEG = 0.05      # ~5km, so short gaps still see the surrounding grid
# If the nearest known road to an anchor is further than this, that anchor isn't
# on the downloaded network and routing from it would invent a leg.
_ROUTE_MAX_ANCHOR_M = 1000.0
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


def _vkey(lng, lat):
    """Vertex identity for the routing graph: the coordinate itself.

    Two OSM ways connect by *sharing a node*, which means they carry the byte
    identical coordinate at that position. So rounding to ~1cm and keying on the
    pair is equivalent to keying on the node id — without depending on node ids
    lining up with the geometry, which is exactly what used to break the graph:
    Overpass returns incomplete geometry for ways clipped at a query-box edge,
    the importer then stored no node ids for them, and every such way was
    silently dropped from the graph. Enough of them are dropped that routes stop
    existing and every gap degrades to a straight line.
    """
    return (round(lng, 7), round(lat, 7))


def _load_graph(a, b, mode=''):
    """Build a routing graph from the RoadSegment rows around the two anchors.

    Ways are split at coordinates shared with another way — an intersection —
    giving edges between junctions. Splitting rather than using every vertex
    keeps the node count (and so the A* frontier) roughly an order of magnitude
    smaller.
    """
    from django.contrib.gis.geos import Polygon
    from .models import RoadSegment

    min_lng, max_lng = min(a[0], b[0]), max(a[0], b[0])
    min_lat, max_lat = min(a[1], b[1]), max(a[1], b[1])
    # Pad generously: the road actually taken can detour well outside the box
    # the two anchors span, and a route that leaves the box cannot be found at
    # all. Cheap insurance — the bbox filter is index-backed.
    span = max(max_lng - min_lng, max_lat - min_lat)
    pad = max(span * _BBOX_PAD_FRAC, _BBOX_MIN_PAD_DEG)
    bbox = Polygon.from_bbox((min_lng - pad, min_lat - pad,
                             max_lng + pad, max_lat + pad))

    segs = list(
        RoadSegment.objects.filter(geom__bboverlaps=bbox)
        .values_list('highway', 'oneway', 'geom')[:_MAX_GRAPH_SEGMENTS + 1]
    )
    if len(segs) > _MAX_GRAPH_SEGMENTS:
        logger.info('Local route: %d ways in bbox, above cap — bailing', len(segs))
        return None, None
    if not segs:
        logger.info('Local route: no road data around (%.4f,%.4f)-(%.4f,%.4f)',
                    a[0], a[1], b[0], b[1])
        return None, None

    # A coordinate touched by two or more ways is a junction; way endpoints
    # always are, so every way contributes at least one edge.
    coords_per_seg = []
    seen, junctions = set(), set()
    for highway, oneway, geom in segs:
        coords = list(geom.coords)
        if len(coords) < 2:
            coords_per_seg.append(None)
            continue
        coords_per_seg.append(coords)
        for c in coords:
            k = _vkey(c[0], c[1])
            if k in seen:
                junctions.add(k)
            else:
                seen.add(k)
        junctions.add(_vkey(coords[0][0], coords[0][1]))
        junctions.add(_vkey(coords[-1][0], coords[-1][1]))
    del seen

    adj = {}
    node_pos = {}
    # One-way restrictions are a driving concept; on foot both directions are
    # walkable, so honouring them would refuse perfectly ordinary routes.
    honour_oneway = mode not in ('walk', 'cycle')
    for (highway, oneway, _geom), coords in zip(segs, coords_per_seg):
        if not coords:
            continue
        weight_mult = _HIGHWAY_WEIGHT.get(highway, _DEFAULT_HIGHWAY_WEIGHT)
        cut = [i for i, c in enumerate(coords) if _vkey(c[0], c[1]) in junctions]
        for k in range(len(cut) - 1):
            i, j = cut[k], cut[k + 1]
            k_from = _vkey(coords[i][0], coords[i][1])
            k_to = _vkey(coords[j][0], coords[j][1])
            if k_from == k_to:
                continue
            sub = coords[i:j + 1]
            cost = _polyline_length_m(sub) * weight_mult
            node_pos[k_from] = coords[i]
            node_pos[k_to] = coords[j]
            adj.setdefault(k_from, []).append((k_to, cost, sub))
            if not (oneway and honour_oneway):
                adj.setdefault(k_to, []).append((k_from, cost, sub[::-1]))
    return adj, node_pos


def _project_on_segment(pt, s1, s2):
    """Closest point to `pt` on segment s1→s2, as (point, distance_m).

    Planar approximation with longitude scaled by cos(lat) — over a single road
    segment the error is far below the metre.
    """
    scale = math.cos(math.radians(pt[1])) or 1e-9
    px, py = pt[0] * scale, pt[1]
    ax, ay = s1[0] * scale, s1[1]
    bx, by = s2[0] * scale, s2[1]
    dx, dy = bx - ax, by - ay
    den = dx * dx + dy * dy
    t = 0.0 if den <= 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / den))
    qx, qy = ax + dx * t, ay + dy * t
    q = (qx / scale, qy)
    return q, _haversine_m(pt[0], pt[1], q[0], q[1])


def _nearest_edge(adj, pt, tol_deg=0.03):
    """Nearest graph edge to `pt`: (distance_m, u, v, seg_index, projection, sub).

    Edges whose segment bounding box doesn't come within `tol_deg` of the point
    are rejected with four comparisons, before any trigonometry — that filter is
    what keeps this affordable over tens of thousands of edges.
    """
    best = None
    for u, edges in adj.items():
        for (v, _cost, sub) in edges:
            for i in range(len(sub) - 1):
                s1, s2 = sub[i], sub[i + 1]
                if (pt[0] < min(s1[0], s2[0]) - tol_deg or pt[0] > max(s1[0], s2[0]) + tol_deg
                        or pt[1] < min(s1[1], s2[1]) - tol_deg or pt[1] > max(s1[1], s2[1]) + tol_deg):
                    continue
                q, d = _project_on_segment(pt, s1, s2)
                if best is None or d < best[0]:
                    best = (d, u, v, i, q, sub)
    return best


def _splice_anchor(adj, node_pos, pt, label):
    """Add `pt`'s projection onto the road network as a temporary graph vertex.

    Without this the route could only start at a *junction*, so it opened and
    closed with a straight off-road leg from the GPS anchor to that junction —
    which is what produced long runs of evenly-spaced points cutting across
    open ground. Splicing into the nearest edge means the path starts and ends
    exactly on tarmac and never leaves it.

    Returns (vertex_key, distance_m, sub, seg_index) or (None, distance, …).
    """
    found = _nearest_edge(adj, pt)
    if not found:
        return None, None, None, None
    d, u, v, i, proj, sub = found
    if d > _ROUTE_MAX_ANCHOR_M:
        return None, d, None, None

    key = ('anchor', label)
    node_pos[key] = proj
    to_v = [proj] + list(sub[i + 1:])
    to_u = [proj] + list(reversed(sub[:i + 1]))
    cost_v = _polyline_length_m(to_v)
    cost_u = _polyline_length_m(to_u)
    # Both directions: one-way rules don't stop you joining or leaving the road
    # you're already stopped on.
    adj.setdefault(key, []).append((v, cost_v, to_v))
    adj.setdefault(key, []).append((u, cost_u, to_u))
    adj.setdefault(u, []).append((key, cost_u, to_u[::-1]))
    adj.setdefault(v, []).append((key, cost_v, to_v[::-1]))
    return key, d, sub, i


def _local_route(a, b, mode=''):
    adj, node_pos = _load_graph(a, b, mode)
    if not adj:
        return None

    start, start_d, sub_a, i_a = _splice_anchor(adj, node_pos, a, 'a')
    goal, goal_d, sub_b, i_b = _splice_anchor(adj, node_pos, b, 'b')
    if start is None or goal is None or start == goal:
        # Nowhere near a known road at one end — usually the road download hasn't
        # covered this area. Report no route; the caller records the gap
        # unfilled rather than inventing a line across country.
        logger.info('Local route: anchors %s / %s m from nearest road',
                    f'{start_d:.0f}' if start_d else '?',
                    f'{goal_d:.0f}' if goal_d else '?')
        return None

    # Both anchors on the same stretch of road: connect them along it directly,
    # otherwise A* has to detour out to a junction and back.
    if sub_a is sub_b:
        lo, hi = (i_a, i_b) if i_a <= i_b else (i_b, i_a)
        mid = list(sub_a[lo + 1:hi + 1])
        seg = [node_pos[start]] + (mid if i_a <= i_b else list(reversed(mid))) + [node_pos[goal]]
        cost = _polyline_length_m(seg)
        adj.setdefault(start, []).append((goal, cost, seg))
        adj.setdefault(goal, []).append((start, cost, seg[::-1]))

    goal_pos = node_pos[goal]

    def h(n):
        c = node_pos[n]
        return _haversine_m(c[0], c[1], goal_pos[0], goal_pos[1])

    # A* — the haversine heuristic is admissible for the unweighted case and
    # near enough with the class multipliers to keep the search tight. The
    # counter is a tiebreaker so the heap never has to compare vertex keys, which
    # are a mix of coordinate tuples and ('anchor', label) and aren't orderable.
    counter = 0
    open_heap = [(h(start), 0.0, counter, start)]
    came = {start: (None, None)}
    best_g = {start: 0.0}
    while open_heap:
        _f, g, _c, node = heapq.heappop(open_heap)
        if node == goal:
            break
        if g > best_g.get(node, float('inf')):
            continue
        for nxt, cost, sub in adj.get(node, ()):
            ng = g + cost
            if ng < best_g.get(nxt, float('inf')):
                best_g[nxt] = ng
                came[nxt] = (node, sub)
                counter += 1
                heapq.heappush(open_heap, (ng + h(nxt), ng, counter, nxt))

    if goal not in came:
        return None

    parts, node = [], goal
    while came[node][0] is not None:
        prev, sub = came[node]
        parts.append(sub)
        node = prev
    parts.reverse()

    # No raw anchors: the path is road geometry from end to end.
    path = []
    for sub in parts:
        for c in sub:
            if not path or (c[0], c[1]) != (path[-1][0], path[-1][1]):
                path.append((c[0], c[1]))
    return path if len(path) >= 2 else None


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
