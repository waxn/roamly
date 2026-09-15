"""Carrier identification and tower-position estimation for cell coverage.

Split out of views.py because both pieces are real logic with real edge cases
rather than request handling, and both are wanted by three endpoints
(`cell_samples_api`, `cell_towers_api`, `cell_insights_api`).
"""
import math

# ── Carriers ────────────────────────────────────────────────────────────────
#
# Name-matching FIRST, MNC second. `carrier` comes from the modem's
# networkOperatorName, which is what the user actually recognises and is also
# what an MVNO reports when it rides a host network. The MNC table is the
# fallback for a blank or unhelpful name, and is deliberately small — a
# complete MCC/MNC registry is thousands of rows that go stale, and every
# carrier missing from it still renders correctly, just in a generic colour.

_CARRIER_NAME_MATCHES = (
    ('verizon', ('verizon', 'vzw')),
    ('tmobile', ('t-mobile', 'tmobile', 't mobile', 'metro by t-mobile', 'metropcs')),
    ('att', ('at&t', 'at & t', 'at-t', 'cingular', 'firstnet')),
)

# US MCCs only (310/311/312/313/316). Outside the US these MNCs mean other
# things entirely, which is why the MCC is part of the test.
_US_MCCS = {'310', '311', '312', '313', '316'}
_CARRIER_MNCS = {
    'verizon': {'004', '005', '006', '010', '012', '013', '110', '270', '271', '272',
                '273', '274', '275', '276', '277', '278', '279', '280', '281', '282',
                '283', '284', '285', '286', '287', '288', '289', '390', '480', '481',
                '482', '483', '484', '485', '486', '487', '488', '489', '590', '820',
                '890', '910'},
    'tmobile':  {'026', '160', '200', '210', '220', '230', '240', '250', '260', '270',
                 '300', '310', '660', '800', '490', '580', '840'},
    'att':      {'038', '070', '090', '150', '170', '180', '280', '380', '410', '560',
                 '680', '980'},
}
# 310-270 is claimed by both historically; Verizon wins, matching how phones
# report it today. Resolved by checking Verizon first in the loop below.
_CARRIER_MNC_ORDER = ('verizon', 'att', 'tmobile')

CARRIER_LABELS = {'verizon': 'Verizon', 'tmobile': 'T-Mobile', 'att': 'AT&T'}

# Brand hues for the three the user asked for. Everything else draws from the
# generic palette, assigned by sample count so the same carrier keeps the same
# colour across a page load.
CARRIER_COLORS = {
    'verizon': '#e01c3c',   # red
    'tmobile': '#e20074',   # magenta
    'att':     '#00a8e0',   # blue
}
_GENERIC_COLORS = ('#6fbf8e', '#bf9a6f', '#8e6fbf', '#6f9abf', '#bf6f9a', '#9abf6f')
UNKNOWN_COLOR = '#8a8f98'


def carrier_key(name, mcc='', mnc=''):
    """Stable slug for a carrier: one of the known keys, or the lowercased name."""
    low = (name or '').strip().lower()
    for key, needles in _CARRIER_NAME_MATCHES:
        if any(n in low for n in needles):
            return key
    if (mcc or '') in _US_MCCS and mnc:
        m = str(mnc).zfill(3)
        for key in _CARRIER_MNC_ORDER:
            if m in _CARRIER_MNCS[key]:
                return key
    return low or 'unknown'


def build_carrier_legend(counter):
    """[{key, name, color}] ordered by sample count, for a payload's legend.

    Colours are assigned server-side so every endpoint and the insights page
    agree on them without duplicating the palette in three templates.
    """
    generic = 0
    legend = []
    for key, _count in counter.most_common():
        if key in CARRIER_COLORS:
            color = CARRIER_COLORS[key]
        elif key == 'unknown':
            color = UNKNOWN_COLOR
        else:
            color = _GENERIC_COLORS[generic % len(_GENERIC_COLORS)]
            generic += 1
        legend.append({
            'key': key,
            'name': CARRIER_LABELS.get(key, key.title() if key != 'unknown' else 'Unknown'),
            'color': color,
        })
    return legend


# ── Distance from signal strength ───────────────────────────────────────────
#
# A log-distance path-loss model collapsed into an interpolation table for a
# typical urban macro cell. This is genuinely rough — terrain, indoor
# attenuation, tower height and transmit power all move it by a factor of two
# or more, and the table is calibrated for LTE RSRP while GSM/WCDMA report a
# different quantity on the same scale.
#
# It is used for ONE thing, where that error is tolerable: saying a tower is
# "roughly a kilometre away, direction unknown" rather than drawing it on top
# of the only place it was ever observed from. Do not present it as a measured
# distance.

_RSRP_DISTANCE = (
    (-60, 80), (-70, 150), (-80, 300), (-85, 450), (-90, 700),
    (-95, 1100), (-100, 1700), (-105, 2600), (-110, 4000), (-120, 8000),
)


def distance_from_dbm(dbm):
    """Very rough metres-to-tower for a signal reading. None if unusable."""
    if dbm is None:
        return None
    if dbm >= _RSRP_DISTANCE[0][0]:
        return float(_RSRP_DISTANCE[0][1])
    if dbm <= _RSRP_DISTANCE[-1][0]:
        return float(_RSRP_DISTANCE[-1][1])
    for (d0, m0), (d1, m1) in zip(_RSRP_DISTANCE, _RSRP_DISTANCE[1:]):
        if d1 <= dbm <= d0:
            # Interpolated in log space — distance is exponential in dB.
            t = (d0 - dbm) / (d0 - d1) if d0 != d1 else 0.0
            return math.exp(math.log(m0) + t * (math.log(m1) - math.log(m0)))
    return None


# ── Tower position ──────────────────────────────────────────────────────────

# Below this, every observation of the cell came from effectively one spot and
# the tower's position is NOT constrained by the data — no estimator can place
# it, because nothing in the readings says which direction it lies in.
MIN_SPREAD_M = 250.0
# Above this, the observations surround the cell well enough that refining the
# centroid against the distance estimates is worth doing.
GOOD_SPREAD_M = 1200.0
MIN_SAMPLES_FOR_REFINE = 8

EARTH_R = 6_371_000.0


def haversine_m(lat1, lon1, lat2, lon2):
    d_lat = math.radians(lat2 - lat1)
    d_lon = math.radians(lon2 - lon1)
    a = (math.sin(d_lat / 2) ** 2 +
         math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(d_lon / 2) ** 2)
    return EARTH_R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def estimate_tower(points, spread_m=None):
    """Estimate one tower's position from its observations.

    `points` is [(lat, lon, dbm), ...]. Returns a dict with the position, how
    far the observations spread, a rough range to the tower, and — the part
    that matters — a `confidence` saying whether the position means anything.

    THE CENTROID OF OBSERVATIONS IS NOT THE TOWER. If every reading of a cell
    was taken from one house, the signal-weighted centroid is that house, and
    drawing a confident dot there is simply wrong: the data constrains the
    tower's *distance* (weakly, via signal strength) and says nothing at all
    about its direction. That case returns confidence='unconstrained' with a
    `range_m`, and the map draws a ring rather than a point.
    """
    if not points:
        return None

    # Weighted centroid. Weight is metres above the noise floor, floored at 1:
    # a pathological -145 dBm reading would otherwise weigh zero or negative
    # and pull the centroid AWAY from where the signal was strongest.
    wsum = wlat = wlng = 0.0
    best_dbm = -999
    for lat, lon, dbm in points:
        w = max(1.0, (dbm if dbm is not None else -120) + 140.0)
        wsum += w
        wlat += w * lat
        wlng += w * lon
        if dbm is not None and dbm > best_dbm:
            best_dbm = dbm
    if not wsum:
        return None
    lat = wlat / wsum
    lng = wlng / wsum

    # Callers that scanned a whole history pass the exact spread from a running
    # bounding box, so they can hand us a capped sample of points instead of
    # every one of them — the centroid is a running sum anyway and only _refine
    # needs the individual readings.
    spread = spread_m if spread_m is not None else max(
        haversine_m(lat, lng, p[0], p[1]) for p in points)
    # Distance implied by the STRONGEST reading — the closest you ever got.
    range_m = distance_from_dbm(best_dbm if best_dbm > -999 else None)

    if spread < MIN_SPREAD_M:
        return {'lat': round(lat, 6), 'lng': round(lng, 6),
                'spread_m': round(spread), 'range_m': round(range_m) if range_m else None,
                'confidence': 'unconstrained'}

    if spread >= GOOD_SPREAD_M and len(points) >= MIN_SAMPLES_FOR_REFINE:
        lat, lng = _refine(lat, lng, points)
        conf = 'good'
    else:
        conf = 'rough'
    return {'lat': round(lat, 6), 'lng': round(lng, 6),
            'spread_m': round(spread), 'range_m': round(range_m) if range_m else None,
            'confidence': conf}


def _refine(lat, lng, points, iterations=12):
    """Nudge the centroid toward a position consistent with the distance estimates.

    Plain gradient descent on sum of (observed_distance - estimated_distance)^2,
    in a local flat-earth frame. Deliberately BOUNDED: the path-loss estimates
    are rough enough that an unconstrained solve can run away, so the result is
    clamped to the observation spread. It is a nudge off the coverage centroid,
    not a survey.
    """
    obs = [(p[0], p[1], distance_from_dbm(p[2])) for p in points]
    obs = [o for o in obs if o[2]]
    if len(obs) < MIN_SAMPLES_FOR_REFINE:
        return lat, lng

    m_per_deg_lat = 111_320.0
    m_per_deg_lng = 111_320.0 * math.cos(math.radians(lat))
    if not m_per_deg_lng:
        return lat, lng

    x = y = 0.0  # metres from the starting centroid
    max_move = max(haversine_m(lat, lng, o[0], o[1]) for o in obs)
    for _ in range(iterations):
        gx = gy = 0.0
        for olat, olng, target in obs:
            ox = (olng - lng) * m_per_deg_lng
            oy = (olat - lat) * m_per_deg_lat
            dx, dy = x - ox, y - oy
            d = math.hypot(dx, dy)
            if d < 1.0:
                continue
            err = d - target
            gx += err * dx / d
            gy += err * dy / d
        n = len(obs)
        x -= 0.35 * gx / n
        y -= 0.35 * gy / n
        move = math.hypot(x, y)
        if move > max_move:
            x *= max_move / move
            y *= max_move / move
    return lat + y / m_per_deg_lat, lng + x / m_per_deg_lng
