"""UI layouts (Settings → Appearance → Layout): which page frame a signed-in
user sees. See tracker/appearance.py for the registry and docs/claude/web-ui.md.

A layout changes markup, not just CSS, so the *server* decides it and the
client mirrors the answer (`window.__RLY_LAYOUT__`, `<html data-layout>`):

  request_layout(request)       'classic' | 'atlas' | 'chronicle' | 'hearth'
  shell_template(request)       the include that replaces the navbar, or ''
  layout_templates(request, p)  template list for `render()`: the layout's own
                                version of page `p` first, the classic one last
  is_embed(request)             `?embed=1`: the page is shown inside an Atlas
                                panel, so it renders without chrome

Classic is the app as it was: no shell include, no template override.

Order of precedence for the layout: `?rly_layout=<id>` (a preview that lasts
one page load), then the account's saved appearance, then Classic.
"""
from . import appearance

# Pages a layout may redesign: page id -> the classic template. A layout opts
# in by shipping tracker/layouts/<layout>/<page>.html; anything it does not
# ship falls back to the classic template inside the layout's shell.
PAGES = {
    'map': 'tracker/map.html',
    'stats': 'tracker/stats.html',
    'journals': 'tracker/journals.html',
    'places': 'tracker/places.html',
    'adventures': 'tracker/adventures.html',
    'health': 'tracker/health.html',
    'visits': 'tracker/visits.html',
    'data': 'tracker/data_table.html',
    'achievements': 'tracker/achievements.html',
    'activities': 'tracker/activities.html',
    'activity_detail': 'tracker/activity_detail.html',
    'cells': 'tracker/cells.html',
    'flights': 'tracker/flights.html',
    # Layout-only pages. Classic has no equivalent, so these have no fallback.
    'home': '',
    'timeline': '',
}

_VALID = set(appearance._IDS['layout'])


def request_layout(request):
    """The layout for this request. Cached on the request: the context
    processor, the views and the middleware all ask."""
    cached = getattr(request, '_rly_layout', None)
    if cached:
        return cached
    layout = 'classic'
    user = getattr(request, 'user', None)
    if user is not None and user.is_authenticated:
        preview = request.GET.get('rly_layout')
        if preview in _VALID:
            layout = preview
        else:
            profile = getattr(user, 'profile', None)
            stored = getattr(profile, 'appearance', None) if profile else None
            if stored:
                layout = appearance.sanitize(stored).get('layout', 'classic')
    request._rly_layout = layout
    return layout


def is_embed(request):
    return request.GET.get('embed') == '1'


def shell_template(request):
    layout = request_layout(request)
    return '' if layout == 'classic' else f'tracker/layouts/{layout}/shell.html'


def layout_templates(request, page):
    """Template names for django.shortcuts.render(): the layout's own page,
    then the classic one. Layout-only pages (`home`, `timeline`) have no
    classic fallback, so asking for one under another layout is a 404 upstream."""
    layout = request_layout(request)
    names = []
    if layout != 'classic':
        names.append(f'tracker/layouts/{layout}/{page}.html')
    if PAGES.get(page):
        names.append(PAGES[page])
    return names
