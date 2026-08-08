"""Subway route reconstruction over locally-downloaded track geometry.

The rail counterpart to roads.py's local provider, and it reuses that module's
machinery wholesale: the coordinate-keyed graph idea (roads._vkey), the edge
spatial index (roads._index_edge / _nearest_edge), the anchor-splicing A*
(roads._route_on), and the penalty-method alternatives — none of which are
road-specific. Only the graph *construction* differs, in three ways that matter
for rail:

  1. **Uniform, bidirectional edges.** There is no highway class to weight by and
     no one-way to honour — a train runs both directions over the modelled track.

  2. **A dense graph, not one split at junctions.** Roads split each way at
     shared-node junctions to shrink the A* frontier. Rail can't: two lines
     meeting at an interchange are in *separate* tunnels at different depths, so
     their tracks share no node and there is no junction to split at. Keeping
     every shape point as a vertex means a station always has track vertices
     within reach to stitch to, and the network is small enough (a city's subway
     is a few thousand shape points) that the larger frontier is free.

  3. **Transfer stitching.** After the track graph is built, each RailStation is
     connected to every track vertex within _RAIL_STATION_LINK_M through a
     synthetic hub node, at a small transfer cost. That is what lets A* step from
     one line's tracks onto another's at a shared station — i.e. a ride that
     needs a line change still routes end to end. The connectors are *not* added
     to the edge index, so a GPS anchor never snaps onto a transfer walk.

Subway routing is always local (no Mapbox/OSRM rail profile exists), and it is
PostGIS-only, like the local road provider. Everything returns None rather than
raising when there is simply no route.
"""

import logging

from .roads import (
    RoadProviderError,
    _haversine_m, _polyline_length_m, _vkey, _ecell, _index_edge,
    _route_on, _route_similarity, _graph_bbox,
    _MAX_GRAPH_SEGMENTS, _ALT_PENALTY, _ALT_SIMILARITY,
)

logger = logging.getLogger(__name__)

# Anchors are surface GPS fixes near a station entrance, not on the platform, so
# the tolerance is wide (like the editor's _EDITOR_ANCHOR_M) — far wider than the
# 80m road-snapping gate.
_RAIL_ANCHOR_M = 500.0
# A single subway ride is short; beyond this a "gap" is something else.
_RAIL_ROUTE_MAX_KM = 120.0
# How close a track vertex must be to a station node to be stitched to it. Snug —
# a real interchange puts both lines' tracks within a platform's width of the
# station node — but comfortably inside the ~1.1km edge-index cell so one ring of
# cells finds every candidate.
_RAIL_STATION_LINK_M = 150.0
# Half the cost of a transfer (vertex→hub→vertex pays it twice). Nonzero so the
# router never routes *through* a station as a shortcut along one line, small
# enough that a genuine transfer is always cheaper than any track detour.
_RAIL_TRANSFER_HALF_M = 40.0

_rail_graph_cache = {'bbox': None, 'data': None}


def clear_rail_graph_cache():
    _rail_graph_cache['bbox'] = None
    _rail_graph_cache['data'] = None


def _load_rail_graph(a, b):
    """Build a routing graph from RailSegment rows around the two anchors.

    Returns (adj, node_pos, edge_index) or None. Memoised for one bbox at a time,
    like roads._load_graph; the borrower undoes its own anchor splices (roads.
    _route_on does this in a finally), so the cached graph carries no anchor state.
    """
    from django.contrib.gis.geos import Polygon
    from .models import RailSegment, RailStation

    w, s_, e, n = _graph_bbox(a, b)
    cached = _rail_graph_cache.get('bbox')
    if cached and _rail_graph_cache.get('data'):
        cw, cs, ce, cn = cached
        if cw <= w and cs <= s_ and ce >= e and cn >= n:
            return _rail_graph_cache['data']

    bbox = Polygon.from_bbox((w, s_, e, n))
    segs = list(
        RailSegment.objects.filter(geom__bboverlaps=bbox)
        .values_list('geom', flat=True)[:_MAX_GRAPH_SEGMENTS + 1]
    )
    if len(segs) > _MAX_GRAPH_SEGMENTS:
        logger.info('Subway route: %d ways in bbox, above cap — bailing', len(segs))
        return None
    if not segs:
        logger.info('Subway route: no rail data around (%.4f,%.4f)-(%.4f,%.4f)',
                    a[0], a[1], b[0], b[1])
        return None

    adj = {}
    node_pos = {}
    edge_index = {}
    # Dense edges: one per consecutive shape-point pair, both directions. Ways
    # that share a node share the coordinate, so keying on _vkey wires them
    # together automatically.
    for geom in segs:
        coords = list(geom.coords)
        for i in range(len(coords) - 1):
            c0, c1 = coords[i], coords[i + 1]
            k0, k1 = _vkey(c0[0], c0[1]), _vkey(c1[0], c1[1])
            if k0 == k1:
                continue
            sub = [c0, c1]
            cost = _haversine_m(c0[0], c0[1], c1[0], c1[1])
            node_pos[k0] = c0
            node_pos[k1] = c1
            adj.setdefault(k0, []).append((k1, cost, sub))
            adj.setdefault(k1, []).append((k0, cost, sub[::-1]))
            _index_edge(edge_index, k0, k1, sub)

    _stitch_transfers(adj, node_pos, bbox)

    data = (adj, node_pos, edge_index)
    _rail_graph_cache['bbox'] = (w, s_, e, n)
    _rail_graph_cache['data'] = data
    return data


def _stitch_transfers(adj, node_pos, bbox):
    """Connect nearby track vertices through a per-station hub node.

    This is the one rail-specific graph step. A station whose neighbourhood
    contains track vertices from two different lines becomes the place A* can
    cross between them; a station touching a single line just gains a harmless
    hub. The hub is never indexed for anchor snapping (edge_index is untouched),
    so it only ever affects routing, not where a GPS fix lands.
    """
    from .models import RailStation

    # Bucket every graph vertex into the same ~1.1km grid the edge index uses, so
    # each station only scans its own cell and the eight around it.
    vcell = {}
    for key, pos in node_pos.items():
        vcell.setdefault(_ecell(pos[0], pos[1]), []).append((key, pos))

    stations = RailStation.objects.filter(geom__bboverlaps=bbox).values_list('node_id', 'geom')
    for node_id, geom in stations:
        slng, slat = geom.x, geom.y
        cx, cy = _ecell(slng, slat)
        near = []
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for key, pos in vcell.get((cx + dx, cy + dy), ()):
                    d = _haversine_m(slng, slat, pos[0], pos[1])
                    if d <= _RAIL_STATION_LINK_M:
                        near.append((key, pos, d))
        # A hub with a single spoke can't enable a transfer, only cost memory.
        if len(near) < 2:
            continue
        hub = ('station', node_id)
        hub_pos = (slng, slat)
        node_pos[hub] = hub_pos
        for key, pos, d in near:
            cost = max(d, _RAIL_TRANSFER_HALF_M)
            to_hub = [pos, hub_pos]
            adj.setdefault(hub, []).append((key, cost, [hub_pos, pos]))
            adj.setdefault(key, []).append((hub, cost, to_hub))


def _rail_route(a, b, max_anchor_m=None, penalties=None):
    graph = _load_rail_graph(a, b)
    if not graph:
        return None
    adj, node_pos, edge_index = graph
    if not adj:
        return None

    from .roads import _unsplice
    undo, anchor_keys = [], []
    try:
        return _route_on(adj, node_pos, edge_index, a, b, undo, anchor_keys,
                         max_anchor_m=max_anchor_m or _RAIL_ANCHOR_M, penalties=penalties)
    finally:
        _unsplice(adj, node_pos, undo, anchor_keys)


def _rail_alternatives(a, b, k=3, max_anchor_m=None):
    """Up to k distinct subway routes, penalising edges each one used.

    Same penalty method as roads._local_alternatives — at an interchange the
    inflated edges push the next search onto a different line, which is exactly
    the "which line did you ride" choice a person wants to confirm.
    """
    routes = []
    penalties = {}
    for _ in range(k):
        path = _rail_route(a, b, max_anchor_m=max_anchor_m, penalties=penalties)
        if not path:
            break
        if any(_route_similarity(path, r) >= _ALT_SIMILARITY for r in routes):
            used = penalties.pop('__used__', [])
            if not used:
                break
            for edge in used:
                penalties[edge] = penalties.get(edge, 1.0) * _ALT_PENALTY
            continue
        routes.append(path)
        used = penalties.pop('__used__', [])
        if not used:
            break
        for edge in used:
            penalties[edge] = penalties.get(edge, 1.0) * _ALT_PENALTY
    return routes


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def route_subway(a, b):
    """Single best subway route between two (lng, lat) anchors, or None.

    Used both to fill a gap and, in detection, to decide whether a subway even
    connects the two ends. PostGIS-only; returns None (never raises) when there
    is no rail data or no route.
    """
    from .models import _subway_data_available
    if not _subway_data_available():
        return None
    if _haversine_m(a[0], a[1], b[0], b[1]) / 1000.0 > _RAIL_ROUTE_MAX_KM:
        return None
    return _rail_route(a, b, max_anchor_m=_RAIL_ANCHOR_M)


def route_alternatives_subway(a, b, k=3):
    """Up to k distinct subway routes, best first: [{'coords', 'length_m'}]."""
    from .models import _subway_data_available
    if not _subway_data_available():
        return []
    if _haversine_m(a[0], a[1], b[0], b[1]) / 1000.0 > _RAIL_ROUTE_MAX_KM:
        return []
    out = []
    for coords in _rail_alternatives(a, b, k=k, max_anchor_m=_RAIL_ANCHOR_M):
        if coords and len(coords) >= 2:
            out.append({'coords': coords, 'length_m': _polyline_length_m(coords)})
    return out
