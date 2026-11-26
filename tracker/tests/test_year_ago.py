"""Header "a year ago today" chip: saved, written ahead at night, consent-gated, and silent when empty."""
import json
from datetime import date, datetime, timedelta, timezone as dt_timezone

import pytest
from django.contrib.auth.models import User
from django.core.cache import cache
from django.test import Client
from django.utils import timezone

from tracker import ai_tasks, year_ago_tasks
from tracker.models import Device, JournalEntry, Location, UserProfile, YearAgoRecap
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


def test_line_is_saved_and_served_from_the_row(client, device, provider):
    track(device)
    first = client.post(URL).json()
    cache.clear()                                  # the row, not the cache, is the store
    again = client.post(URL).json()
    assert again == first
    assert len(provider.calls) == 1
    row = YearAgoRecap.objects.get(user=device.user)
    assert row.day == date(2025, 6, 19)
    assert row.headline == 'Brunch in Bangor, then home'
    assert row.summary == 'You had brunch in Bangor.'
    assert row.viewed_at is not None


def test_saved_lines_do_not_count_toward_the_rate_limit(client, device, provider):
    track(device)
    for _ in range(25):                            # the limit is 20 provider-bound requests
        assert client.post(URL).status_code == 200
    assert len(provider.calls) == 1


def test_stepped_day_is_saved_but_not_marked_viewed(client, device, provider):
    track(device, THEN - timedelta(days=1))
    client.post(URL, {'offset': '-1'})
    row = YearAgoRecap.objects.get(user=device.user)
    assert row.day == date(2025, 6, 18)
    assert row.viewed_at is None


def test_journal_text_needs_consent(client, user, device, provider):
    track(device)
    JournalEntry.objects.create(user=user, date=date(2025, 6, 19), body='SECRET-DIARY')
    client.post(URL)
    assert 'SECRET-DIARY' not in json.dumps(provider.calls[-1])
    UserProfile.objects.filter(user=user).update(ai_allow_journals=True)
    client.post(URL)
    assert 'SECRET-DIARY' in json.dumps(provider.calls[-1])
    # Rewritten in place, so turning consent off again rewrites it once more.
    row = YearAgoRecap.objects.get(user=user)
    assert row.journal_used and row.journal_consent
    UserProfile.objects.filter(user=user).update(ai_allow_journals=False)
    client.post(URL)
    assert 'SECRET-DIARY' not in json.dumps(provider.calls[-1])
    assert len(provider.calls) == 3
    assert YearAgoRecap.objects.get(user=user).journal_used is False


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
    assert not YearAgoRecap.objects.exists()


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


# ── The 2am sweep ──────────────────────────────────────────────────────────

def test_pregenerate_writes_today_and_tomorrow(user, device, provider):
    track(device)
    track(device, THEN + timedelta(days=1))
    profile = UserProfile.objects.get(user=user)
    assert year_ago_tasks.pregenerate_for(profile, force=True) == 2
    assert sorted(YearAgoRecap.objects.values_list('day', flat=True)) == [date(2025, 6, 19), date(2025, 6, 20)]
    # Already saved: a second pass asks the provider nothing.
    assert year_ago_tasks.pregenerate_for(profile, force=True) == 0
    assert len(provider.calls) == 2


def test_pregenerated_line_is_what_the_chip_gets(client, user, device, provider):
    track(device)
    year_ago_tasks.pregenerate_for(UserProfile.objects.get(user=user), force=True)
    calls = len(provider.calls)
    assert client.post(URL).json()['headline'] == 'Brunch in Bangor, then home'
    assert len(provider.calls) == calls


def test_pregenerate_waits_for_the_night_window(user, device, provider, monkeypatch):
    track(device)
    monkeypatch.setattr(year_ago_tasks, 'PREGEN_HOUR', 25)     # never reached
    assert year_ago_tasks.pregenerate_for(UserProfile.objects.get(user=user)) == 0
    assert not provider.calls


def test_pregenerate_gives_up_after_repeated_failures(user, device, monkeypatch):
    track(device)
    calls = []

    def boom(profile, messages, tools=None):
        calls.append(1)
        raise ai_tasks.AIProviderError('down')

    monkeypatch.setattr(ai_tasks, '_provider_chat', boom)
    profile = UserProfile.objects.get(user=user)
    for _ in range(year_ago_tasks.MAX_ATTEMPTS + 2):
        assert year_ago_tasks.pregenerate_for(profile, force=True) == 0
    assert len(calls) == year_ago_tasks.MAX_ATTEMPTS


def test_sweep_skips_accounts_that_never_look_at_the_chip(user, device, provider, monkeypatch):
    track(device)
    track(device, THEN + timedelta(days=1))
    monkeypatch.setattr(year_ago_tasks, 'PREGEN_HOUR', 0)
    monkeypatch.setattr(year_ago_tasks, 'PREGEN_UNTIL_HOUR', 24)
    year_ago_tasks._sweep()
    assert not provider.calls
    # A stepped day isn't "looking at the chip"; the chip's own day is.
    YearAgoRecap.objects.create(user=user, day=date(2025, 6, 1), headline='Old', viewed_at=NOW)
    year_ago_tasks._sweep()
    assert YearAgoRecap.objects.filter(day__in=[date(2025, 6, 19), date(2025, 6, 20)]).count() == 2

