"""Offline reverse geocoding.

City/state/country labelling for tracked points, done entirely on the server
against a bundled GeoNames dataset — no network call, no rate limits, no
Nominatim `429` blocks. A continuous tracker generates far more points than the
public Nominatim usage policy allows (which is why its IP gets blocked); the
data Roamly stores is city-level (Nominatim was only ever queried at zoom=10),
which offline nearest-place matching reproduces.

**Dataset.** ``tracker/data/places.json.gz`` is GeoNames `cities500` (every
place of 500+ people), built by ``scripts/build_cities.py``. The
``reverse_geocode`` package's own `cities1000` is the fallback when that file is
missing. `cities1000` is what labelled a drive round Campobello Island — whose
villages are a few hundred people each — as Eastport, Maine: the nearest town it
had, in another country.

**Border check.** Nearest-place matching knows nothing about borders, so a point
near one can take the label of a bigger town across it. The timezone polygons
``timezonefinder`` already loads for ``tz_utils`` follow real borders
(Campobello keeps Atlantic time, America/Moncton; Eastport is America/New_York),
and IANA's zone.tab maps every land zone to one country. When the nearest place
is in a different country from the point's zone, the nearest place *in the
zone's country* is used instead — or, if none is within ``_BORDER_MAX_KM``, just
that country's state and name with no town, rather than naming a town the point
was never in. Ocean (``Etc/*``) and unmapped zones skip the check.

Everything is built lazily on first use and kept for the process lifetime, so
only a process that actually geocodes pays the (one-off, couple-second) load.
"""
import gzip
import json
import logging
import math
import os
import threading

logger = logging.getLogger(__name__)

_DATA_PATH = os.path.join(os.path.dirname(__file__), 'data', 'places.json.gz')

# How far the nearest same-country place may be when the border check swaps
# countries. Past it the town is left blank: across a strait, the nearest town
# in the right country is usually a different community altogether.
_BORDER_MAX_KM = 12.0

_EARTH_KM = 6371.0088

_places = None
_places_failed = False
_places_lock = threading.Lock()


def _xyz(lat, lon):
    """Unit-sphere coordinates, so a k-d tree's Euclidean nearest neighbour is
    the true nearest place (lat/lon degrees overweight longitude away from the
    equator)."""
    la, lo = math.radians(lat), math.radians(lon)
    c = math.cos(la)
    return (c * math.cos(lo), c * math.sin(lo), math.sin(la))


def _chord_km(chord):
    return 2 * _EARTH_KM * math.asin(min(1.0, chord / 2))


class _Places:
    def __init__(self, data):
        from scipy.spatial import cKDTree

        self.countries = data['countries']
        self.admin1 = data['admin1']
        self.tz_country = data['tz_country']
        rows = data['rows']
        self.cc = [r[2] for r in rows]
        self.city = [r[3] for r in rows]
        self.state = [r[4] for r in rows]
        self.points = [_xyz(r[0], r[1]) for r in rows]
        self.tree = cKDTree(self.points)
        self._country_trees = {}
        self._country_lock = threading.Lock()

    def nearest(self, xyz):
        d, i = self.tree.query(xyz)
        return int(i), float(d)

    def nearest_in(self, xyz, cc):
        """Nearest place in country ``cc`` → (index, chord) or (None, None)."""
        with self._country_lock:
            entry = self._country_trees.get(cc)
            if entry is None:
                from scipy.spatial import cKDTree
                idx = [i for i, c in enumerate(self.cc) if c == cc]
                entry = (cKDTree([self.points[i] for i in idx]), idx) if idx else (None, None)
                self._country_trees[cc] = entry
        tree, idx = entry
        if tree is None:
            return None, None
        d, j = tree.query(xyz)
        return idx[int(j)], float(d)

    def label(self, i, with_city=True):
        cc = self.cc[i]
        return _label(self.city[i] if with_city else '', self.admin1[self.state[i]],
                      self.countries.get(cc, ''), cc)


def _label(city, state, country, cc):
    city, state, country = (city or '').strip(), (state or '').strip(), (country or '').strip()
    return {
        'city': city,
        'state': state,
        'country': country,
        'country_code': (cc or '').strip().upper(),
        'place_name': ', '.join(p for p in (city, state, country) if p),
    }


def _get_places():
    global _places, _places_failed
    if _places is None and not _places_failed:
        with _places_lock:
            if _places is None and not _places_failed:
                try:
                    with gzip.open(_DATA_PATH, 'rt', encoding='utf-8') as f:
                        _places = _Places(json.load(f))
                except FileNotFoundError:
                    logger.warning('%s missing; falling back to reverse_geocode cities1000', _DATA_PATH)
                    _places_failed = True
                except Exception:
                    logger.exception('Failed to load %s; falling back to reverse_geocode', _DATA_PATH)
                    _places_failed = True
    return _places


def zone_country(lat, lon):
    """ISO country code of the timezone polygon containing the point, or ''
    (ocean, an unmapped zone, or the dataset/finder unavailable)."""
    places = _get_places()
    if places is None:
        return ''
    from .tz_utils import zone_name_for_point
    return places.tz_country.get(zone_name_for_point(lat, lon), '')


def offline_reverse_geocode(coords):
    """Reverse-geocode a list of ``(lat, lon)`` tuples to place labels.

    Returns a list parallel to ``coords`` of dicts:
        ``{city, state, country, country_code, place_name}``

    Raises ``ImportError`` if neither the bundled dataset nor the
    ``reverse_geocode`` package can be loaded — callers should catch it and
    surface a clear message rather than silently labelling nothing.
    """
    if not coords:
        return []

    places = _get_places()
    if places is None:
        return _package_reverse_geocode(coords)

    out = []
    for lat, lon in coords:
        xyz = _xyz(lat, lon)
        i, _ = places.nearest(xyz)
        tz_cc = zone_country(lat, lon)
        if tz_cc and tz_cc != places.cc[i]:
            j, chord = places.nearest_in(xyz, tz_cc)
            if j is not None:
                out.append(places.label(j, with_city=_chord_km(chord) <= _BORDER_MAX_KM))
                continue
        out.append(places.label(i))
    return out


def _package_reverse_geocode(coords):
    """The original path: the ``reverse_geocode`` package's cities1000, no
    border check. Only used when the bundled dataset is missing."""
    # Lazy import: only load the dataset / build the k-d tree when geocoding runs.
    import reverse_geocode

    if hasattr(reverse_geocode, "search"):
        raw = reverse_geocode.search(list(coords))
    else:  # very old versions only expose a single-point get()
        raw = [reverse_geocode.get(c) for c in coords]
    return [_label(r.get('city'), r.get('state'), r.get('country'), r.get('country_code'))
            for r in (r or {} for r in raw)]


def local_reverse_geocode(lat, lon):
    """Town-level reverse geocode for a single ``(lat, lon)``.

    US points resolve by **point-in-polygon** against the TIGER boundaries loaded
    into PostGIS by the ``import_boundaries`` command — i.e. the town that actually
    *contains* the point (Waldo), not the nearest centroid (Belfast). Incorporated
    places / CDPs (Charlottesville, Ruckersville) are tried first, then county
    subdivisions (New England towns like Waldo). Anything with no US boundary match
    — i.e. abroad, or before boundaries are imported — falls back to nearest-city
    offline geocoding, so international travel is still labelled.

    Returns ``{city, state, country, country_code, place_name}`` or ``None``.
    """
    try:
        from django.conf import settings
        has_postgis = "postgis" in settings.DATABASES.get("default", {}).get("ENGINE", "")
        if has_postgis:
            from django.contrib.gis.geos import Point
            from .models import Boundary

            pt = Point(lon, lat, srid=4326)
            b = (Boundary.objects.filter(kind="place", geom__contains=pt).first()
                 or Boundary.objects.filter(kind="cousub", geom__contains=pt).first())
            # A generalised (1:500k) boundary can reach a little past the
            # border; the timezone polygons are the finer test of which side
            # the point is on.
            if b and zone_country(lat, lon) not in ('', 'US'):
                b = None
            if b:
                parts = [p for p in (b.name, b.state, "United States") if p]
                return {
                    "city": b.name,
                    "state": b.state,
                    "country": "United States",
                    "country_code": "US",
                    "place_name": ", ".join(parts),
                }
    except Exception as e:
        logger.warning("Boundary lookup failed for %s,%s: %s", lat, lon, e)

    # International / no US match / boundaries not loaded yet → nearest-city offline.
    res = offline_reverse_geocode([(lat, lon)])
    return res[0] if res else None
