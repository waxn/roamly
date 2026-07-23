import time
from django.utils import timezone

# Paths we never want to log (static assets, tile API, health checks).
_LOG_SKIP_PREFIXES = (
    '/static/', '/media/', '/favicon', '/sw.js', '/robots.txt',
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
                        api_key.last_used = timezone.now()
                        api_key.save(update_fields=['last_used'])
                        request.user = api_key.user
                    except Exception:
                        pass

        return self.get_response(request)
