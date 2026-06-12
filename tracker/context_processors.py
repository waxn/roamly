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
    """Provide the instance custom JS, the viewer's admin flag, and the per-user
    AI Ask feature flag to all templates."""
    is_admin = False
    ai_ask_enabled = False
    user = getattr(request, 'user', None)
    if user is not None and user.is_authenticated:
        profile = getattr(user, 'profile', None)
        is_admin = bool(profile and profile.is_admin)
        # Reuse the same profile object — the Ask tab shows only once the user
        # has enabled AI and supplied a base URL, key, and model.
        ai_ask_enabled = bool(profile and profile.ai_configured)
    return {
        'CUSTOM_JS_SNIPPET': get_custom_js(),
        'IS_ADMIN': is_admin,
        'AI_ASK_ENABLED': ai_ask_enabled,
    }
