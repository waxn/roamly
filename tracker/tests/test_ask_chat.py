"""Ask chat page: saved chats, streamed turns, map cards — and read-only."""
import json
from datetime import date, datetime, timedelta, timezone as dt_timezone

import pytest
from django.contrib.auth.models import User
from django.core.cache import cache

from tracker import ai_tasks, ask_map, ask_tools
from tracker.models import (
    Adventure, AskChat, AskMessage, CustomPlace, Device, Flight, JournalEntry,
    Location, UserProfile,
)

pytestmark = pytest.mark.django_db

T0 = datetime(2026, 6, 19, 14, 0, tzinfo=dt_timezone.utc)


@pytest.fixture(autouse=True)
def _clean():
    cache.clear()
    yield
    cache.clear()


def _user(name):
    u = User.objects.create_user(name, password='pw-ask-chat-1')
    UserProfile.objects.update_or_create(user=u, defaults={
        'ai_ask_enabled': True, 'ai_base_url': 'https://llm.example/v1',
        'ai_api_key': 'k', 'ai_model': 'm',
    })
    return u


@pytest.fixture
def user():
    return _user('rae')


@pytest.fixture
def client(user, client):
    client.force_login(user)
    return client


@pytest.fixture
def device(user):
    return Device.objects.create(user=user, device_id='phone', name='Phone')


def track(device, day=T0, n=6, city='Waldo', state='Maine'):
    for i in range(n):
        Location.objects.create(
            device=device, latitude=44.4 + i * 0.001, longitude=-69.0,
            timestamp=day + timedelta(minutes=5 * i),
            city=city, state=state, country='United States', country_code='US')


@pytest.fixture
def llm(monkeypatch):
    """A scripted provider: each entry in ``rounds`` is one streamed reply."""
    class LLM:
        rounds = []
        sent = []

    def stream(profile, messages, tools=None):
        LLM.sent.append({'messages': list(messages), 'tools': tools})
        r = LLM.rounds.pop(0)
        if isinstance(r, Exception):
            raise r
        if isinstance(r, str):
            for i in range(0, len(r), 5):
                yield 'delta', r[i:i + 5]
            yield 'final', {'content': r, 'tool_calls': None}
        else:
            yield 'final', {'content': '', 'tool_calls': r}

    monkeypatch.setattr(ai_tasks, '_provider_stream', stream)
    monkeypatch.setattr(ai_tasks, 'followups', lambda p, q, a: ('Named chat', ['More?', 'And then?']))
    return LLM


def call(name, **args):
    return {'id': f'c-{name}', 'type': 'function',
            'function': {'name': name, 'arguments': json.dumps(args)}}


def events(resp):
    assert resp.status_code == 200, resp.content
    body = b''.join(resp.streaming_content).decode()
    return [json.loads(line) for line in body.splitlines() if line.strip()]


def send(client, **body):
    return events(client.post('/api/ask/send/', json.dumps({'tz_offset': 0, **body}),
                              content_type='application/json'))


# ── Saving ──────────────────────────────────────────────────────────────

def test_first_turn_creates_and_names_a_chat(client, user, llm):
    llm.rounds = ['You were in Waldo.']
    evs = send(client, message='Where was I?')
    types = [e['type'] for e in evs]
    assert types[0] == 'chat' and 'done' in types and types[-1] == 'followups'
    chat = AskChat.objects.get(user=user)
    assert chat.title == 'Named chat' and chat.titled
    msgs = list(chat.messages.values_list('role', 'content'))
    assert msgs == [('user', 'Where was I?'), ('assistant', 'You were in Waldo.')]
    assert chat.messages.last().meta['followups'] == ['More?', 'And then?']


def test_second_turn_sends_history_and_keeps_a_typed_title(client, user, llm):
    llm.rounds = ['One.', 'Two.']
    send(client, message='first')
    chat = AskChat.objects.get(user=user)
    client.post(f'/api/ask/chats/{chat.id}/rename/', json.dumps({'title': 'Mine'}),
                content_type='application/json')
    send(client, message='second', chat_id=chat.id)
    roles = [m['role'] for m in llm.sent[-1]['messages']]
    assert roles == ['system', 'user', 'assistant', 'user']
    chat.refresh_from_db()
    assert chat.title == 'Mine'


def test_provider_failure_leaves_nothing_behind(client, user, llm):
    llm.rounds = [ai_tasks.AIProviderError('The AI provider timed out. Try again.')]
    evs = send(client, message='hello')
    assert evs[-1] == {'type': 'error', 'error': 'The AI provider timed out. Try again.', 'chat_deleted': True}
    assert not AskChat.objects.filter(user=user).exists()


def test_regenerate_replaces_the_last_answer_and_can_edit_the_question(client, user, llm):
    llm.rounds = ['Old answer.', 'New answer.']
    send(client, message='Where?')
    chat = AskChat.objects.get(user=user)
    send(client, chat_id=chat.id, regenerate=True, message='Where exactly?')
    msgs = list(chat.messages.values_list('role', 'content'))
    assert msgs == [('user', 'Where exactly?'), ('assistant', 'New answer.')]


def test_failed_regenerate_keeps_the_old_answer_and_question(client, user, llm):
    llm.rounds = ['Old answer.', ai_tasks.AIProviderError('down')]
    send(client, message='Where?')
    chat = AskChat.objects.get(user=user)
    send(client, chat_id=chat.id, regenerate=True, message='Edited')
    msgs = list(chat.messages.values_list('role', 'content'))
    assert msgs == [('user', 'Where?'), ('assistant', 'Old answer.')]


def test_chats_are_private(client, user, llm):
    other = _user('sam')
    theirs = AskChat.objects.create(user=other, title='Secret')
    AskMessage.objects.create(chat=theirs, role='user', content='private')
    assert client.get(f'/api/ask/chats/{theirs.id}/').status_code == 404
    assert client.post(f'/api/ask/chats/{theirs.id}/delete/', '{}', content_type='application/json').status_code == 200
    assert AskChat.objects.filter(pk=theirs.pk).exists()
    resp = client.post('/api/ask/send/', json.dumps({'message': 'hi', 'chat_id': theirs.id}),
                       content_type='application/json')
    assert resp.status_code == 404
    assert client.get('/api/ask/chats/?q=private').json()['chats'] == []


def test_search_and_pin(client, user):
    a = AskChat.objects.create(user=user, title='Portugal')
    b = AskChat.objects.create(user=user, title='Flights')
    AskMessage.objects.create(chat=b, role='user', content='longest flight to Lisbon')
    client.post(f'/api/ask/chats/{b.id}/pin/', json.dumps({'pinned': True}), content_type='application/json')
    rows = client.get('/api/ask/chats/').json()['chats']
    assert rows[0]['id'] == b.id and rows[0]['pinned']
    found = {r['id'] for r in client.get('/api/ask/chats/?q=lisbon').json()['chats']}
    assert found == {b.id} and a.id not in found


def test_disabled_ask_is_refused(client, user):
    UserProfile.objects.filter(user=user).update(ai_ask_enabled=False)
    assert client.get('/api/ask/chats/').status_code == 403
    resp = client.post('/api/ask/send/', json.dumps({'message': 'x'}), content_type='application/json')
    assert resp.status_code == 403


def test_send_requires_csrf(user):
    from django.test import Client
    c = Client(enforce_csrf_checks=True)
    c.force_login(user)
    resp = c.post('/api/ask/send/', json.dumps({'message': 'x'}), content_type='application/json')
    assert resp.status_code == 403


# ── Maps ────────────────────────────────────────────────────────────────

def test_show_map_streams_a_card_and_stores_it(client, user, device, llm):
    track(device)
    llm.rounds = [[call('show_map', date='2026-06-19', title='Friday')], 'Here it is.']
    evs = send(client, message='Show Friday')
    cards = [e['card'] for e in evs if e['type'] == 'map']
    assert cards == [{'date': '2026-06-19', 'title': 'Friday'}]
    tool_msg = llm.sent[1]['messages'][-1]
    assert tool_msg['role'] == 'tool' and '_card' not in tool_msg['content']
    assert json.loads(tool_msg['content'])['point_count'] == 6
    assert AskMessage.objects.get(role='assistant').cards == cards


def test_map_geometry_is_the_users_own(client, user, device):
    track(device)
    other_dev = Device.objects.create(user=_user('sam'), device_id='p2', name='P2')
    track(other_dev, n=4)
    geo = client.get('/api/ask/map/?date=2026-06-19&tz_offset=0').json()
    assert geo['count'] == 6 and geo['mode'] == 'lines' and geo['url'] == '/map/?date=2026-06-19'
    assert client.get('/api/ask/map/?date=nope').status_code == 400


def test_flights_card(client, user):
    Flight.objects.create(user=user, date=date(2025, 5, 1), dep_iata='BOS', arr_iata='LIS',
                          dep_lat=42.36, dep_lon=-71.01, arr_lat=38.77, arr_lon=-9.13)
    Flight.objects.create(user=user, date=date(2025, 5, 9), dep_iata='LIS', arr_iata='BOS',
                          dep_lat=38.77, dep_lon=-9.13, arr_lat=42.36, arr_lon=-71.01)
    geo = client.get('/api/ask/map/?kind=flights&year=2025').json()
    assert geo['count'] == 2 and len(geo['arcs']) == 1 and geo['arcs'][0]['n'] == 2


def test_map_tools_are_not_offered_to_the_app(user):
    names = {t['function']['name'] for t in ai_tasks.build_tool_schemas(UserProfile.objects.get(user=user))}
    assert not names & ai_tasks._MAP_TOOL_NAMES
    assert {'list_flights', 'get_stays', 'search_near'} <= names


# ── Tools ───────────────────────────────────────────────────────────────

def test_flight_tools_hide_booking_details(user):
    Flight.objects.create(user=user, date=date(2025, 5, 1), flight_number='TP218',
                          dep_iata='BOS', arr_iata='LIS', distance_km=4800,
                          booking_ref='ABC123', ticket_number='0471234567890',
                          passenger_name='DOE/RAE', frequent_flyer='TP 123', bcbp_raw='M1DOE')
    blob = json.dumps([ask_tools.list_flights(user), ask_tools.get_flight_stats(user)], default=str)
    for secret in ('ABC123', '0471234567890', 'DOE/RAE', 'TP 123', 'M1DOE'):
        assert secret not in blob
    assert ask_tools.get_flight_stats(user)['flights'] == 1


def test_search_near(user, device):
    track(device)
    assert ask_tools.search_near(user, latitude=44.4, longitude=-69.0, radius_m=500)['been_there']
    assert not ask_tools.search_near(user, latitude=0, longitude=0)['been_there']
    assert 'error' in ask_tools.search_near(user, latitude=999, longitude=0)


# transaction=True: several tool calls in one turn run on worker threads with
# their own connections, which can't see a test's uncommitted rows.
@pytest.mark.django_db(transaction=True)
def test_every_tool_is_read_only(client, user, device, llm):
    """Run every tool the chat offers, through the real loop, and check that no
    user data changed. The chat's own rows are the only thing written."""
    track(device)
    Flight.objects.create(user=user, date=date(2025, 5, 1), dep_iata='BOS', arr_iata='LIS',
                          dep_lat=42.36, dep_lon=-71.01, arr_lat=38.77, arr_lon=-9.13)
    CustomPlace.objects.create(user=user, name='Home', latitude=44.4, longitude=-69.0, radius_m=200)
    Adventure.objects.create(device=device, creator=user, name='Maine', start_time=T0,
                             end_time=T0 + timedelta(days=1))
    JournalEntry.objects.create(user=user, date=date(2026, 6, 19), title='Good day', body='Sun')

    def snapshot():
        return (list(Location.objects.order_by('id').values()),
                list(Flight.objects.order_by('id').values()),
                list(CustomPlace.objects.order_by('id').values()),
                list(Adventure.objects.order_by('id').values()),
                list(JournalEntry.objects.order_by('id').values()))

    before = snapshot()
    calls = [
        call('search_history', query='Waldo'), call('get_day_detail', date='2026-06-19'),
        call('list_visited_places'), call('list_custom_places'),
        call('get_custom_place_detail', name='Home'), call('get_distance'),
        call('get_history_overview'), call('list_flights'), call('get_flight_stats'),
        call('list_adventures'), call('get_adventure', name='Maine'), call('get_period_summary'),
        call('get_travel_modes'), call('get_stays', start_date='2026-06-18', end_date='2026-06-20'),
        call('get_achievements'), call('list_unvisited', level='us_state'),
        call('search_near', latitude=44.4, longitude=-69.0),
        call('show_map', date='2026-06-19'), call('show_flights_map'),
        call('show_places', places=[{'name': 'Bar Harbor', 'latitude': 44.39, 'longitude': -68.2}]),
        call('read_journal_entry', date='2026-06-19'),
    ]
    llm.rounds = [calls, 'Done.']
    evs = send(client, message='Everything, please')
    assert any(e['type'] == 'done' for e in evs)
    assert snapshot() == before
    results = [json.loads(m['content']) for m in llm.sent[1]['messages'] if m['role'] == 'tool']
    assert len(results) == len(calls)
    # Journals were not opted in, so the journal tool was refused.
    assert 'not enabled' in results[-1]['error']


def test_clean_card_rejects_junk():
    for bad in ({}, {'date': 'x'}, {'kind': 'drop_table'}, {'kind': 'pins', 'pins': 'x'}):
        with pytest.raises(ask_map.CardError):
            ask_map.clean_card(bad)


def test_mode_is_kept_and_auto_clears_it(client, user, llm):
    llm.rounds = ['a', 'b', 'c']
    send(client, message='plan it', mode='plan')
    chat = AskChat.objects.get(user=user)
    assert chat.mode == 'plan'
    send(client, message='more', chat_id=chat.id)
    assert 'day-by-day plan' in llm.sent[-1]['messages'][0]['content']
    send(client, message='just facts', chat_id=chat.id, mode='')
    chat.refresh_from_db()
    assert chat.mode == '' and 'day-by-day plan' not in llm.sent[-1]['messages'][0]['content']


def test_bad_chat_id_is_not_found(client, llm):
    resp = client.post('/api/ask/send/', json.dumps({'message': 'x', 'chat_id': 'abc'}),
                       content_type='application/json')
    assert resp.status_code == 404


# ── Streaming details ───────────────────────────────────────────────────

class _SSE:
    """A streamed provider reply, as requests hands it over: raw bytes."""
    def __init__(self, chunks, status=200):
        self.status_code = status
        self.headers = {'Content-Type': 'text/event-stream'}  # no charset, like most servers
        self._lines = [('data: ' + json.dumps(c, ensure_ascii=False)).encode() for c in chunks]
        self._lines.append(b'data: [DONE]')

    def iter_lines(self, decode_unicode=False):
        yield from self._lines

    def json(self):
        return {'error': {'message': 'Unrecognized request argument: stream_options'}}

    def close(self):
        pass


def test_stream_decodes_utf8_and_retries_without_stream_options(monkeypatch):
    sent = []

    def post(url, json=None, **kw):
        sent.append(json)
        if 'stream_options' in json:
            return _SSE([], status=400)
        return _SSE([{'choices': [{'delta': {'content': 'Where next? 🌍 — '}}]},
                     {'choices': [{'delta': {'content': 'Lisboa'}}]},
                     {'choices': [], 'usage': {'prompt_tokens': 50, 'completion_tokens': 7}}])

    monkeypatch.setattr(ai_tasks, 'validate_outbound_url', lambda *a, **k: None)
    monkeypatch.setattr(ai_tasks._session, 'post', post)
    monkeypatch.setattr(ai_tasks, '_NO_STREAM_OPTIONS', set())
    profile = type('P', (), {'ai_base_url': 'https://strict.example', 'ai_model': 'm', 'ai_api_key': 'k'})()
    ev = list(ai_tasks._provider_stream(profile, []))
    assert ''.join(v for k, v in ev if k == 'delta') == 'Where next? 🌍 — Lisboa'
    assert ev[-1][1]['usage']['completion_tokens'] == 7
    assert 'stream_options' in sent[0] and 'stream_options' not in sent[1]


def test_turn_stats_are_streamed_and_saved(client, user, llm):
    llm.rounds = ['Twelve chars']
    evs = send(client, message='hi')
    stats = next(e for e in evs if e['type'] == 'done')['stats']
    # The fake provider reports no usage, so output is an estimate.
    assert stats['estimated'] and stats['out_tokens'] == 3 and stats['rounds'] == 1
    assert AskMessage.objects.get(role='assistant').meta['stats'] == stats
