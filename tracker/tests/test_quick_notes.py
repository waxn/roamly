"""Quick notes ("what I did today"): stored on the day's JournalEntry.

Journals shows the note, but the notes endpoints never hand out the journal's
own title or body: that is the one-way rule the feature is built on.
"""
import json
from datetime import date

import pytest
from django.core.cache import cache

from tracker.models import JournalEntry, JournalPhoto, UserProfile

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
def client_in(client, user):
    client.force_login(user)
    return client


def _save(client, day, note):
    return client.post(f'/api/notes/{day}/', data=json.dumps({'note': note}),
                       content_type='application/json')


def test_save_creates_entry_and_reads_back(client_in, user):
    r = _save(client_in, '2026-11-20', '  walked to the market  ')
    assert r.status_code == 200
    assert r.json()['note'] == 'walked to the market'
    entry = JournalEntry.objects.get(user=user, date=date(2026, 11, 20))
    assert entry.quick_note == 'walked to the market'
    assert entry.body == ''
    assert client_in.get('/api/notes/2026-11-20/').json()['note'] == 'walked to the market'


def test_empty_day_reads_blank(client_in):
    r = client_in.get('/api/notes/2026-11-20/')
    assert r.status_code == 200
    assert r.json() == {'date': '2026-11-20', 'note': '', 'updated_at': None}


def test_list_never_includes_journal_body(client_in, user):
    JournalEntry.objects.create(user=user, date=date(2026, 11, 18), title='Secret', body='private body')
    JournalEntry.objects.create(user=user, date=date(2026, 11, 19), body='more', quick_note='bike ride')
    notes = client_in.get('/api/notes/').json()['notes']
    assert notes == [{'date': '2026-11-19', 'note': 'bike ride', 'updated_at': notes[0]['updated_at']}]
    assert 'private body' not in json.dumps(notes)


def test_list_respects_range(client_in, user):
    for d in (10, 15, 20):
        JournalEntry.objects.create(user=user, date=date(2026, 11, d), quick_note=f'day {d}')
    notes = client_in.get('/api/notes/?start=2026-11-12&end=2026-11-20').json()['notes']
    assert [n['date'] for n in notes] == ['2026-11-20', '2026-11-15']
    assert client_in.get('/api/notes/?start=nope').status_code == 400


def test_clearing_removes_note_only_entry(client_in, user):
    _save(client_in, '2026-11-20', 'something')
    _save(client_in, '2026-11-20', '')
    assert not JournalEntry.objects.filter(user=user).exists()


def test_clearing_keeps_a_real_journal_entry(client_in, user):
    JournalEntry.objects.create(user=user, date=date(2026, 11, 20), body='a long day', quick_note='x')
    _save(client_in, '2026-11-20', '')
    entry = JournalEntry.objects.get(user=user)
    assert entry.quick_note == '' and entry.body == 'a long day'


def test_clearing_keeps_entry_with_photo(client_in, user):
    e = JournalEntry.objects.create(user=user, date=date(2026, 11, 20), quick_note='x')
    JournalPhoto.objects.create(entry=e, image='journals/a.jpg')
    _save(client_in, '2026-11-20', '')
    assert JournalEntry.objects.filter(pk=e.pk).exists()


def test_saving_note_leaves_journal_fields_alone(client_in, user):
    JournalEntry.objects.create(user=user, date=date(2026, 11, 20), title='T', body='B', mood='🙂')
    _save(client_in, '2026-11-20', 'quick')
    e = JournalEntry.objects.get(user=user)
    assert (e.title, e.body, e.mood, e.quick_note) == ('T', 'B', '🙂', 'quick')


def test_journal_detail_shows_the_note(client_in, user):
    _save(client_in, '2026-11-20', 'saw a heron')
    entry = client_in.get('/api/journals/2026-11-20/').json()['entry']
    assert entry['exists'] and entry['quick_note'] == 'saw a heron'
    listed = client_in.get('/api/journals/?year=2026&month=11').json()['entries']
    assert listed[0]['quick_note'] == 'saw a heron'


def test_notes_are_per_user(client_in, django_user_model):
    other = django_user_model.objects.create_user('pal', password='pw-123456')
    JournalEntry.objects.create(user=other, date=date(2026, 11, 20), quick_note='theirs')
    assert client_in.get('/api/notes/').json()['notes'] == []
    assert client_in.get('/api/notes/2026-11-20/').json()['note'] == ''


def test_rejects_bad_input(client_in):
    assert client_in.get('/api/notes/not-a-date/').status_code == 400
    assert _save(client_in, '2026-11-20', 'x' * 2001).status_code == 400
    r = client_in.post('/api/notes/2026-11-20/', data=json.dumps({'note': 5}),
                       content_type='application/json')
    assert r.status_code == 400


def test_form_post_without_intent_is_refused(client_in, user):
    # A cross-site form can only send form-encoded bodies with no custom headers.
    r = client_in.post('/api/notes/2026-11-20/', data={'note': 'forged'})
    assert r.status_code in (400, 403)
    assert not JournalEntry.objects.filter(user=user).exists()


def test_requires_login(client):
    r = client.get('/api/notes/')
    assert r.status_code in (302, 401, 403)
