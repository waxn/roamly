"""Detect GPS gaps a subway ride explains, and reconstruct the route.

An underground ride leaves a hole in the track: the last surface fix, a stretch
of nothing, then the next surface fix somewhere else. This scans each device's
history for consecutive fixes whose separation *looks* like a subway hop (a real
time gap, a plausible jump, a speed above walking), then asks the rail router
whether a subway route actually connects the two ends.

That last test is the one that matters, and is exactly the user's requirement —
"only where a subway route is nearby between the two". Ordinary above-ground
travel that merely happens to pass near a station produces no rail route between
its endpoints (the anchors don't project onto track, or the network doesn't join
them), so it never surfaces. The cheap time/distance/speed filters just keep the
routing step — the only expensive part — off the vast majority of fix pairs.

Detection is on the fly and cached (like place suggestions); nothing is stored
until the user confirms a fill. PostGIS-only, via rails.py.
"""

import logging

from .roads import _haversine_m

logger = logging.getLogger(__name__)

# A gap's time span. Below the minimum it isn't lost tracking, just normal push
# cadence; above it, a single ride is a stretch.
_GAP_MIN_S = 90
_GAP_MAX_S = 5400            # 90 minutes
# Straight-line jump between the two surface fixes.
_JUMP_MIN_M = 300.0
_JUMP_MAX_M = 40000.0
# Implied average speed (straight line / dt): above a walk, below a teleport.
# Deliberately loose — the real discriminator is whether a subway route exists.
_SUB_MIN_MPS = 1.5
_SUB_MAX_MPS = 35.0
# Reject a "route" far longer than the straight jump: that's the router taking a
# scenic path through the network, not the ride. Slack so a short hop whose
# stations sit off the anchors still passes.
_MAX_ROUTE_RATIO = 2.6
_MAX_ROUTE_SLACK_M = 800.0
# Ceilings so a heavy-commuter history can't produce an unbounded response or run
# the router thousands of times.
_MAX_GAPS = 300
_MAX_ROUTE_ATTEMPTS = 600

CACHE_TTL = 1800


def cache_key(user_id):
    return f'subway_gaps:{user_id}'


def _round_key(slat, slng, elat, elng):
    """Anchor-pair identity for dismissals, rounded to ~11m."""
    return (round(slat, 4), round(slng, 4), round(elat, 4), round(elng, 4))


def _dismissed_set(user):
    from .models import DismissedSubwayGap
    return {
        _round_key(g[0], g[1], g[2], g[3])
        for g in DismissedSubwayGap.objects.filter(user=user).values_list(
            'start_latitude', 'start_longitude', 'end_latitude', 'end_longitude')
    }


def detect_gaps(user):
    """Every subway-explained gap in the user's history, most recent first.

    Returns a list of dicts carrying both anchors, the time gap, and up to three
    candidate routes (coords + length) for the picker. The routes are cached with
    the gap so a later fill commits one by index without the client ever sending
    geometry back.
    """
    from .models import Device, Location, _subway_data_available
    from . import rails

    if not _subway_data_available():
        return []

    dismissed = _dismissed_set(user)
    gaps = []
    attempts = 0

    for device in Device.objects.filter(user=user):
        prev = None
        qs = (Location.objects.filter(device=device)
              .order_by('timestamp', 'id')
              .values_list('id', 'latitude', 'longitude', 'timestamp', 'speed')
              .iterator(chunk_size=20000))
        for lid, lat, lng, ts, spd in qs:
            if lat is None or lng is None or ts is None:
                prev = None
                continue
            if prev is not None:
                p_id, p_lat, p_lng, p_ts = prev
                dt = (ts - p_ts).total_seconds()
                if _GAP_MIN_S <= dt <= _GAP_MAX_S:
                    d = _haversine_m(p_lng, p_lat, lng, lat)
                    if (_JUMP_MIN_M <= d <= _JUMP_MAX_M
                            and _SUB_MIN_MPS <= d / dt <= _SUB_MAX_MPS
                            and _round_key(p_lat, p_lng, lat, lng) not in dismissed
                            and attempts < _MAX_ROUTE_ATTEMPTS):
                        attempts += 1
                        a = (p_lng, p_lat)
                        b = (lng, lat)
                        alts = rails.route_alternatives_subway(a, b, k=3)
                        if alts and alts[0]['length_m'] <= max(
                                d * _MAX_ROUTE_RATIO, d + _MAX_ROUTE_SLACK_M):
                            gaps.append({
                                'device_id': device.device_id,
                                'device_name': device.name or device.device_id,
                                'start_id': p_id,
                                'end_id': lid,
                                'start': {'lat': p_lat, 'lng': p_lng},
                                'end': {'lat': lat, 'lng': lng},
                                'start_ts': p_ts.isoformat(),
                                'end_ts': ts.isoformat(),
                                'dt_s': int(dt),
                                'straight_m': round(d, 1),
                                'length_m': round(alts[0]['length_m'], 1),
                                'routes': [{'coords': al['coords'],
                                            'length_m': round(al['length_m'], 1)}
                                           for al in alts],
                            })
            prev = (lid, lat, lng, ts)

    # Most recent rides first; that's the one a user is most likely acting on.
    gaps.sort(key=lambda g: g['start_ts'], reverse=True)
    return gaps[:_MAX_GAPS]
