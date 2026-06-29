from django.utils import timezone


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
