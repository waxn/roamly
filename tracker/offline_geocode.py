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
