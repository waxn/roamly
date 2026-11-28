"""Views that exist only in a UI layout (see tracker/layouts.py).

`/home/` is the Overview dashboard of Hearth (its card home) and Chronicle (its
tile dashboard); `/timeline/` is Chronicle's day feed. Under any layout that
does not own a page they send you to the map, so a bookmark never 404s after
you switch layouts back. Their data endpoints live further down this module.
"""
import logging

from django.contrib.auth.decorators import login_required
from django.shortcuts import redirect, render

from .layouts import request_layout

logger = logging.getLogger(__name__)

# page id -> the layouts that ship it (tracker/layouts/<layout>/<page>.html)
_OWNERS = {'home': ('hearth', 'chronicle'), 'timeline': ('chronicle',)}


def _page_context(request, layout, page):
    """Extra template context. Chronicle's Overview draws its map tiles on the
    user's chosen basemap, so it gets the same server-resolved list the map and
    adventures pages render as #roamly-basemaps (keys and the CARTO instance
    key already in the URLs)."""
    if (layout, page) != ('chronicle', 'home'):
        return {}
    try:
        from .views import _user_basemaps
        return {'basemaps': _user_basemaps(request)}
    except Exception:
        # The tiles fall back to the built-in CARTO style; never fail the page.
        logger.exception('home: basemap list failed')
        return {'basemaps': []}


def _layout_page(request, page):
    layout = request_layout(request)
    if layout not in _OWNERS[page]:
        return redirect('tracker:map')
    return render(request, f'tracker/layouts/{layout}/{page}.html', _page_context(request, layout, page))


@login_required
def home_view(request):
    return _layout_page(request, 'home')


@login_required
def timeline_view(request):
    return _layout_page(request, 'timeline')
