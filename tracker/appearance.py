"""Appearance: the registry behind Settings → Appearance.

A look is not one monolithic theme but a set of independent facets, each a
separate layer of CSS (and sometimes JS) under tracker/static/tracker/styles/:

  palette     colours only           palettes/<id>.css     html[data-palette]
  components  how things are drawn   components/<id>.css   html[data-style]
  shape       corners, everywhere    facets.css            html[data-shape]
  type        typefaces              facets.css            html[data-type]
  background  what sits behind       backgrounds/<id>.css  html[data-bg]
  extras      small behaviours       extras.css/.js        html[data-fx~=…]
  size, motion                       facets.css            html[data-size|motion]

A preset is just a named set of facet values (Field Journal = all defaults,
which loads nothing at all). Presets never touch size or motion, which are
personal comfort settings rather than part of a look.

Pure Python on purpose (no Django imports): the context processor maps the
paths through `static()`, and the offline preview harness imports it as is.
"""

PALETTES = [
    ('field', 'Field Journal', ['#14160f', '#1b1e15', '#e8763d', '#7f9b6d', '#e9e5d8']),
    ('lantern', 'Lantern', ['#100c08', '#1a140d', '#f29a3b', '#c9432f', '#f1e4c8']),
    ('transit', 'Transit', ['#0a0b0d', '#ffcd00', '#e4312b', '#1a82e2', '#00a651']),
    ('jelly', 'Jelly', ['#091113', '#ff4f63', '#ffb627', '#7ee04f', '#3fd3d6']),
    ('riso', 'Riso', ['#131211', '#ff48b0', '#3a70ff', '#ffe800', '#f3efe6']),
    ('postmark', 'Postmark', ['#111316', '#cf2e2a', '#1f4fa8', '#efe4c8', '#d9a548']),
    ('broadsheet', 'Broadsheet', ['#0d0d0c', '#171715', '#ebe6d9', '#e0352c', '#7a776d']),
    ('blueprint', 'Blueprint', ['#0b2a4b', '#0f345c', '#eaf3ff', '#8fd0ff', '#ffd25a']),
    ('basecamp', 'Basecamp', ['#0c0803', '#1a1206', '#ffb000', '#ff7a1a', '#ffd27a']),
    ('pebble', 'Pebble', ['#121211', '#24231f', '#a9c79f', '#dc9a7e', '#ecebe5']),
    ('tidepool', 'Tidepool', ['#03141a', '#0d3a42', '#5ff2d0', '#ff7b6b', '#f1d39a']),
    ('bento', 'Bento', ['#0d0d0e', '#1f3b2d', '#1b2a4a', '#4a201a', '#d4ff4a']),
]

# (id, name, has_js)
COMPONENTS = [
    ('field', 'Field Journal', False),
    ('lantern', 'Lantern — stitched leather, index cards', False),
    ('transit', 'Transit — route lines, station signs', False),
    ('jelly', 'Jelly — glossy gummies', False),
    ('riso', 'Riso — cut-outs, stickers, tape', False),
    ('postmark', 'Postmark — perforated stamps', False),
    ('broadsheet', 'Broadsheet — rules and columns', True),
    ('blueprint', 'Blueprint — drawn panels', False),
    ('basecamp', 'Basecamp — ASCII boxes', False),
    ('pebble', 'Pebble — matte stones', False),
    ('tidepool', 'Tidepool — frosted glass', False),
    ('bento', 'Bento — colour-blocked tiles', False),
]

SHAPES = [
    ('field', 'Field', 'Square plates, as designed'),
    ('sharp', 'Sharp', 'Everything square, pills included'),
    ('crisp', 'Crisp', 'A hint of rounding'),
    ('soft', 'Soft', 'Gently rounded'),
    ('round', 'Round', 'Big friendly corners'),
    ('squircle', 'Squircle', 'Superellipse corners, like a phone icon'),
]

TYPES = [
    ('field', 'Serif headings', 'Fraunces headings, Plex Sans text'),
    ('grotesk', 'All sans', 'Plex Sans throughout'),
    ('book', 'All serif', 'Fraunces throughout, mono for figures'),
    ('mono', 'All mono', 'Plex Mono throughout'),
]

# (id, name, has_js)
BACKGROUNDS = [
    ('field', 'Contours', False),
    ('plain', 'Plain', False),
    ('lantern', 'Lamplight', False),
    ('network', 'Metro map', True),
    ('jelly', 'Jelly blobs (3D)', True),
    ('halftone', 'Halftone', False),
    ('album', 'Album pages', False),
    ('newsprint', 'Newsprint', False),
    ('grid', 'Drafting grid', False),
    ('crt', 'CRT scanlines', True),
    ('stones', 'Sunken stones', False),
    ('water', 'Night water', True),
]

# (id, name, from-preset, exclusive-group or '')
EXTRAS = [
    ('lamp', 'Lamp glow follows the cursor', 'lantern', ''),
    ('compass', 'Compass in the corner', 'lantern', ''),
    ('kicker', 'Dated kicker over page titles', 'lantern', 'title'),
    ('stamp', 'Badges stamp down as they appear', 'lantern', ''),
    ('ink', 'Ink blot where you press', 'lantern', 'press'),
    ('flap', 'Split-flap numbers', 'transit', 'numbers'),
    ('clock', 'LED clock in the nav', 'transit', ''),
    ('roundel', 'Station code beside page titles', 'transit', ''),
    ('pin', '3D gummy pin logo', 'jelly', ''),
    ('squish', 'Squishy, wobbly buttons', 'jelly', ''),
    ('tilt', 'Cards tilt toward the cursor', 'jelly', ''),
    ('goo', 'Goo blob follows you along the nav', 'jelly', 'navmark'),
    ('drop', 'Cards bounce in', 'jelly', 'entrance'),
    ('marker', 'Active page circled in marker', 'riso', ''),
    ('slip', 'Headlines slip out of register when scrolling', 'riso', ''),
    ('postmark', 'Dated postmark by page titles', 'postmark', ''),
    ('cancel', 'Cancellation marks on tiles', 'postmark', ''),
    ('byline', 'Section kicker and byline', 'broadsheet', 'title'),
    ('dims', 'Dimension line under page titles', 'blueprint', ''),
    ('callout', 'Size call-out on hovered tiles', 'blueprint', ''),
    ('crosshair', 'Crosshair cursor with coordinates', 'blueprint', ''),
    ('titleblock', 'Title block in the corner', 'blueprint', ''),
    ('boot', 'Boot screen once a session', 'basecamp', ''),
    ('prompt', 'Shell prompt over page titles', 'basecamp', 'title'),
    ('stone', 'A stone slides under the active page', 'pebble', 'navmark'),
    ('sheen', 'Light follows the cursor across cards', 'pebble', ''),
    ('rise', 'Cards rise into place', 'pebble', 'entrance'),
    ('splash', 'Ripple where you press', 'tidepool', 'press'),
    ('countup', 'Numbers count up', 'bento', 'numbers'),
    ('pop', 'Tiles pop in', 'bento', 'entrance'),
]

SIZES = [('s', 'Small'), ('m', 'Default'), ('l', 'Large'), ('xl', 'Extra large')]
MOTIONS = [('full', 'Full'), ('calm', 'Calm')]

PRESETS = [
    ('field', 'Field Journal', 'The original. Topographic ink, trail-blaze orange.',
     dict(palette='field', components='field', shape='field', type='field', bg='field', fx=[])),
    ('lantern', 'Lantern', 'The field notebook after dark: lamplight, stitched leather, rubber stamps.',
     dict(palette='lantern', components='lantern', shape='crisp', type='field', bg='lantern',
          fx=['lamp', 'compass', 'kicker', 'stamp', 'ink'])),
    ('transit', 'Transit', 'Your life as a metro network. Route lines, station signs, split-flap boards.',
     dict(palette='transit', components='transit', shape='crisp', type='grotesk', bg='network',
          fx=['flap', 'clock', 'roundel'])),
    ('jelly', 'Jelly', 'Squishy 3D gummies on licorice. Everything wobbles when you poke it.',
     dict(palette='jelly', components='jelly', shape='round', type='field', bg='jelly',
          fx=['pin', 'squish', 'tilt', 'goo', 'drop'])),
    ('riso', 'Riso', 'A photocopied punk travel zine. Fluoro inks, misprints, tape and halftone.',
     dict(palette='riso', components='riso', shape='sharp', type='field', bg='halftone',
          fx=['marker', 'slip'])),
    ('postmark', 'Postmark', 'A stamp collector’s album. Perforated tiles, airmail stripes, cancellations.',
     dict(palette='postmark', components='postmark', shape='crisp', type='field', bg='album',
          fx=['postmark', 'cancel'])),
    ('broadsheet', 'Broadsheet', 'The night edition. Huge headlines, column rules, drop caps, one red.',
     dict(palette='broadsheet', components='broadsheet', shape='sharp', type='field', bg='newsprint',
          fx=['byline'])),
    ('blueprint', 'Blueprint', 'Drafted on cyanotype. Dimension lines, registration marks, a title block.',
     dict(palette='blueprint', components='blueprint', shape='sharp', type='grotesk', bg='grid',
          fx=['dims', 'callout', 'crosshair', 'titleblock'])),
    ('basecamp', 'Basecamp', 'An amber field terminal in a mountain hut. Phosphor, scanlines, a blinking cursor.',
     dict(palette='basecamp', components='basecamp', shape='sharp', type='mono', bg='crt',
          fx=['boot', 'prompt'])),
    ('pebble', 'Pebble', 'Smooth river stones in the dark. Matte squircles, soft light, one sliding stone.',
     dict(palette='pebble', components='pebble', shape='squircle', type='grotesk', bg='stones',
          fx=['stone', 'sheen', 'rise'])),
    ('tidepool', 'Tidepool', 'Night water. Frosted squircles, drifting caustics, plankton that glows.',
     dict(palette='tidepool', components='tidepool', shape='squircle', type='field', bg='water',
          fx=['splash', 'rise'])),
    ('bento', 'Bento', 'Colour-blocked squircle tiles. Chunky numbers that count up, a lime that means go.',
     dict(palette='bento', components='bento', shape='squircle', type='grotesk', bg='plain',
          fx=['countup', 'pop'])),
]

FACETS = ('palette', 'components', 'shape', 'type', 'bg')
_IDS = {
    'palette': [p[0] for p in PALETTES],
    'components': [c[0] for c in COMPONENTS],
    'shape': [s[0] for s in SHAPES],
    'type': [t[0] for t in TYPES],
    'bg': [b[0] for b in BACKGROUNDS],
    'size': [s[0] for s in SIZES],
    'motion': [m[0] for m in MOTIONS],
}
_EXTRA_IDS = [e[0] for e in EXTRAS]
_EXCLUSIVE = {e[0]: e[3] for e in EXTRAS if e[3]}
PRESET_IDS = [p[0] for p in PRESETS]
DEFAULT = dict(PRESETS[0][3], size='m', motion='full')


def preset(pid):
    for p in PRESETS:
        if p[0] == pid:
            return dict(p[3], fx=list(p[3]['fx']))
    return None


def sanitize(data):
    """Return a complete, valid appearance dict from untrusted input: unknown
    keys dropped, unknown values replaced by the default, extras de-duplicated
    with only the last of each exclusive group kept. A `preset` key, when
    given alone or alongside others, is applied first and then overridden."""
    out = dict(DEFAULT, fx=[])
    if not isinstance(data, dict):
        return out
    base = preset(data.get('preset')) if isinstance(data.get('preset'), str) else None
    if base:
        out.update(base)
    for key, allowed in _IDS.items():
        v = data.get(key)
        if isinstance(v, str) and v in allowed:
            out[key] = v
    if 'fx' in data:
        fx, groups = [], {}
        raw = data['fx'] if isinstance(data['fx'], list) else []
        for f in raw:
            if not isinstance(f, str) or f not in _EXTRA_IDS or f in fx:
                continue
            g = _EXCLUSIVE.get(f)
            if g:
                if g in groups:
                    fx.remove(groups[g])
                groups[g] = f
            fx.append(f)
        out['fx'] = fx
    return out


def matching_preset(app):
    """The preset id whose facets this appearance exactly matches, or ''."""
    for pid, _name, _blurb, facets in PRESETS:
        if all(app.get(k) == facets[k] for k in FACETS) and sorted(app.get('fx', [])) == sorted(facets['fx']):
            return pid
    return ''


def registry(static):
    """Everything the client needs, with asset paths resolved by `static`."""
    s = lambda p: static('tracker/styles/' + p)
    return {
        'presets': [{'id': p[0], 'name': p[1], 'blurb': p[2], 'facets': p[3],
                     'swatch': next((x[2] for x in PALETTES if x[0] == p[3]['palette']), [])} for p in PRESETS],
        'palette': [{'id': i, 'name': n, 'swatch': sw, 'css': s(f'palettes/{i}.css') if i != 'field' else ''} for i, n, sw in PALETTES],
        'components': [{'id': i, 'name': n, 'css': s(f'components/{i}.css') if i != 'field' else '',
                        'js': s(f'components/{i}.js') if js else ''} for i, n, js in COMPONENTS],
        'shape': [{'id': i, 'name': n, 'hint': h} for i, n, h in SHAPES],
        'type': [{'id': i, 'name': n, 'hint': h} for i, n, h in TYPES],
        'bg': [{'id': i, 'name': n, 'css': s(f'backgrounds/{i}.css') if i not in ('field',) else '',
                'js': s(f'backgrounds/{i}.js') if js else ''} for i, n, js in BACKGROUNDS],
        'extras': [{'id': i, 'name': n, 'from': f, 'group': g} for i, n, f, g in EXTRAS],
        'size': [{'id': i, 'name': n} for i, n in SIZES],
        'motion': [{'id': i, 'name': n} for i, n in MOTIONS],
        'facets_css': s('facets.css'),
        'extras_css': s('extras.css'),
        'extras_js': s('extras.js'),
        'default': DEFAULT,
    }
