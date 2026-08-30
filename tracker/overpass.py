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
one on ``timeout``/``unreachable`` — but not on an ``http`` answer *about the
query*, because there the server did answer and asking three more mirrors the
same oversized question only earns three more rate limits. The exception is a
mirror-down status (``_FAILOVER_STATUSES``): a 502 or 503 from a reverse proxy
says nothing about the query, so the walk continues rather than failing every
batch against a first endpoint that is simply having a bad day. The endpoint
that last worked is remembered so a run doesn't re-pay a dead endpoint's failure
cost on every request.

Crucially, a query is only reported ``unreachable`` when **every** endpoint was
unreachable. If even one was reached-but-slow, the aggregate is ``timeout``, so
the caller's connectivity circuit breaker stays out of it.
"""

import errno
import http.client
import json
import concurrent.futures
import logging
import re
import socket
import ssl
import time
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
#
# **Every endpoint here must be a *world* instance.** A regional extract is the
# single worst thing that can be in this pool, because it does not fail: it
# answers HTTP 200 with an empty ``elements`` list for anything outside its own
# region, which is indistinguishable from "there genuinely is no subway in this
# cell". The download then records zero ways, counts zero failures, and — since
# a batch with no failures is a batch that succeeded — writes every one of its
# cells into DownloadedRegion as *covered*, so the area is never asked about
# again. ``overpass.osm.ch`` was in this list and is Switzerland-only; that is
# exactly how a Montreal metro download came back with 0 segments and 0
# stations and then stayed that way on every retry. Verify a candidate with the
# admin panel's endpoint test, which now probes coverage as well as reachability.
DEFAULT_POOL = [
    'https://overpass.kumi.systems/api/interpreter',
    'https://overpass.private.coffee/api/interpreter',
    'https://maps.mail.ru/osm/tools/overpass/api/interpreter',
]

# Which endpoint last answered. Cached so every gunicorn worker benefits, with a
# process-local mirror so a cache outage still saves the walk within one worker.
LAST_GOOD_CACHE_KEY = 'overpass:last_good'
_LAST_GOOD_TTL = 3600
_last_good = None

# Resolved endpoint pool, cached because every Overpass request reads it and it
# only changes when an admin saves. Busted by site_overpass_config_api.
ENDPOINTS_CACHE_KEY = 'overpass_endpoints'
_ENDPOINTS_TTL = 3600

# Ceiling on the configured list. Each endpoint is a potential attempt on every
# single request, so a long list turns one unreachable batch into a long walk.
MAX_ENDPOINTS = 12

_HTTP_NOTES = {
    429: ' (rate limited)',
    500: ' (server error)',
    502: ' (bad gateway)',
    503: ' (service unavailable)',
    504: ' (gateway timeout)',
}

# HTTP statuses that mean *this mirror is down*, not *this query was rejected*.
# The general rule below is to stop the walk on any http answer, because 429 and
# 504 are Overpass telling us something about the request — asking three more
# mirrors the same oversized question only earns three more rate limits. A 502
# or 503 from a reverse proxy says nothing about the query at all: the mirror
# simply is not serving. Stopping there is how a pool whose *first* endpoint is
# having a bad day fails every batch while a perfectly healthy mirror sits
# untried two lines further down the list.
_FAILOVER_STATUSES = {500, 502, 503}

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


def parse_endpoint_list(text):
    """Split an admin-entered blob into endpoint URLs, in order, deduped.

    Accepts newline-, comma- or space-separated input. Anything that isn't an
    http(s) URL is dropped rather than rejected, so a stray blank line or a
    pasted comment can't lock an admin out of saving.
    """
    out = [u.strip() for u in re.split(r'[,\s]+', text or '') if u.strip()]
    # Scheme is case-insensitive per RFC 3986, and the admin panel's own filter
    # is too — a mismatch here would silently drop an endpoint on save that the
    # UI had happily accepted.
    out = [u for u in out if u.lower().startswith(('http://', 'https://'))]
    return list(dict.fromkeys(out))


def _resolve_endpoints():
    """SiteConfig wins outright when set, else the settings pool.

    An admin who has typed a list has made an explicit choice — quietly
    appending the built-in pool underneath it would send queries to mirrors
    they did not pick, which is exactly the surprise this field exists to
    remove for an instance with a private Overpass.
    """
    try:
        from .models import SiteConfig
        configured = parse_endpoint_list(SiteConfig.load().overpass_urls)
        if configured:
            return configured
    except Exception:
        # No DB yet (a migration, a management command) — fall through.
        pass
    urls = list(getattr(django_settings, 'OVERPASS_URLS', None) or DEFAULT_POOL)
    return list(dict.fromkeys(u.strip() for u in urls if u and u.strip()))


def endpoints():
    """The configured endpoint pool, in order, deduped.

    Cached like context_processors.get_contact_email — this is read on every
    Overpass request from a background thread, and the answer changes only when
    an admin saves. ENDPOINTS_CACHE_KEY is deleted by the save endpoint.
    """
    urls = cache.get(ENDPOINTS_CACHE_KEY)
    if urls is None:
        urls = _resolve_endpoints()
        try:
            cache.set(ENDPOINTS_CACHE_KEY, urls, _ENDPOINTS_TTL)
        except Exception:
            pass
    return urls


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
                status = exc.code if isinstance(exc, urllib.error.HTTPError) else None
                if status in _FAILOVER_STATUSES:
                    # The mirror is down rather than objecting to the query, so
                    # the next one is worth a try. Recorded in `kinds` as a
                    # timeout: it is reached-but-no-usable-answer, which is what
                    # that bucket means, and it keeps a pool-wide outage out of
                    # the callers' connectivity circuit breaker (the endpoints
                    # *were* reached) while capping their bisect depth. The
                    # message keeps the real HTTP status, so the admin panel
                    # still names what actually happened and where.
                    kinds.append('timeout')
                    logger.warning('Overpass %s via %s — trying next endpoint', msg, url)
                    continue
                # Reached, and it answered *about the query*. Do not fail over —
                # see module docs.
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


def error_fields(res):
    """last_error* column values for a failed OverpassResult (or None).

    Shared by all three download jobs so their rows describe a failure the same
    way. `res` is whatever `overpass_query` returned for the most recent failing
    request, or None when nothing has failed this run.
    """
    if res is None or res.kind is None:
        return {'last_error': '', 'last_error_kind': '', 'last_error_endpoint': ''}
    return {
        'last_error': (res.error or '')[:300],
        'last_error_kind': (res.kind or '')[:16],
        'last_error_endpoint': (res.endpoint or '')[:300],
    }


# Trivial query for the admin connection test. Deliberately tiny: all we are
# measuring is whether an endpoint answers and how fast, so an empty result is
# a perfectly good success.
PROBE_QUERY = '[out:json][timeout:10];way(1);out ids;'

# Probe budget. Generous on purpose: probes run concurrently, so the whole test
# is bounded by the slowest endpoint rather than their sum, and a mirror that
# needs 20s for a trivial query is still perfectly usable for a real download at
# SOCKET_TIMEOUT. Failing it at a tight 15s would tell an admin to remove an
# endpoint that works.
PROBE_TIMEOUT = 30.0
# Answered, but slowly enough to be worth flagging — the downloads will work,
# they will just take a long time and are more likely to hit SOCKET_TIMEOUT on
# the heavier queries.
PROBE_SLOW_MS = 5000


def probe_one(url, timeout=PROBE_TIMEOUT):
    """Reachability + latency for a single endpoint. Never raises."""
    started = time.monotonic()
    try:
        data = _post(url, PROBE_QUERY, timeout)
    except Exception as exc:
        kind, msg = classify(exc)
        status = exc.code if isinstance(exc, urllib.error.HTTPError) else None
        return {
            'url': url, 'ok': False, 'ms': int((time.monotonic() - started) * 1000),
            'kind': kind, 'error': msg, 'status': status, 'remark': '',
            'slow': False,
        }
    ms = int((time.monotonic() - started) * 1000)
    return {
        'url': url, 'ok': True, 'ms': ms,
        'kind': None, 'error': '', 'status': 200, 'slow': ms >= PROBE_SLOW_MS,
        # A mirror that answers but truncates is worth surfacing — it looks
        # healthy here while quietly returning partial data to a real download.
        'remark': (data.get('remark') or '')[:200] if isinstance(data, dict) else '',
    }


def probe_endpoints(timeout=PROBE_TIMEOUT, urls=None):
    """Probe every endpoint in `urls` (default: the configured pool).

    Passing `urls` lets the admin panel test a list that has been reordered or
    added to but not yet saved.

    Deliberately *not* the failover walk: the point is a per-endpoint answer,
    including for endpoints a real request would never have reached because an
    earlier one succeeded. Probes run concurrently so the call is bounded by the
    slowest single endpoint rather than the sum of them.
    """
    pool = list(urls) if urls else endpoints()
    if not pool:
        return []
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(pool)) as ex:
        results = list(ex.map(lambda u: probe_one(u, timeout), pool))
    for i, r in enumerate(results):
        r['primary'] = (i == 0)
    return results
