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
            for iata, icao, name, city, cc, lat, lon in rows:
                rec = {'iata': iata, 'icao': icao, 'name': name, 'city': city,
                       'country': cc, 'lat': lat, 'lon': lon}
                if icao:
                    idx.setdefault(icao, rec)
                if iata:
                    idx[iata] = rec  # IATA wins a 3-letter clash with an ICAO
            _by_code = idx
    return _by_code


def lookup(code):
    """An airport by IATA (3 letters) or ICAO (4) code, or None."""
    code = (code or '').strip().upper()
    if not code:
        return None
    return _load().get(code)


def great_circle_km(lat1, lon1, lat2, lon2):
    r = 6371.0088
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))
