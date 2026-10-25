"""Fetch a flight's track from the OpenSky Network.

The fallback for flights the user has no recording of: no phone fixes in the
air, no KML. OpenSky is free for non-commercial use, but its track endpoint
is marked experimental and only reaches back **30 days**, so this runs on
save and on a periodic sweep while a flight is still inside that window. Older
flights still need a KML.

Auth is OAuth2 client credentials: an admin creates an API client on their
OpenSky account and pastes its id and secret into Admin Panel -> Flight data.
The secret is exchanged here for a bearer token (valid 30 minutes), cached
and reused until shortly before it expires.

Credits: OpenSky meters each endpoint *family* (states, tracks, flights)
separately, each with its own daily balance (4,000/day for a standard
account; a track or departures query costs 4 within the last day, 30 for one
or two day-partitions). Every response's ``X-Rate-Limit-Remaining`` is cached
per family under REMAINING_KEY, so Admin Panel -> Flight data can show the
tracks and flights balances without spending a call to find them out.

The fetched track is stored on the Flight (``opensky_track``), never as
Location rows: it is not the user's own movement, and must not appear on their
map or count toward their distance. A recorded track (their phone, or a KML
they uploaded) always takes precedence when both exist.
"""
import logging
import math
from datetime import timedelta

import requests
from django.core.cache import cache
from django.utils import timezone

logger = logging.getLogger(__name__)

TOKEN_URL = 'https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token'
API = 'https://opensky-network.org/api'
TOKEN_KEY = 'opensky:token'
REMAINING_KEY = 'opensky:remaining'
TIMEOUT_S = 30
MAX_AGE = timedelta(days=29)      # OpenSky keeps tracks for 30 days
SETTLE = timedelta(hours=2)       # after landing, before a track is worth asking for
DEPARTURES_SETTLE = timedelta(hours=26)  # the departures list is a nightly batch
MAX_ATTEMPTS = 6
RETRY_AFTER = timedelta(hours=6)
MAX_POINTS = 20000


class OpenSkyError(Exception):
    pass


def configured(cfg):
    return bool(cfg.opensky_client_id and cfg.opensky_client_secret)


def _token(cfg, refresh=False):
    tok = None if refresh else cache.get(TOKEN_KEY)
    if tok:
        return tok
    try:
        r = requests.post(TOKEN_URL, timeout=TIMEOUT_S, data={
            'grant_type': 'client_credentials',
            'client_id': cfg.opensky_client_id,
            'client_secret': cfg.opensky_client_secret,
        })
    except requests.RequestException as e:
        raise OpenSkyError(f'Could not reach OpenSky to sign in ({e.__class__.__name__}).')
    if r.status_code in (400, 401):
        raise OpenSkyError('OpenSky rejected the client id or secret.')
    if r.status_code >= 400:
        raise OpenSkyError(f'OpenSky sign-in failed (HTTP {r.status_code}).')
    try:
        body = r.json()
        tok = body['access_token']
        ttl = int(body.get('expires_in') or 1800)
    except (ValueError, KeyError, TypeError):
        raise OpenSkyError('OpenSky sign-in returned something unreadable.')
    cache.set(TOKEN_KEY, tok, max(60, ttl - 120))
    return tok


def _family(path):
    return path.strip('/').split('/')[0]   # 'tracks', 'flights', 'states'


def _record_remaining(path, resp):
    rem = resp.headers.get('X-Rate-Limit-Remaining')
    if rem is None:
        return
    try:
        usage = dict(cache.get(REMAINING_KEY) or {})
        usage[_family(path)] = {'remaining': int(rem), 'at': timezone.now().isoformat()}
        cache.set(REMAINING_KEY, usage, None)
    except (TypeError, ValueError):
        pass


def remaining():
    """{family: {'remaining': int, 'at': iso}} from the last responses, or {}."""
    return cache.get(REMAINING_KEY) or {}


def _get(cfg, path, params):
    """GET an API path. Returns parsed JSON, None for 404, or raises."""
    for attempt in (0, 1):
        tok = _token(cfg, refresh=attempt == 1)
        try:
            r = requests.get(f'{API}{path}', params=params, timeout=TIMEOUT_S,
                             headers={'Authorization': f'Bearer {tok}'})
        except requests.RequestException as e:
            raise OpenSkyError(f'Could not reach OpenSky ({e.__class__.__name__}).')
        _record_remaining(path, r)
        if r.status_code == 401 and attempt == 0:
            continue    # token revoked or expired early: sign in again once
        if r.status_code == 404:
            return None
        if r.status_code == 429:
            wait = r.headers.get('X-Rate-Limit-Retry-After-Seconds')
            raise OpenSkyError('OpenSky credits are used up for today'
                               + (f' (retry in {int(wait) // 3600}h).' if wait and wait.isdigit() else '.'))
        if r.status_code >= 400:
            raise OpenSkyError(f'OpenSky answered with HTTP {r.status_code}.')
        try:
            return r.json()
        except ValueError:
            raise OpenSkyError('OpenSky sent a response that could not be read.')
    raise OpenSkyError('OpenSky rejected the access token.')


def test(cfg):
    """Sign in and make the cheapest call in the tracks family (a live track
    for an aircraft that isn't flying: 4 credits), which refreshes the count
    the flight fetches actually draw on."""
    cache.delete(TOKEN_KEY)
    _get(cfg, '/tracks/all', {'icao24': 'abc123', 'time': 0})
    return remaining()


# ---------------------------------------------------------------------------
# Finding and fetching a flight
# ---------------------------------------------------------------------------

def _times(flight):
    dep = flight.actual_dep or flight.sched_dep
    arr = flight.actual_arr or flight.sched_arr
    return dep, arr


def _callsigns(flight):
    """Callsigns the flight is likely to have flown under: ICAO airline
    designator + number (`UAL123`), with the IATA form as a fallback."""
    import re
    fn = (flight.flight_number or '').replace(' ', '').upper()
    # Strip the airline: three letters (ICAO, `UAL123`) or two characters
    # (IATA, which may include a digit: `B623`).
    m = re.match(r'^(?:[A-Z]{3}|[A-Z0-9]{2})(\d{1,4}[A-Z]?)$', fn)
    num = (m.group(1).lstrip('0') or m.group(1)) if m else ''
    out = set()
    if flight.airline_icao and num:
        out.add(f'{flight.airline_icao}{num}')
    if fn:
        out.add(fn)
    return out


def find_icao24(cfg, flight):
    """The aircraft's icao24 from OpenSky's departures list, or ''."""
    dep, arr = _times(flight)
    if not dep or not flight.dep_icao:
        return ''
    begin = int((dep - timedelta(hours=2)).timestamp())
    end = int((dep + timedelta(hours=2)).timestamp())
    rows = _get(cfg, '/flights/departure', {'airport': flight.dep_icao, 'begin': begin, 'end': end}) or []
    want = _callsigns(flight)
    best, best_key = '', None
    for row in rows if isinstance(rows, list) else []:
        cs = (row.get('callsign') or '').strip().upper()
        hit_cs = cs in want
        hit_arr = bool(flight.arr_icao) and row.get('estArrivalAirport') == flight.arr_icao
        if not (hit_cs or hit_arr):
            continue
        seen = row.get('firstSeen') or begin
        key = (not hit_cs, not hit_arr, abs(seen - dep.timestamp()))
        if best_key is None or key < best_key:
            best, best_key = (row.get('icao24') or '').lower(), key
    return best


def _clean_path(path):
    out = []
    for p in path or []:
        try:
            t, lat, lon = int(p[0]), float(p[1]), float(p[2])
        except (TypeError, ValueError, IndexError):
            continue
        if not (-90 <= lat <= 90 and -180 <= lon <= 180) or math.isnan(lat) or math.isnan(lon):
            continue
        try:   # any number: a restore's streamed JSON yields Decimal
            alt = round(float(p[3])) if len(p) > 3 and p[3] is not None else None
        except (TypeError, ValueError):
            alt = None
        out.append([t, round(lat, 5), round(lon, 5), alt])
    return out[:MAX_POINTS]


def fetch(flight, cfg=None, force=False):
    """Fetch and store the OpenSky track for `flight`. Returns its status.

    Without `force`, a flight that already has a recorded or fetched track,
    is outside OpenSky's window, or was tried recently is left alone.
    """
    from .models import SiteConfig
    cfg = cfg or SiteConfig.load()
    now = timezone.now()

    def done(status, message='', track=None, attempt=True):
        flight.opensky_status = status
        flight.opensky_message = message[:200]
        flight.opensky_checked_at = now
        fields = ['opensky_status', 'opensky_message', 'opensky_checked_at', 'icao24']
        if attempt:
            flight.opensky_attempts = (flight.opensky_attempts or 0) + 1
            fields.append('opensky_attempts')
        if track is not None:
            flight.opensky_track = track
            fields.append('opensky_track')
        type(flight).objects.filter(pk=flight.pk).update(**{f: getattr(flight, f) for f in fields})
        return status

    if not configured(cfg):
        return done('', 'OpenSky is not set up on this instance.', attempt=False)
    dep, arr = _times(flight)
    when = arr or (dep + timedelta(hours=3) if dep else None)
    if when is None:
        return done('error', 'The flight needs a departure or arrival time to find its track.', attempt=False)
    if now - when > MAX_AGE:
        return done('too_old', 'OpenSky only keeps tracks for 30 days. Upload a KML instead.', attempt=False)
    if now - when < SETTLE:
        return done('pending', 'Waiting for the flight to land before asking OpenSky.', attempt=False)

    try:
        if not flight.icao24:
            if now - (dep or when) < DEPARTURES_SETTLE and not force:
                return done('pending', "OpenSky's departure lists are published the next day; will retry.",
                            attempt=False)
            flight.icao24 = find_icao24(cfg, flight)
            if not flight.icao24:
                return done('no_aircraft', "OpenSky has no aircraft matching this flight. Add the "
                            "aircraft's Mode S hex under Advanced, or upload a KML.")
        mid = dep + (arr - dep) / 2 if dep and arr else when - timedelta(hours=1)
        body = _get(cfg, '/tracks/all', {'icao24': flight.icao24, 'time': int(mid.timestamp())})
    except OpenSkyError as e:
        return done('error', str(e))

    track = _clean_path((body or {}).get('path'))
    if len(track) < 2:
        return done('none', 'OpenSky has no track for this flight.', track=None)
    return done('ok', f'{len(track)} waypoints from OpenSky.', track=track)


def due(flight, now=None):
    """Whether the periodic sweep should (re)try this flight."""
    now = now or timezone.now()
    if flight.track_device_id or flight.opensky_track:
        return False
    if flight.opensky_status in ('ok', 'too_old'):
        return False
    if (flight.opensky_attempts or 0) >= MAX_ATTEMPTS:
        return False
    if flight.opensky_checked_at and now - flight.opensky_checked_at < RETRY_AFTER \
            and flight.opensky_status != 'pending':
        return False
    dep, arr = _times(flight)
    when = arr or (dep + timedelta(hours=3) if dep else None)
    return bool(when) and SETTLE <= now - when <= MAX_AGE


def kick(flight_id):
    """Fetch in the background after a save, so the save never waits on OpenSky."""
    from .scheduler_utils import db_thread

    def run():
        from .models import Flight
        f = Flight.objects.filter(pk=flight_id).first()
        if f and due(f):
            try:
                fetch(f)
            except Exception:
                logger.exception('OpenSky fetch failed for flight %s', flight_id)

    db_thread(run, name='opensky-fetch').start()


# ---------------------------------------------------------------------------
# Periodic sweep
# ---------------------------------------------------------------------------

SWEEP_EVERY_S = 3 * 3600
_LOCK_NAMESPACE = 0x52414d4b  # 'RAMK' — distinct from every other scheduler's tag
_scheduler_thread = None


def _sweep():
    from .models import Flight, SiteConfig
    cfg = SiteConfig.load()
    if not configured(cfg):
        return
    now = timezone.now()
    lo = (now - MAX_AGE).date() - timedelta(days=1)
    for f in Flight.objects.filter(date__gte=lo, track_device__isnull=True,
                                   opensky_track__isnull=True).order_by('date')[:300]:
        if not due(f, now):
            continue
        try:
            status = fetch(f, cfg)
        except Exception:
            logger.exception('OpenSky fetch failed for flight %s', f.pk)
            continue
        if status == 'error' and 'credits' in (f.opensky_message or ''):
            break   # out of credits for today: stop asking


def _scheduler_loop():
    import time
    from django.db import close_old_connections, connection
    from .scheduler_utils import wait_for_app_registry
    wait_for_app_registry()
    time.sleep(120)
    while True:
        try:
            close_old_connections()
            locked = True
            if connection.vendor == 'postgresql':
                with connection.cursor() as c:
                    c.execute('SELECT pg_try_advisory_lock(%s, 0)', [_LOCK_NAMESPACE])
                    locked = c.fetchone()[0]
            if locked:
                try:
                    _sweep()
                finally:
                    if connection.vendor == 'postgresql':
                        with connection.cursor() as c:
                            c.execute('SELECT pg_advisory_unlock(%s, 0)', [_LOCK_NAMESPACE])
        except Exception:
            logger.exception('OpenSky sweep failed')
        time.sleep(SWEEP_EVERY_S)


def start_opensky_scheduler():
    import threading
    global _scheduler_thread
    if _scheduler_thread is not None and _scheduler_thread.is_alive():
        return
    _scheduler_thread = threading.Thread(target=_scheduler_loop, daemon=True, name='opensky-sweep')
    _scheduler_thread.start()
    logger.info('OpenSky track scheduler started')
