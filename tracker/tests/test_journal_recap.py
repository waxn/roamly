"""Journals page AI recap: on demand, consent-gated, and stored nowhere."""
import json
from datetime import datetime, timedelta, timezone as dt_timezone

import pytest
from django.contrib.auth.models import User
from django.core.cache import cache
from django.test import Client
from django.utils import timezone

from tracker import ai_tasks
from tracker.models import Device, JournalEntry, Location, UserProfile, Visit

pytestmark = pytest.mark.django_db

URL = '/api/journals/recap/'
NOW = datetime(2026, 6, 19, 18, 0, tzinfo=dt_timezone.utc)   # a Friday


@pytest.fixture(autouse=True)
def _clean(monkeypatch):
    monkeypatch.setattr(timezone, 'now', lambda: NOW)
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def user():
    u = User.objects.create_user('rae', password='pw-recap-1')
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
    """Stands in for the user's LLM, keeping what it was sent."""
    class Provider:
        calls = []
        reply = ['You stayed around Waldo.\n\nMuch the same as usual.']

    def chat(profile, messages, tools=None):
        Provider.calls.append({'messages': messages, 'tools': tools})
        return {'choices': [{'message': {'content': Provider.reply[0]}}]}

    monkeypatch.setattr(ai_tasks, '_provider_chat', chat)
    return Provider


def track(device, days_ago, city='Waldo', state='Maine', lat=44.4, hours=(10, 11, 12)):
    for hour in hours:
        Location.objects.create(
            device=device, latitude=lat + hour * 0.01, longitude=-69.0,
            timestamp=NOW.replace(hour=hour) - timedelta(days=days_ago),
            city=city, state=state, country='United States', country_code='US')


def sent(provider):
    """(system prompt, the JSON the provider was given)."""
    messages = provider.calls[-1]['messages']
    body = messages[1]['content']
    return messages[0]['content'], json.loads(body[body.index('{'):body.rindex('}') + 1])


def test_requires_login(provider):
    assert Client().post(URL).status_code == 302
    assert not provider.calls


def test_get_never_reaches_the_provider(client, device, provider):
    track(device, 1)
    assert client.get(URL).status_code == 405
    assert not provider.calls


def test_csrf_is_enforced(user, device, provider):
    track(device, 1)
    strict = Client(enforce_csrf_checks=True)
    strict.force_login(user)
    assert strict.post(URL).status_code == 403
    assert not provider.calls


def test_unconfigured_ai_asks_for_setup(client, user, device, provider):
    track(device, 1)
    UserProfile.objects.filter(user=user).update(ai_ask_enabled=False)
    response = client.post(URL)
    assert response.status_code == 503
    assert response.json()['setup'] is True
    assert not provider.calls


def test_nothing_recorded_makes_no_call(client, provider):
    response = client.post(URL)
    assert response.json()['status'] == 'empty'
    assert not provider.calls


def test_no_earlier_data_means_no_comparison(client, device, provider):
    track(device, 1)
    provider.reply[0] = 'First paragraph.\n\nSecond paragraph.'
    data = client.post(URL).json()
    assert data['has_earlier'] is False
    assert data['comparison'] == []
    assert data['summary'] == ['First paragraph.', 'Second paragraph.']
    system, payload = sent(provider)
    assert 'earlier' not in payload
    assert 'no earlier data' in system
    assert all(f['earlier'] is None for f in data['facts'])


def test_a_week_with_the_tracker_mostly_off_is_not_a_comparison(client, device, provider):
    track(device, 1)
    track(device, 8)     # one day out of that week's five
    assert client.post(URL).json()['has_earlier'] is False


def test_compares_with_earlier_weeks(client, device, provider):
    track(device, 1)
    track(device, 2, city='Portland', lat=43.6)
    for days_ago in (7, 8, 9):
        track(device, days_ago)
    data = client.post(URL).json()
    assert data['has_earlier'] is True
    assert data['weeks_compared'] == 1
    assert data['summary'] == ['You stayed around Waldo.']
    assert data['comparison'] == ['Much the same as usual.']
    system, payload = sent(provider)
    assert 'comparing these days' in system
    weeks = payload['earlier']['recent_weeks']
    assert weeks['towns'] == ['Waldo, Maine']
    assert weeks['towns_not_in_those_weeks'] == ['Portland, Maine']
    assert data['facts'][0]['earlier'] is not None


def test_earlier_stretch_stops_at_the_same_clock_time(client, device, provider):
    """Today is part done, so last week's matching day has to be cut off too."""
    track(device, 0, hours=(10,))
    for days_ago in (7, 8, 9):
        track(device, days_ago, hours=(10,))
    # Later in the day than "now", on the matching day last week.
    track(device, 7, city='Bangor', state='Maine', hours=(20,))
    client.post(URL)
    _, payload = sent(provider)
    assert payload['earlier']['recent_weeks']['towns'] == ['Waldo, Maine']


def test_year_ago_stretch_is_offered_when_tracked(client, device, provider):
    track(device, 1)
    for days_ago in (364, 365, 366):
        track(device, days_ago, city='Lisbon', state='', lat=38.7)
    data = client.post(URL).json()
    assert data['has_earlier'] is True
    assert data['weeks_compared'] == 0
    _, payload = sent(provider)
    assert 'recent_weeks' not in payload['earlier']
    assert payload['earlier']['year_ago']['top_towns'][0]['name'] == 'Lisbon'


def test_journal_text_needs_its_own_consent(client, user, device, provider):
    track(device, 1)
    JournalEntry.objects.create(user=user, date=NOW.date() - timedelta(days=1), body='A secret picnic')
    data = client.post(URL).json()
    assert 'secret picnic' not in json.dumps(provider.calls[-1]['messages'])
    assert data['journals_allowed'] is False
    assert data['journals_used'] == 0

    UserProfile.objects.filter(user=user).update(ai_allow_journals=True)
    data = client.post(URL).json()
    assert 'secret picnic' in json.dumps(provider.calls[-1]['messages'])
    assert data['journals_used'] == 1


def test_journal_entries_outside_the_stretch_stay_home(client, user, device, provider):
    UserProfile.objects.filter(user=user).update(ai_allow_journals=True)
    track(device, 1)
    JournalEntry.objects.create(user=user, date=NOW.date() - timedelta(days=30), body='Old news')
    client.post(URL)
    assert 'Old news' not in json.dumps(provider.calls[-1]['messages'])


def test_a_journal_entry_alone_is_enough(client, user, provider):
    UserProfile.objects.filter(user=user).update(ai_allow_journals=True)
    JournalEntry.objects.create(user=user, date=NOW.date(), title='Stayed in')
    assert client.post(URL).json()['status'] == 'ok'
    assert provider.calls


def test_only_the_callers_data(client, device, provider):
    track(device, 1)
    other = User.objects.create_user('sam', password='pw-recap-2')
    theirs = Device.objects.create(user=other, device_id='other', name='Other')
    track(theirs, 1, city='Elsewhere', state='Ohio', lat=40.0)
    JournalEntry.objects.create(user=other, date=NOW.date(), body='Not yours')
    client.post(URL)
    blob = json.dumps(provider.calls[-1]['messages'])
    assert 'Elsewhere' not in blob and 'Not yours' not in blob


def test_days_follow_the_users_zone(client, device, provider):
    """01:00 UTC on the 19th is still the evening of the 18th in Maine."""
    Location.objects.create(
        device=device, latitude=44.4, longitude=-69.0, timestamp=NOW.replace(hour=1),
        city='Waldo', state='Maine', country='United States', country_code='US')
    Visit.objects.create(
        device=device, latitude=44.4, longitude=-69.0, city='Waldo', state='Maine',
        start_time=NOW.replace(hour=0, minute=30), end_time=NOW.replace(hour=1, minute=30))
    client.post(URL)
    _, payload = sent(provider)
    day = {d['date']: d for d in payload['days']}
    assert payload['today'] == '2026-06-19'
    assert day['2026-06-18']['stays'] == ['20:30–21:30 Waldo, Maine']
    assert day['2026-06-19']['stays'] == []


def test_provider_failure_is_shown(client, device, monkeypatch):
    track(device, 1)

    def fail(profile, messages, tools=None):
        raise ai_tasks.AIProviderError('The AI provider rejected the API key.')

    monkeypatch.setattr(ai_tasks, '_provider_chat', fail)
    response = client.post(URL)
    assert response.status_code == 502
    assert response.json()['error'] == 'The AI provider rejected the API key.'


def test_an_empty_reply_is_an_error(client, device, provider):
    track(device, 1)
    provider.reply[0] = '   '
    assert client.post(URL).status_code == 502


def test_rate_limited_per_account(client, device, provider, monkeypatch):
    monkeypatch.setattr('tracker.views._JOURNAL_RECAP_LIMIT', 2)
    track(device, 1)
    assert client.post(URL).status_code == 200
    assert client.post(URL).status_code == 200
    assert client.post(URL).status_code == 429
    assert len(provider.calls) == 2


def test_nothing_is_stored(client, user, device, provider):
    track(device, 1)
    before = UserProfile.objects.filter(user=user).values().get()
    entries = JournalEntry.objects.count()
    response = client.post(URL)
    assert response['Cache-Control'] == 'no-store'
    assert UserProfile.objects.filter(user=user).values().get() == before
    assert JournalEntry.objects.count() == entries


def test_page_starts_closed_and_escapes_the_reply(client):
    html = client.get('/journals/').content.decode()
    assert 'id="j-recap-toggle"' in html
    assert 'class="j-recap" id="j-recap"' in html       # no "open" class
    assert 'const AI_READY = true' in html
    assert 'recapParas(paras) { return paras.map(p=>`<p>${escapeHtml(p)}</p>`)' in html
