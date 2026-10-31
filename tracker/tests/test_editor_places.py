"""Map editor: filtering to a place, and relabelling what it finds."""
from datetime import datetime, timedelta, timezone as dt_timezone

import pytest
from django.contrib.auth.models import User
from django.core.cache import cache

from tracker.models import Device, Location

pytestmark = pytest.mark.django_db


@pytest.fixture(autouse=True)
def _clean():
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def user():
    return User.objects.create_user('ed', password='pw-editor-1')


@pytest.fixture
def client(user, client):
    client.force_login(user)
    return client


def point(device, day, city, state='Maine', country='United States', hour=12, lat=44.4, lon=-69.0):
    return Location.objects.create(
        device=device, latitude=lat, longitude=lon,
        timestamp=datetime(2026, 3, day, hour, tzinfo=dt_timezone.utc),
        city=city, state=state, country=country, country_code='US',
        place_name=f'{city}, {state}, {country}')


@pytest.fixture
def device(user):
    return Device.objects.create(user=user, device_id='phone', name='Phone')


def test_search_finds_towns_states_and_countries(client, device):
    point(device, 1, 'Morrill')
    point(device, 2, 'Morrill')
    point(device, 3, 'Waldo')
    res = client.get('/api/editor/places/', {'q': 'morr'}).json()['results']
    assert [r['kind'] for r in res] == ['town']
    assert res[0]['filter'] == {'city': 'Morrill', 'state': 'Maine', 'country': 'United States'}
    assert res[0]['points'] == 2
    res = client.get('/api/editor/places/', {'q': 'maine'}).json()['results']
    assert res[0]['kind'] == 'state' and res[0]['points'] == 3
    assert client.get('/api/editor/places/', {'q': 'united'}).json()['results'][0]['kind'] == 'country'


def test_search_only_sees_own_points(client, device):
    other = User.objects.create_user('other', password='pw-other-1')
    point(Device.objects.create(user=other, device_id='x'), 1, 'Secretville')
    assert client.get('/api/editor/places/', {'q': 'secret'}).json()['results'] == []


def test_days_merge_into_stays_newest_first(client, device):
    for day in (1, 2, 3, 10):
        point(device, day, 'Waldo')
    point(device, 5, 'Morrill')
    d = client.get('/api/editor/places/days/', {'city': 'Waldo'}).json()
    assert d['points'] == 4 and d['days'] == 4
    assert d['first'] == '2026-03-01' and d['last'] == '2026-03-10'
    assert [(r['start'], r['end'], r['days']) for r in d['ranges']] == [
        ('2026-03-10', '2026-03-10', 1), ('2026-03-01', '2026-03-03', 3)]


def test_days_need_a_place(client, device):
    assert client.get('/api/editor/places/days/').status_code == 400
