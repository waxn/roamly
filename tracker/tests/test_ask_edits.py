"""Ask proposals: the assistant drafts a change, only the user's click applies it."""
import json
from datetime import date, timedelta

import pytest
from django.contrib.auth.models import User
from django.test import Client
from django.utils import timezone

from tracker import ai_tasks, ask_edits
from tracker.models import AskChat, AskProposal, CustomPlace, Flight, JournalEntry, UserProfile
from tracker.tests.test_ask_chat import _clean, _user, call, client, llm, send, user  # noqa: F401

pytestmark = pytest.mark.django_db


def approve(client, pid):
    return client.post(f'/api/ask/proposals/{pid}/approve/', '{}', content_type='application/json')


def deny(client, pid):
    return client.post(f'/api/ask/proposals/{pid}/deny/', '{}', content_type='application/json')


def propose(client, llm, *calls, message='do it'):
    llm.rounds = [list(calls), 'Waiting for your approval.']
    return send(client, message=message)


def tool_results(llm, n=1):
    return [json.loads(m['content']) for m in llm.sent[n]['messages'] if m['role'] == 'tool']


UA234 = dict(flight_number='UA234', date='2026-11-01', dep_iata='BOS', arr_iata='LIS',
             sched_dep='2026-11-01T22:05:00Z', sched_arr='2026-11-02T09:10:00Z',
             airline_name='United Airlines', aircraft_model='Boeing 767-300')


# ── Flights ─────────────────────────────────────────────────────────────

def test_proposing_a_flight_saves_nothing_until_approved(client, user, llm):
    evs = propose(client, llm, call('propose_add_flight', **UA234))
    assert not Flight.objects.exists()
    prop = next(e['proposal'] for e in evs if e['type'] == 'proposal')
    assert prop['status'] == 'pending'
    assert {'label': 'Flight', 'before': None, 'after': 'UA234'} in prop['rows']
    assert 'Nothing has been saved' in tool_results(llm)[0]['note']
    p = AskProposal.objects.get()
    assert p.message_id is not None  # attached to the saved reply

    r = approve(client, p.id)
    assert r.status_code == 200 and r.json()['proposal']['status'] == 'approved'
    f = Flight.objects.get(user=user)
    assert (f.flight_number, f.dep_iata, f.arr_iata, f.aircraft_model) == \
           ('UA234', 'BOS', 'LIS', 'Boeing 767-300')
    assert f.sched_dep.isoformat() == '2026-11-01T22:05:00+00:00'
    # A second click (or tab) never applies it twice.
    approve(client, p.id)
    assert Flight.objects.count() == 1


def test_denied_flight_is_never_saved_and_the_model_is_told(client, user, llm):
    propose(client, llm, call('propose_add_flight', **UA234))
    p = AskProposal.objects.get()
    assert deny(client, p.id).json()['proposal']['status'] == 'denied'
    assert approve(client, p.id).json()['proposal']['status'] == 'denied'
    assert not Flight.objects.exists()

    llm.rounds = ['ok']
    send(client, message='and?', chat_id=p.chat_id)
    replay = [m['content'] for m in llm.sent[-1]['messages'] if m['role'] == 'assistant']
    assert 'DENIED by the user' in replay[-1]


def test_model_cannot_slip_booking_details_in(client, user, llm):
    propose(client, llm, call('propose_add_flight', **UA234, booking_ref='ABC123',
                              ticket_number='0161234567890', source='boarding_pass'))
    p = AskProposal.objects.get()
    assert not {'booking_ref', 'ticket_number', 'source'} & set(p.payload)
    approve(client, p.id)
    f = Flight.objects.get()
    assert f.booking_ref == '' and f.ticket_number == '' and f.source == 'manual'


def test_duplicate_flight_is_refused_at_proposal(client, user, llm):
    Flight.objects.create(user=user, flight_number='UA234', date=date(2026, 11, 1),
                          dep_iata='BOS', arr_iata='LIS')
    propose(client, llm, call('propose_add_flight', **UA234))
    assert not AskProposal.objects.exists()
    assert 'already in the log' in tool_results(llm)[0]['error']


def test_flight_edit_changes_only_the_listed_fields(client, user, llm):
    f = Flight.objects.create(user=user, flight_number='UA234', date=date(2026, 11, 1),
                              dep_iata='BOS', arr_iata='LIS', seat='12A', notes='window',
                              booking_ref='XYZ789', passenger_name='RAE/TEST')
    before = Flight.objects.filter(pk=f.pk).values().get()
    propose(client, llm, call('propose_update_flight', flight_id=f.id,
                              changes={'seat': '3C', 'seat_class': 'business'}))
    p = AskProposal.objects.get()
    assert sorted(p.payload['after']) == ['seat', 'seat_class']
    assert {'label': 'Seat', 'before': '12A', 'after': '3C'} in p.rows
    assert Flight.objects.filter(pk=f.pk).values().get() == before

    assert approve(client, p.id).json()['proposal']['status'] == 'approved'
    after = Flight.objects.filter(pk=f.pk).values().get()
    changed = {k for k in after if after[k] != before[k]} - {'updated_at'}
    assert changed == {'seat', 'seat_class'}
    assert after['seat'] == '3C' and after['booking_ref'] == 'XYZ789'


def test_flight_edit_is_refused_when_the_flight_changed_since(client, user, llm):
    f = Flight.objects.create(user=user, flight_number='UA234', date=date(2026, 11, 1),
                              dep_iata='BOS', arr_iata='LIS', seat='12A')
    propose(client, llm, call('propose_update_flight', flight_id=f.id, changes={'seat': '3C'}))
    Flight.objects.filter(pk=f.pk).update(seat='40F')  # edited on the Flights page meanwhile
    out = approve(client, AskProposal.objects.get().id).json()['proposal']
    assert out['status'] == 'failed' and 'changed since' in out['error']
    assert Flight.objects.get(pk=f.pk).seat == '40F'


def test_cannot_edit_someone_elses_flight(client, user, llm):
    other = _user('sam')
    f = Flight.objects.create(user=other, date=date(2026, 1, 1), dep_iata='BOS', arr_iata='LIS')
    propose(client, llm, call('propose_update_flight', flight_id=f.id, changes={'seat': '1A'}))
    assert not AskProposal.objects.exists()
    assert 'No flight' in tool_results(llm)[0]['error']


# ── Journals ────────────────────────────────────────────────────────────

def _allow_journals(user):
    UserProfile.objects.filter(user=user).update(ai_allow_journals=True)


def test_journal_proposals_need_the_journal_opt_in(client, user, llm):
    propose(client, llm, call('propose_journal_entry', date='2026-11-01', title='Lisbon'))
    assert 'not enabled' in tool_results(llm)[0]['error']
    names = {t['function']['name'] for t in llm.sent[0]['tools']}
    assert 'propose_journal_entry' not in names and 'propose_add_flight' in names


def test_journal_fills_empty_fields_and_appends(client, user, llm):
    _allow_journals(user)
    JournalEntry.objects.create(user=user, date=date(2026, 11, 1), body='Landed early.', mood='')
    propose(client, llm, call('propose_journal_entry', date='2026-11-01', title='Lisbon',
                              mood='😊', append_text='Pastéis de nata in Belém.'))
    assert JournalEntry.objects.get().title == ''
    approve(client, AskProposal.objects.get().id)
    e = JournalEntry.objects.get()
    assert (e.title, e.mood) == ('Lisbon', '😊')
    assert e.body == 'Landed early.\n\nPastéis de nata in Belém.'


def test_journal_never_replaces_existing_text(client, user, llm):
    _allow_journals(user)
    JournalEntry.objects.create(user=user, date=date(2026, 11, 1), title='Mine')
    propose(client, llm, call('propose_journal_entry', date='2026-11-01', title='Theirs'))
    assert 'never replace' in tool_results(llm)[0]['error']
    assert not AskProposal.objects.exists()


def test_journal_append_refused_after_the_entry_was_edited(client, user, llm):
    _allow_journals(user)
    JournalEntry.objects.create(user=user, date=date(2026, 11, 1), body='One.')
    propose(client, llm, call('propose_journal_entry', date='2026-11-01', append_text='Two.'))
    JournalEntry.objects.update(body='One. Edited.')
    out = approve(client, AskProposal.objects.get().id).json()['proposal']
    assert out['status'] == 'failed'
    assert JournalEntry.objects.get().body == 'One. Edited.'


# ── Places ──────────────────────────────────────────────────────────────

def test_place_is_created_only_on_approval(client, user, llm):
    evs = propose(client, llm, call('propose_add_place', name='School', latitude=44.42,
                                    longitude=-69.01, radius_m=300))
    prop = next(e['proposal'] for e in evs if e['type'] == 'proposal')
    assert prop['pin'] == {'name': 'School', 'lat': 44.42, 'lon': -69.01}
    assert not CustomPlace.objects.exists()
    approve(client, prop['id'])
    place = CustomPlace.objects.get(user=user)
    assert (place.name, place.radius_m) == ('School', 300)


# ── Guards ──────────────────────────────────────────────────────────────

def test_no_tool_call_writes_on_its_own(client, user, llm, monkeypatch):
    """Every propose tool through the real loop, then no approval: nothing moves."""
    _allow_journals(user)
    # One call at a time: worker threads open their own DB connections, which
    # can't see the test's transaction.
    monkeypatch.setattr(ai_tasks, '_run_tool_calls', lambda user, calls, *a, **k: [
        ai_tasks._run_one_tool(user, c, *a, **k) for c in calls])
    f = Flight.objects.create(user=user, date=date(2026, 1, 1), dep_iata='BOS', arr_iata='LIS')

    def snapshot():
        return (list(Flight.objects.order_by('id').values()),
                list(JournalEntry.objects.order_by('id').values()),
                list(CustomPlace.objects.order_by('id').values()))

    before = snapshot()
    propose(client, llm,
            call('propose_add_flight', **UA234),
            call('propose_update_flight', flight_id=f.id, changes={'seat': '9B'}),
            call('propose_journal_entry', date='2026-11-01', title='Hi', append_text='x'),
            call('propose_add_place', name='Gym', latitude=1, longitude=2))
    assert AskProposal.objects.filter(status="pending").count() == 4
    assert snapshot() == before


def test_the_app_cannot_propose(user):
    profile = UserProfile.objects.get(user=user)
    names = {t['function']['name'] for t in ai_tasks.build_tool_schemas(profile, maps=True)}
    assert not names & ask_edits.EDIT_TOOL_NAMES
    out = ai_tasks._run_one_tool(user, call('propose_add_flight', **UA234), 0, False)
    assert 'error' in out and not AskProposal.objects.exists()


def test_proposals_are_private_and_need_csrf(client, user, llm):
    propose(client, llm, call('propose_add_place', name='Gym', latitude=1, longitude=2))
    pid = AskProposal.objects.get().id

    other = Client()
    other.force_login(_user('sam'))
    assert approve(other, pid).status_code == 404

    strict = Client(enforce_csrf_checks=True)
    strict.force_login(user)
    assert approve(strict, pid).status_code == 403
    assert not CustomPlace.objects.exists()


def test_failed_turn_leaves_no_approvable_proposal(client, user, llm):
    llm.rounds = [[call('propose_add_place', name='Gym', latitude=1, longitude=2)],
                  ai_tasks.AIProviderError('down')]
    evs = send(client, message='add my gym')
    assert evs[-1]['type'] == 'error'
    assert not AskProposal.objects.exists() and not AskChat.objects.exists()


def test_old_proposals_expire(client, user, llm):
    propose(client, llm, call('propose_add_place', name='Gym', latitude=1, longitude=2))
    AskProposal.objects.update(created_at=timezone.now() - timedelta(days=8))
    out = approve(client, AskProposal.objects.get().id).json()['proposal']
    assert out['status'] == 'failed' and 'expired' in out['error']
    assert not CustomPlace.objects.exists()


def test_reloaded_chat_shows_proposals(client, user, llm):
    propose(client, llm, call('propose_add_place', name='Gym', latitude=1, longitude=2))
    chat = AskChat.objects.get()
    msgs = client.get(f'/api/ask/chats/{chat.id}/').json()['messages']
    assert msgs[-1]['proposals'][0]['summary'] == 'Add place “Gym”'
