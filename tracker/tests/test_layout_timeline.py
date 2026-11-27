"""Chronicle's day feed (GET /api/layout/timeline/): local days, newest first,
only the requesting user's data, a working cursor, and no error on an empty account."""
from datetime import date, datetime, timedelta, timezone as dt_timezone

import pytest
from django.core.cache import cache
from django.utils import timezone

from tracker import tz_utils
from tracker.models import (
    Device, Flight, HealthSample, JournalEntry, Location, UserProfile,
)

pytestmark = pytest.mark.django_db

URL = '/api/layout/timeline/'
NOW = datetime(2026, 6, 19, 18, 0, tzinfo=dt_timezone.utc)


@pytest.fixture(autouse=True)
def _clean(monkeypatch):
    monkeypatch.setattr(timezone, 'now', lambda: NOW)
    # Pin the derived zone instead of resolving it from coordinates.
    monkeypatch.setattr(tz_utils, 'zone_name_for_user', lambda u: 'America/New_York')
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user('linus', password='pw-123456')
    UserProfile.objects.get_or_create(user=u)
    return u


@pytest.fixture
def client(user, client):
    client.force_login(user)
    return client


@pytest.fixture
def device(user):
    return Device.objects.create(user=user, device_id='phone', name='Phone')


def walk(device, start, n=40, mode=''):
    """A straight walk north, one fix a minute, ~220 m apart."""
    for i in range(n):
        Location.objects.create(
            device=device, latitude=40.70 + i * 0.002, longitude=-74.0,
            timestamp=start + timedelta(minutes=i), accuracy=5.0,
            city='New York', state='New York', country='United States',
            transport_mode=mode)


def test_requires_login():
    from django.test import Client
    r = Client().get(URL)
    assert r.status_code in (302, 401, 403)


def test_empty_account_is_empty_not_an_error(client):
    r = client.get(URL + '?year_strip=1')
    assert r.status_code == 200
    body = r.json()
    assert body['days'] == []
    assert body['next_before'] is None
    assert len(body['activity']) == 24
    assert all(v == {} for v in body['activity'].values())


def test_bad_params_are_400(client):
    assert client.get(URL + '?before=yesterday').status_code == 400
    assert client.get(URL + '?limit=lots').status_code == 400


def test_days_are_local_days(client, device):
    # 02:00 UTC on Jun 18 is 22:00 on Jun 17 in New York.
    walk(device, datetime(2026, 6, 18, 2, 0, tzinfo=dt_timezone.utc), mode='walk')
    days = client.get(URL).json()['days']
    assert [d['date'] for d in days] == ['2026-06-17']
    day = days[0]
    assert day['weekday'] == 'Wednesday'
    assert day['distance_km'] > 1.0
    assert day['transport'].get('walk', 0) > 0
    assert day['moving_seconds'] >= day['transport']['walk']
    assert day['places'][0]['name'] == 'New York, New York'
    assert day['places'][0]['kind'] == 'city'


def test_unclassified_motion_still_counts_as_moving(client, device):
    walk(device, datetime(2026, 6, 18, 14, 0, tzinfo=dt_timezone.utc))
    day = client.get(URL).json()['days'][0]
    assert day['transport'] == {}
    assert day['moving_seconds'] > 0


def test_journal_note_flight_and_steps(client, user):
    JournalEntry.objects.create(user=user, date=date(2026, 6, 10), title='Coast',
                                body='word ' * 100, mood='calm', quick_note='Swam twice')
    JournalEntry.objects.create(user=user, date=date(2026, 6, 12), quick_note='Only a note')
    Flight.objects.create(user=user, date=date(2026, 6, 11), flight_number='BA117',
                          dep_iata='LHR', arr_iata='JFK')
    noon = datetime(2026, 6, 9, 16, 0, tzinfo=dt_timezone.utc)
    for i, (src, v) in enumerate([('a', 4000), ('a', 3000), ('b', 5000)]):
        HealthSample.objects.create(user=user, kind='steps', value=v, source=src,
                                    start_time=noon + timedelta(minutes=i),
                                    end_time=noon + timedelta(minutes=i + 1),
                                    external_id=f'x{i}')

    days = {d['date']: d for d in client.get(URL).json()['days']}
    assert list(days) == ['2026-06-12', '2026-06-11', '2026-06-10', '2026-06-09']

    assert days['2026-06-12']['journal'] is None
    assert days['2026-06-12']['notes'] == ['Only a note']
    j = days['2026-06-10']['journal']
    assert j['title'] == 'Coast' and j['mood'] == 'calm'
    assert len(j['excerpt']) <= 201 and j['excerpt'].endswith('…')
    assert days['2026-06-10']['notes'] == ['Swam twice']
    assert days['2026-06-11']['flights'] == [{'id': days['2026-06-11']['flights'][0]['id'],
                                             'from': 'LHR', 'to': 'JFK', 'number': 'BA117'}]
    # One source per day, never a sum across apps.
    assert days['2026-06-09']['health']['steps'] == 7000


def test_cursor_walks_back_through_every_day(client, user):
    for d in (1, 3, 5, 7, 9):
        JournalEntry.objects.create(user=user, date=date(2026, 6, d), title=f'Day {d}')
    seen, before = [], None
    for _ in range(5):
        url = URL + '?limit=2' + (f'&before={before}' if before else '')
        body = client.get(url).json()
        seen += [d['date'] for d in body['days']]
        before = body['next_before']
        if not before:
            break
    assert seen == ['2026-06-09', '2026-06-07', '2026-06-05', '2026-06-03', '2026-06-01']
    assert before is None


def test_before_is_exclusive_and_future_is_clamped(client, user):
    JournalEntry.objects.create(user=user, date=date(2026, 6, 15), title='A')
    JournalEntry.objects.create(user=user, date=date(2026, 6, 16), title='B')
    days = client.get(URL + '?before=2026-06-16').json()['days']
    assert [d['date'] for d in days] == ['2026-06-15']
    days = client.get(URL + '?before=2030-01-01').json()['days']
    assert [d['date'] for d in days] == ['2026-06-16', '2026-06-15']


def test_only_my_data(client, user, django_user_model):
    other = django_user_model.objects.create_user('someone', password='pw-654321')
    JournalEntry.objects.create(user=other, date=date(2026, 6, 14), title='Not yours')
    other_dev = Device.objects.create(user=other, device_id='theirs')
    walk(other_dev, datetime(2026, 6, 14, 14, 0, tzinfo=dt_timezone.utc))
    assert client.get(URL).json()['days'] == []


def test_year_strip_scores_journal_days(client, user):
    JournalEntry.objects.create(user=user, date=date(2026, 5, 2), title='May day')
    act = client.get(URL + '?year_strip=1').json()['activity']
    assert act['2026-05'] == {'2026-05-02': 1}
    assert list(act)[-1] == '2026-06' and list(act)[0] == '2024-07'
