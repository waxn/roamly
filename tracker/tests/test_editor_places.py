"""Map editor: filtering to a place, and relabelling what it finds."""
import json
from datetime import datetime, timezone as dt_timezone

import pytest
from django.contrib.auth.models import User
from django.core.cache import cache

from tracker.models import Device, Location

pytestmark = pytest.mark.django_db


@pytest.fixture(autouse=True)
def _clean(monkeypatch):
    # A real thread here would fight the test transaction for the database.
    monkeypatch.setattr('tracker.stats_tasks.start_stats_compute', lambda user_id: None)
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


def post(client, url, body):
    return client.post(url, json.dumps(body), content_type='application/json')


def labels(loc):
    loc.refresh_from_db()
    return loc.city, loc.state, loc.country, loc.country_code, loc.place_name


def test_filter_relabel_reaches_only_the_place_and_dates(client, device):
    inside = point(device, 2, 'Morrill')
    late = point(device, 20, 'Morrill')
    other = point(device, 2, 'Belmont', hour=13)
    res = post(client, '/api/editor/relabel/', {
        'filters': 'city=Morrill&start_date=2026-03-01T00:00:00%2B00:00&end_date=2026-03-05T23:59:59%2B00:00',
        'city': 'Waldo'}).json()
    assert res['points'] == 1
    assert labels(inside) == ('Waldo', 'Maine', 'United States', 'US', 'Waldo, Maine, United States')
    assert labels(late)[0] == 'Morrill'
    assert labels(other)[0] == 'Belmont'


def test_blank_fields_keep_each_points_own_value(client, device):
    a = point(device, 1, 'Morrill', state='Maine')
    b = point(device, 2, 'Morrill', state='Vermont')
    post(client, '/api/editor/relabel/', {'scope': 'selection', 'ids': [a.id, b.id], 'city': 'Waldo'})
    assert labels(a)[:2] == ('Waldo', 'Maine')
    assert labels(b)[:2] == ('Waldo', 'Vermont')


def test_relabel_needs_a_place_or_selection_and_a_name(client, device):
    point(device, 1, 'Morrill')
    assert post(client, '/api/editor/relabel/', {'filters': 'all=1', 'city': 'Waldo'}).status_code == 400
    assert post(client, '/api/editor/relabel/', {'scope': 'selection', 'ids': [], 'city': 'Waldo'}).status_code == 400
    assert post(client, '/api/editor/relabel/', {'filters': 'all=1&city=Morrill'}).status_code == 400


def test_relabel_cannot_touch_another_account(client, device):
    other = User.objects.create_user('other', password='pw-other-1')
    theirs = point(Device.objects.create(user=other, device_id='x'), 1, 'Morrill')
    res = post(client, '/api/editor/relabel/', {'scope': 'selection', 'ids': [theirs.id], 'city': 'Waldo'}).json()
    assert res['points'] == 0
    assert labels(theirs)[0] == 'Morrill'


def test_country_sets_its_code_and_a_typo_is_refused(client, device, monkeypatch):
    monkeypatch.setattr('tracker.offline_geocode.country_by_name',
                        lambda n: ('Canada', 'CA') if n.lower() == 'canada' else None)
    loc = point(device, 1, 'Eastport')
    ids = {'scope': 'selection', 'ids': [loc.id]}
    assert post(client, '/api/editor/relabel/', {**ids, 'country': 'Cananda'}).status_code == 400
    post(client, '/api/editor/relabel/', {**ids, 'country': 'canada'})
    assert labels(loc)[2:4] == ('Canada', 'CA')


def test_undo_restores_labels_but_not_over_a_newer_edit(client, device):
    a = point(device, 1, 'Morrill')
    b = point(device, 2, 'Morrill')
    before = labels(a)
    batch = post(client, '/api/editor/relabel/', {'filters': 'all=1&city=Morrill', 'city': 'Waldo'}).json()['batch_id']
    post(client, '/api/editor/relabel/', {'scope': 'selection', 'ids': [b.id], 'city': 'Belmont'})
    res = post(client, f'/api/editor/batches/{batch}/undo/', {}).json()
    assert res['affected'] == 1
    assert labels(a) == before
    assert labels(b)[0] == 'Belmont'


def test_regeocode_leaves_hand_labels_until_undone(client, device, user, monkeypatch):
    from tracker import regeocode_tasks
    monkeypatch.setattr(regeocode_tasks, 'ensure_boundaries', lambda *a, **k: None)
    monkeypatch.setattr('tracker.offline_geocode.local_reverse_geocode', lambda lat, lon: {
        'city': 'Brooks', 'state': 'Maine', 'country': 'United States', 'country_code': 'US',
        'place_name': 'Brooks, Maine, United States', 'source': 'boundary', 'zone_cc': 'US'})
    kept = point(device, 1, 'Morrill')
    free = point(device, 2, 'Belmont')
    batch = post(client, '/api/editor/relabel/', {'scope': 'selection', 'ids': [kept.id], 'city': 'Waldo'}).json()['batch_id']

    regeocode_tasks.regeocode([user.id], rebuild=True)
    assert labels(kept)[0] == 'Waldo'
    assert labels(free)[0] == 'Brooks'

    post(client, f'/api/editor/batches/{batch}/undo/', {})
    regeocode_tasks.regeocode([user.id])
    assert labels(kept)[0] == 'Brooks'


def test_overwrite_hand_replaces_hand_labels_and_retires_the_edit(client, device, user, monkeypatch):
    from tracker import regeocode_tasks
    from tracker.models import EditBatch
    monkeypatch.setattr(regeocode_tasks, 'ensure_boundaries', lambda *a, **k: None)
    monkeypatch.setattr('tracker.offline_geocode.local_reverse_geocode', lambda lat, lon: {
        'city': 'Brooks', 'state': 'Maine', 'country': 'United States', 'country_code': 'US',
        'place_name': 'Brooks, Maine, United States', 'source': 'nearest', 'zone_cc': 'US'})
    hand = point(device, 1, 'Morrill')
    guess = point(device, 2, 'Belmont')
    post(client, '/api/editor/relabel/', {'scope': 'selection', 'ids': [hand.id], 'city': 'Waldo'})

    # A nearest-town guess is not a reason to touch `guess`, even so.
    regeocode_tasks.regeocode([user.id], overwrite_hand=True)
    assert labels(hand)[0] == 'Brooks'
    assert labels(guess)[0] == 'Belmont'
    assert not EditBatch.objects.filter(kind='relabel', undone=False).exists()


def test_stopping_an_overwrite_keeps_hand_labels_and_their_undo(client, device, user, monkeypatch):
    from tracker import regeocode_tasks
    from tracker.models import EditBatch
    monkeypatch.setattr(regeocode_tasks, 'ensure_boundaries', lambda *a, **k: None)
    monkeypatch.setattr('tracker.offline_geocode.local_reverse_geocode', lambda lat, lon: {
        'city': 'Brooks', 'state': 'Maine', 'country': 'United States', 'country_code': 'US',
        'place_name': 'Brooks, Maine, United States', 'source': 'nearest', 'zone_cc': 'US'})
    hand = point(device, 1, 'Morrill')
    post(client, '/api/editor/relabel/', {'scope': 'selection', 'ids': [hand.id], 'city': 'Waldo'})
    calls = []

    def stop_in_apply():
        calls.append(1)
        return len(calls) > 3

    with pytest.raises(regeocode_tasks._Stopped):
        regeocode_tasks.regeocode([user.id], overwrite_hand=True, should_stop=stop_in_apply)
    assert labels(hand)[0] == 'Waldo'
    assert EditBatch.objects.filter(kind='relabel', undone=False).exists()


def test_regeocode_start_passes_the_toggle(client, device, monkeypatch):
    from tracker import regeocode_tasks
    seen = {}
    monkeypatch.setattr(regeocode_tasks, 'start', lambda uid, **kw: seen.update(kw) or {'status': 'running'})
    post(client, '/api/geocode/relabel/', {'overwrite_hand': True})
    assert seen == {'rebuild': False, 'overwrite_hand': True}


def test_preview_counts_and_batches_list_stays_light(client, device):
    point(device, 1, 'Morrill')
    point(device, 2, 'Morrill')
    d = post(client, '/api/editor/relabel/preview/', {'filters': 'all=1&city=Morrill'}).json()
    assert d['points'] == 2 and d['labels'][0]['city'] == 'Morrill'
    assert 'Morrill' in d['options']['city']
    post(client, '/api/editor/relabel/', {'filters': 'all=1&city=Morrill', 'city': 'Waldo'})
    row = client.get('/api/editor/batches/').json()['batches'][0]
    assert row['kind'] == 'relabel' and row['point_count'] == 2


def test_editor_page_renders_the_place_tools(client, device):
    html = client.get('/editor/').content.decode()
    assert 'id="place-search"' in html and 'id="panel-relabel"' in html
