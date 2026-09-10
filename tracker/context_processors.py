from django.conf import settings
from django.core.cache import cache
from urllib.parse import quote

CUSTOM_JS_CACHE_KEY = 'site_custom_js'
CONTACT_EMAIL_CACHE_KEY = 'site_contact_email'
TURNSTILE_ENABLED_CACHE_KEY = 'site_turnstile_enabled'
TURNSTILE_SITE_KEY_CACHE_KEY = 'site_turnstile_site_key'
CARTO_API_KEY_CACHE_KEY = 'site_carto_api_key'

# One key holding every public SiteConfig value, instead of five.
#
# custom_js_snippet runs on every template render and used to make five separate
# cache.get calls — five Redis round-trips per page, before the profile query and
# the road-provider lookup. They all read the same singleton row and all have the
# same TTL and the same invalidation, so there was never a reason for them to be
# separate keys.
#
# The individual getters below stay as thin readers of this dict so no caller has
# to change, and each save endpoint keeps busting its own key: those deletes are
# harmless now, and _bust_site_config() is what actually clears this.
SITE_CONFIG_CACHE_KEY = 'site_config_public'
_SITE_CONFIG_TTL = 3600

# Every key the old per-value getters used. Deleting these alongside the combined
# one keeps a mid-deploy mix of old and new code from serving stale values.
_LEGACY_SITE_KEYS = (
    CUSTOM_JS_CACHE_KEY, CONTACT_EMAIL_CACHE_KEY, TURNSTILE_ENABLED_CACHE_KEY,
    TURNSTILE_SITE_KEY_CACHE_KEY, CARTO_API_KEY_CACHE_KEY,
)


def _site_config_public():
    """The admin-editable SiteConfig values templates need, cached as one dict.

    Never includes turnstile_secret_key — that is read server-side only, inside
    _verify_turnstile.
    """
    data = cache.get(SITE_CONFIG_CACHE_KEY)
    if data is None:
        from .models import SiteConfig
        try:
            cfg = SiteConfig.load()
            data = {
                'custom_js': cfg.custom_js or '',
                'contact_email': cfg.contact_email or '',
                'turnstile_enabled': bool(cfg.turnstile_enabled),
                'turnstile_site_key': cfg.turnstile_site_key or '',
                'carto_api_key': cfg.carto_api_key or '',
            }
        except Exception:
            data = {'custom_js': '', 'contact_email': '', 'turnstile_enabled': False,
                    'turnstile_site_key': '', 'carto_api_key': ''}
        cache.set(SITE_CONFIG_CACHE_KEY, data, _SITE_CONFIG_TTL)
    return data


def bust_site_config():
    """Clear the combined cache (and the legacy per-value keys)."""
    cache.delete(SITE_CONFIG_CACHE_KEY)
    cache.delete_many(list(_LEGACY_SITE_KEYS))


def get_custom_js():
    """Instance-wide custom JS (admin-editable)."""
    return _site_config_public()['custom_js']


def get_contact_email():
    """Instance contact address. '' means no address configured, in which case
    the footer contact link is hidden entirely."""
    return _site_config_public()['contact_email']


def get_turnstile_enabled():
    """Whether the admin has switched on the Turnstile CAPTCHA."""
    return _site_config_public()['turnstile_enabled']


def get_turnstile_site_key():
    """Turnstile public site key — safe to expose to templates. The secret key
    is never cached or exposed here; it is read server-side at verification time
    via SiteConfig.load()."""
    return _site_config_public()['turnstile_site_key']


def get_carto_api_key():
    """CARTO basemap API key (admin-editable). Public by design — it rides in the
    tile URL of every map the app draws, so unlike the Turnstile secret there is
    nothing to keep from a template."""
    return _site_config_public()['carto_api_key']


def get_carto_tile_qs():
    """The query string to append to a CARTO raster tile URL, or ''.

    Exposed to templates as CARTO_TILE_QS and appended verbatim to each
    basemaps.cartocdn.com URL. Templates get the finished suffix rather than the
    raw key because every one of the dozen-odd tile URLs across the app wants
    exactly the same thing, and building it once is what stops them drifting
    apart. A blank key gives a blank suffix, leaving those URLs byte for byte
    what they were before the key existed.
    """
    key = get_carto_api_key()
    if not key:
        return ''
    return '?key=' + quote(key, safe='')


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
    # Read the SiteConfig cache ONCE. Calling the five getters here would be five
    # cache round-trips again, which is the thing the combined key exists to avoid.
    cfg = _site_config_public()
    carto = cfg['carto_api_key']
    return {
        'CUSTOM_JS_SNIPPET': cfg['custom_js'],
        'IS_ADMIN': is_admin,
        'AI_ASK_ENABLED': ai_ask_enabled,
        'MAPBOX_TOKEN': mapbox_token,
        'ROAD_SNAP_ENABLED': road_snap,
        'ROAD_PROVIDER': road_provider,
        'INTRO_PENDING': intro_pending,
        # Footer contact link + form (landing + settings). The form only renders
        # when SMTP is configured; otherwise the link falls back to a mailto:.
        'CONTACT_EMAIL': cfg['contact_email'],
        'EMAIL_ENABLED': bool(getattr(settings, 'EMAIL_ENABLED', False)),
        'TURNSTILE_ENABLED': cfg['turnstile_enabled'],
        'TURNSTILE_SITE_KEY': cfg['turnstile_site_key'],
        # Appended to every basemaps.cartocdn.com tile URL in the templates and
        # handed to map-core.js via ROAMLY_MAP_CFG. '' when no key is set.
        'CARTO_TILE_QS': ('?key=' + quote(carto, safe='')) if carto else '',
    }
