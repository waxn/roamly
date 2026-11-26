"""Header "a year ago today" chip: cached, consent-gated, and silent when empty."""
import json
from datetime import date, datetime, timedelta, timezone as dt_timezone

import pytest
from django.contrib.auth.models import User
from django.core.cache import cache
from django.test import Client
from django.utils import timezone

from tracker import ai_tasks
from tracker.models import Device, JournalEntry, Location, UserProfile
from tracker.year_ago import year_ago

pytestmark = pytest.mark.django_db

URL = '/api/year-ago/'
NOW = datetime(2026, 6, 19, 18, 0, tzinfo=dt_timezone.utc)
THEN = NOW - timedelta(days=365)          # 2025-06-19


@pytest.fixture(autouse=True)
def _clean(monkeypatch):
    monkeypatch.setattr(timezone, 'now', lambda: NOW)
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def user():
    u = User.objects.create_user('rae', password='pw-yago-1')
    UserProfile.objects.update_or_create(user=u, defaults={
        'ai_ask_enabled': True, 'ai_base_url': 'https://llm.example/v1',
        'ai_api_key': 'k', 'ai_model': 'm', 'distance_unit': 'kmh',
    })
    return u


@pytest.fixture
def client(user, client):
    client.force_login(user)
    return client


@pytest.fixture
def device(user):
    return Device.objects.create(user=user, device_id='phone', name='Phone')


@pytest.fixture
def provider(monkeypatch):
    class Provider:
        calls = []
        reply = ['Brunch in Bangor, then home\n\nYou had brunch in Bangor.']

    Provider.calls = []

    def chat(profile, messages, tools=None):
        Provider.calls.append(messages)
        return {'choices': [{'message': {'content': Provider.reply[0]}}]}

    monkeypatch.setattr(ai_tasks, '_provider_chat', chat)
    return Provider


def track(device, when=THEN):
    for hour in (10, 11, 12):
        Location.objects.create(
            device=device, latitude=44.4 + hour * 0.01, longitude=-69.0,
            timestamp=when.replace(hour=hour), city='Bangor', state='Maine',
            country='United States', country_code='US')


def test_year_ago_handles_leap_day():
    assert year_ago(date(2028, 2, 29)) == date(2027, 2, 28)
    assert year_ago(date(2026, 6, 19)) == date(2025, 6, 19)


def test_requires_login_and_post(provider):
    assert Client().post(URL).status_code == 302
    assert not provider.calls


def test_get_never_reaches_the_provider(client, device, provider):
    track(device)
    assert client.get(URL).status_code == 405
    assert not provider.calls


def test_unconfigured_ai_is_quiet(client, user, device, provider):
    track(device)
    UserProfile.objects.filter(user=user).update(ai_ask_enabled=False)
    response = client.post(URL)
    assert response.status_code == 200
    assert response.json()['status'] == 'unavailable'
    assert not provider.calls


def test_no_activity_a_year_ago_makes_no_call(client, device, provider):
    track(device, NOW - timedelta(days=200))      # busy, but not that day
    assert client.post(URL).json()['status'] == 'none'
    assert not provider.calls


def test_writes_headline_and_summary(client, device, provider):
    track(device)
    data = client.post(URL).json()
    assert data['status'] == 'ok'
    assert data['date'] == '2025-06-19'
    assert data['headline'] == 'Brunch in Bangor, then home'
    assert data['summary'] == 'You had brunch in Bangor.'
    assert len(provider.calls) == 1


def test_second_request_is_served_from_cache(client, device, provider):
    track(device)
    client.post(URL)
    again = client.post(URL).json()
    assert again['status'] == 'ok'
    assert len(provider.calls) == 1


def test_journal_text_needs_consent(client, user, device, provider):
    track(device)
    JournalEntry.objects.create(user=user, date=date(2025, 6, 19), body='SECRET-DIARY')
    client.post(URL)
    assert 'SECRET-DIARY' not in json.dumps(provider.calls[-1])
    UserProfile.objects.filter(user=user).update(ai_allow_journals=True)
    client.post(URL)
    assert 'SECRET-DIARY' in json.dumps(provider.calls[-1])


def test_single_paragraph_reply_still_splits(client, device, provider):
    track(device)
    provider.reply[0] = 'You went to Bangor. You had brunch there.'
    data = client.post(URL).json()
    assert data['headline'] == 'You went to Bangor'
    assert data['summary']


def test_provider_error_is_502_and_not_cached(client, device, monkeypatch):
    track(device)

    def boom(profile, messages, tools=None):
        raise ai_tasks.AIProviderError('down')

    monkeypatch.setattr(ai_tasks, '_provider_chat', boom)
    assert client.post(URL).status_code == 502
    assert cache.get(f'year_ago:{device.user_id}:2025-06-19:0') is None


def test_offset_steps_to_neighbouring_days(client, device, provider):
    track(device, THEN - timedelta(days=1))
    data = client.post(URL, {'offset': '-1'}).json()
    assert data['status'] == 'ok'
    assert data['date'] == '2025-06-18'
    assert client.post(URL, {'offset': '1'}).json() == {'status': 'none', 'date': '2025-06-20'}
    assert len(provider.calls) == 1


def test_offset_is_clamped_and_validated(client, device, provider):
    assert client.post(URL, {'offset': '999'}).json()['date'] == '2025-07-19'
    assert client.post(URL, {'offset': 'soon'}).status_code == 400
