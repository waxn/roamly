from django.conf import settings
from django.core.cache import cache

CUSTOM_JS_CACHE_KEY = 'site_custom_js'
CONTACT_EMAIL_CACHE_KEY = 'site_contact_email'
TURNSTILE_ENABLED_CACHE_KEY = 'site_turnstile_enabled'
TURNSTILE_SITE_KEY_CACHE_KEY = 'site_turnstile_site_key'


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


def get_turnstile_enabled():
    """Whether the admin has switched on the Turnstile CAPTCHA, cached like the
    other admin-editable SiteConfig flags."""
    enabled = cache.get(TURNSTILE_ENABLED_CACHE_KEY)
    if enabled is None:
        from .models import SiteConfig
        try:
            enabled = SiteConfig.load().turnstile_enabled
        except Exception:
            enabled = False
        cache.set(TURNSTILE_ENABLED_CACHE_KEY, enabled, 3600)
    return bool(enabled)


def get_turnstile_site_key():
    """Turnstile public site key — safe to expose to templates. The secret key
    is never cached or exposed here; it's only read server-side at verification
    time via SiteConfig.load()."""
    key = cache.get(TURNSTILE_SITE_KEY_CACHE_KEY)
    if key is None:
        from .models import SiteConfig
        try:
            key = SiteConfig.load().turnstile_site_key or ''
        except Exception:
            key = ''
        cache.set(TURNSTILE_SITE_KEY_CACHE_KEY, key, 3600)
    return key


def custom_js_snippet(request):
    """Provide the instance custom JS, the viewer's admin flag, and the per-user
    AI Ask feature flag to all templates."""
    is_admin = False
    ai_ask_enabled = False
    mapbox_token = ''
    road_snap = False
    road_provider = ''
    intro_pending = False
    user = getattr(request, 'user', None)
    if user is not None and user.is_authenticated:
        profile = getattr(user, 'profile', None)
        is_admin = bool(profile and profile.is_admin)
        # First-run welcome tour (RoamlyIntro) — only unseen for a brand new
        # signup (see UserProfile.intro_seen); every other profile is True.
        intro_pending = bool(profile and not profile.intro_seen)
        # Reuse the same profile object — the Ask tab shows only once the user
        # has enabled AI and supplied a base URL, key, and model.
        ai_ask_enabled = bool(profile and profile.ai_configured)
        # Server-side Mapbox token so the map + settings render it inline and it
        # stays in sync across the user's devices.
        mapbox_token = (profile.mapbox_token if profile else '') or ''
        # Snapping needs a usable provider, so the map only wires up its snap
        # layer when there is something behind it.
        if profile:
            road_provider = profile.road_provider_resolved
            road_snap = bool(profile.snap_to_roads and road_provider)
    return {
        'CUSTOM_JS_SNIPPET': get_custom_js(),
        'IS_ADMIN': is_admin,
        'AI_ASK_ENABLED': ai_ask_enabled,
        'MAPBOX_TOKEN': mapbox_token,
        'ROAD_SNAP_ENABLED': road_snap,
        'ROAD_PROVIDER': road_provider,
        'INTRO_PENDING': intro_pending,
        # Footer contact link + form (landing + settings). The form only renders
        # when SMTP is configured; otherwise the link falls back to a mailto:.
        'CONTACT_EMAIL': get_contact_email(),
        'EMAIL_ENABLED': bool(getattr(settings, 'EMAIL_ENABLED', False)),
        'TURNSTILE_ENABLED': get_turnstile_enabled(),
        'TURNSTILE_SITE_KEY': get_turnstile_site_key(),
    }
