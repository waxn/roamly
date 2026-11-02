"""Journals: settings cog, AI title + mood, moving a day, automatic weather.

The rule running through all of it: a field someone filled in is never replaced.
"""
import json
from datetime import date, datetime, timedelta, timezone as dt_timezone

import pytest
import requests
from django.core.cache import cache
from django.utils import timezone

from tracker import ai_tasks, journal_assist
from tracker.models import Device, JournalEntry, JournalPhoto, Location, UserProfile

pytestmark = pytest.mark.django_db


@pytest.fixture(autouse=True)
def _clean():
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user('linus', password='pw-123456')
    UserProfile.objects.get_or_create(user=u)
    return u


@pytest.fixture
def ai_user(user):
    UserProfile.objects.filter(user=user).update(
        ai_ask_enabled=True, ai_base_url='https://llm.example/v1', ai_api_key='k', ai_model='m',
        journal_auto_title=True, journal_auto_mood=True)
    user.profile.refresh_from_db()
    return user


def _reply(monkeypatch, content, calls=None):
    def fake(profile, messages, tools=None):
        if calls is not None:
            calls.append(messages)
        return {'choices': [{'message': {'content': content}}]}
    monkeypatch.setattr(ai_tasks, '_provider_chat', fake)


class _Resp:
    def __init__(self, payload):
        self.payload = payload

    def raise_for_status(self):
        pass

    def json(self):
        return self.payload


def _meteo(code=61, hi=18.4, lo=6.2):
    return _Resp({'daily': {'weather_code': [code], 'temperature_2m_max': [hi], 'temperature_2m_min': [lo]}})


def _post(client, url, data=None):
    return client.post(url, data=json.dumps(data or {}), content_type='application/json')


# ── settings ────────────────────────────────────────────────────────────────

def test_settings_default_and_partial_save(client, user):
    client.force_login(user)
    s = client.get('/api/journals/settings/').json()
    assert s == {'auto_title': False, 'auto_mood': False, 'auto_weather': True, 'ai_available': False}
    s = _post(client, '/api/journals/settings/', {'auto_mood': True}).json()
    assert s['auto_mood'] is True and s['auto_title'] is False and s['auto_weather'] is True


def test_settings_route_is_not_taken_for_a_date(client, user):
    client.force_login(user)
    assert 'auto_title' in client.get('/api/journals/settings/').json()


# ── AI title + mood ─────────────────────────────────────────────────────────

def test_autofill_fills_blanks(client, ai_user, monkeypatch):
    calls = []
    _reply(monkeypatch, '```json\n{"title": "\\"Rain on the ferry\\"", "emoji": "🌧️"}\n```', calls)
    e = JournalEntry.objects.create(user=ai_user, date=date(2026, 10, 1), body='We took the ferry in the rain.')
    client.force_login(ai_user)
    r = client.post('/api/journals/2026-10-01/autofill/')
    assert r.status_code == 200 and r.json() == {'title': 'Rain on the ferry', 'mood': '🌧️'}
    e.refresh_from_db()
    assert e.title == 'Rain on the ferry' and e.mood == '🌧️'
    assert 'ferry' in calls[0][1]['content']


def test_autofill_never_replaces_what_was_typed(client, ai_user, monkeypatch):
    _reply(monkeypatch, '{"title": "AI title", "emoji": "🌧️"}')
    e = JournalEntry.objects.create(user=ai_user, date=date(2026, 10, 1), body='x', title='Mine')
    client.force_login(ai_user)
    assert client.post('/api/journals/2026-10-01/autofill/').json() == {'mood': '🌧️'}
    e.refresh_from_db()
    assert e.title == 'Mine'


def test_autofill_loses_to_a_title_typed_during_the_call(ai_user, monkeypatch):
    e = JournalEntry.objects.create(user=ai_user, date=date(2026, 10, 1), body='x')

    def fake(profile, messages, tools=None):
        JournalEntry.objects.filter(pk=e.pk).update(title='Typed meanwhile')
        return {'choices': [{'message': {'content': '{"title": "AI", "emoji": "😀"}'}}]}
    monkeypatch.setattr(ai_tasks, '_provider_chat', fake)
    assert journal_assist.autofill(ai_user.profile, e) == {'mood': '😀'}
    e.refresh_from_db()
    assert e.title == 'Typed meanwhile'


def test_autofill_respects_each_toggle_and_empty_entries(ai_user, monkeypatch):
    calls = []
    _reply(monkeypatch, '{"title": "T", "emoji": "😀"}', calls)
    blank = JournalEntry.objects.create(user=ai_user, date=date(2026, 10, 1), body='   ')
    assert journal_assist.autofill(ai_user.profile, blank) == {}
    assert calls == []                          # nothing to read, no credits spent
    e = JournalEntry.objects.create(user=ai_user, date=date(2026, 10, 2), body='A day')
    p = ai_user.profile
    p.journal_auto_title = False
    assert journal_assist.autofill(p, e) == {'mood': '😀'}
    p.journal_auto_mood = False
    e.refresh_from_db()
    e.mood = ''
    assert journal_assist.autofill(p, e) == {}
    assert len(calls) == 1


def test_autofill_refuses_a_word_for_a_mood(ai_user, monkeypatch):
    _reply(monkeypatch, '{"title": "T", "emoji": "happy"}')
    e = JournalEntry.objects.create(user=ai_user, date=date(2026, 10, 1), body='x')
    assert journal_assist.autofill(ai_user.profile, e) == {'title': 'T'}


def test_autofill_bad_reply_and_no_provider(client, ai_user, user, monkeypatch):
    _reply(monkeypatch, 'I think this day felt nice.')
    JournalEntry.objects.create(user=ai_user, date=date(2026, 10, 1), body='x')
    client.force_login(ai_user)
    assert client.post('/api/journals/2026-10-01/autofill/').status_code == 502
    UserProfile.objects.filter(user=ai_user).update(ai_ask_enabled=False)
    assert client.post('/api/journals/2026-10-01/autofill/').status_code == 400


def test_autofill_is_owner_scoped(client, ai_user, django_user_model, monkeypatch):
    _reply(monkeypatch, '{"title": "T", "emoji": "😀"}')
    other = django_user_model.objects.create_user('other', password='pw-123456')
    JournalEntry.objects.create(user=other, date=date(2026, 10, 1), body='secret')
    client.force_login(ai_user)
    assert client.post('/api/journals/2026-10-01/autofill/').status_code == 404


# ── moving a day ────────────────────────────────────────────────────────────

def test_move_keeps_the_entry_and_its_photos(client, user):
    e = JournalEntry.objects.create(user=user, date=date(2026, 10, 1), body='x')
    photo = JournalPhoto.objects.create(entry=e, image='journal/a.jpg')
    client.force_login(user)
    r = _post(client, '/api/journals/2026-10-01/move/', {'date': '2026-09-28'})
    assert r.status_code == 200 and r.json()['date'] == '2026-09-28'
    e.refresh_from_db()
    photo.refresh_from_db()
    assert e.date == date(2026, 9, 28) and photo.entry_id == e.id


def test_move_refuses_a_taken_day(client, user):
    e = JournalEntry.objects.create(user=user, date=date(2026, 10, 1), body='a')
    JournalEntry.objects.create(user=user, date=date(2026, 10, 2), body='b')
    client.force_login(user)
    assert _post(client, '/api/journals/2026-10-01/move/', {'date': '2026-10-02'}).status_code == 409
    e.refresh_from_db()
    assert e.date == date(2026, 10, 1)


def test_move_bad_input(client, user, django_user_model):
    client.force_login(user)
    assert _post(client, '/api/journals/2026-10-01/move/', {'date': '2026-10-02'}).status_code == 404
    JournalEntry.objects.create(user=user, date=date(2026, 10, 1))
    assert _post(client, '/api/journals/2026-10-01/move/', {'date': 'nope'}).status_code == 400
    other = django_user_model.objects.create_user('other', password='pw-123456')
    JournalEntry.objects.create(user=other, date=date(2026, 10, 5))
    # Another account's entry on the target day is no conflict; its own is.
    assert _post(client, '/api/journals/2026-10-01/move/', {'date': '2026-10-05'}).status_code == 200


def test_move_refreshes_fetched_weather_but_not_typed(client, user, monkeypatch):
    monkeypatch.setattr(journal_assist, 'weather_for',
                        lambda u, d, entry=None: f'auto {d.isoformat()}')
    client.force_login(user)
    e = JournalEntry.objects.create(user=user, date=date(2026, 10, 1), weather='auto 2026-10-01')
    assert _post(client, '/api/journals/2026-10-01/move/', {'date': '2026-10-02'}).json()['weather'] == 'auto 2026-10-02'
    typed = JournalEntry.objects.create(user=user, date=date(2026, 10, 3), weather='Glorious')
    _post(client, '/api/journals/2026-10-03/move/', {'date': '2026-10-04'})
    typed.refresh_from_db()
    assert typed.weather == 'Glorious'
    e.refresh_from_db()
    assert e.date == date(2026, 10, 2)


# ── weather ─────────────────────────────────────────────────────────────────

def test_recent_day_uses_forecast_older_uses_archive(monkeypatch):
    seen = []

    def fake(url, params=None, timeout=None):
        seen.append((url, params))
        return _meteo()
    monkeypatch.setattr(requests, 'get', fake)
    today = timezone.localdate()
    assert journal_assist.fetch_weather(44.1234, -69.5678, today - timedelta(days=2)) == 'Light rain, 18° / 6°'
    journal_assist.fetch_weather(44.1234, -69.5678, today - timedelta(days=30), fahrenheit=True)
    assert seen[0][0] == journal_assist.FORECAST_URL and seen[1][0] == journal_assist.ARCHIVE_URL
    assert seen[0][1]['latitude'] == 44.12 and seen[0][1]['longitude'] == -69.57     # rounded
    assert seen[1][1]['temperature_unit'] == 'fahrenheit'


def test_weather_is_cached_and_a_future_day_asks_nothing(monkeypatch):
    calls = []
    monkeypatch.setattr(requests, 'get', lambda *a, **k: calls.append(1) or _meteo())
    d = timezone.localdate() - timedelta(days=20)
    journal_assist.fetch_weather(1.0, 2.0, d)
    journal_assist.fetch_weather(1.0, 2.0, d)
    assert len(calls) == 1
    assert journal_assist.fetch_weather(1.0, 2.0, timezone.localdate() + timedelta(days=1)) == ''
    assert len(calls) == 1


def test_weather_failure_is_silent(monkeypatch):
    def boom(*a, **k):
        raise requests.ConnectionError('down')
    monkeypatch.setattr(requests, 'get', boom)
    assert journal_assist.fetch_weather(1.0, 2.0, timezone.localdate()) == ''


def test_missing_values_are_blank(monkeypatch):
    monkeypatch.setattr(requests, 'get', lambda *a, **k: _Resp({'daily': {
        'weather_code': [None], 'temperature_2m_max': [None], 'temperature_2m_min': [None]}}))
    assert journal_assist.fetch_weather(1.0, 2.0, timezone.localdate() - timedelta(days=9)) == ''


def test_day_location_prefers_the_pin_then_the_track(user):
    d = date(2026, 10, 1)
    assert journal_assist.day_location(user, d) is None
    dev = Device.objects.create(user=user, device_id='phone')
    for i, lat in enumerate((10.0, 20.0, 30.0)):
        Location.objects.create(device=dev, latitude=lat, longitude=5.0,
                                timestamp=datetime(2026, 10, 1, 12, i, tzinfo=dt_timezone.utc))
    assert journal_assist.day_location(user, d) == (20.0, 5.0)
    e = JournalEntry.objects.create(user=user, date=d, pin_latitude=1.0, pin_longitude=2.0)
    assert journal_assist.day_location(user, d, e) == (1.0, 2.0)


def test_weather_api_honours_the_toggle(client, user, monkeypatch):
    monkeypatch.setattr(journal_assist, 'weather_for', lambda *a, **k: 'Clear, 20° / 10°')
    client.force_login(user)
    assert client.get('/api/journals/2026-10-01/weather/').json()['weather'] == 'Clear, 20° / 10°'
    assert not JournalEntry.objects.filter(user=user).exists()      # opening a day stores nothing
    UserProfile.objects.filter(user=user).update(journal_auto_weather=False)
    assert client.get('/api/journals/2026-10-01/weather/').json()['weather'] == ''
