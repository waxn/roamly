import time
from django.utils import timezone

# Paths we never want to log (static assets, tile API, health checks).
_LOG_SKIP_PREFIXES = (
    '/static/', '/media/', '/favicon', '/sw.js', '/robots.txt',
    # Polled every 30s by the container healthcheck — pure noise in the
    # access log, and it would dominate the admin panel's top-paths.
    '/healthz/',
    '/sitemap', '/api/tiles/',
)


def _get_client_ip(request):
    x_forwarded = request.META.get('HTTP_X_FORWARDED_FOR')
    if x_forwarded:
        return x_forwarded.split(',')[0].strip()
    return request.META.get('REMOTE_ADDR')


class RequestLoggingMiddleware:
    """Log every non-static HTTP request to AccessLog for the admin panel.

    The row is handed to a shared batched background writer (single reused
    connection) so the request is never blocked on the DB write. Must sit AFTER
    ApiKeyAuthMiddleware so request.user is resolved (session or Bearer key).
    """

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        path = request.path_info
        if any(path.startswith(p) for p in _LOG_SKIP_PREFIXES):
            return self.get_response(request)

        t0 = time.monotonic()
        response = self.get_response(request)
        elapsed_ms = int((time.monotonic() - t0) * 1000)

        try:
            from .log_writer import enqueue_access
            enqueue_access(
                ip_address=_get_client_ip(request),
                user_id=request.user.id if request.user.is_authenticated else None,
                path=path[:500],
                method=request.method,
                user_agent=request.META.get('HTTP_USER_AGENT', '')[:500],
                status_code=response.status_code,
                response_ms=elapsed_ms,
                timestamp=timezone.now(),
            )
        except Exception:
            pass
        return response


class ApiKeyAuthMiddleware:
    """
    Authenticate requests using a Bearer API key when no session is present.
    This allows the mobile app to use its stored API key even after the
    Django session has expired, without requiring re-login.
    Must be placed AFTER AuthenticationMiddleware in settings.MIDDLEWARE.
    """

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        if not request.user.is_authenticated:
            auth_header = request.headers.get('Authorization', '')
            if auth_header.startswith('Bearer '):
                key = auth_header[7:].strip()
                if key:
                    try:
                        from .models import APIKey
                        api_key = APIKey.objects.select_related('user').get(
                            key=key, is_active=True
                        )
                        # Stamp last_used at most once every 5 minutes.
                        #
                        # This ran on EVERY Bearer request: a phone pushing GPS
                        # every 30 seconds is ~2,900 UPDATEs a day per key,
                        # each a WAL write and a row lock on one hot row, plus
                        # the autovacuum churn that follows. last_used is a
                        # "when did we last see this key" display field — it
                        # does not need second precision.
                        from django.core.cache import cache
                        if cache.add(f'apikey_seen:{api_key.id}', 1, 300):
                            APIKey.objects.filter(pk=api_key.id).update(
                                last_used=timezone.now()
                            )
                        request.user = api_key.user
                    except Exception:
                        pass

        return self.get_response(request)


class UserTimezoneMiddleware:
    """Activate the request user's local timezone, derived from their latest fix.

    Roamly stores timestamps as aware UTC instants but the user thinks in local
    time, so every *boundary* drawn over those instants — a picked date range, a
    daily bucket — should be a local one. Django reads the active zone in
    ``timezone.make_aware`` / ``localtime`` / ``localdate`` and in ``TruncDate``
    / ``TruncHour``, so activating it here is what makes the date-range filters
    across the location, stats, distance, diagnostics and transport endpoints
    mean local midnight rather than UTC midnight, without touching any of them.

    Must sit AFTER ApiKeyAuthMiddleware so request.user is resolved (session or
    Bearer key) — a mobile client authenticating by key gets the same treatment
    as a browser session.

    The zone is set explicitly on every request, anonymous ones included: it is
    thread-local and gunicorn reuses threads, so leaving a previous request's
    zone in place would apply one user's timezone to the next request served by
    that worker.

    Deliberately NOT applied to the admin monitoring aggregation, which pins UTC
    itself — see ``_admin_daily_series``.
    """

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        try:
            user = getattr(request, 'user', None)
            if user is not None and user.is_authenticated:
                from .tz_utils import timezone_for_user
                timezone.activate(timezone_for_user(user))
            else:
                timezone.deactivate()
        except Exception:
            timezone.deactivate()
        return self.get_response(request)


class SecurityHeadersMiddleware:
    """Content-Security-Policy and Permissions-Policy on every response.

    Not a package — the policy is short, static and better read inline than
    configured through a dependency.

    The app injects admin-supplied HTML verbatim (``{{ CUSTOM_JS_SNIPPET|safe }}``)
    into every template including the public ones, so it needs 'unsafe-inline'
    for scripts and styles by design; base.html alone is a 47KB inline <style>.
    That is fine, because the clauses doing the real work here are the ones an
    injected script cannot talk its way around: ``connect-src`` bounds where data
    can be sent, ``object-src 'none'`` kills plugin-based escapes, ``base-uri``
    stops a rewritten <base> retargeting every relative URL, and
    ``frame-ancestors`` prevents clickjacking.

    Report-only unless ``CSP_ENFORCE``, so an instance can watch its console
    before switching the policy on for real.
    """

    # Third parties the app genuinely talks to. Anything else is a bug or an
    # attack. basemaps.cartocdn.com / arcgisonline / mapbox are basemap tiles,
    # challenges.cloudflare.com is Turnstile. Note there is no CDN entry: since
    # MapLibre was vendored, the app loads no third-party script at all.
    _TILE_HOSTS = (
        'https://basemaps.cartocdn.com',
        'https://*.basemaps.cartocdn.com',
        'https://server.arcgisonline.com',
        'https://api.mapbox.com',
    )
    _TURNSTILE = 'https://challenges.cloudflare.com'

    def __init__(self, get_response):
        self.get_response = get_response
        from django.conf import settings
        extra = ' '.join(getattr(settings, 'CSP_EXTRA_HOSTS', []) or [])
        tiles = ' '.join(self._TILE_HOSTS)
        self.policy = '; '.join([
            "default-src 'self'",
            # 'unsafe-eval' is required by MapLibre GL, which compiles style
            # expressions at runtime.
            f"script-src 'self' 'unsafe-inline' 'unsafe-eval' {self._TURNSTILE} {extra}".strip(),
            "style-src 'self' 'unsafe-inline'",
            f"img-src 'self' data: blob: {tiles} {extra}".strip(),
            "font-src 'self'",
            # blob: is MapLibre's web workers; the tile hosts are the raster
            # basemaps; the AI Ask provider is called server-side, never here.
            f"connect-src 'self' blob: {tiles} {self._TURNSTILE} {extra}".strip(),
            "worker-src 'self' blob:",
            "media-src 'self' blob:",
            f"frame-src {self._TURNSTILE}",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'",
        ])
        self.enforce = bool(getattr(settings, 'CSP_ENFORCE', False))
        self.header = ('Content-Security-Policy' if self.enforce
                       else 'Content-Security-Policy-Report-Only')

    def __call__(self, request):
        response = self.get_response(request)
        # Never override a policy a view set for itself, and leave the Django
        # admin alone — it is a separate app with its own inline assets.
        if 'Content-Security-Policy' not in response and not request.path.startswith('/admin/'):
            response[self.header] = self.policy
        response.setdefault('Permissions-Policy',
                            'geolocation=(self), camera=(), microphone=(), payment=()')
        response.setdefault('X-Content-Type-Options', 'nosniff')
        return response
