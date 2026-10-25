"""Look a flight up on AeroDataBox by number and local departure date.

AeroDataBox is a paid API (RapidAPI or API.Market, both with a small free
tier). The key is an instance-wide admin setting on SiteConfig — masked like
the Turnstile secret, because it is a server-side secret that costs money
when it leaks — and is never sent to a browser.

``lookup(number, date)`` returns ``(legs, error)``. Each leg is already in the
shape the Flights API accepts, so the page can hand one straight back to
``POST /api/flights/``. Answers are cached for 30 days: a flight that has
already happened does not change, and the free tier is a few hundred calls a
month.
"""
import logging
from datetime import datetime, timezone as dt_timezone

import requests
from django.core.cache import cache

logger = logging.getLogger(__name__)

PROVIDERS = {
    'rapidapi': {
        'base': 'https://aerodatabox.p.rapidapi.com',
        'headers': lambda key: {'X-RapidAPI-Key': key,
                                'X-RapidAPI-Host': 'aerodatabox.p.rapidapi.com'},
    },
    'apimarket': {
        'base': 'https://prod.api.market/api/v1/aedbx/aerodatabox',
        # API.Market's gateway header; both spellings are sent since the
        # marketplace has used each, and an unknown header is simply ignored.
        'headers': lambda key: {'x-magicapi-key': key, 'x-api-market-key': key},
    },
}
TIMEOUT_S = 20
CACHE_TTL = 30 * 86400


def _parse_time(v):
    """AeroDataBox writes '2026-05-01 08:15Z' (or '...+02:00'). -> aware UTC ISO."""
    if not v:
        return None
    if isinstance(v, dict):
        v = v.get('utc')
        if not v:
            return None
    try:
        dt = datetime.fromisoformat(str(v).strip().replace(' ', 'T').replace('Z', '+00:00'))
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=dt_timezone.utc)
    return dt.astimezone(dt_timezone.utc).isoformat()


def _when(mov, name):
    # Current responses nest {utc, local}; older ones flatten to <name>Utc.
    return _parse_time(mov.get(name)) or _parse_time(mov.get(f'{name}Utc'))


def _airport(side, mov):
    ap = mov.get('airport') or {}
    loc = ap.get('location') or {}
    return {
        f'{side}_iata': (ap.get('iata') or '')[:3],
        f'{side}_icao': (ap.get('icao') or '')[:4],
        f'{side}_name': ap.get('name') or '',
        f'{side}_city': ap.get('municipalityName') or '',
        f'{side}_country': (ap.get('countryCode') or '')[:2].upper(),
        f'{side}_lat': loc.get('lat'),
        f'{side}_lon': loc.get('lon'),
        f'{side}_tz': ap.get('timeZone') or '',
    }


def _leg(raw, number, date_str):
    dep = raw.get('departure') or {}
    arr = raw.get('arrival') or {}
    airline = raw.get('airline') or {}
    aircraft = raw.get('aircraft') or {}
    leg = {
        'flight_number': ''.join((raw.get('number') or number).split()).upper(),
        'date': date_str,
        'airline_name': airline.get('name') or '',
        'airline_iata': airline.get('iata') or '',
        'airline_icao': airline.get('icao') or '',
        'aircraft_model': aircraft.get('model') or '',
        'registration': aircraft.get('reg') or '',
        # The transponder address OpenSky indexes tracks by.
        'icao24': (aircraft.get('modeS') or '').strip().lower()[:6],
        'sched_dep': _when(dep, 'scheduledTime'),
        'sched_arr': _when(arr, 'scheduledTime'),
        # Wheels-off/on beats a revised estimate, which beats nothing.
        'actual_dep': _when(dep, 'runwayTime') or _when(dep, 'revisedTime'),
        'actual_arr': _when(arr, 'runwayTime') or _when(arr, 'revisedTime'),
        'status': raw.get('status') or '',
        'source': 'aerodatabox',
    }
    leg.update(_airport('dep', dep))
    leg.update(_airport('arr', arr))
    return leg


def configured(cfg):
    return bool(cfg.aerodatabox_key) and cfg.aerodatabox_provider in PROVIDERS


def lookup(cfg, number, date_str, use_cache=True):
    """Return (legs, error). `error` is a user-readable string or None."""
    if not configured(cfg):
        return [], 'Flight lookup is not set up on this instance.'
    key = f'flightlookup:{number}:{date_str}'
    if use_cache:
        hit = cache.get(key)
        if hit is not None:
            return hit, None

    prov = PROVIDERS[cfg.aerodatabox_provider]
    url = f"{prov['base']}/flights/number/{number}/{date_str}"
    try:
        r = requests.get(url, headers=prov['headers'](cfg.aerodatabox_key),
                         params={'withAircraftImage': 'false', 'withLocation': 'false'},
                         timeout=TIMEOUT_S)
    except requests.Timeout:
        return [], 'AeroDataBox did not answer in time. Try again in a moment.'
    except requests.RequestException as e:
        logger.warning('AeroDataBox unreachable: %s', e)
        return [], 'Could not reach AeroDataBox.'

    if r.status_code in (401, 403):
        return [], 'AeroDataBox rejected the API key (ask your admin to check it).'
    if r.status_code == 429:
        return [], 'The AeroDataBox quota is used up for now. Fill the flight in by hand.'
    if r.status_code in (204, 404):
        cache.set(key, [], CACHE_TTL)
        return [], None
    if r.status_code >= 400:
        return [], f'AeroDataBox answered with an error (HTTP {r.status_code}).'
    try:
        body = r.json()
    except ValueError:
        return [], 'AeroDataBox sent a response that could not be read.'
    raws = body if isinstance(body, list) else [body]
    legs = [_leg(x, number, date_str) for x in raws if isinstance(x, dict)]
    cache.set(key, legs, CACHE_TTL)
    return legs, None
