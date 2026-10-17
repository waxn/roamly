"""Basemap catalog — every map background the web map can offer, and which of
them a given user has switched on.

The map used to hardcode three basemaps (plus three Mapbox ones behind a token)
in map-core.js. This module is now the single list: map_view / editor_view hand
`resolve_for(profile)` to the page, map-core.js builds the sidebar picker from
it, and Admin Panel → Basemaps edits the instance-wide
`SiteConfig.basemap_config` through `site_basemaps_api`.

Built server-side rather than in JS so the keys are filled in once, in one
place, and so the same answer is available to anything else that wants it
(the mobile app, later).

Entry ids double as the value stored in the `roamly_map_tile_style`
localStorage key, so the six ids that existed before this module ('Streets',
'Dark', 'Satellite', 'Mapbox Streets', 'Mapbox Outdoors', 'Mapbox Satellite')
must never change — a renamed id silently resets everyone's chosen basemap.

All keys here are client-side tile keys: they ride in every tile URL and are
visible in devtools regardless, the same reasoning that leaves
`SiteConfig.carto_api_key` and `UserProfile.mapbox_token` unmasked. Masking them would only stop the user
checking what they typed.
"""

from urllib.parse import quote

_GLYPHS = 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf'

# Free AWS Open Data terrain tiles (Terrarium encoding). Used for the hillshade
# variants, which is how a "dark outdoors" look is had without any key at all.
_DEM_TILES = 'https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png'

# Key-bearing providers, in the order Admin Panel → Basemaps lists them.
PROVIDERS = {
    'mapbox': {
        'name': 'Mapbox',
        'signup': 'https://account.mapbox.com/access-tokens/',
        'note': 'Public token (pk.…) for the whole instance. A user\'s own token in Settings → Maps takes priority for them.',
    },
    'maptiler': {
        'name': 'MapTiler',
        'signup': 'https://cloud.maptiler.com/account/keys/',
        'note': 'Free plan is non-commercial. Has Outdoor Dark — the closest thing to a dark Mapbox Outdoors.',
    },
    'thunderforest': {
        'name': 'Thunderforest',
        'signup': 'https://manage.thunderforest.com/',
        'note': 'Free Hobby Project plan (150k tiles/month).',
    },
    'stadia': {
        'name': 'Stadia Maps',
        'signup': 'https://client.stadiamaps.com/',
        'note': 'Free for non-commercial use. Stamen Terrain/Toner live here now.',
    },
}

_KEY_PROVIDERS = ('maptiler', 'thunderforest', 'stadia')

_OSM = '&copy; OpenStreetMap contributors'


def _raster(source_id, tiles, attribution, tile_size=256, maxzoom=None):
    src = {'type': 'raster', 'tiles': tiles, 'tileSize': tile_size,
           'attribution': attribution}
    if maxzoom:
        src['maxzoom'] = maxzoom
    return {
        'version': 8,
        'glyphs': _GLYPHS,
        'sources': {source_id: src},
        'layers': [{'id': source_id, 'type': 'raster', 'source': source_id}],
    }


def _with_hillshade(style, dark=True):
    """Lay terrain shading over a raster style. Shadows/highlights are
    translucent so the base map's own colours still read through."""
    style['sources']['dem'] = {
        'type': 'raster-dem', 'tiles': [_DEM_TILES], 'tileSize': 256,
        'maxzoom': 15, 'encoding': 'terrarium',
        'attribution': 'Terrain: Mapzen / AWS Open Data',
    }
    style['layers'].append({
        'id': 'hillshade', 'type': 'hillshade', 'source': 'dem',
        'paint': {
            'hillshade-exaggeration': 0.55 if dark else 0.4,
            'hillshade-shadow-color': 'rgba(0,0,0,0.6)' if dark else 'rgba(60,40,20,0.35)',
            'hillshade-highlight-color': 'rgba(255,255,255,0.10)' if dark else 'rgba(255,255,255,0.25)',
            'hillshade-accent-color': 'rgba(0,0,0,0.25)' if dark else 'rgba(60,40,20,0.15)',
        },
    })
    return style


def _carto(variant, carto_qs, attribution='&copy; OpenStreetMap contributors &copy; CARTO'):
    # Keep the original source ids ('osm' for light, 'carto' for dark) so any
    # code that ever keyed on them still finds them.
    source_id = 'osm' if variant == 'light_all' else 'carto'
    return _raster(source_id,
                   [f'https://basemaps.cartocdn.com/{variant}/{{z}}/{{x}}/{{y}}.png{carto_qs}'],
                   attribution)


def _esri(service, maxzoom=19, attribution='&copy; Esri'):
    return _raster('esri',
                   [f'https://server.arcgisonline.com/ArcGIS/rest/services/{service}/MapServer/tile/{{z}}/{{y}}/{{x}}'],
                   attribution, maxzoom=maxzoom)


def _mapbox(style_path, token):
    return _raster('mapbox',
                   [f'https://api.mapbox.com/styles/v1/{style_path}/tiles/256/{{z}}/{{x}}/{{y}}'
                    f'?access_token={quote(token, safe="")}'],
                   '&copy; <a href="https://www.mapbox.com/about/maps/">Mapbox</a> ' + _OSM,
                   maxzoom=22)


def _maptiler(map_id, key, ext='png'):
    # 512px raster tiles are MapTiler's native size and read sharper than its
    # 256 endpoint at the same zoom.
    return _raster('maptiler',
                   [f'https://api.maptiler.com/maps/{map_id}/{{z}}/{{x}}/{{y}}.{ext}?key={quote(key, safe="")}'],
                   '&copy; <a href="https://www.maptiler.com/copyright/">MapTiler</a> ' + _OSM,
                   tile_size=512, maxzoom=22)


def _thunderforest(style_id, key):
    return _raster('thunderforest',
                   [f'https://tile.thunderforest.com/{style_id}/{{z}}/{{x}}/{{y}}@2x.png?apikey={quote(key, safe="")}'],
                   '&copy; <a href="https://www.thunderforest.com/">Thunderforest</a> ' + _OSM,
                   maxzoom=22)


def _stadia(style_id, key, ext='png'):
    return _raster('stadia',
                   [f'https://tiles.stadiamaps.com/tiles/{style_id}/{{z}}/{{x}}/{{y}}@2x.{ext}?api_key={quote(key, safe="")}'],
                   '&copy; <a href="https://stadiamaps.com/">Stadia Maps</a> &copy; Stamen Design ' + _OSM,
                   maxzoom=20)


# (id, group, short label, description, provider-or-None, default_on, builder)
# `builder(keys, carto_qs)` returns a MapLibre style object or a style URL.
# Labels are proper-case words; long ones wrap onto two lines in the picker.
CATALOG = [
    # ── built-in ─────────────────────────────────────────────────────────
    ('Streets', 'built-in', 'Streets', 'CARTO Positron — light, low-contrast.', None, True,
     lambda k, c: _carto('light_all', c)),
    ('Dark', 'built-in', 'Dark', 'CARTO Dark Matter.', None, True,
     lambda k, c: _carto('dark_all', c, '&copy; CartoDB')),
    ('Satellite', 'built-in', 'Satellite', 'Esri World Imagery.', None, True,
     lambda k, c: _esri('World_Imagery')),

    # ── free, no key ─────────────────────────────────────────────────────
    ('Dark Terrain', 'free', 'Dark Terrain', 'CARTO Dark with free terrain hillshading — a key-less dark outdoors.', None, False,
     lambda k, c: _with_hillshade(_carto('dark_all', c, '&copy; CartoDB'), dark=True)),
    ('Light Terrain', 'free', 'Terrain', 'CARTO Voyager with terrain hillshading.', None, False,
     lambda k, c: _with_hillshade(_carto('rastertiles/voyager', c), dark=False)),
    ('Voyager', 'free', 'Voyager', 'CARTO Voyager — colourful, readable streets.', None, False,
     lambda k, c: _carto('rastertiles/voyager', c)),
    ('OpenStreetMap', 'free', 'OSM', 'The standard OpenStreetMap style.', None, False,
     lambda k, c: _raster('osmstd', ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'], _OSM, maxzoom=19)),
    ('OpenTopoMap', 'free', 'Topo', 'Topographic: contours, trails, peaks. Max zoom 17.', None, False,
     lambda k, c: _raster('otm', ['https://tile.opentopomap.org/{z}/{x}/{y}.png'],
                          'Map: &copy; <a href="https://opentopomap.org">OpenTopoMap</a> (CC-BY-SA) ' + _OSM,
                          maxzoom=17)),
    ('CyclOSM', 'free', 'CyclOSM', 'Cycling-focused: bike lanes, paths, surfaces.', None, False,
     lambda k, c: _raster('cyclosm', ['https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png',
                                      'https://b.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png',
                                      'https://c.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png'],
                          '<a href="https://www.cyclosm.org">CyclOSM</a> ' + _OSM, maxzoom=20)),
    ('Esri Topo', 'free', 'Esri Topo', 'Esri World Topographic.', None, False,
     lambda k, c: _esri('World_Topo_Map')),
    ('Esri Dark Gray', 'free', 'Esri Dark', 'Esri Dark Gray Canvas — very muted. Max zoom 16.', None, False,
     lambda k, c: _esri('Canvas/World_Dark_Gray_Base', maxzoom=16)),
    ('OpenFreeMap Liberty', 'free', 'Vector', 'OpenFreeMap vector style — crisp labels, no key, no limits.', None, False,
     lambda k, c: 'https://tiles.openfreemap.org/styles/liberty'),
    ('OpenFreeMap Dark', 'free', 'Vector Dark', 'OpenFreeMap dark vector style.', None, False,
     lambda k, c: 'https://tiles.openfreemap.org/styles/dark'),

    # ── Mapbox (token) ───────────────────────────────────────────────────
    ('Mapbox Streets', 'mapbox', 'Streets', 'Mapbox Streets.', 'mapbox', True,
     lambda k, c: _mapbox('mapbox/streets-v12', k['mapbox'])),
    ('Mapbox Outdoors', 'mapbox', 'Outdoors', 'Mapbox Outdoors.', 'mapbox', True,
     lambda k, c: _mapbox('mapbox/outdoors-v12', k['mapbox'])),
    ('Mapbox Satellite', 'mapbox', 'Satellite', 'Mapbox Satellite Streets.', 'mapbox', True,
     lambda k, c: _mapbox('mapbox/satellite-streets-v12', k['mapbox'])),
    ('Mapbox Dark', 'mapbox', 'Dark', 'Mapbox Dark.', 'mapbox', False,
     lambda k, c: _mapbox('mapbox/dark-v11', k['mapbox'])),
    ('Mapbox Light', 'mapbox', 'Light', 'Mapbox Light.', 'mapbox', False,
     lambda k, c: _mapbox('mapbox/light-v11', k['mapbox'])),
    ('Mapbox Night', 'mapbox', 'Night', 'Mapbox Navigation Night.', 'mapbox', False,
     lambda k, c: _mapbox('mapbox/navigation-night-v1', k['mapbox'])),

    # ── MapTiler (key) ───────────────────────────────────────────────────
    ('MapTiler Outdoor Dark', 'maptiler', 'Outdoor Dark', 'Outdoor, dark: trails, contours, hillshade.', 'maptiler', True,
     lambda k, c: _maptiler('outdoor-v2-dark', k['maptiler'])),
    ('MapTiler Outdoor', 'maptiler', 'Outdoor', 'Outdoor: trails, contours, hillshade.', 'maptiler', True,
     lambda k, c: _maptiler('outdoor-v2', k['maptiler'])),
    ('MapTiler Topo', 'maptiler', 'Topo', 'Topographic.', 'maptiler', False,
     lambda k, c: _maptiler('topo-v2', k['maptiler'])),
    ('MapTiler Topo Dark', 'maptiler', 'Topo Dark', 'Topographic, dark.', 'maptiler', False,
     lambda k, c: _maptiler('topo-v2-dark', k['maptiler'])),
    ('MapTiler Streets Dark', 'maptiler', 'Streets Dark', 'Streets, dark.', 'maptiler', False,
     lambda k, c: _maptiler('streets-v2-dark', k['maptiler'])),
    ('MapTiler Winter', 'maptiler', 'Winter', 'Ski runs and winter trails.', 'maptiler', False,
     lambda k, c: _maptiler('winter-v2', k['maptiler'])),
    ('MapTiler Satellite', 'maptiler', 'Satellite', 'Satellite with labels.', 'maptiler', False,
     lambda k, c: _maptiler('hybrid', k['maptiler'], ext='jpg')),

    # ── Thunderforest (key) ──────────────────────────────────────────────
    ('Thunderforest Outdoors', 'thunderforest', 'Outdoors', 'Hiking-focused outdoors map.', 'thunderforest', True,
     lambda k, c: _thunderforest('outdoors', k['thunderforest'])),
    ('Thunderforest Landscape', 'thunderforest', 'Landscape', 'Terrain-forward landscape map.', 'thunderforest', False,
     lambda k, c: _thunderforest('landscape', k['thunderforest'])),
    ('Thunderforest Cycle', 'thunderforest', 'Cycle', 'OpenCycleMap.', 'thunderforest', False,
     lambda k, c: _thunderforest('cycle', k['thunderforest'])),
    ('Thunderforest Transport Dark', 'thunderforest', 'Transit Dark', 'Dark transit map.', 'thunderforest', False,
     lambda k, c: _thunderforest('transport-dark', k['thunderforest'])),
    ('Thunderforest Spinal', 'thunderforest', 'Spinal', 'Spinal Map — loud, dark, fun.', 'thunderforest', False,
     lambda k, c: _thunderforest('spinal-map', k['thunderforest'])),
    ('Thunderforest Atlas', 'thunderforest', 'Atlas', 'Clean atlas style.', 'thunderforest', False,
     lambda k, c: _thunderforest('atlas', k['thunderforest'])),

    # ── Stadia (key) ─────────────────────────────────────────────────────
    ('Stadia Smooth Dark', 'stadia', 'Smooth Dark', 'Alidade Smooth Dark.', 'stadia', True,
     lambda k, c: _stadia('alidade_smooth_dark', k['stadia'])),
    ('Stadia Smooth', 'stadia', 'Smooth', 'Alidade Smooth.', 'stadia', False,
     lambda k, c: _stadia('alidade_smooth', k['stadia'])),
    ('Stadia Outdoors', 'stadia', 'Outdoors', 'Stadia Outdoors.', 'stadia', True,
     lambda k, c: _stadia('outdoors', k['stadia'])),
    ('Stamen Terrain', 'stadia', 'Terrain', 'Stamen Terrain.', 'stadia', False,
     lambda k, c: _stadia('stamen_terrain', k['stadia'])),
    ('Stamen Toner', 'stadia', 'Toner', 'Stamen Toner — high-contrast black & white.', 'stadia', False,
     lambda k, c: _stadia('stamen_toner', k['stadia'])),
    ('Stamen Watercolor', 'stadia', 'Watercolor', 'Stamen Watercolor (no labels).', 'stadia', False,
     lambda k, c: _stadia('stamen_watercolor', k['stadia'], ext='jpg')),
]

GROUP_LABELS = {
    'built-in': 'built-in', 'free': 'free', 'mapbox': 'mapbox',
    'maptiler': 'maptiler', 'thunderforest': 'thunderforest', 'stadia': 'stadia',
    'custom': 'custom',
}

_IDS = {e[0] for e in CATALOG}
_FALLBACK = 'Streets'

MAX_CUSTOM = 10
MAX_KEY_LEN = 500
MAX_URL_LEN = 1000

SITE_CACHE_KEY = 'site_basemap_config'
_STORED_KEYS = ('mapbox',) + _KEY_PROVIDERS


def empty_config():
    return {'enabled': {}, 'keys': {}, 'custom': [], 'mapbox_style': ''}


def site_config():
    """`SiteConfig.basemap_config`, cached 1h (busted on save) — read on every
    map page load, so it should not cost a query each time."""
    from django.core.cache import cache
    raw = cache.get(SITE_CACHE_KEY)
    if raw is None:
        from .models import SiteConfig
        try:
            raw = SiteConfig.load().basemap_config or {}
        except Exception:
            raw = {}
        cache.set(SITE_CACHE_KEY, raw, 3600)
    return raw


def bust_site_cache():
    from django.core.cache import cache
    cache.delete(SITE_CACHE_KEY)


def _cfg(raw):
    raw = raw or {}
    out = empty_config()
    if isinstance(raw.get('enabled'), dict):
        out['enabled'] = {k: bool(v) for k, v in raw['enabled'].items() if k in _IDS}
    if isinstance(raw.get('keys'), dict):
        out['keys'] = {p: str(raw['keys'].get(p) or '')[:MAX_KEY_LEN] for p in _STORED_KEYS}
    if isinstance(raw.get('custom'), list):
        out['custom'] = [c for c in raw['custom'] if isinstance(c, dict)][:MAX_CUSTOM]
    out['mapbox_style'] = str(raw.get('mapbox_style') or '')[:MAX_URL_LEN]
    return out


def _keys(cfg, profile=None):
    """Instance keys, except Mapbox: a user's own token from Settings wins over
    the instance one, so someone with their own Mapbox account bills to it."""
    keys = {p: (cfg['keys'].get(p) or '').strip() for p in _STORED_KEYS}
    own = (getattr(profile, 'mapbox_token', '') or '').strip() if profile else ''
    if own:
        keys['mapbox'] = own
    return keys


def mapbox_style_path(value):
    """Turn what Mapbox Studio's Share dialog offers into `user/style_id`.

    Accepts `mapbox://styles/user/id`, any api.mapbox.com/styles/v1/user/id…
    URL, or a bare `user/id`. Returns '' for anything else."""
    v = (value or '').strip()
    if not v:
        return ''
    for prefix in ('mapbox://styles/', 'https://api.mapbox.com/styles/v1/'):
        if v.startswith(prefix):
            v = v[len(prefix):]
            break
    v = v.split('?', 1)[0].split('.html', 1)[0]
    parts = [p for p in v.split('/') if p]
    if len(parts) < 2:
        return ''
    user, style = parts[0], parts[1]
    ok = lambda s: s and all(ch.isalnum() or ch in '-_' for ch in s)
    return f'{user}/{style}' if ok(user) and ok(style) else ''


def clean_custom_url(url):
    """A custom XYZ template must be https and contain {z}/{x}/{y}."""
    u = (url or '').strip()[:MAX_URL_LEN]
    if not u.startswith('https://'):
        return ''
    if not all(t in u for t in ('{z}', '{x}', '{y}')):
        return ''
    return u


def is_available(provider, keys):
    return provider is None or bool(keys.get(provider))


def carto_qs():
    """The '?key=...' suffix for CARTO tiles (instance key, same tab), or ''.
    Same value templates get as CARTO_TILE_QS."""
    from .context_processors import get_carto_api_key
    key = get_carto_api_key()
    return ('?key=' + quote(key, safe='')) if key else ''


def catalog_for(raw, carto_suffix='', admin_profile=None):
    """Every catalog entry with its on/off state — what Admin Panel →
    Basemaps renders. Mapbox entries are always switchable even with no
    instance token, since users can bring their own; previews fall back to the
    admin's own token. Entries with a usable key carry their `style` so the
    page can preview them."""
    cfg = _cfg(raw)
    keys = _keys(cfg)
    preview_keys = _keys(cfg, admin_profile)
    entries = []
    for bid, group, label, desc, provider, default_on, builder in CATALOG:
        previewable = is_available(provider, preview_keys)
        entries.append({
            'id': bid, 'group': group, 'label': label, 'description': desc,
            'provider': provider, 'default_on': default_on,
            'available': provider == 'mapbox' or is_available(provider, keys),
            'enabled': cfg['enabled'].get(bid, default_on),
            'style': builder(preview_keys, carto_suffix) if previewable else None,
        })
    providers = [{'id': pid, **meta, 'key': keys.get(pid, '')} for pid, meta in PROVIDERS.items()]
    return {
        'entries': entries,
        'providers': providers,
        'custom': cfg['custom'],
        'mapbox_style': cfg['mapbox_style'],
        'mapbox_style_valid': bool(mapbox_style_path(cfg['mapbox_style'])),
        'admin_has_mapbox': bool(preview_keys.get('mapbox')),
    }


def resolve_for(profile, carto_qs='', raw=None):
    """The basemaps `profile`'s map should offer, in picker order:
    `[{id, group, label, name, style}]`. `style` is a MapLibre style object or
    a style URL. Always non-empty — Streets is the floor."""
    cfg = _cfg(site_config() if raw is None else raw)
    keys = _keys(cfg, profile)
    out = []
    for bid, group, label, desc, provider, default_on, builder in CATALOG:
        if not is_available(provider, keys):
            continue
        if not cfg['enabled'].get(bid, default_on):
            continue
        out.append({'id': bid, 'group': group, 'label': label, 'name': bid,
                    'style': builder(keys, carto_qs)})

    mb_path = mapbox_style_path(cfg['mapbox_style'])
    if mb_path and keys['mapbox']:
        out.append({'id': 'Mapbox Custom', 'group': 'mapbox', 'label': 'Custom',
                    'name': 'Mapbox Studio style', 'style': _mapbox(mb_path, keys['mapbox'])})

    for i, c in enumerate(cfg['custom']):
        url = clean_custom_url(c.get('url'))
        if not url or c.get('enabled') is False:
            continue
        name = (str(c.get('name') or '').strip() or f'Custom {i + 1}')[:40]
        try:
            maxzoom = max(1, min(24, int(c.get('max_zoom') or 19)))
        except (TypeError, ValueError):
            maxzoom = 19
        attribution = str(c.get('attribution') or '')[:200]
        out.append({'id': f'custom:{name}', 'group': 'custom',
                    'label': name[:20], 'name': name,
                    'style': _raster('custom', [url], attribution, maxzoom=maxzoom)})

    if not out:
        builder = next(e for e in CATALOG if e[0] == _FALLBACK)[6]
        out.append({'id': _FALLBACK, 'group': 'built-in', 'label': 'Streets',
                    'name': _FALLBACK, 'style': builder(keys, carto_qs)})
    return out


def sanitize_config(data, current):
    """Validate a POSTed config against `current` (the stored dict). Only the
    keys present in `data` change, so the page can autosave one control at a
    time without resending the rest."""
    cfg = dict(empty_config(), **(current or {}))
    if isinstance(data.get('enabled'), dict):
        enabled = dict(cfg.get('enabled') or {})
        for k, v in data['enabled'].items():
            if k in _IDS:
                enabled[k] = bool(v)
        cfg['enabled'] = enabled
    if isinstance(data.get('keys'), dict):
        keys = dict(cfg.get('keys') or {})
        for p in _STORED_KEYS:
            if p in data['keys']:
                keys[p] = str(data['keys'][p] or '').strip()[:MAX_KEY_LEN]
        cfg['keys'] = keys
    if 'mapbox_style' in data:
        cfg['mapbox_style'] = str(data.get('mapbox_style') or '').strip()[:MAX_URL_LEN]
    if isinstance(data.get('custom'), list):
        custom = []
        for c in data['custom'][:MAX_CUSTOM]:
            if not isinstance(c, dict):
                continue
            url = clean_custom_url(c.get('url'))
            if not url:
                continue
            try:
                mz = max(1, min(24, int(c.get('max_zoom') or 19)))
            except (TypeError, ValueError):
                mz = 19
            custom.append({
                'name': str(c.get('name') or '').strip()[:40],
                'url': url,
                'max_zoom': mz,
                'attribution': str(c.get('attribution') or '').strip()[:200],
                'enabled': c.get('enabled') is not False,
            })
        cfg['custom'] = custom
    return cfg
