"""Airport lookup from the bundled OurAirports subset.

The data is tracker/static/tracker/data/airports.json, built by
scripts/build_airports.py: rows of [iata, icao, name, city, country, lat, lon].
The browser loads the same file for autocomplete and the flights map; this
module is the server's copy, used to fill in and validate a manually typed
airport code. Loaded lazily on first use, like geoip_utils, so a worker that
never touches flights never pays for it.
"""
import json
import math
import os
import threading

_PATH = os.path.join(os.path.dirname(__file__), 'static', 'tracker', 'data', 'airports.json')
_lock = threading.Lock()
_by_code = None
_grid = None  # (int lat, int lon) -> large/medium IATA airports, for nearest()


def _load():
    global _by_code
    if _by_code is not None:
        return _by_code
    with _lock:
        if _by_code is None:
            idx = {}
            try:
                with open(_PATH, encoding='utf-8') as f:
                    rows = json.load(f)
            except (OSError, ValueError):
                rows = []
            grid = {}
            for row in rows:
                iata, icao, name, city, cc, lat, lon = row[:7]
                size = row[7] if len(row) > 7 else 0
                rec = {'iata': iata, 'icao': icao, 'name': name, 'city': city,
                       'country': cc, 'lat': lat, 'lon': lon, 'size': size}
                if icao:
                    idx.setdefault(icao, rec)
                if iata:
                    idx[iata] = rec  # IATA wins a 3-letter clash with an ICAO
                    if size:
                        grid.setdefault((math.floor(lat), math.floor(lon)), []).append(rec)
            global _grid
            _grid = grid
            _by_code = idx
    return _by_code


def lookup(code):
    """An airport by IATA (3 letters) or ICAO (4) code, or None."""
    code = (code or '').strip().upper()
    if not code:
        return None
    return _load().get(code)


# How far a fix may be from an airport's reference point and still count as
# "at" it, by size. A gate or a car park at a large hub can be several km from
# the reference point. Small fields (heliports, strips) are left out of
# nearest() entirely: a passenger flight almost never uses one, and in a city
# they sit close enough to ordinary streets to claim them.
_NEAR_KM = {2: 8.0, 1: 5.0}


def nearest(lat, lon):
    """The airport a position is most plausibly at, or None.

    Among large and medium IATA airports within their size's radius, the
    largest wins and then the closest — so a fix at a big hub is not attributed
    to a regional field that happens to sit a little nearer.
    """
    _load()
    if lat is None or lon is None or not _grid:
        return None
    best, best_key = None, None
    gy, gx = math.floor(lat), math.floor(lon)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            for a in _grid.get((gy + dy, ((gx + dx + 180) % 360) - 180), ()):
                d = great_circle_km(lat, lon, a['lat'], a['lon'])
                if d > _NEAR_KM[a["size"]]:
                    continue
                key = (-a['size'], d)
                if best_key is None or key < best_key:
                    best, best_key = a, key
    return best


def great_circle_km(lat1, lon1, lat2, lon2):
    r = 6371.0088
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))
