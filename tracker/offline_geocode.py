"""Offline reverse geocoding.

City/state/country labelling for tracked points, done entirely on the server
against a bundled GeoNames cities dataset (via the `reverse_geocode` package) —
no network call, no rate limits, no Nominatim `429` blocks. A continuous tracker
generates far more points than the public Nominatim usage policy allows (which is
why its IP gets blocked); the data Roamly stores is city-level (Nominatim was only
ever queried at zoom=10), which offline nearest-city matching reproduces exactly.

The k-d tree over the cities dataset is built lazily on first use and cached for
the process lifetime by the underlying package, so only a process that actually
geocodes pays the (~one-off, couple-second) load cost.

**Border check** — the one addition to the original nearest-city matching.
Nearest-city knows nothing about borders: the villages of Campobello Island, New
Brunswick are too small for the package's `cities1000`, so a drive round the
island was labelled after the nearest town it did have — Eastport, Maine, in
another country. The timezone polygons ``timezonefinder`` already loads for
``tz_utils`` follow real borders (Campobello keeps Atlantic time,
America/Moncton; Eastport is America/New_York), and IANA's zone.tab maps every
land zone to one country. Only when the nearest city is in a *different country*
from the point's zone does anything change: the label becomes the nearest place
in the zone's country from ``tracker/data/places.json.gz`` (GeoNames
`cities500`, built by ``scripts/build_cities.py``, so the small places a border
crossing needs are there), or — if none is within ``_BORDER_MAX_KM`` — just that
country's state and name with no town. Every other point is labelled exactly as
before; the smaller-places list is deliberately *not* used for them, because a
nearer hamlet then beats the town people actually name (Silverdale over
Sellersville). Ocean (``Etc/*``) and unmapped zones skip the check.
"""
import gzip
import json
import logging
import math
import os
import threading

logger = logging.getLogger(__name__)

_DATA_PATH = os.path.join(os.path.dirname(__file__), 'data', 'places.json.gz')

# How far the nearest place in the point's own country may be when the border
# check swaps countries. Past it the town is left blank: across a strait, the
# nearest town in the right country is usually a different community altogether.
_BORDER_MAX_KM = 12.0

_EARTH_KM = 6371.0088

_places = None
_places_failed = False
_places_lock = threading.Lock()


def _xyz(lat, lon):
    """Unit-sphere coordinates, so a k-d tree's Euclidean nearest neighbour is
    the true nearest place."""
    la, lo = math.radians(lat), math.radians(lon)
    c = math.cos(la)
    return (c * math.cos(lo), c * math.sin(lo), math.sin(la))


def _chord_km(chord):
    return 2 * _EARTH_KM * math.asin(min(1.0, chord / 2))


class _Places:
    """The border-check dataset: places by country, and timezone → country."""

    def __init__(self, data):
        self.countries = data['countries']
        self.admin1 = data['admin1']
        self.tz_country = data['tz_country']
        rows = data['rows']
        self.cc = [r[2] for r in rows]
        self.city = [r[3] for r in rows]
        self.state = [r[4] for r in rows]
        self.points = [_xyz(r[0], r[1]) for r in rows]
        self._trees = {}
        self._trees_lock = threading.Lock()

    def nearest_in(self, lat, lon, cc):
        """Nearest place in country ``cc`` → (index, km), or (None, None)."""
        with self._trees_lock:
            entry = self._trees.get(cc)
            if entry is None:
                from scipy.spatial import cKDTree
                idx = [i for i, c in enumerate(self.cc) if c == cc]
                entry = (cKDTree([self.points[i] for i in idx]), idx) if idx else (None, None)
                self._trees[cc] = entry
        tree, idx = entry
        if tree is None:
            return None, None
        d, j = tree.query(_xyz(lat, lon))
        return idx[int(j)], _chord_km(float(d))

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
                except Exception:
                    logger.exception('Failed to load %s; the border check is off', _DATA_PATH)
                    _places_failed = True
    return _places


def country_by_name(name):
    """(canonical name, ISO code) for a country name, or None.

    The spelling stored labels use, so a typed country matches them.
    """
    places = _get_places()
    if places is None:
        return None
    wanted = (name or '').strip().casefold()
    for cc, country in places.countries.items():
        if country.casefold() == wanted:
            return country, cc
    return None


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
        ``{city, state, country, country_code, place_name, zone_cc}``

    ``zone_cc`` is the point's timezone country ('' when unknown), which a
    re-geocode uses to recognise a stored label on the wrong side of a border.

    Raises ``ImportError`` if the offline geocoder package isn't installed yet
    (i.e. the image hasn't been rebuilt with the new requirement) — callers should
    catch it and surface a clear message rather than silently labelling nothing.
    """
    if not coords:
        return []

    # Lazy import: only load the dataset / build the k-d tree when geocoding runs,
    # never at module import (keeps web worker startup and the request path light).
    import reverse_geocode

    if hasattr(reverse_geocode, "search"):
        raw = reverse_geocode.search(list(coords))
    else:  # very old versions only expose a single-point get()
        raw = [reverse_geocode.get(c) for c in coords]

    places = _get_places()
    out = []
    for (lat, lon), r in zip(coords, raw):
        r = r or {}
        res = _label(r.get("city"), r.get("state"), r.get("country"), r.get("country_code"))
        zone_cc = zone_country(lat, lon)
        if places is not None and zone_cc and res['country_code'] and zone_cc != res['country_code']:
            j, km = places.nearest_in(lat, lon, zone_cc)
            if j is not None:
                res = places.label(j, with_city=km <= _BORDER_MAX_KM)
        res['zone_cc'] = zone_cc
        out.append(res)
    return out


def local_reverse_geocode(lat, lon):
    """Town-level reverse geocode for a single ``(lat, lon)``.

    US points resolve by **point-in-polygon** against the TIGER boundaries loaded
    into PostGIS by the ``import_boundaries`` command — i.e. the town that actually
    *contains* the point (Waldo), not the nearest centroid (Belfast). Incorporated
    places / CDPs (Charlottesville, Ruckersville) are tried first, then county
    subdivisions (New England towns like Waldo). Anything with no US boundary match
    — i.e. abroad, or before boundaries are imported — falls back to nearest-city
    offline geocoding, so international travel is still labelled.

    Returns ``{city, state, country, country_code, place_name, source, zone_cc}``
    or ``None``. ``source`` is ``'boundary'`` for a containment match and
    ``'nearest'`` otherwise — a guess, which a re-geocode never lets overwrite a
    stored label (see ``regeocode_tasks``).
    """
    zone_cc = zone_country(lat, lon)
    try:
        from django.conf import settings
        has_postgis = "postgis" in settings.DATABASES.get("default", {}).get("ENGINE", "")
        # A generalised (1:500k) boundary can reach a little past the border;
        # the timezone polygons are the finer test of which side the point is
        # on, so a point another country's zone claims skips the US lookup.
        if has_postgis and zone_cc in ('', 'US'):
            from django.contrib.gis.geos import Point
            from .models import Boundary

            pt = Point(lon, lat, srid=4326)
            b = (Boundary.objects.filter(kind="place", geom__contains=pt).first()
                 or Boundary.objects.filter(kind="cousub", geom__contains=pt).first())
            if b:
                parts = [p for p in (b.name, b.state, "United States") if p]
                return {
                    "city": b.name,
                    "state": b.state,
                    "country": "United States",
                    "country_code": "US",
                    "place_name": ", ".join(parts),
                    "source": "boundary",
                    "zone_cc": zone_cc,
                }
    except Exception as e:
        logger.warning("Boundary lookup failed for %s,%s: %s", lat, lon, e)

    # International / no US match / boundaries not loaded yet → nearest-city offline.
    res = offline_reverse_geocode([(lat, lon)])
    if not res:
        return None
    return dict(res[0], source='nearest')
