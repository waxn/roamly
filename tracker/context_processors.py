from django.conf import settings


def custom_js_snippet(request):
    return {'CUSTOM_JS_SNIPPET': getattr(settings, 'CUSTOM_JS_SNIPPET', '')}


def ai_enabled(request):
    if not request.user.is_authenticated:
        return {'ai_enabled': False}
    from .models import AIConfig
    enabled = AIConfig.objects.filter(user=request.user, enabled=True).exists()
    return {'ai_enabled': enabled}
