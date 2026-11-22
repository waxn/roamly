"""Map transport-mode filter (?modes=): every render path narrows the same way."""
from datetime import datetime, timedelta, timezone as dt_timezone
from types import SimpleNamespace

import pytest
from django.contrib.auth.models import User
from django.core.cache import cache
from django.http import QueryDict

from tracker import roads, views
from tracker.models import Device, Location

pytestmark = pytest.mark.django_db

T0 = datetime(2026, 3, 1, 12, 0, tzinfo=dt_timezone.utc)
DAY = {'start_date': '2026-03-01', 'end_date': '2026-03-01'}


@pytest.fixture(autouse=True)
def _clean(monkeypatch):
    monkeypatch.setattr('tracker.stats_tasks.start_stats_compute', lambda user_id: None)
    # The All-time overview starts a tile warm-up thread, which would fight the
    # test transaction for the database.
    monkeypatch.setattr(views, '_kick_tile_warmup', lambda *a, **k: None)
    monkeypatch.setattr(roads, 'snap_points', lambda profile, pts: {})
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def user():
    return User.objects.create_user('mover', password='pw-mover-1')


@pytest.fixture
def client(user, client):
    client.force_login(user)
    return client


@pytest.fixture
def device(user):
    return Device.objects.create(user=user, device_id='phone', name='Phone')


def point(device, minute, mode, lat=44.40, lon=-69.00):
    return Location.objects.create(
        device=device, latitude=lat, longitude=lon, accuracy=5, speed=1.0,
        timestamp=T0 + timedelta(minutes=minute), transport_mode=mode)


@pytest.fixture
def mixed(device):
    """Two walk fixes, two vehicle fixes and one never classified, each in its
    own ~110m overview cell."""
    return {
        'walk': [point(device, 0, 'walk', lon=-69.000), point(device, 1, 'walk', lon=-69.002)],
        'vehicle': [point(device, 2, 'vehicle', lon=-69.010), point(device, 3, 'vehicle', lon=-69.020)],
        '': [point(device, 4, '', lon=-69.030)],
    }


def ids(*groups):
    return sorted(p.id for g in groups for p in g)


def track_ids(client, **params):
    devices = client.get('/api/track/', {'all': '1', **params}).json()['devices']
    return sorted(p['id'] for d in devices for p in d['points'])


def test_mode_filter_parsing():
    def parse(qs):
        return views._mode_filter(QueryDict(qs))
    assert parse('') is None
    assert parse('modes=walk,none') == ('', 'walk')
    assert parse('modes=walk,bogus') == ('walk',)
    # Nothing ticked matches nothing, which is not the same as no filter.
    assert parse('modes=') == ()
    assert parse('modes=still,walk,cycle,vehicle,plane,none') is None


def test_track_narrows_to_the_chosen_modes(client, mixed):
    assert track_ids(client) == ids(*mixed.values())
    assert track_ids(client, modes='walk') == ids(mixed['walk'])
    assert track_ids(client, modes='walk,none') == ids(mixed['walk'], mixed[''])
    assert track_ids(client, modes='') == []


def test_detail_points_narrow_too(client, mixed):
    res = client.get('/api/locations/', {'all': '1', 'modes': 'vehicle'}).json()
    assert sorted(p['id'] for p in res['locations']) == ids(mixed['vehicle'])


def test_detail_points_say_how_they_moved(client, mixed):
    devices = client.get('/api/locations/', {'all': '1'}).json()['devices']
    modes = {p['id']: p['transport_mode'] for d in devices for p in d['locations']}
    assert modes[mixed['walk'][0].id] == 'walk' and modes[mixed[''][0].id] == ''


def test_bounds_follow_the_filter(client, mixed):
    b = client.get('/api/locations/bounds/', {'all': '1', 'modes': 'vehicle'}).json()['bounds']
    assert b == [[-69.02, 44.4], [-69.01, 44.4]]


def test_all_time_overview_counts_only_chosen_modes(client, mixed):
    def total(**params):
        cells = client.get('/api/map/overview/', params).json()['cells']
        return sum(cells[2::3])
    assert total() == 5
    # Cached per filter: the unfiltered answer must not be served for this one.
    assert total(modes='walk') == 2
    assert total(modes='none') == 1


@pytest.mark.skipif(not views.HAS_POSTGIS, reason='vector tiles need PostGIS')
def test_vector_tiles_follow_the_filter(client, mixed):
    z = 10
    x, y = views._tile_coords(44.40, -69.01, z)
    url = f'/api/tiles/{z}/{x}/{y}.pbf'
    assert client.get(url, {'all': '1', 'modes': 'walk'}).status_code == 200
    # Same tile, different filter: a separate cache entry, and nothing in it.
    assert client.get(url, {'all': '1', 'modes': 'plane'}).status_code == 204
    assert client.get(url, {'all': '1'}).status_code == 200


def test_hidden_points_break_a_line_rather_than_chord_across_it():
    pts = [{'id': i, 'lat': 44.4, 'lng': -69.0 + i * 0.01, 'accuracy': 5, 'speed': 1.5,
            'transport_mode': mode, 'flag': '', 'ts': (T0 + timedelta(minutes=i)).timestamp(),
            'dt': T0 + timedelta(minutes=i)}
           for i, mode in enumerate(['walk', 'walk', 'vehicle', 'vehicle', 'walk', 'walk'])]
    profile = SimpleNamespace(snap_to_roads=False)
    one = views._build_line_segments(pts, [], profile, 3600, [0])
    assert len(one) == 1
    walks = views._build_line_segments(pts, [], profile, 3600, [0], keep_modes=('walk',))
    assert [len(s['coords']) for s in walks] == [2, 2]


def test_dwell_blobs_follow_the_still_button(client, device):
    for m in range(12):
        point(device, m, '')

    def dwells(**params):
        devices = client.get('/api/track/lines/', {**DAY, **params}).json()['devices']
        return [d for dev in devices for d in dev['dwells']]
    assert len(dwells()) == 1
    assert dwells(modes='walk,none') == []
    assert len(dwells(modes='still,none')) == 1
