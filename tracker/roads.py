"""Road snapping and route reconstruction, with four interchangeable providers.

Two public entry points, both taking a UserProfile so provider choice is
per-user:

    snap_points(profile, pts)       -> {location_id: (lng, lat)}
    route_between(profile, a, b)    -> [(lng, lat), ...] along real roads

Snapping is used for *display only* — the caller never writes the result back to
Location. Routing is used by the gap filler, which does persist its output, but
into InferredLocation rather than Location.

Providers, in the order "auto" resolves them (see
UserProfile.road_provider_resolved):

  local     Roads downloaded into the RoadSegment table by road_download_tasks.
            No external calls at query time at all, which is the same reasoning
            that moved reverse geocoding offline (offline_geocode.py): a tracker
            that runs continuously cannot lean on a public API. Snapping is a
            PostGIS KNN probe plus a Viterbi smoothing pass; routing is A* over a
            graph rebuilt from the ways' shared node ids. PostGIS-only.
  mapbox    Map Matching + Directions, using the token the user already has for
            basemaps. Capped at 100 coordinates per matching request.
  osrm      A configurable OSRM instance (self-hosted, or the public demo).
            /match/v1 and /route/v1.
  valhalla  A configurable Valhalla instance (self-hosted; there's no
            equivalent of OSRM's public demo server). Meili — Valhalla's
            map-matching engine — via /trace_attributes (shape_match:
            map_snap), and /route for point-to-point gap routing. Deliberately
            last in "auto": it's opt-in infrastructure a self-hoster stands up
            and tile-builds on purpose, not something that's "just there" the
            way local roads or an already-set Mapbox token are, so reaching it
            via an explicit choice is the expected path.

Every function degrades rather than raising at the caller: a failed snap returns
the input unchanged, and a failed route lets the gap filler fall back to
straight-line interpolation. RoadProviderError carries a message fit to show a
user, in the style of ai_tasks.AIProviderError.
"""

import heapq
import json
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
# Never move a point further than this. A fix that is genuinely off-road (a car
# park, a field, a private drive, a garden) must stay where it was recorded —
# snapping removes GPS scatter, it does not relocate real positions. Kept tight
# because the movement gate below already excludes the cases that need slack.
_SNAP_MIN_MAX_M = 20.0
_SNAP_ACC_MULT = 1.5
_SNAP_HARD_MAX_M = 60.0
# Cost of moving to a different way between consecutive points. Nearest-road
# snapping alone ping-pongs between a highway and its frontage road; this makes
# the smoother prefer staying on one road unless the evidence is strong.
_SNAP_SWITCH_PENALTY_M = 40.0
# How much to punish distorting the distance between consecutive points.
_SNAP_STRETCH_WEIGHT = 0.5
_SNAP_CHUNK = 400          # points per SQL round-trip
# 30 days. Snapped coordinates live only in the cache — never in the database, so
# they are never in a backup either. They are regenerable on demand, so a long
# TTL is free and Redis eviction is the only thing that needs to reclaim them.
_SNAP_CACHE_TTL = 2592000
# Bumped whenever the eligibility rules below change: entries cached under the
# old rules would otherwise keep returning decisions this version disagrees with.
_SNAP_CACHE_VERSION = 'v2'

# --- What may be snapped ----------------------------------------------------
# Only road journeys. Walking is never snapped: no pedestrian geometry is stored
# (road_download_tasks.HIGHWAY_RE excludes footway/path/track/cycleway), so the
# nearest candidate for a fix on a trail or in a garden is always a *road* — and
# dragging it there destroys exactly the granular detail that makes walking
# around a property worth recording.
_SNAP_MODES = ('vehicle', 'cycle')
_SNAP_NEVER_MODES = ('walk', 'still', 'plane')
# Fallback threshold when transport_mode is unset. ~18 km/h: comfortably clear of
# transport_tasks._WALK_MAX_MPS (2.2 m/s) so a brisk walk or jog never qualifies,
# low enough to admit cycling.
_SNAP_MIN_SPEED_MPS = 5.0
# ~162 km/h. Above virtually every real-world road speed limit (including
# realistic cruising on unrestricted autobahn sections), while ordinary and
# high-speed trains cruise well past it — a sustained speed above this is a
# strong sign of rail, not road, even for an explicitly 'vehicle'-classified
# stretch. See snappable()'s docstring for what this does and doesn't catch.
_SNAP_MAX_PLAUSIBLE_ROAD_MPS = 45.0
# Neighbours either side for the median. A *raw* per-point threshold is not
# usable: a phone sitting still reports wildly varying Doppler speeds (spikes
# over 30 m/s are common), so isolated readings say nothing about what the user
# was doing. The median over a short window is what makes the gate hold.
_SNAP_SPEED_WINDOW = 3
# A speed above this is a GPS teleport, not travel — same value transport_tasks
# uses. Such a reading is discarded rather than believed, so it can't drag the
# median up and let a stationary point qualify.
_MAX_SANE_MPS = 300.0

# --- Routing ----------------------------------------------------------------
# Ways in the search bbox before we give up. Lower than it looks because the
# graph is keyed on coordinates, so a way contributes a vertex per shape point,
# not one per junction — 25k ways is already ~500k transient dict entries.
_MAX_GRAPH_SEGMENTS = 25000
_ROUTE_MAX_KM = 150.0         # longer than this is not a plausible road gap
_BBOX_PAD_FRAC = 0.60         # generous: a detour outside the box is unroutable
_BBOX_MIN_PAD_DEG = 0.05      # ~5km, so short gaps still see the surrounding grid
# If the nearest known road to an anchor is further than this, that anchor isn't
# on the downloaded network and routing from it would invent a leg. Kept tight on
# purpose: a generous tolerance let both ends of a gap project onto the same
# nearby road at nearly the same spot, producing a tiny stub of a route that had
# nothing to do with the journey — a short floating line on the map.
_ROUTE_MAX_ANCHOR_M = 80.0
# Bucket size for the edge index. ~1.1km, comfortably larger than
# _ROUTE_MAX_ANCHOR_M, so an anchor's own cell plus its eight neighbours is
# guaranteed to contain every edge that could be within tolerance.
_EDGE_CELL_DEG = 0.01
# Only memoise graphs up to this many ways. A full _MAX_GRAPH_SEGMENTS graph is
# tens of MB of Python objects, and this runs in a background thread of a web
# worker — not somewhere to pin that much memory between gaps.
_GRAPH_CACHE_MAX_WAYS = 8000
# One entry, replaced wholesale. Holding several would multiply the memory above.
_graph_cache = {'bbox': None, 'data': None}
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


def _derived_speeds(pts):
    """Per-point speed in m/s: Doppler where usable, else derived from the track.

    Same policy as transport_tasks._point_speeds — a stored 0.0 is trusted (a
    stationary phone really does report zero), only None/negative falls back to
    distance over time, and an implausible value is discarded rather than
    believed. Imported history (CSV/GPX/Takeout) carries no Doppler at all, which
    is why the fallback has to exist.
    """
    out = []
    prev = None
    for p in pts:
        spd = p.get('speed')
        if spd is None or spd < 0 or spd > _MAX_SANE_MPS:
            spd = None
            ts, pts_ = p.get('timestamp'), prev
            if pts_ is not None and ts is not None and pts_.get('timestamp') is not None:
                dt = (ts - pts_['timestamp']).total_seconds()
                if 0 < dt <= 900:
                    d = _haversine_m(pts_['lng'], pts_['lat'], p['lng'], p['lat'])
                    derived = d / dt
                    if derived <= _MAX_SANE_MPS:
                        spd = derived
        prev = p
        out.append(spd)
    return out


def _median(vals):
    s = sorted(v for v in vals if v is not None)
    if not s:
        return None
    n = len(s)
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2.0


def snappable(pts):
    """Which points are road-journey points, and so may be snapped.

    `pts` must be in timestamp order. Returns a list of bools aligned with it.

    Prefers `transport_mode`, because it is decided per *journey* —
    transport_tasks._classify scores a whole run of movement on its 85th
    percentile speed — so a car waiting at a red light is still 'vehicle' and one
    fast GPS glitch never promotes a walk. That job is manual though, and every
    point recorded since the last run is unlabelled, so unlabelled points fall
    back to the median speed of their neighbours.

    `plane` is excluded outright via `_SNAP_NEVER_MODES`, but transport_tasks
    deliberately does **not** split car from train or ferry — "their speed
    profiles overlap too much to call reliably" (transport_tasks.py) — so both
    land on 'vehicle' and, without a further check here, would be just as
    snappable as an actual car. RoadSegment (and every external provider's
    driving profile) only knows about roads, so a train running near one gets
    dragged onto it: the trace reads as "you drove 250 km/h down this street."
    A general car/train speed split isn't reliable per that same overlap, but a
    **sustained** speed above `_SNAP_MAX_PLAUSIBLE_ROAD_MPS` is: it's above
    virtually every real-world road speed limit (including unrestricted
    autobahn sections' realistic cruising speed), while ordinary and high-speed
    trains cruise well above it — so it catches the single most visually wrong
    case (a fast train dragged onto a parallel road) without trying to catch a
    slower train or a ferry, which stay a known limit of the same overlap.
    Checked against the same median-neighbour window as the unlabelled
    fallback, not the single point, so one Doppler spike can't trip it.
    """
    speeds = _derived_speeds(pts)
    out = []
    for i, p in enumerate(pts):
        mode = (p.get('transport_mode') or '').strip()
        lo = max(0, i - _SNAP_SPEED_WINDOW)
        hi = min(len(speeds), i + _SNAP_SPEED_WINDOW + 1)
        med = _median(speeds[lo:hi])
        implausible = med is not None and med > _SNAP_MAX_PLAUSIBLE_ROAD_MPS
        if mode in _SNAP_NEVER_MODES:
            out.append(False)
        elif mode in _SNAP_MODES:
            out.append(not implausible)
        else:
            out.append(med is not None and _SNAP_MIN_SPEED_MPS <= med <= _SNAP_MAX_PLAUSIBLE_ROAD_MPS)
    return out


def _profile_for(provider, mode):
    """Map a Location.transport_mode to the provider's routing profile."""
    if provider == 'mapbox':
        return {'walk': 'walking', 'cycle': 'cycling'}.get(mode, 'driving')
    if provider == 'valhalla':
        # Valhalla's costing model names.
        return {'walk': 'pedestrian', 'cycle': 'bicycle'}.get(mode, 'auto')
    # OSRM's stock profile names.
    return {'walk': 'foot', 'cycle': 'bike'}.get(mode, 'driving')


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def snap_points(profile, pts):
    """Snap points to the road network. Display only — nothing is persisted.

    `pts` is a list of {'id', 'lat', 'lng', 'accuracy', 'speed',
    'transport_mode', 'timestamp'} in **timestamp order** — the smoother relies
    on that order for continuity, and the movement gate relies on it to look at
    neighbours. Returns {id: (lng, lat)} containing only the points that actually
    moved, so a caller can leave the rest untouched.

    Results are cached per point id — a recorded fix and the road beside it are
    both immutable, so this is the one place a snapped coordinate lives. Panning
    the map therefore re-snaps almost nothing.
    """
    provider = profile.road_provider_resolved
    if not provider or not pts:
        return {}

    # Decide eligibility before touching the cache or the road table: a walk is
    # rejected here for nothing, which is also what keeps the pre-paint snap wait
    # proportional to driving points only.
    eligible = snappable(pts)

    out = {}
    todo = []
    for p, ok in zip(pts, eligible):
        if not ok:
            # Deliberately not cached. The gate reads neighbours, so a point at
            # the edge of one viewport batch sees fewer of them than it will in
            # another; caching a rejection could pin a genuine driving point
            # unsnapped for the whole TTL. Re-deciding costs no query.
            continue
        key = f"snap:{_SNAP_CACHE_VERSION}:{provider}:{p['id']}"
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
            elif provider == 'valhalla':
                fresh = _valhalla_snap(profile, todo)
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
            cache.set(f"snap:{_SNAP_CACHE_VERSION}:{provider}:{p['id']}",
                      list(coord) if coord else 0, _SNAP_CACHE_TTL)
            if coord:
                out[p['id']] = coord

    return out


# How much to inflate an edge a previous alternative already used. High enough
# to push A* onto a different road, not so high that it takes an absurd detour.
_ALT_PENALTY = 4.0
# Two routes counted as "the same" if they share this much of their length.
_ALT_SIMILARITY = 0.75


def route_alternatives(profile, a, b, mode='', k=3, max_anchor_m=None):
    """Up to `k` distinct road routes between two points, best first.

    Returns [{'coords': [...], 'length_m': float}]. The caller picks; that is the
    whole point — for a lot of gaps there is more than one plausible way round
    and only the person who drove it knows which.
    """
    provider = profile.road_provider_resolved
    if not provider:
        raise RoadProviderError('No road data provider is configured.')
    if _haversine_m(a[0], a[1], b[0], b[1]) / 1000.0 > _ROUTE_MAX_KM:
        return []

    if provider == 'local':
        routes = _local_alternatives(a, b, mode, k, max_anchor_m)
    elif provider == 'mapbox':
        routes = _mapbox_routes(profile, a, b, mode, k)
    elif provider == 'valhalla':
        routes = _valhalla_routes(profile, a, b, mode, k)
    else:
        routes = _osrm_routes(profile, a, b, mode, k)

    out = []
    for coords in routes:
        if not coords or len(coords) < 2:
            continue
        out.append({'coords': coords, 'length_m': _polyline_length_m(coords)})
    return out


def _route_similarity(a, b):
    """Rough overlap of two polylines, by shared rounded vertices."""
    sa = {(round(x, 5), round(y, 5)) for x, y in a}
    sb = {(round(x, 5), round(y, 5)) for x, y in b}
    if not sa or not sb:
        return 0.0
    return len(sa & sb) / min(len(sa), len(sb))


def _local_alternatives(a, b, mode='', k=3, max_anchor_m=None):
    """Find distinct local routes by penalising the edges each one used.

    A* returns the single cheapest path, so asking it again yields the same
    answer. Inflating the cost of the edges already taken makes the next search
    prefer a different road — the standard penalty method, and far simpler than
    a full k-shortest-paths implementation for the two or three options a person
    actually wants to choose between.
    """
    routes = []
    penalties = {}
    for _ in range(k):
        path = _local_route(a, b, mode, max_anchor_m=max_anchor_m, penalties=penalties)
        if not path:
            break
        if any(_route_similarity(path, r) >= _ALT_SIMILARITY for r in routes):
            # Too close to one we already have; penalise harder and try again.
            used = penalties.pop('__used__', [])
            if not used:
                break
            for e in used:
                penalties[e] = penalties.get(e, 1.0) * _ALT_PENALTY
            continue
        routes.append(path)
        used = penalties.pop('__used__', [])
        if not used:
            break
        for e in used:
            penalties[e] = penalties.get(e, 1.0) * _ALT_PENALTY
    return routes


def _osrm_routes(profile, a, b, mode='', k=3):
    base = _osrm_base(profile)
    prof = _profile_for('osrm', mode)
    data = _get_json(f'{base}/route/v1/{prof}/{a[0]},{a[1]};{b[0]},{b[1]}',
                     {'geometries': 'geojson', 'overview': 'full',
                      'alternatives': 'true'})
    if data.get('code') not in (None, 'Ok'):
        return []
    return [[(c[0], c[1]) for c in (r.get('geometry', {}).get('coordinates') or [])]
            for r in (data.get('routes') or [])[:k]]


def _mapbox_routes(profile, a, b, mode='', k=3):
    prof = _profile_for('mapbox', mode)
    data = _get_json(
        f'https://api.mapbox.com/directions/v5/mapbox/{prof}/{a[0]},{a[1]};{b[0]},{b[1]}',
        {'access_token': profile.mapbox_token, 'geometries': 'geojson',
         'overview': 'full', 'alternatives': 'true'})
    return [[(c[0], c[1]) for c in (r.get('geometry', {}).get('coordinates') or [])]
            for r in (data.get('routes') or [])[:k]]


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
    if provider == 'valhalla':
        return _valhalla_route(profile, a, b, mode)
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


def _graph_bbox(a, b):
    """(west, south, east, north) to search for a route between two anchors.

    Padded generously: the road actually taken can detour well outside the box
    the anchors span, and a route that leaves the box cannot be found at all.
    Cheap insurance — the bbox filter is index-backed.
    """
    min_lng, max_lng = min(a[0], b[0]), max(a[0], b[0])
    min_lat, max_lat = min(a[1], b[1]), max(a[1], b[1])
    span = max(max_lng - min_lng, max_lat - min_lat)
    pad = max(span * _BBOX_PAD_FRAC, _BBOX_MIN_PAD_DEG)
    return (min_lng - pad, min_lat - pad, max_lng + pad, max_lat + pad)


def clear_graph_cache():
    """Release the memoised routing graph. Call at the end of a fill run."""
    _graph_cache['bbox'] = None
    _graph_cache['data'] = None


def _load_graph(a, b, mode=''):
    """Build a routing graph from the RoadSegment rows around the two anchors.

    Returns (adj, node_pos, edge_index) or None.

    Ways are split at coordinates shared with another way — an intersection —
    giving edges between junctions. Splitting rather than using every vertex
    keeps the node count (and so the A* frontier) roughly an order of magnitude
    smaller.

    Memoised for one bbox at a time: gaps along a single drive are spatially
    adjacent, so the next one is usually inside the box already loaded, and the
    distance trigger makes many more gaps to route. Only modest graphs are held —
    a full 25k-way graph is tens of MB of Python objects and this runs inside a
    web worker. Whoever borrows the cached graph must undo its splices (see
    _local_route); the cache stores no anchor state itself.
    """
    from django.contrib.gis.geos import Polygon
    from .models import RoadSegment

    w, s_, e, n = _graph_bbox(a, b)
    cached = _graph_cache.get('bbox')
    if cached and _graph_cache.get('data'):
        cw, cs, ce, cn = cached
        if cw <= w and cs <= s_ and ce >= e and cn >= n:
            return _graph_cache['data']

    bbox = Polygon.from_bbox((w, s_, e, n))
    segs = list(
        RoadSegment.objects.filter(geom__bboverlaps=bbox)
        .values_list('highway', 'oneway', 'geom')[:_MAX_GRAPH_SEGMENTS + 1]
    )
    if len(segs) > _MAX_GRAPH_SEGMENTS:
        logger.info('Local route: %d ways in bbox, above cap — bailing', len(segs))
        return None
    if not segs:
        logger.info('Local route: no road data around (%.4f,%.4f)-(%.4f,%.4f)',
                    a[0], a[1], b[0], b[1])
        return None

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
    edge_index = {}
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
            # Index the forward edge only; _nearest_edge just needs to find the
            # geometry, and both directions carry the same shape.
            _index_edge(edge_index, k_from, k_to, sub)

    data = (adj, node_pos, edge_index)
    if len(segs) <= _GRAPH_CACHE_MAX_WAYS:
        _graph_cache['bbox'] = (w, s_, e, n)
        _graph_cache['data'] = data
    else:
        clear_graph_cache()
    return data


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


def _ecell(lng, lat):
    return (int(lng // _EDGE_CELL_DEG), int(lat // _EDGE_CELL_DEG))


def _index_edge(edge_index, u, v, sub):
    """Register each of an edge's segments in the cells its bbox covers."""
    for i in range(len(sub) - 1):
        s1, s2 = sub[i], sub[i + 1]
        cx0, cy0 = _ecell(min(s1[0], s2[0]), min(s1[1], s2[1]))
        cx1, cy1 = _ecell(max(s1[0], s2[0]), max(s1[1], s2[1]))
        for cx in range(cx0, cx1 + 1):
            for cy in range(cy0, cy1 + 1):
                edge_index.setdefault((cx, cy), []).append((u, v, sub, i))


def _nearest_edge(edge_index, pt, reach=1):
    """Nearest graph edge to `pt`: (distance_m, u, v, seg_index, projection, sub).

    Consults only the point's own index cell and its eight neighbours. Scanning
    every edge instead was the dominant cost of routing a gap, and with the
    distance trigger detecting far more gaps it stopped being affordable. The
    cell is wider than _ROUTE_MAX_ANCHOR_M, so nothing within tolerance can hide
    outside that 3x3 block.
    """
    best = None
    cx, cy = _ecell(pt[0], pt[1])
    rng = range(-reach, reach + 1)
    for dx in rng:
        for dy in rng:
            for (u, v, sub, i) in edge_index.get((cx + dx, cy + dy), ()):
                q, d = _project_on_segment(pt, sub[i], sub[i + 1])
                if best is None or d < best[0]:
                    best = (d, u, v, i, q, sub)
    return best


def _note(undo, adj, key):
    """Record a vertex's adjacency length so the splice can be rolled back.

    Only appends ever happen, so remembering the prior length is a complete
    record; `None` means the key didn't exist and must be removed outright.
    """
    undo.append((key, len(adj[key]) if key in adj else None))


def _unsplice(adj, node_pos, undo, anchor_keys):
    """Restore the graph to its pre-splice state.

    The graph is memoised across gaps, so leaving anchor vertices and their edges
    behind would let one gap's route wander through a previous gap's anchors —
    a silent, hard-to-spot corruption. Undone in reverse so repeated notes on one
    key unwind correctly.
    """
    for key, prior in reversed(undo):
        if prior is None:
            adj.pop(key, None)
        else:
            del adj[key][prior:]
    for key in anchor_keys:
        node_pos.pop(key, None)


def _splice_anchor(adj, node_pos, edge_index, pt, label, undo, max_anchor_m=None):
    """Add `pt`'s projection onto the road network as a temporary graph vertex.

    Without this the route could only start at a *junction*, so it opened and
    closed with a straight off-road leg from the GPS anchor to that junction —
    which is what produced long runs of evenly-spaced points cutting across
    open ground. Splicing into the nearest edge means the path starts and ends
    exactly on tarmac and never leaves it.

    Mutates `adj`; every touched vertex is recorded in `undo` for _unsplice.
    Returns (vertex_key, distance_m, sub, seg_index) or (None, distance, …).
    """
    # A generous tolerance needs a correspondingly wider search: the index cell is
    # ~1.1km, so one ring around the anchor only covers the default 80m gate.
    reach = 1 if not max_anchor_m else max(1, int(max_anchor_m / 1100) + 1)
    found = _nearest_edge(edge_index, pt, reach=reach)
    if not found:
        return None, None, None, None
    d, u, v, i, proj, sub = found
    if d > (max_anchor_m or _ROUTE_MAX_ANCHOR_M):
        return None, d, None, None

    key = ('anchor', label)
    node_pos[key] = proj
    to_v = [proj] + list(sub[i + 1:])
    to_u = [proj] + list(reversed(sub[:i + 1]))
    cost_v = _polyline_length_m(to_v)
    cost_u = _polyline_length_m(to_u)
    # Both directions: one-way rules don't stop you joining or leaving the road
    # you're already stopped on.
    _note(undo, adj, key)
    adj.setdefault(key, []).append((v, cost_v, to_v))
    adj.setdefault(key, []).append((u, cost_u, to_u))
    _note(undo, adj, u)
    adj.setdefault(u, []).append((key, cost_u, to_u[::-1]))
    _note(undo, adj, v)
    adj.setdefault(v, []).append((key, cost_v, to_v[::-1]))
    return key, d, sub, i


def _local_route(a, b, mode='', max_anchor_m=None, penalties=None):
    graph = _load_graph(a, b, mode)
    if not graph:
        return None
    adj, node_pos, edge_index = graph
    if not adj:
        return None

    undo, anchor_keys = [], []
    try:
        return _route_on(adj, node_pos, edge_index, a, b, undo, anchor_keys,
                         max_anchor_m=max_anchor_m, penalties=penalties)
    finally:
        # Always, including on an exception: a half-spliced cached graph would
        # poison every later route.
        _unsplice(adj, node_pos, undo, anchor_keys)


def _route_on(adj, node_pos, edge_index, a, b, undo, anchor_keys,
              max_anchor_m=None, penalties=None):
    start, start_d, sub_a, i_a = _splice_anchor(adj, node_pos, edge_index, a, 'a', undo, max_anchor_m)
    if start is not None:
        anchor_keys.append(start)
    goal, goal_d, sub_b, i_b = _splice_anchor(adj, node_pos, edge_index, b, 'b', undo, max_anchor_m)
    if goal is not None:
        anchor_keys.append(goal)
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
        _note(undo, adj, start)
        adj.setdefault(start, []).append((goal, cost, seg))
        _note(undo, adj, goal)
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
            # Penalised edges are the ones a previous alternative already used;
            # inflating them pushes A* onto a genuinely different road rather
            # than a near-identical variant of the same one.
            if penalties:
                cost = cost * penalties.get((node, nxt), 1.0)
            ng = g + cost
            if ng < best_g.get(nxt, float('inf')):
                best_g[nxt] = ng
                came[nxt] = (node, sub)
                counter += 1
                heapq.heappush(open_heap, (ng + h(nxt), ng, counter, nxt))

    if goal not in came:
        return None

    parts, used, node = [], [], goal
    while came[node][0] is not None:
        prev, sub = came[node]
        parts.append(sub)
        used.append((prev, node))
        node = prev
    parts.reverse()
    if penalties is not None:
        penalties['__used__'] = used

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
# Valhalla provider — Meili for map matching, /route for gap routing.
#
# Valhalla's action endpoints (trace_attributes, route, ...) are POST-only in
# their own docs, but every one also accepts a plain GET with the request body
# URL-encoded into a `json` query parameter — the "convenience" form of the
# API. Reusing _get_json (GET-only, with this module's HTTP-error-to-English
# translation already built in) via that form means no second HTTP helper is
# needed just for this provider.
# ---------------------------------------------------------------------------

# Meili's own default is far higher than this; kept modest so one failed
# request costs a bounded amount of retry work, same reasoning as the other
# two providers' per-request coordinate caps.
_VALHALLA_TRACE_MAX = 500


def _valhalla_base(profile):
    url = (profile.valhalla_url or '').strip().rstrip('/')
    if not url:
        raise RoadProviderError('No Valhalla URL is configured.')
    return url


def _decode_polyline6(encoded):
    """Decode a Valhalla-encoded shape string into [(lng, lat), ...].

    Same algorithm as Google's polyline encoding, but Valhalla uses 6 decimal
    places of precision (divisor 1e6) where most other implementations
    (including the one this format is usually associated with) use 5 — using
    the wrong divisor doesn't error, it just silently returns coordinates off
    by roughly 10x, so this is worth getting exactly right rather than reusing
    a generic polyline library tuned for the more common precision.
    """
    coords = []
    index = lat = lng = 0
    length = len(encoded)
    while index < length:
        for is_lat in (True, False):
            shift = result = 0
            while True:
                b = ord(encoded[index]) - 63
                index += 1
                result |= (b & 0x1f) << shift
                shift += 5
                if b < 0x20:
                    break
            delta = ~(result >> 1) if (result & 1) else (result >> 1)
            if is_lat:
                lat += delta
            else:
                lng += delta
        coords.append((lng / 1e6, lat / 1e6))
    return coords


def _valhalla_snap(profile, pts):
    base = _valhalla_base(profile)
    prof = _profile_for('valhalla', '')
    out = {}
    for i in range(0, len(pts), _VALHALLA_TRACE_MAX):
        chunk = pts[i:i + _VALHALLA_TRACE_MAX]
        shape = []
        for p in chunk:
            pt = {'lat': p['lat'], 'lon': p['lng']}
            if p.get('timestamp') is not None:
                pt['time'] = int(p['timestamp'].timestamp())
            if p.get('accuracy') is not None:
                pt['accuracy'] = p['accuracy']
            shape.append(pt)
        body = {
            'shape': shape,
            'costing': prof,
            'shape_match': 'map_snap',
            'trace_options': {'search_radius': 50},
        }
        data = _get_json(f'{base}/trace_attributes', {'json': json.dumps(body)})
        matched = data.get('matched_points') or []
        for p, mp in zip(chunk, matched):
            # Meili marks a point 'unmatched' when nothing within its search
            # radius fit the trace — same "leave it alone" case as OSRM's null
            # tracepoint entries.
            if not mp or mp.get('type') == 'unmatched':
                continue
            lng, lat = mp.get('lon'), mp.get('lat')
            if lng is None or lat is None:
                continue
            if _haversine_m(p['lng'], p['lat'], lng, lat) <= _snap_limit_m(p.get('accuracy')):
                out[p['id']] = (lng, lat)
    return out


def _valhalla_route(profile, a, b, mode=''):
    base = _valhalla_base(profile)
    prof = _profile_for('valhalla', mode)
    body = {
        'locations': [{'lat': a[1], 'lon': a[0]}, {'lat': b[1], 'lon': b[0]}],
        'costing': prof,
    }
    data = _get_json(f'{base}/route', {'json': json.dumps(body)})
    legs = (data.get('trip') or {}).get('legs') or []
    if not legs:
        return None
    shape = legs[0].get('shape')
    if not shape:
        return None
    coords = _decode_polyline6(shape)
    return coords if len(coords) >= 2 else None


def _valhalla_routes(profile, a, b, mode='', k=3):
    base = _valhalla_base(profile)
    prof = _profile_for('valhalla', mode)
    body = {
        'locations': [{'lat': a[1], 'lon': a[0]}, {'lat': b[1], 'lon': b[0]}],
        'costing': prof,
        # Valhalla's own count of the primary trip, so ask for k-1 more.
        'alternates': max(0, k - 1),
    }
    data = _get_json(f'{base}/route', {'json': json.dumps(body)})
    trips = []
    if data.get('trip'):
        trips.append(data['trip'])
    for alt in (data.get('alternates') or []):
        if alt.get('trip'):
            trips.append(alt['trip'])

    out = []
    for trip in trips[:k]:
        legs = trip.get('legs') or []
        if not legs:
            continue
        shape = legs[0].get('shape')
        if not shape:
            continue
        coords = _decode_polyline6(shape)
        if len(coords) >= 2:
            out.append(coords)
    return out


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
