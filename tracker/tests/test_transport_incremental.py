"""Incremental transport detection: the nightly backlog and the map's last 24h."""
from datetime import timedelta

import pytest
from django.contrib.auth.models import User
from django.core.cache import cache
from django.utils import timezone

from tracker import transport_tasks
from tracker.models import Device, Location

pytestmark = pytest.mark.django_db


@pytest.fixture(autouse=True)
def _clean(monkeypatch):
    monkeypatch.setattr('tracker.stats_tasks.start_stats_compute', lambda user_id: None)
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def user():
    return User.objects.create_user('walker', password='pw-walker-1')


@pytest.fixture
def client(user, client):
    client.force_login(user)
    return client


@pytest.fixture
def device(user):
    return Device.objects.create(user=user, device_id='phone', name='Phone')


def walk(device, start, minutes=10, mode=''):
    """A fix every 30s at walking pace (Doppler 1.5 m/s), heading east."""
    return [Location.objects.create(
        device=device, latitude=44.4, longitude=-69.0 + i * 0.0006, accuracy=5, speed=1.5,
        timestamp=start + timedelta(seconds=30 * i), transport_mode=mode)
        for i in range(minutes * 2)]


def modes(points):
    return {Location.objects.get(id=p.id).transport_mode for p in points}


def test_classify_since_labels_only_the_window(user, device):
    now = timezone.now()
    old = walk(device, now - timedelta(days=3))
    recent = walk(device, now - timedelta(hours=2))
    gen = transport_tasks.transport_gen(user.id)
    n = transport_tasks.classify_since(user.id, now - timedelta(hours=24))
    assert n == len(recent)
    assert modes(recent) == {'walk'}
    assert modes(old) == {''}
    assert transport_tasks.transport_gen(user.id) == gen + 1


def test_context_is_read_but_not_rewritten(user, device):
    now = timezone.now()
    since = now - timedelta(hours=1)
    # A walk already labelled 'cycle' that runs across the window start keeps
    # its label before it, and the part after is scored with it as context.
    pts = walk(device, since - timedelta(minutes=5), mode='cycle')
    transport_tasks.classify_since(user.id, since)
    before = [p for p in pts if p.timestamp < since]
    after = [p for p in pts if p.timestamp >= since]
    assert modes(before) == {'cycle'}
    assert modes(after) == {'walk'}


def test_backlog_reaches_back_to_the_oldest_unlabelled_point(user, device):
    now = timezone.now()
    labelled = walk(device, now - timedelta(days=20), mode='vehicle')
    backlog = walk(device, now - timedelta(days=10))
    transport_tasks.classify_backlog(user.id)
    assert modes(backlog) == {'walk'}
    assert modes(labelled) == {'vehicle'}


def test_recent_endpoint_labels_then_skips_until_new_points(client, user, device):
    now = timezone.now()
    pts = walk(device, now - timedelta(hours=3))
    first = client.post('/api/transport/detect/recent/').json()
    assert first == {'classified': len(pts), 'skipped': False}
    assert modes(pts) == {'walk'}
    assert client.post('/api/transport/detect/recent/').json()['skipped'] is True


def test_recent_endpoint_needs_post(client):
    assert client.get('/api/transport/detect/recent/').status_code == 405
