"""Single point of contact with Overpass for the road / subway / POI downloads.

Every Overpass request the app makes goes through `overpass_query`. Before this
module the request code was triplicated across `road_download_tasks`,
`rail_download_tasks` and `poi_tasks`, each with its own copy of the exception
handling and its own timeout constants — which is exactly how POI's drifted to a
far tighter 30s/35s than the roads' 60s/80s, and made POI time out against any
mirror slower than a few seconds.

Two ideas do the real work here.

**Failure classification.** A request can fail three ways, and they want three
different responses from the caller:

``http``
    Overpass was reached and answered with an error status (429 rate limit, 504
    gateway timeout). A *smaller* query is genuinely less likely to hit the same
    limit, so the caller should retry and then bisect.
``timeout``
    An endpoint was reached but produced no usable answer in time. Also worth
    retrying and shrinking — a smaller query genuinely finishes faster. This
    covers more than literal timeouts: a connection dropped mid-response is the
    same situation (reached, no usable answer), so it lands here too.
``unreachable``
    The request never got out the door — DNS failure, connection refused, no
    route, TLS failure. Every leaf of a bisection tree would fail identically,
    so the caller must fail fast instead of subdividing.

Collapsing ``timeout`` into ``unreachable`` — which is what the old two-branch
``except HTTPError: ... except Exception: ...`` in each downloader did — is what
made a merely *slow* mirror report as a total outage. Two slow batches tripped
the downloaders' ``CONNECTIVITY_FAIL_LIMIT`` circuit breaker, which counts every
remaining batch as failed, which made the admin panel print "could not reach
Overpass for any area" about a server that could reach Overpass perfectly well.

**Endpoint failover.** Requests walk a pool of endpoints, advancing to the next
one only on ``timeout``/``unreachable`` — never on ``http``, because there the
server *did* answer and asking three more mirrors the same oversized question
only earns three more rate limits. The endpoint that last worked is remembered
so a run doesn't re-pay a dead endpoint's failure cost on every request.

Crucially, a query is only reported ``unreachable`` when **every** endpoint was
unreachable. If even one was reached-but-slow, the aggregate is ``timeout``, so
the caller's connectivity circuit breaker stays out of it.
"""

import errno
import http.client
import json
import logging
import socket
import ssl
import urllib.error
import urllib.parse
import urllib.request
from typing import NamedTuple, Optional

from django.conf import settings as django_settings
from django.core.cache import cache

logger = logging.getLogger(__name__)

HEADERS = {'User-Agent': 'Roamly (self-hosted location tracker)'}

# One timeout regime for all three downloaders, so they cannot drift apart
# again. QUERY_TIMEOUT goes into the ``[out:json][timeout:N]`` header the caller
# builds; SOCKET_TIMEOUT is how long we wait on the socket, deliberately *above*
# it so Overpass's own timeout fires first and we get a real 504 — an ``http``
# kind, worth bisecting — rather than a blind client-side cutoff that tells us
# nothing about why the query was too big.
QUERY_TIMEOUT = 60
SOCKET_TIMEOUT = QUERY_TIMEOUT + 20

# Endpoints tried in order when nothing else is configured. The official
# instance (overpass-api.de) is deliberately absent: it enforces a fair-use
# policy and refuses connections outright from an IP it has judged abusive, so
# for an instance in that position it is a guaranteed failed attempt on every
# request. Add it back at the top of the list from Admin Panel -> Downloads if
# this server can reach it.
DEFAULT_POOL = [
    'https://overpass.kumi.systems/api/interpreter',
    'https://overpass.private.coffee/api/interpreter',
    'https://maps.mail.ru/osm/tools/overpass/api/interpreter',
    'https://overpass.osm.ch/api/interpreter',
]

# Which endpoint last answered. Cached so every gunicorn worker benefits, with a
# process-local mirror so a cache outage still saves the walk within one worker.
LAST_GOOD_CACHE_KEY = 'overpass:last_good'
_LAST_GOOD_TTL = 3600
_last_good = None

_HTTP_NOTES = {
    429: ' (rate limited)',
    503: ' (service unavailable)',
    504: ' (gateway timeout)',
}

# errnos that mean the packet never had anywhere to go.
_UNREACHABLE_ERRNOS = {
    errno.ENETUNREACH, errno.EHOSTUNREACH, errno.ENETDOWN,
    errno.EHOSTDOWN, errno.EADDRNOTAVAIL, errno.ECONNREFUSED,
}


class OverpassResult(NamedTuple):
    """Outcome of one `overpass_query` call.

    ``kind`` is None on success, else one of 'http' / 'timeout' / 'unreachable'
    (see the module docstring for what each obliges the caller to do).
    """
    data: Optional[dict]
    kind: Optional[str]
    error: str
    endpoint: str
    status: Optional[int]

    @property
    def ok(self):
        return self.kind is None


def endpoints():
    """The configured endpoint pool, in order, deduped."""
    urls = list(getattr(django_settings, 'OVERPASS_URLS', None) or DEFAULT_POOL)
    return list(dict.fromkeys(u.strip() for u in urls if u and u.strip()))


def _get_last_good():
    global _last_good
    try:
        cached = cache.get(LAST_GOOD_CACHE_KEY)
        if cached:
            _last_good = cached
    except Exception:
        pass
    return _last_good


def _remember_good(url):
    global _last_good
    _last_good = url
    try:
        cache.set(LAST_GOOD_CACHE_KEY, url, _LAST_GOOD_TTL)
    except Exception:
        pass


def _ordered_endpoints():
    """The pool with the last endpoint that worked moved to the front."""
    pool = endpoints()
    good = _get_last_good()
    if good and good in pool:
        return [good] + [u for u in pool if u != good]
    return pool


def _classify_reason(r):
    """Classify a raw exception, or a URLError's ``.reason`` payload.

    Shared by both so the URLError-wrapped and directly-raised forms of the same
    underlying failure can never be classified differently.
    """
    if isinstance(r, TimeoutError):
        return 'timeout', 'timed out waiting for a response'
    if isinstance(r, (socket.gaierror, socket.herror)):
        return 'unreachable', f'DNS lookup failed: {r}'
    if isinstance(r, ConnectionRefusedError):
        return 'unreachable', 'connection refused'
    if isinstance(r, ssl.SSLCertVerificationError):
        return 'unreachable', f'TLS certificate verification failed: {r}'
    if isinstance(r, ssl.SSLError):
        return 'unreachable', f'TLS error: {r}'
    # Reached, started answering, then died. Not a timeout by name, but the same
    # situation for the caller: an endpoint that produced no usable answer, and
    # a smaller query may well complete.
    if isinstance(r, (ConnectionResetError, BrokenPipeError,
                      http.client.RemoteDisconnected, http.client.IncompleteRead)):
        return 'timeout', 'connection dropped mid-response'
    if isinstance(r, http.client.HTTPException):
        return 'timeout', f'malformed response: {type(r).__name__}'
    # An error page or a captive proxy answering instead of Overpass. The server
    # answered, so this is 'http' — failing over would hide a misconfigured URL.
    if isinstance(r, (json.JSONDecodeError, UnicodeDecodeError)):
        return 'http', 'endpoint returned non-JSON (an error page or a proxy?)'
    if isinstance(r, OSError):
        num = getattr(r, 'errno', None)
        if num == errno.ETIMEDOUT:
            return 'timeout', 'connection timed out'
        if num in _UNREACHABLE_ERRNOS:
            name = errno.errorcode.get(num, str(num))
            return 'unreachable', f'{name}: {r.strerror or r}'
    # urllib sometimes hands back a bare string reason.
    if isinstance(r, str):
        return ('timeout', r) if 'timed out' in r.lower() else ('unreachable', r)
    # Conservative default: treat a genuinely novel failure as unreachable, which
    # preserves the pre-existing fast-fail behaviour rather than optimistically
    # retrying something we do not understand.
    return 'unreachable', f'{type(r).__name__}: {r}'


def classify(exc):
    """Map a failed request attempt to ``(kind, one-line message)``.

    An explicit ordered isinstance chain rather than a stack of ``except``
    clauses, so the two ordering traps are visible right where they matter
    instead of being implicit in clause order:

    * ``HTTPError`` is a subclass of ``URLError`` — test it first, or every HTTP
      status is misread as a connection failure.
    * ``socket.timeout`` *is* ``TimeoutError`` on Python 3.10+, and
      ``TimeoutError`` subclasses ``OSError`` — test it before any generic errno
      handling, or every read timeout falls through to the conservative default
      and gets called unreachable. That misreading is the whole bug this module
      exists to fix.
    """
    if isinstance(exc, urllib.error.HTTPError):
        note = _HTTP_NOTES.get(exc.code, '')
        return 'http', f'HTTP {exc.code} {exc.reason}{note}'
    if isinstance(exc, TimeoutError):
        return 'timeout', 'timed out waiting for a response'
    if isinstance(exc, urllib.error.URLError):
        return _classify_reason(exc.reason)
    return _classify_reason(exc)


def _post(url, query, timeout):
    """POST one Overpass QL query and return the parsed JSON. Raises on failure."""
    body = urllib.parse.urlencode({'data': query}).encode()
    req = urllib.request.Request(url, body, HEADERS)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def overpass_query(query, timeout=SOCKET_TIMEOUT, beat=None):
    """Run `query` against the endpoint pool, failing over as needed.

    `beat` is the caller's liveness callback and is invoked before *every*
    endpoint attempt, not once per call: a failover walk over a four-endpoint
    pool can cost 4 x SOCKET_TIMEOUT, which is well past the downloaders'
    STALE_AFTER_S, and a status poll served by another gunicorn worker would
    otherwise conclude the run had died and start a second one.
    """
    pool = _ordered_endpoints()
    if not pool:
        return OverpassResult(None, 'unreachable',
                              'no Overpass endpoints configured', '', None)

    kinds = []
    last_msg = ''
    last_url = ''
    for url in pool:
        if beat:
            try:
                beat()
            except Exception:
                pass
        try:
            data = _post(url, query, timeout)
        except Exception as exc:
            kind, msg = classify(exc)
            last_msg, last_url = msg, url
            if kind == 'http':
                # Reached, and it answered. Do not fail over — see module docs.
                status = exc.code if isinstance(exc, urllib.error.HTTPError) else None
                logger.warning('Overpass http error via %s: %s', url, msg)
                return OverpassResult(None, 'http', msg, url, status)
            kinds.append(kind)
            logger.warning('Overpass %s via %s: %s — trying next endpoint',
                           kind, url, msg)
            continue
        _remember_good(url)
        return OverpassResult(data, None, '', url, None)

    # Pool exhausted. Only call the whole thing unreachable when *every*
    # endpoint was: if even one was reached-but-slow, the caller must not trip
    # its connectivity circuit breaker, because retrying a smaller query
    # against that endpoint can still succeed.
    kind = 'unreachable' if kinds and all(k == 'unreachable' for k in kinds) else 'timeout'
    return OverpassResult(None, kind, last_msg, last_url, None)
