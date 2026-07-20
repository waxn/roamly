from django.conf import settings
from django.core.cache import cache

CUSTOM_JS_CACHE_KEY = 'site_custom_js'
CONTACT_EMAIL_CACHE_KEY = 'site_contact_email'


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


def get_contact_email():
    """Instance contact address (admin-editable), cached like the custom JS.

    Empty string means no contact address is configured, in which case the
    footer contact link is hidden entirely. The cache stores the empty string,
    and ``cache.get`` returning None means "not looked up yet".
    """
    email = cache.get(CONTACT_EMAIL_CACHE_KEY)
    if email is None:
        from .models import SiteConfig
        try:
            email = SiteConfig.load().contact_email or ''
        except Exception:
            email = ''
        cache.set(CONTACT_EMAIL_CACHE_KEY, email, 3600)
    return email


def custom_js_snippet(request):
    """Provide the instance custom JS, the viewer's admin flag, and the per-user
    AI Ask feature flag to all templates."""
    is_admin = False
    ai_ask_enabled = False
    mapbox_token = ''
    user = getattr(request, 'user', None)
    if user is not None and user.is_authenticated:
        profile = getattr(user, 'profile', None)
        is_admin = bool(profile and profile.is_admin)
        # Reuse the same profile object — the Ask tab shows only once the user
        # has enabled AI and supplied a base URL, key, and model.
        ai_ask_enabled = bool(profile and profile.ai_configured)
        # Server-side Mapbox token so the map + settings render it inline and it
        # stays in sync across the user's devices.
        mapbox_token = (profile.mapbox_token if profile else '') or ''
    return {
        'CUSTOM_JS_SNIPPET': get_custom_js(),
        'IS_ADMIN': is_admin,
        'AI_ASK_ENABLED': ai_ask_enabled,
        'MAPBOX_TOKEN': mapbox_token,
        # Footer contact link + form (landing + settings). The form only renders
        # when SMTP is configured; otherwise the link falls back to a mailto:.
        'CONTACT_EMAIL': get_contact_email(),
        'EMAIL_ENABLED': bool(getattr(settings, 'EMAIL_ENABLED', False)),
    }
