"""Auto-fill of single missed fixes: only on snapped roads, inferred only."""
from datetime import datetime, timedelta, timezone as dt_timezone

import pytest
from django.contrib.auth.models import User
from django.core.cache import cache

from tracker import gapfill_tasks, roads
from tracker.models import Device, InferredLocation, Location, UserProfile

pytestmark = pytest.mark.django_db

T0 = datetime(2026, 11, 1, 12, 0, tzinfo=dt_timezone.utc)


@pytest.fixture(autouse=True)
def _clean(monkeypatch):
    monkeypatch.setattr('tracker.stats_tasks.start_stats_compute', lambda user_id: None)
    cache.clear()
    yield
    cache.clear()


def row(i, secs, lng):
    return {'id': i, 'lat': 44.0, 'lng': lng, 'accuracy': 5, 'speed': 15.0,
            'transport_mode': 'vehicle', 'timestamp': T0 + timedelta(seconds=secs)}


def fake_snap(profile, pts):
    return {p['id']: (p['lng'], p['lat']) for p in pts}


def fake_route(profile, a, b, mode=''):
    return [a, b]


@pytest.fixture
def road(monkeypatch):
    monkeypatch.setattr(roads, 'snap_points', fake_snap)
    monkeypatch.setattr(roads, 'route_between', fake_route)


def test_fills_one_midpoint_at_mid_time(road):
    rows = [row(1, 0, -69.000), row(2, 10, -68.999), row(3, 40, -68.995), row(4, 50, -68.994)]
    out = gapfill_tasks.fill_window(None, rows, [])
    assert len(out) == 1
    lng, lat, when = out[0]
    assert when == T0 + timedelta(seconds=25)
    assert -68.999 < lng < -68.995


@pytest.mark.parametrize('gap', [19, 46])
def test_gap_outside_bounds_is_left_alone(road, gap):
    rows = [row(1, 0, -69.000), row(2, gap, -68.996)]
    assert gapfill_tasks.fill_window(None, rows, []) == []


def test_unsnapped_neighbour_does_nothing(monkeypatch):
    monkeypatch.setattr(roads, 'snap_points', lambda p, pts: {pts[0]['id']: (pts[0]['lng'], pts[0]['lat'])})
    monkeypatch.setattr(roads, 'route_between', fake_route)
    rows = [row(1, 0, -69.000), row(2, 30, -68.996)]
    assert gapfill_tasks.fill_window(None, rows, []) == []


def test_no_route_does_nothing(monkeypatch):
    monkeypatch.setattr(roads, 'snap_points', fake_snap)
    monkeypatch.setattr(roads, 'route_between', lambda *a, **k: None)
    assert gapfill_tasks.fill_window(None, [row(1, 0, -69.0), row(2, 30, -68.996)], []) == []


def test_detour_route_does_nothing(monkeypatch):
    monkeypatch.setattr(roads, 'snap_points', fake_snap)
    monkeypatch.setattr(roads, 'route_between',
                        lambda p, a, b, m='': [a, (a[0], a[1] + 0.01), b])
    assert gapfill_tasks.fill_window(None, [row(1, 0, -69.0), row(2, 30, -68.996)], []) == []


def test_already_filled_gap_is_skipped(road):
    rows = [row(1, 0, -69.000), row(2, 30, -68.996)]
    assert gapfill_tasks.fill_window(None, rows, [T0 + timedelta(seconds=15)]) == []


def test_scan_user_creates_inferred_batch_once(road, monkeypatch):
    monkeypatch.setattr(UserProfile, 'road_provider_resolved', property(lambda s: 'local'))
    user = User.objects.create_user('gf', password='pw-gapfill-1')
    UserProfile.objects.get_or_create(user=user)
    dev = Device.objects.create(user=user, device_id='gf1', name='Phone')
    now = datetime.now(dt_timezone.utc) - timedelta(hours=1)
    for secs, lng in [(0, -69.0), (10, -68.999), (40, -68.995), (50, -68.994)]:
        Location.objects.create(device=dev, latitude=44.0, longitude=lng, accuracy=5,
                                speed=15.0, transport_mode='vehicle',
                                timestamp=now + timedelta(seconds=secs))
    assert gapfill_tasks.scan_user(user.id) == 1
    assert InferredLocation.objects.filter(device=dev, batch__kind='auto').count() == 1
    assert gapfill_tasks.scan_user(user.id) == 0
    assert Location.objects.count() == 4


def test_scan_user_without_snapping_does_nothing(road, monkeypatch):
    monkeypatch.setattr(UserProfile, 'road_provider_resolved', property(lambda s: ''))
    user = User.objects.create_user('gf2', password='pw-gapfill-1')
    UserProfile.objects.get_or_create(user=user)
    dev = Device.objects.create(user=user, device_id='gf2', name='Phone')
    now = datetime.now(dt_timezone.utc) - timedelta(hours=1)
    for secs, lng in [(0, -69.0), (30, -68.996)]:
        Location.objects.create(device=dev, latitude=44.0, longitude=lng,
                                timestamp=now + timedelta(seconds=secs))
    assert gapfill_tasks.scan_user(user.id) == 0
