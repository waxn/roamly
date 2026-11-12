"""The opt-in style layer: every style's files exist, the loader names them,
and the Style lab renders. Field Journal must stay the default (no style
file is loaded for it)."""
import os
import re

import pytest
from django.conf import settings
from django.contrib.staticfiles import finders

from tracker.models import UserProfile

pytestmark = pytest.mark.django_db

STYLES = ['lantern', 'transit', 'jelly', 'riso', 'postmark', 'broadsheet', 'blueprint', 'basecamp']
LAYER = os.path.join(settings.BASE_DIR, 'tracker', 'templates', 'tracker', '_style_layer.html')


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user('linus', password='pw-123456')
    UserProfile.objects.get_or_create(user=u)
    return u


@pytest.mark.parametrize('style', STYLES)
def test_style_files_exist_and_are_scoped(style):
    for ext in ('css', 'js'):
        assert finders.find(f'tracker/styles/{style}.{ext}'), f'{style}.{ext} missing'
    css = open(finders.find(f'tracker/styles/{style}.css'), encoding='utf-8').read()
    # Everything but @keyframes must live inside the one scoped block, so a
    # style can never leak into Field Journal or another style.
    outside = re.sub(r'@keyframes[^{]+\{(?:[^{}]*\{[^{}]*\})*[^{}]*\}', '', re.sub(r'/\*.*?\*/', '', css, flags=re.S))
    assert outside.strip().startswith(f'html[data-style="{style}"]')


def test_loader_lists_every_style_and_field_loads_nothing():
    src = open(LAYER, encoding='utf-8').read()
    for style in STYLES:
        assert f"tracker/styles/{style}.css" in src
        assert f"tracker/styles/{style}.js" in src
    assert re.search(r"field:\s*\{[^}]*css: ''", src)


def test_style_lab_renders(client, user):
    client.force_login(user)
    r = client.get('/styles/')
    assert r.status_code == 200
    body = r.content.decode()
    assert 'RoamlyStyle' in body and 'sl-gallery' in body


def test_style_lab_requires_login(client):
    r = client.get('/styles/')
    assert r.status_code in (301, 302)
