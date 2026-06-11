from django.core.cache import cache

CUSTOM_JS_CACHE_KEY = 'site_custom_js'


def get_custom_js():
    """Instance-wide custom JS (admin-editable), cached to avoid a per-request DB hit."""
    js = cache.get(CUSTOM_JS_CACHE_KEY)
    if js is None:
        from .models import SiteConfig
        try:
            js = SiteConfig.load().custom_js or ''
        except Exception:
            js = ''
        cache.set(CUSTOM_JS_CACHE_KEY, js, 3600)
    return js


def custom_js_snippet(request):
    """Provide the instance custom JS and the viewer's admin flag to templates."""
    is_admin = False
    user = getattr(request, 'user', None)
    if user is not None and user.is_authenticated:
        profile = getattr(user, 'profile', None)
        is_admin = bool(profile and profile.is_admin)
    return {'CUSTOM_JS_SNIPPET': get_custom_js(), 'IS_ADMIN': is_admin}
