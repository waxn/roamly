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
is in a different country from the point's zone, candidates are restricted to
the zone's country — or, if none is within ``_BORDER_MAX_KM`` and a town across
the border is nearer, the label is just that country's state and name with no
town, rather than a town the point was never in. Ocean (``Etc/*``) and unmapped
zones skip the check.

**Town size.** A town of 1000+ within ``_TOWN_PREFER_KM`` wins over a nearer
hamlet; smaller places are used only when no town is that close. See
``_TOWN_MIN_POP``.

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

# Towns of this size are preferred whenever one is within _TOWN_PREFER_KM.
# cities500 adds hamlets of a few hundred people right next to the town people
# actually name (Silverdale beside Sellersville, Pennsylvania), and nearest-only
# matching would hand every point between them to whichever centre is closer.
# Below this size a place is used only when no town is that close — which is
# what Campobello Island (villages of a few hundred, the nearest Canadian town
# 20 km over the water) needs. 1000 is the old cities1000 cut-off, so labels
# where a town is close come out as they always did.
_TOWN_MIN_POP = 1000
_TOWN_PREFER_KM = 8.0

# How far the nearest place in the point's own country may be when nothing
# bigger is close. Past it the town is left blank if the only nearer town is in
# another country: across a strait, that is a different community altogether.
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
        self.countries = data['countries']
        self.admin1 = data['admin1']
        self.tz_country = data['tz_country']
        rows = data['rows']
        self.cc = [r[2] for r in rows]
        self.city = [r[3] for r in rows]
        self.state = [r[4] for r in rows]
        self.pop = [r[5] if len(r) > 5 else 0 for r in rows]
        self.points = [_xyz(r[0], r[1]) for r in rows]
        self._trees = {}
        self._trees_lock = threading.Lock()

    def _tree(self, cc, big):
        """k-d tree over the places of country ``cc`` (None = everywhere),
        only those of ``_TOWN_MIN_POP``+ people when ``big``. Built on first use."""
        key = (cc, big)
        with self._trees_lock:
            entry = self._trees.get(key)
            if entry is None:
                from scipy.spatial import cKDTree
                idx = [i for i in range(len(self.cc))
                       if (cc is None or self.cc[i] == cc) and (not big or self.pop[i] >= _TOWN_MIN_POP)]
                entry = (cKDTree([self.points[i] for i in idx]), idx) if idx else (None, None)
                self._trees[key] = entry
        return entry

    def nearest(self, xyz, cc=None, big=False):
        """Nearest place → (index, km), or (None, None) if there is none."""
        tree, idx = self._tree(cc, big)
        if tree is None:
            return None, None
        d, j = tree.query(xyz)
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
        ``{city, state, country, country_code, place_name, source, zone_cc}``

    ``source`` is ``'nearest'``: a nearest-place guess (see ``_nearest_label``
    and ``regeocode``). ``zone_cc`` is the timezone polygon's country, or ''
    when unknown.

    Raises ``ImportError`` if neither the bundled dataset nor the
    ``reverse_geocode`` package can be loaded — callers should catch it and
    surface a clear message rather than silently labelling nothing.
    """
    if not coords:
        return []

    places = _get_places()
    if places is None:
        return _package_reverse_geocode(coords)
    return [_nearest_label(places, lat, lon, zone_country(lat, lon)) for lat, lon in coords]


def _nearest_label(places, lat, lon, tz_cc):
    """Label a point from the place list. ``tz_cc`` is its timezone country
    ('' over the ocean or where unknown), which every candidate must share."""
    xyz = _xyz(lat, lon)
    cc = tz_cc or None

    # 1. A town of 1000+ close by, in the point's own country.
    town, town_km = places.nearest(xyz, cc, big=True)
    if town is not None and (town_km <= _TOWN_PREFER_KM or cc is None):
        return dict(places.label(town), source='nearest', zone_cc=tz_cc)

    # 2. Any place close by, however small (Campobello Island).
    small, small_km = places.nearest(xyz, cc, big=False)
    if small is not None and small_km <= _BORDER_MAX_KM:
        return dict(places.label(small), source='nearest', zone_cc=tz_cc)

    # 3. Nothing close. The nearest town in this country is fine — unless a
    #    town in another country is nearer, in which case name only the state,
    #    rather than a town across the border or the water.
    anywhere, anywhere_km = places.nearest(xyz, None, big=True)
    if town is not None and not (anywhere is not None and places.cc[anywhere] != cc
                                 and anywhere_km < town_km):
        return dict(places.label(town), source='nearest', zone_cc=tz_cc)
    if small is not None:
        return dict(places.label(small, with_city=False), source='nearest', zone_cc=tz_cc)
    return dict(places.label(anywhere), source='nearest', zone_cc=tz_cc)


def v082_guess(lat, lon, tz_cc):
    """The label v0.82.0 gave a point: the nearest place of any size, swapped
    for the nearest in the timezone's country across a border. Kept only so a
    re-geocode can recognise — and replace — labels that release wrote, since
    it preferred hamlets over the towns people name (see ``regeocode``)."""
    places = _get_places()
    if places is None:
        return None
    xyz = _xyz(lat, lon)
    i, _ = places.nearest(xyz)
    if tz_cc and tz_cc != places.cc[i]:
        j, km = places.nearest(xyz, tz_cc)
        if j is not None:
            return places.label(j, with_city=km <= _BORDER_MAX_KM)
    return places.label(i)


def _package_reverse_geocode(coords):
    """The original path: the ``reverse_geocode`` package's cities1000, no
    border check. Only used when the bundled dataset is missing."""
    # Lazy import: only load the dataset / build the k-d tree when geocoding runs.
    import reverse_geocode

    if hasattr(reverse_geocode, "search"):
        raw = reverse_geocode.search(list(coords))
    else:  # very old versions only expose a single-point get()
        raw = [reverse_geocode.get(c) for c in coords]
    return [dict(_label(r.get('city'), r.get('state'), r.get('country'), r.get('country_code')),
                 source='nearest', zone_cc='')
            for r in (r or {} for r in raw)]


def local_reverse_geocode(lat, lon):
    """Town-level reverse geocode for a single ``(lat, lon)``.

    US points resolve by **point-in-polygon** against the TIGER boundaries loaded
    into PostGIS by the ``import_boundaries`` command — i.e. the town that actually
    *contains* the point (Waldo), not the nearest centroid (Belfast or Morrill).
    Incorporated places / CDPs (Charlottesville, Ruckersville) are tried first,
    then county subdivisions (New England towns like Waldo). Anything with no US
    boundary match — abroad, or before boundaries are imported — falls back to
    the nearest place, so international travel is still labelled.

    Returns ``{city, state, country, country_code, place_name, source, zone_cc}``
    or ``None``. ``source`` is ``'boundary'`` for a containment match, the one
    answer that is authoritative; see ``offline_reverse_geocode`` for the rest.
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

    # International / no US match / boundaries not loaded yet → nearest place.
    places = _get_places()
    if places is not None:
        return _nearest_label(places, lat, lon, zone_cc)
    res = offline_reverse_geocode([(lat, lon)])
    return res[0] if res else None
