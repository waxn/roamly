"""Health: recorded activities join the workouts list with calories and steps
read back out of the samples for the time they were happening.
"""
import re
from datetime import datetime, timedelta, timezone as dt_timezone
from pathlib import Path

import pytest
from django.core.cache import cache

from tracker import achievement_defs as defs
from tracker.models import Activity, Device, HealthSample, HealthWorkout, UserProfile

pytestmark = pytest.mark.django_db

T0 = datetime(2026, 11, 20, 10, 0, tzinfo=dt_timezone.utc)


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


def _sample(user, kind, value, start, minutes, source='phone', ext=None):
    return HealthSample.objects.create(
        user=user, kind=kind, value=value, start_time=start,
        end_time=start + timedelta(minutes=minutes), source=source,
        external_id=ext or f'{kind}:{source}:{start.isoformat()}')


@pytest.fixture
def activity(user):
    dev = Device.objects.create(user=user, device_id='phone', name='Phone')
    return Activity.objects.create(
        user=user, device=dev, client_id='a1', kind='run', title='Morning run',
        start_time=T0, end_time=T0 + timedelta(minutes=30), elapsed_seconds=1800,
        distance_km=5.0)


def test_activity_appears_in_workouts_with_window_totals(client_in, user, activity):
    # Inside the run, outside it, and one record straddling the start edge.
    _sample(user, 'steps', 3000, T0 + timedelta(minutes=5), 10)
    _sample(user, 'steps', 999, T0 + timedelta(hours=2), 10)
    _sample(user, 'steps', 1000, T0 - timedelta(minutes=10), 20)   # half inside
    _sample(user, 'calories_active', 250, T0, 30)
    r = client_in.get('/api/health/workouts/?all=1').json()
    assert r['count'] == 1 and r['recorded_count'] == 1
    w = r['workouts'][0]
    assert w['recorded'] is True
    assert w['exercise_slug'] == 'running'
    assert w['title'] == 'Morning run'
    assert w['steps'] == 3500            # 3000 + half of the straddling 1000
    assert w['calories_kcal'] == 250
    assert w['distance_m'] == 5000
    assert w['url'] == f'/activities/{activity.id}/'


def test_one_source_per_kind_inside_the_window(client_in, user, activity):
    _sample(user, 'steps', 3000, T0 + timedelta(minutes=5), 10, source='watch')
    _sample(user, 'steps', 2900, T0 + timedelta(minutes=5), 10, source='phone')
    w = client_in.get('/api/health/workouts/?all=1').json()['workouts'][0]
    assert w['steps'] == 3000            # the larger source, never the sum


def test_imported_workouts_merge_newest_first(client_in, user, activity):
    HealthWorkout.objects.create(
        user=user, external_id='hc1', start_time=T0 + timedelta(days=1),
        end_time=T0 + timedelta(days=1, hours=1), exercise_slug='yoga', duration_s=3600)
    rows = client_in.get('/api/health/workouts/?all=1').json()['workouts']
    assert [w['recorded'] for w in rows] == [False, True]
    assert rows[0]['exercise_slug'] == 'yoga'


def test_day_view_lists_the_activity(client_in, user, activity):
    _sample(user, 'steps', 100, T0, 5)
    day = client_in.get('/api/health/day/2026-11-20/?tz_offset=0').json()
    assert [w['title'] for w in day['workouts']] == ['Morning run']
    assert day['workouts'][0]['recorded'] is True


# ── Achievements: icons replace emoji ────────────────────────────────────────

def test_every_badge_icon_exists_in_the_icon_set():
    js = (Path(__file__).resolve().parent.parent / 'static' / 'tracker' / 'icons.js').read_text()
    names = set(re.findall(r"^\s{8}(\w+): '", js, re.M))
    missing = {b.icon for b in defs.BADGES} - names
    assert not missing, missing


def test_health_badges_exist_and_group_is_declared():
    ids = {b.id for b in defs.BADGES if b.group == 'health'}
    assert {'steps', 'step_day', 'steps_10k', 'steps_streak', 'walked',
            'kcal_total', 'kcal_day', 'workouts', 'activities'} <= ids
    assert 'health' in {g for g, _ in defs.GROUPS}


def test_compat_game_patches_an_old_snapshot():
    game = {'badges': [{'id': 'steps', 'emoji': 'x', 'group': 'sky'}],
            'recent': [{'badge': 'steps', 'emoji': 'x'}], 'timeline': [], 'groups': []}
    out = defs.compat_game(game)
    assert out['badges'][0]['group'] == 'health'
    assert out['badges'][0]['icon'] == 'feet' and 'emoji' not in out['badges'][0]
    assert out['recent'][0]['icon'] == 'feet'
    assert any(g['id'] == 'health' for g in out['groups'])
