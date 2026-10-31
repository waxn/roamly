"""Achievements: badge maths, the trust rules behind records, and one scan.

The scan test runs everywhere (SQLite included): the PostGIS-only phases —
boundary coverage and road coverage — are skipped there by design, and
everything else (the history pass, places, extras, badges) is exercised.
"""
from datetime import datetime, timedelta, timezone as dt_timezone
from collections import Counter

import pytest

from tracker import achievement_defs as defs
from tracker import achievement_tasks as at


# ── Pure helpers ─────────────────────────────────────────────────────────────

def test_tiers_and_progress():
    b = defs.BADGE_BY_ID['countries']  # (2, 5, 15, 40)
    assert defs.tier_for(b, None) == 0
    assert defs.tier_for(b, 1) == 0
    assert defs.tier_for(b, 2) == 1
    assert defs.tier_for(b, 14) == 2
    assert defs.tier_for(b, 400) == 4
    assert defs.progress_for(b, 1) == pytest.approx(0.5)
    assert defs.progress_for(b, 400) == 1.0


def test_xp_and_levels_are_monotonic():
    last = -1
    for n in range(1, 60):
        need = defs.xp_for_level(n)
        assert need > last
        last = need
    lvl = defs.level_for_xp(0)
    assert lvl['level'] == 1 and lvl['title'] == 'Homebody'
    assert defs.level_for_xp(defs.xp_for_level(10))['level'] == 10


def test_evaluate_missing_metrics_stay_locked():
    rows, xp = defs.evaluate({})
    assert xp == 0
    assert all(r['tier'] == 0 and not r['available'] for r in rows)
    rows, xp = defs.evaluate({'countries': 5})
    c = next(r for r in rows if r['id'] == 'countries')
    assert c['tier'] == 2 and xp == defs.TIER_XP[0] + defs.TIER_XP[1]


def test_reached_at_backdates_to_crossing():
    reach = [('2024-01-01T00:00:00+00:00', 1), ('2024-02-01T00:00:00+00:00', 2),
             ('2024-03-01T00:00:00+00:00', 5)]
    assert defs.reached_at(reach, 2) == '2024-02-01T00:00:00+00:00'
    assert defs.reached_at(reach, 3) == '2024-03-01T00:00:00+00:00'
    assert defs.reached_at(reach, 6) is None


def _window(speeds, step_m, acc=5.0):
    """Fixes 1s apart, moving `step_m` metres north each second."""
    out = []
    for i, s in enumerate(speeds):
        out.append((45.0 + i * step_m / 111_195.0, -69.0, 1000.0 + i, s, acc))
    return out


def test_corroborated_speed_accepts_a_real_motorway():
    w = _window([30, 31, 30, 29, 30], step_m=30)
    assert at.corroborated_speed(w) == pytest.approx(30)


def test_corroborated_speed_rejects_a_lone_spike():
    # One 70 m/s reading in a 30 m/s run moves the median, not the record.
    w = _window([30, 30, 70, 30, 30], step_m=30)
    assert at.corroborated_speed(w) == pytest.approx(30)


def test_corroborated_speed_rejects_parked_phone_jitter():
    # Garbage ~45 m/s Doppler while the position barely moves.
    w = _window([44, 46, 45, 47, 45], step_m=0.5)
    assert at.corroborated_speed(w) is None


def _cand(mps, seq, ep, lat=45.0, lon=-70.0, mode='', dev=1):
    ts = datetime.fromtimestamp(ep, dt_timezone.utc)
    return (mps, seq, dev, ep, ts, lat, lon, mode)


def test_ground_speed_skips_airborne_windows():
    # A takeoff roll at 80 m/s sits beside flight evidence; the 35 m/s drive
    # an hour earlier is the real record.
    air = []
    at._mark_air(air, 10_000, 10_000)  # a 'plane' fix
    top = [_cand(80.0, 1, 9_000), _cand(35.0, 2, 3_000, mode='vehicle')]
    modes = {'vehicle': [top[1]], '': [top[0]]}
    ms, by_mode = at._ground_speed_records(top, modes, {1: air}, [])
    assert ms[0] == 35.0
    assert '' not in by_mode and by_mode['vehicle'][0] == 35.0


def test_ground_speed_skips_logged_flights_and_runways():
    top = [_cand(85.0, 1, 50_000), _cand(30.0, 2, 1_000)]
    ms, _ = at._ground_speed_records(top, {}, {}, [(49_000, 60_000, None)])
    assert ms[0] == 30.0
    # Fast at JFK with no other evidence: a runway, not a road.
    top = [_cand(70.0, 1, 50_000, lat=40.6413, lon=-73.7781), _cand(30.0, 2, 1_000)]
    ms, _ = at._ground_speed_records(top, {}, {}, [])
    assert ms[0] == 30.0


def test_mark_air_merges_overlapping_windows():
    air = []
    at._mark_air(air, 0, 0)
    at._mark_air(air, 600, 900)
    at._mark_air(air, 100_000, 100_000)
    assert len(air) == 2 and air[0][1] == 900 + at._AIR_PAD_S


def test_corroborated_speed_rejects_poor_accuracy():
    w = _window([30, 30, 30, 30, 30], step_m=30, acc=80)
    assert at.corroborated_speed(w) is None


def test_home_is_the_most_slept_at_cell():
    c = Counter({(22500, -34500): 3, (22510, -34490): 40})
    lat, lon = at.home_from_counts(c)
    assert lat == pytest.approx(22510 / 500) and lon == pytest.approx(-34490 / 500)
    assert at.home_from_counts(Counter()) is None


def test_classify_poi_from_names():
    assert 'cafe' in at.classify_poi('Starbucks', 'amenity')
    assert 'fuel' in at.classify_poi('Irving Oil', 'amenity')
    assert {'grocery', 'store'} <= at.classify_poi('Hannaford', 'shop')
    assert 'store' in at.classify_poi('Ace Hardware', 'shop')
    assert 'park' in at.classify_poi('Moose Point State Park', 'leisure')
    assert 'beach' in at.classify_poi('Sand Beach', 'natural')
    assert 'culture' in at.classify_poi('Farnsworth Art Museum', 'tourism')


def test_longest_run():
    from datetime import date
    days = {date(2024, 1, 1), date(2024, 1, 2), date(2024, 1, 3), date(2024, 1, 10)}
    assert at._longest_run(days) == (3, date(2024, 1, 3))


# ── One scan, end to end ─────────────────────────────────────────────────────

def _make_track(device):
    """A week at home with a daily drive out to a town and back, plus a walk."""
    from tracker.models import Location

    rows = []
    base = datetime(2025, 6, 2, 7, 0, tzinfo=dt_timezone.utc)  # 03:00 in Maine
    home = (44.45, -69.10)
    town = (44.60, -69.30)
    for day in range(7):
        t = base + timedelta(days=day)
        # Night at home: a few fixes at 03:00 local.
        for k in range(6):
            rows.append((home[0], home[1], t + timedelta(minutes=10 * k), 0.0, 'still', 'Waldo', 'Maine'))
        # A drive to town at ~25 m/s, 1 fix / 10s.
        start = t + timedelta(hours=10)
        n = 90
        for i in range(n + 1):
            f = i / n
            rows.append((home[0] + (town[0] - home[0]) * f, home[1] + (town[1] - home[1]) * f,
                         start + timedelta(seconds=10 * i), 25.0, 'vehicle',
                         'Waldo' if f < 0.5 else 'Belfast', 'Maine'))
        # A walk around town.
        ws = start + timedelta(minutes=30)
        for i in range(60):
            rows.append((town[0] + i * 0.00025, town[1], ws + timedelta(seconds=20 * i), 1.4, 'walk',
                         'Belfast', 'Maine'))
    for lat, lon, ts, spd, mode, city, state in rows:
        loc = Location(device=device, latitude=lat, longitude=lon, timestamp=ts)
        loc.speed = spd
        loc.accuracy = 5.0
        loc.altitude = 50.0 + (lat - 44.45) * 1000
        loc.transport_mode = mode
        loc.city, loc.state, loc.country, loc.country_code = city, state, 'United States', 'US'
        loc.save()
    return Location.objects.filter(device=device).count()


@pytest.mark.django_db
def test_full_scan_produces_payload_and_unlocks():
    from django.contrib.auth.models import User
    from tracker.models import AchievementSnapshot, AchievementUnlock, Device

    user = User.objects.create_user('ach', password='x')
    dev = Device.objects.create(user=user, device_id='phone', name='Phone')
    assert _make_track(dev) > 500

    at.compute_achievements(user.id)
    snap = AchievementSnapshot.objects.get(user=user)
    assert snap.status == 'done', snap.error
    p = snap.payload_json
    m = p['metrics']
    assert m['countries'] == 1
    assert m['us_states'] == 1
    assert m['towns'] == 2
    assert m['days_tracked'] == 7
    assert m['total_km'] > 50
    # 25 m/s drive → ~90 km/h, never more than the ground cap.
    assert 80 < m['max_ground_kmh'] < 100
    assert p['speed']['by_mode']['vehicle']['kmh'] > 80
    assert p['journeys']['count'] >= 7
    # Seven ~23 km drives: a gap in the track must never be bridged into one
    # giant journey (the night before, or the week between trips).
    assert 15 < m['longest_journey_km'] < 30
    assert p['journeys']['drive']['hours'] < 1
    assert 1 < m['longest_walk_km'] < 5
    assert p['globe']['home'] is not None
    assert p['collector']['alphabet']['W']['count'] == 1
    assert p['game']['level']['level'] >= 1
    assert AchievementUnlock.objects.filter(user=user, badge_id='days_tracked').exists() is False
    assert AchievementUnlock.objects.filter(user=user, badge_id='track_streak').exists() is False

    # A rescan is idempotent for unlocks.
    n = AchievementUnlock.objects.filter(user=user).count()
    at.compute_achievements(user.id)
    assert AchievementUnlock.objects.filter(user=user).count() == n


@pytest.mark.django_db
def test_api_kicks_a_scan_then_serves_the_snapshot(client, monkeypatch):
    from django.contrib.auth.models import User
    from tracker.models import Device

    started = []
    monkeypatch.setattr(at, 'start_achievement_scan', lambda uid: started.append(uid) or 'started')
    user = User.objects.create_user('ach2', password='x')
    Device.objects.create(user=user, device_id='p')
    client.force_login(user)
    r = client.get('/api/achievements/')
    assert r.status_code == 200 and r.json()['status'] == 'pending'
    assert started == [user.id]

    at.compute_achievements(user.id)
    r = client.get('/api/achievements/')
    assert r.json()['status'] == 'ready'
    assert 'game' in r.json()
    assert client.get('/achievements/').status_code == 200


def _square(lat0, lon0, lat1, lon1):
    from django.contrib.gis.geos import MultiPolygon, Polygon
    p = Polygon(((lon0, lat0), (lon1, lat0), (lon1, lat1), (lon0, lat1), (lon0, lat0)), srid=4326)
    mp = MultiPolygon(p, srid=4326)
    return mp


@pytest.mark.django_db
def test_geography_and_road_coverage():
    from django.contrib.auth.models import User
    from django.contrib.gis.geos import LineString
    from tracker.models import AchievementSnapshot, Boundary, Device, RoadSegment

    user = User.objects.create_user('ach3', password='x')
    dev = Device.objects.create(user=user, device_id='phone')
    _make_track(dev)
    # Two towns (one visited), a county that holds both, and an unvisited town.
    Boundary.objects.create(name='Waldo', state='Maine', kind='cousub', geom=_square(44.40, -69.15, 44.50, -69.05))
    Boundary.objects.create(name='Belfast', state='Maine', kind='place', geom=_square(44.55, -69.35, 44.65, -69.25))
    Boundary.objects.create(name='Elsewhere', state='Maine', kind='place', geom=_square(45.0, -68.0, 45.1, -67.9))
    Boundary.objects.create(name='Waldo County', state='Maine', kind='county', geom=_square(44.3, -69.5, 44.8, -68.9))
    Boundary.objects.create(name='Far County', state='Maine', kind='county', geom=_square(45.0, -68.5, 45.5, -67.5))
    # A road exactly along the drive, and one the track never touches.
    RoadSegment.objects.create(way_id=1, highway='primary',
                               geom=LineString((-69.10, 44.45), (-69.30, 44.60), srid=4326))
    RoadSegment.objects.create(way_id=2, highway='residential',
                               geom=LineString((-69.33, 44.56), (-69.33, 44.64), srid=4326))

    at.compute_achievements(user.id)
    snap = AchievementSnapshot.objects.get(user=user)
    assert snap.status == 'done', snap.error
    p = snap.payload_json
    us = p['explorer']['us']
    assert us['towns_v'] == 1 and us['towns_t'] == 2
    assert us['cousub_v'] == 1
    assert us['counties_v'] == 1 and us['counties_t'] == 2
    assert us['counties_pct'] == 50.0
    assert p['metrics']['us_counties'] == 1
    assert p['explorer']['county_geo']['features']

    roads = p['roads']
    assert roads['available'], roads
    by = {t['type']: t for t in roads['types']}
    # The primary road is driven end to end; the residential one not at all.
    assert by['primary']['km'] == pytest.approx(by['primary']['total_km'], rel=0.1)
    assert by['residential']['km'] == 0
    assert p['metrics']['road_km'] > 15
    fp = snap.roads_fingerprint
    assert fp

    # Nothing changed → the road phase is reused, not recomputed.
    at.compute_achievements(user.id)
    snap.refresh_from_db()
    assert snap.roads_fingerprint == fp
    assert snap.payload_json['roads']['km'] == roads['km']


@pytest.mark.django_db
def test_boundary_download_stores_levels_and_parents(monkeypatch):
    from tracker import boundary_download_tasks as bdt
    from tracker.models import Boundary, DownloadedRegion

    def feat(name, lat0, lon0, lat1, lon1):
        return {'type': 'Feature', 'properties': {'shapeName': name},
                'geometry': {'type': 'Polygon', 'coordinates': [[[lon0, lat0], [lon1, lat0], [lon1, lat1],
                                                                  [lon0, lat1], [lon0, lat0]]]}}
    layers = {
        'ADM1': {'features': [feat('Région A', 45, 0, 46, 1)]},
        'ADM2': {'features': [feat('District 1', 45.1, 0.1, 45.4, 0.4), feat('District 2', 45.5, 0.5, 45.9, 0.9)]},
        'ADM3': None,
    }
    monkeypatch.setattr(bdt, '_fetch_level', lambda iso, level: layers[level])
    ok, n, err = bdt.download_country('FR')
    assert ok and n == 3 and not err
    assert DownloadedRegion.objects.filter(kind='boundary', key='FRA').exists()
    assert set(Boundary.objects.filter(country_code='FR', kind='adm2').values_list('state', flat=True)) == {'Région A'}

    # A failed level is not an answer: nothing is marked covered.
    def boom(iso, level):
        raise TimeoutError('slow')
    monkeypatch.setattr(bdt, '_fetch_level', boom)
    ok, n, err = bdt.download_country('DE')
    assert not ok and 'slow' in err
    assert not DownloadedRegion.objects.filter(kind='boundary', key='DEU').exists()


@pytest.mark.django_db
def test_unit_map_keeps_visited_units_past_the_cap(monkeypatch):
    from tracker.models import Boundary

    far = Boundary.objects.create(name='Far County', state='Maine', kind='county',
                                  geom=_square(45.0, -68.5, 45.5, -67.5))
    near = Boundary.objects.create(name='Near County', state='Maine', kind='county',
                                   geom=_square(44.3, -69.5, 44.8, -68.9))
    visited = {near.id: {'kind': 'county', 'cc': 'US'}}

    gj = at._unit_geojson(visited, ['Maine'], 'county', 'US')
    assert {f['properties']['name']: f['properties']['v'] for f in gj['features']} == {
        'Far County': 0, 'Near County': 1}

    # Unvisited units are what a cap drops, whichever rows came first.
    assert far.id < near.id
    monkeypatch.setattr(at, '_UNIT_MAP_CAP', 1)
    gj = at._unit_geojson(visited, ['Maine'], 'county', 'US')
    assert [f['properties']['name'] for f in gj['features']] == ['Near County']


def _feed(track):
    """Run ``(seconds, lat, lon, speed)`` fixes through the filter; returns the
    fixes credited, in order."""
    got = []
    air = at._FlightFilter(got.append)
    for t, lat, lon, spd in track:
        air.add((t, lat), lat, lon, float(t), spd)
    air.close()
    return [fix[0] for fix in got]


def test_flights_earn_no_credit_but_their_airports_do():
    track = [(0, 44.800, -68.80, 5.0), (30, 44.801, -68.80, 8.0)]       # taxiing
    track.append((60, 44.815, -68.80, 70.0))                            # takeoff roll
    track += [(60 + 60 * i, 44.8 + 0.12 * i, -68.8, 220.0) for i in range(1, 20)]
    track.append((1300, 47.0, -68.8, 70.0))                             # landing roll
    track += [(1330, 47.002, -68.8, 6.0), (1360, 47.003, -68.8, 3.0)]   # at the gate
    assert _feed(track) == [0, 30, 1330, 1360]


def test_the_first_fix_after_landing_counts():
    # The hop that got here is flight speed, but the fix itself is on the ground.
    track = [(60 * i, 44.0 + 0.12 * i, -70.0, 220.0) for i in range(1, 6)]
    track.append((360, 44.72, -70.0, 5.0))
    assert _feed(track) == [360]


def test_a_flown_track_with_no_doppler_is_still_a_flight():
    track = [(0, 44.8, -68.8, None)]
    track += [(60 * i, 44.8 + 0.12 * i, -68.8, None) for i in range(1, 10)]
    assert _feed(track) == [0]


def test_a_high_speed_train_keeps_its_credit():
    # 89 m/s, 1 fix a minute: as fast as any train in service.
    track = [(60 * i, 44.0 + 0.048 * i, -70.0, 89.0) for i in range(30)]
    assert _feed(track) == [t for t, *_ in track]


def test_one_bad_reading_does_not_cost_a_train_ride():
    track = [(60 * i, 44.0 + 0.03 * i, -70.0, 60.0) for i in range(10)]
    track[5] = (300, track[5][1], track[5][2], 180.0)  # Doppler says a plane; the track doesn't
    assert _feed(track) == [t for t, *_ in track]


def test_dwell_repeats_in_the_air_are_not_a_landing():
    track = [(0, 44.0, -70.0, 2.0)]
    track += [(60 * i, 44.0 + 0.12 * i, -70.0, 220.0) for i in range(1, 6)]
    # No fix for a while: the phone re-stamps its last position at speed 0.
    track += [(300 + 60 * i, track[-1][1], track[-1][2], 0.0) for i in range(1, 4)]
    track += [(480 + 60 * i, track[-1][1] + 0.12 * i, -70.0, 220.0) for i in range(1, 6)]
    assert _feed(track) == [0]


def test_a_long_gap_separates_a_train_from_a_later_flight():
    train = [(60 * i, 44.0 + 0.03 * i, -70.0, 60.0) for i in range(5)]
    flight = [(10000 + 60 * i, 46.0 + 0.12 * i, -70.0, 220.0) for i in range(5)]
    assert _feed(train + flight) == [t for t, *_ in train]


@pytest.mark.django_db
def test_scan_does_not_count_places_flown_over():
    from django.contrib.auth.models import User
    from tracker.models import AchievementSnapshot, Device, Location

    user = User.objects.create_user('ach4', password='x')
    dev = Device.objects.create(user=user, device_id='phone', name='Phone')
    _make_track(dev)

    def fix(lat, lon, ts, spd, city, state, country='United States', cc='US'):
        loc = Location(device=dev, latitude=lat, longitude=lon, timestamp=ts)
        loc.speed, loc.accuracy = spd, 10.0
        loc.city, loc.state, loc.country, loc.country_code = city, state, country, cc
        loc.save()

    start = datetime(2025, 7, 1, 12, 0, tzinfo=dt_timezone.utc)
    # A flight over Canada, then Michigan, at ~260 m/s.
    for i in range(1, 21):
        over = ('Sarnia', 'Ontario', 'Canada', 'CA') if i <= 10 else ('Alpena', 'Michigan', 'United States', 'US')
        fix(44.45 + 0.12 * i, -69.10 - 0.1 * i, start + timedelta(minutes=i), 220.0, *over)
    # An Amtrak ride through Virginia, at 50 m/s.
    train = datetime(2025, 7, 5, 12, 0, tzinfo=dt_timezone.utc)
    for i in range(20):
        fix(37.0 + 0.03 * i, -77.5, train + timedelta(minutes=i), 50.0, 'Ashland', 'Virginia')

    at.compute_achievements(user.id)
    snap = AchievementSnapshot.objects.get(user=user)
    assert snap.status == 'done', snap.error
    p = snap.payload_json
    assert p['metrics']['us_states'] == 2  # Maine and the train through Virginia
    assert p['metrics']['countries'] == 1
    assert p['extremes']['n']['lat'] < 45
