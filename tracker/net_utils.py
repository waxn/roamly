"""Outbound URL validation for the endpoints users and admins get to choose.

Roamly makes server-side requests to several addresses that are configured
rather than hard-coded: a user's AI provider (``UserProfile.ai_base_url``), an
OSRM routing instance (``osrm_url``), Zepp's regional host (``zepp_host``), an
S3-compatible backup endpoint (``BackupConfig.endpoint_url``) and the Overpass
pool (``SiteConfig.overpass_urls``).

None of them were validated, which on a multi-user instance means any account
can point one at ``http://169.254.169.254/`` or a service on the Docker network
and read the answer back through the Ask tab or the config page's error field —
server-side request forgery, using the app as the proxy.

Two calls per setting, deliberately:

* at **save** time, so the user gets told immediately rather than discovering it
  through a failing background job;
* at **request** time, because DNS is not stable — a name that resolved to a
  public address when it was saved can resolve to 127.0.0.1 on the next lookup
  (DNS rebinding), and only a check immediately before connecting sees that.

``ALLOW_PRIVATE_OUTBOUND`` exists because a self-hoster running OSRM or a local
Ollama on their own LAN is a normal, legitimate setup — it is common enough that
refusing it outright would just get the feature worked around.
"""

import ipaddress
import socket
import urllib.error
import urllib.request
from urllib.parse import urlparse

from django.conf import settings


class OutboundURLError(ValueError):
    """Raised when a configured URL may not be requested."""


def _allow_private():
    return bool(getattr(settings, 'ALLOW_PRIVATE_OUTBOUND', False))


_SHARED_ADDRESS_SPACE = ipaddress.ip_network('100.64.0.0/10')


def _is_blocked_ip(ip_str):
    """True when an address is one we must never let the server be pointed at."""
    try:
        ip = ipaddress.ip_address(ip_str)
    except ValueError:
        return True  # unparseable: refuse rather than guess
    return (
        ip.is_private          # RFC1918 / ULA — the Docker network, the LAN
        or ip.is_loopback      # 127.0.0.0/8, ::1 — the app's own other ports
        or ip.is_link_local    # 169.254.0.0/16 — cloud instance metadata
        or ip.is_reserved
        or ip.is_multicast
        or ip.is_unspecified
        # 100.64.0.0/10 — carrier-grade NAT and Tailscale. Not is_private,
        # but on a self-hosted box it is exactly "another machine of mine".
        or (ip.version == 4 and ip in _SHARED_ADDRESS_SPACE)
    )


def resolve_and_check(host):
    """Resolve `host` and raise unless every address it maps to is public.

    Every address, not just the first: a name that returns one public and one
    private address would otherwise be usable to reach the private one.
    """
    if _allow_private():
        return
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror as exc:
        raise OutboundURLError(f"Could not resolve host '{host}'.") from exc
    for info in infos:
        ip = info[4][0]
        if _is_blocked_ip(ip):
            raise OutboundURLError(
                f"'{host}' resolves to a private or reserved address ({ip}), "
                "which this server will not connect to. Set "
                "ALLOW_PRIVATE_OUTBOUND=true in .env and restart if you are "
                "deliberately pointing Roamly at a service on your own network."
            )


def validate_outbound_url(url, *, allow_http=None, label='URL'):
    """Validate a user- or admin-supplied URL. Returns it stripped, or raises.

    `allow_http` defaults to whatever ALLOW_PRIVATE_OUTBOUND is: an instance that
    has opted into reaching its own LAN is also the instance whose local OSRM has
    no certificate.
    """
    url = (url or '').strip()
    if not url:
        raise OutboundURLError(f"{label} is empty.")
    parsed = urlparse(url)
    if allow_http is None:
        allow_http = _allow_private()
    schemes = ('https', 'http') if allow_http else ('https',)
    if parsed.scheme not in schemes:
        msg = f"{label} must start with " + " or ".join(f"{s}://" for s in schemes) + "."
        if parsed.scheme == 'http':
            # The usual reason for http:// is a model server on the LAN, which
            # the same setting also has to allow — say so rather than leave the
            # self-hoster guessing.
            msg += (" Plain http:// is only allowed for services on your own "
                    "network: set ALLOW_PRIVATE_OUTBOUND=true in .env and restart.")
        raise OutboundURLError(msg)
    if not parsed.hostname:
        raise OutboundURLError(f"{label} has no hostname.")
    resolve_and_check(parsed.hostname)
    return url


def validate_outbound_host(host, *, label='Host'):
    """Same check for a bare hostname (Zepp stores a host, not a URL)."""
    host = (host or '').strip()
    if not host:
        raise OutboundURLError(f"{label} is empty.")
    if '/' in host or ':' in host:
        raise OutboundURLError(f"{label} must be a bare hostname.")
    resolve_and_check(host)
    return host


class _RefuseRedirect(urllib.request.HTTPRedirectHandler):
    """Turn every 3xx into an HTTPError instead of following it.

    A validated host can still answer with a redirect to 169.254.169.254 or a
    service on the Docker network, and urllib would follow it, carrying our
    headers (Zepp's apptoken included). The `requests` call sites already pass
    allow_redirects=False; this is the urllib equivalent. The target is kept in
    the reason so an admin whose mirror merely moved can fix the URL.
    """

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(
            req.full_url, code, f"{msg} (redirect to {newurl} refused)", headers, fp)


_NO_REDIRECT_OPENER = urllib.request.build_opener(_RefuseRedirect)


def urlopen_no_redirect(req, timeout):
    """urllib.request.urlopen for configured URLs: never follows a redirect."""
    return _NO_REDIRECT_OPENER.open(req, timeout=timeout)
