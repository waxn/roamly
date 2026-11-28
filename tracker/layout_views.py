"""Views that exist only in a UI layout (see tracker/layouts.py).

`/home/` is Hearth's card home and `/timeline/` is Chronicle's day feed. Under
any other layout they send you to the map, so a bookmark never 404s after you
switch layouts back. Their data endpoints live further down this module.
"""
from django.contrib.auth.decorators import login_required
from django.shortcuts import redirect, render

from .layouts import request_layout

# page id -> the layout that owns it
_OWNER = {'home': 'hearth', 'timeline': 'chronicle'}


def _layout_page(request, page):
    if request_layout(request) != _OWNER[page]:
        return redirect('tracker:map')
    return render(request, f'tracker/layouts/{_OWNER[page]}/{page}.html')


@login_required
def home_view(request):
    return _layout_page(request, 'home')


@login_required
def timeline_view(request):
    return _layout_page(request, 'timeline')
