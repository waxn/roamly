"""Offline IP -> country resolution for the admin monitoring dashboard.

Uses the bundled geoip2fast country database (no network calls, no
external service). The database is loaded lazily on first lookup so a
gunicorn worker that never serves an admin request never pays the load
cost.
"""
import logging

logger = logging.getLogger(__name__)

_geoip = None
_geoip_failed = False


def _get_geoip():
    global _geoip, _geoip_failed
    if _geoip is None and not _geoip_failed:
        try:
            from geoip2fast import GeoIP2Fast
            _geoip = GeoIP2Fast(geoip2fast_data_file='geoip2fast-ipv6.dat.gz')
        except Exception:
            logger.exception('Failed to load geoip2fast database')
            _geoip_failed = True
    return _geoip


def country_for_ip(ip):
    """Return (country_code, country_name) for an IP, or (None, None) if
    the IP is private/invalid/unresolvable."""
    if not ip:
        return None, None
    geoip = _get_geoip()
    if geoip is None:
        return None, None
    try:
        result = geoip.lookup(ip)
    except Exception:
        return None, None
    code = (result.country_code or '').strip()
    if not code or code == '--' or getattr(result, 'is_private', False):
        return None, None
    return code, result.country_name


def country_counts(ip_counts):
    """ip_counts: iterable of (ip, count). Returns (countries, unknown_count)
    where countries is a list of {code, name, count} sorted by count desc."""
    totals = {}
    unknown = 0
    for ip, n in ip_counts:
        code, name = country_for_ip(ip)
        if code is None:
            unknown += n
            continue
        bucket = totals.setdefault(code, {'code': code, 'name': name, 'count': 0})
        bucket['count'] += n
    countries = sorted(totals.values(), key=lambda x: -x['count'])
    return countries, unknown
