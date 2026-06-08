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
"""
import logging

logger = logging.getLogger(__name__)


def offline_reverse_geocode(coords):
    """Reverse-geocode a list of ``(lat, lon)`` tuples to place labels.

    Returns a list parallel to ``coords`` of dicts:
        ``{city, state, country, country_code, place_name}``

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

    out = []
    for r in raw:
        r = r or {}
        city = (r.get("city") or "").strip()
        state = (r.get("state") or "").strip()
        country = (r.get("country") or "").strip()
        cc = (r.get("country_code") or "").strip().upper()
        parts = [p for p in (city, state, country) if p]
        out.append({
            "city": city,
            "state": state,
            "country": country,
            "country_code": cc,
            "place_name": ", ".join(parts),
        })
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
