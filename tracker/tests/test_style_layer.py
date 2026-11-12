"""Settings → Appearance: the facet registry, its files, validation and the
account API. Field Journal must stay the default and load nothing."""
import json
import re

import pytest
from django.contrib.staticfiles import finders

from tracker import appearance as ap
from tracker.models import UserProfile

pytestmark = pytest.mark.django_db


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user('linus', password='pw-123456')
    UserProfile.objects.get_or_create(user=u)
    return u


def _scoped_to(path, selector):
    css = open(finders.find(path), encoding='utf-8').read()
    css = re.sub(r'/\*.*?\*/', '', css, flags=re.S)
    # everything outside @keyframes must sit inside the one scoped block
    css = re.sub(r'@keyframes[^{]+\{(?:[^{}]*\{[^{}]*\})*[^{}]*\}', '', css)
    return css.strip().startswith(selector), css.count('{') == css.count('}')


def test_every_registered_file_exists_and_is_scoped():
    reg = ap.registry(lambda p: p)
    for p in reg['palette']:
        if p['css']:
            assert _scoped_to(p['css'], f'html[data-palette="{p["id"]}"]') == (True, True), p['id']
    for c in reg['components']:
        if c['css']:
            assert _scoped_to(c['css'], f'html[data-style="{c["id"]}"]') == (True, True), c['id']
        if c['js']:
            assert finders.find(c['js']), c['js']
    for b in reg['bg']:
        if b['css']:
            assert _scoped_to(b['css'], f'html[data-bg="{b["id"]}"]') == (True, True), b['id']
        if b['js']:
            assert finders.find(b['js']), b['js']
    for f in (reg['facets_css'], reg['extras_css'], reg['extras_js']):
        assert finders.find(f), f


def test_presets_only_name_real_facets():
    for pid, _n, _b, facets in ap.PRESETS:
        assert ap.sanitize({'preset': pid})['fx'] == facets['fx'], pid
        assert ap.matching_preset(ap.sanitize({'preset': pid})) == pid


def test_every_extra_has_a_module():
    js = open(finders.find('tracker/styles/extras.js'), encoding='utf-8').read()
    for e in ap.EXTRAS:
        assert f'M.{e[0]} = function' in js, e[0]
        assert f"'{e[0]}'" in js.split('var ORDER')[1], e[0]


def test_sanitize_drops_unknowns_and_keeps_one_per_group():
    out = ap.sanitize({'palette': 'nope', 'shape': 'round', 'fx': ['flap', 'countup', 'goo', 'evil', 'goo', 3], 'size': 'huge', 'junk': 1})
    assert out['palette'] == 'field' and out['shape'] == 'round' and out['size'] == 'm'
    assert out['fx'] == ['countup', 'goo']   # flap and countup share a group; the later wins
    assert 'junk' not in out
    assert ap.sanitize('not a dict') == ap.sanitize({})


def test_preset_then_overrides():
    out = ap.sanitize({'preset': 'jelly', 'palette': 'transit', 'size': 'l'})
    assert out['components'] == 'jelly' and out['palette'] == 'transit' and out['size'] == 'l'
    assert ap.matching_preset(out) == ''


def test_appearance_api_round_trip(client, user):
    client.force_login(user)
    r = client.get('/api/profile/appearance/')
    assert r.json()['stored'] is False and r.json()['preset'] == 'field'
    r = client.post('/api/profile/appearance/', data=json.dumps({'preset': 'bento', 'motion': 'calm', 'fx': ['pop', 'nope']}), content_type='application/json')
    body = r.json()
    assert body['appearance']['components'] == 'bento' and body['appearance']['motion'] == 'calm'
    assert body['appearance']['fx'] == ['pop']
    assert UserProfile.objects.get(user=user).appearance['components'] == 'bento'
    r = client.post('/api/profile/appearance/', data=json.dumps({'reset': True}), content_type='application/json')
    assert r.json()['stored'] is False
    assert client.post('/api/profile/appearance/', data='[', content_type='application/json').status_code == 400


def test_appearance_api_requires_login(client):
    assert client.get('/api/profile/appearance/').status_code in (301, 302)


def test_saved_appearance_reaches_every_page(client, user):
    UserProfile.objects.filter(user=user).update(appearance={'preset': 'riso'})
    client.force_login(user)
    body = client.get('/settings/').content.decode()
    assert 'rly-appearance-registry' in body and '"components": "riso"' in body
    assert 'Mix and Match' in body and 'ap-presets' in body


def test_old_style_lab_url_redirects_to_settings(client, user):
    client.force_login(user)
    r = client.get('/styles/')
    assert r.status_code == 302 and r['Location'].endswith('/settings/#appearance')
