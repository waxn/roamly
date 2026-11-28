"""Hearth's card home API (/api/layout/home/, tracker/layout_home.py).

One response, one key per card. A failing card is {"error": true} instead of
a 500, nothing leaks between accounts, and the family card follows the circle
consent model: accepted viewers only, accepted + sharing members only.
"""
from datetime import timedelta

import pytest
from django.core.cache import cache
from django.utils import timezone

from tracker import layout_home
from tracker.models import (
    Device, FamilyCircle, FamilyMembership, Location, UserProfile,
)

pytestmark = pytest.mark.django_db

KEYS = {'today', 'recent', 'on_this_day', 'stats', 'health', 'devices', 'family', 'sync', 'generated_at'}


@pytest.fixture(autouse=True)
def _clean(monkeypatch):
    # No snapshot exists in these tests; the stats card asks for one to be
    # computed, which must not start a real background thread mid-test.
    monkeypatch.setattr('tracker.stats_tasks.start_stats_compute', lambda user_id: None)
    cache.clear()
    yield
    cache.clear()


def _user(django_user_model, name):
    u = django_user_model.objects.create_user(name, password='pw-123456')
    UserProfile.objects.get_or_create(user=u)
    return u


@pytest.fixture
def alice(django_user_model):
    return _user(django_user_model, 'alice')


@pytest.fixture
def bob(django_user_model):
    return _user(django_user_model, 'bob')


def _get(client, user, **params):
    client.force_login(user)
    r = client.get('/api/layout/home/', params)
    assert r.status_code == 200
    return r.json()


def _point(device, minutes_ago, lat=51.5007, lon=-0.1246, battery=80):
    return Location.objects.create(
        device=device, latitude=lat, longitude=lon, accuracy=5, battery=battery,
        timestamp=timezone.now() - timedelta(minutes=minutes_ago))


def test_empty_account_has_every_card(client, alice):
    body = _get(client, alice)
    assert KEYS <= set(body)
    assert body['today']['point_count'] == 0
    assert body['today']['distance_km'] == 0
    assert body['today']['shape'] is None
    assert body['recent'] is None
    assert body['on_this_day'] is None
    assert body['health'] is None
    assert body['devices'] == []
    assert body['family'] == []
    assert body['stats']['pending'] is True
    assert body['sync'] == {'last_point_at': None}


def test_a_failing_card_is_an_error_not_a_500(client, alice, monkeypatch):
    def boom(ctx):
        raise RuntimeError('nope')
    monkeypatch.setitem(layout_home._SECTIONS, 'health', boom)
    body = _get(client, alice)
    assert body['health'] == {'error': True}
    assert body['devices'] == []
    # A response with a failed card is not cached, so a reload recovers.
    monkeypatch.setitem(layout_home._SECTIONS, 'health', lambda ctx: None)
    assert _get(client, alice)['health'] is None


def test_only_computes_the_named_cards(client, alice):
    body = _get(client, alice, only='devices,bogus')
    assert set(body) == {'devices', 'sync', 'generated_at'}


def test_devices_and_today_are_per_user(client, alice, bob):
    dev = Device.objects.create(user=alice, device_id='phone-a', name='Pixel')
    _point(dev, 3)
    _point(dev, 1, lat=51.5033, lon=-0.1196)
    a = _get(client, alice)
    assert [d['name'] for d in a['devices']] == ['Pixel']
    assert a['devices'][0]['battery'] == 80
    assert a['today']['point_count'] == 2
    assert a['today']['shape'] and a['today']['shape']['segments']
    assert a['sync']['last_point_at'] is not None

    client.logout()
    b = _get(client, bob)
    assert b['devices'] == []
    assert b['today']['point_count'] == 0
    assert b['sync']['last_point_at'] is None


def test_family_shows_only_consenting_members(client, django_user_model, alice, bob):
    pending = _user(django_user_model, 'pending')
    private = _user(django_user_model, 'private')
    outsider = _user(django_user_model, 'outsider')
    circle = FamilyCircle.objects.create(name='Home', creator=alice)
    now = timezone.now()
    FamilyMembership.objects.create(circle=circle, user=alice, role='creator', accepted_at=now, share_location=True)
    FamilyMembership.objects.create(circle=circle, user=bob, accepted_at=now, share_location=True)
    FamilyMembership.objects.create(circle=circle, user=pending, accepted_at=None, share_location=True)
    FamilyMembership.objects.create(circle=circle, user=private, accepted_at=now, share_location=False)
    _point(Device.objects.create(user=bob, device_id='phone-b'), 5)

    fam = _get(client, alice)['family']
    assert [m['name'] for m in fam] == ['bob']
    assert fam[0]['circles'] == ['Home']
    assert fam[0]['last_seen'] is not None
    assert 'lat' not in fam[0] and 'trail' not in fam[0]

    client.logout()
    # Someone outside the circle sees nobody, and an invite that was never
    # accepted is not enough to see the circle either.
    assert _get(client, outsider)['family'] == []
    client.logout()
    assert _get(client, pending)['family'] == []


def test_cache_is_per_user(client, alice, bob):
    Device.objects.create(user=alice, device_id='phone-a', name='Pixel')
    assert len(_get(client, alice)['devices']) == 1
    client.logout()
    assert _get(client, bob)['devices'] == []


def test_polyline_round_trip_and_projection():
    from tracker.activity_track import encode_polyline
    pts = [(38.5, -120.2), (40.7, -120.95), (43.252, -126.453)]
    assert layout_home.decode_polyline(encode_polyline(pts)) == pts
    assert layout_home.decode_polyline('') == []
    shape = layout_home.project_shape([pts])
    assert max(shape['w'], shape['h']) == 100.0
    xs = [p[0] for p in shape['segments'][0]]
    ys = [p[1] for p in shape['segments'][0]]
    assert min(xs) >= 0 and max(xs) <= shape['w'] and min(ys) >= 0 and max(ys) <= shape['h']
    # One fix draws as a centred dot, not a division by zero.
    dot = layout_home.project_shape([[(51.5, -0.1)]])
    assert dot['segments'] == [[[50.0, 50.0]]]
    assert layout_home.project_shape([]) is None
