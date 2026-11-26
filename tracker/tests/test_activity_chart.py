"""Activity detail charts: the even-in-time speed/elevation series.

Pure Python (``activity_track`` is Django-free), so no database is needed.
"""
import math

from tracker import activity_track as at


def _ride(seconds, speed_mps=8.0, start_t=1_700_000_000, seg=0, alt0=100.0):
    """A straight 1 Hz ride heading north, as smoothed points."""
    out = []
    lat0, lon0 = 59.33, 18.06
    for i in range(seconds):
        lat = lat0 + (i * speed_mps) / 111_195.0
        out.append([start_t + i, lat, lon0, alt0 + i * 0.05, speed_mps, 5.0, seg])
    return out


def test_detail_map_draws_every_smoothed_point():
    # The map line is the whole smoothed track — no Douglas-Peucker by default.
    sm = _ride(600)
    p = at.display_payload(sm)
    assert sum(len(s) for s in p['segments']) == 600
    assert [len(s) for s in p['speeds']] == [len(s) for s in p['segments']]
    # Simplification is still available on request.
    assert sum(len(s) for s in at.display_payload(sm, tolerance_m=2.0)['segments']) <= 3


def test_very_long_recording_drops_only_collinear_points():
    a = _ride(at.DISPLAY_FULL_MAX + 5000)
    for i, p in enumerate(a):
        p[2] += 0.002 * math.sin(i / 60.0)
    p = at.display_payload(a)
    n = sum(len(s) for s in p['segments'])
    assert n <= at.DISPLAY_FULL_MAX
    assert n > 1000   # a curvy line keeps its shape, not a handful of chords


def _decode(enc):
    out, i, lat, lon = [], 0, 0, 0
    while i < len(enc):
        vals = []
        for _ in range(2):
            shift = result = 0
            while True:
                b = ord(enc[i]) - 63
                i += 1
                result |= (b & 0x1f) << shift
                shift += 5
                if b < 0x20:
                    break
            vals.append(~(result >> 1) if result & 1 else result >> 1)
        lat += vals[0]
        lon += vals[1]
        out.append((lat / 1e5, lon / 1e5))
    return out


def test_polyline_encoding_matches_reference():
    pts = [(38.5, -120.2), (40.7, -120.95), (43.252, -126.453)]
    assert at.encode_polyline(pts) == '_p~iF~ps|U_ulLnnqC_mqNvxq`@'


def test_thumb_is_capped_and_keeps_segments_apart():
    a = _ride(3600)
    for i, p in enumerate(a):
        p[2] += 0.002 * math.sin(i / 60.0)  # wiggle so there is shape to keep
    b = _ride(600, start_t=a[-1][0] + 300, seg=1)
    th = at.thumb_polylines(a + b, max_points=96)
    assert len(th) == 2
    pts = [_decode(e) for e in th]
    assert sum(len(p) for p in pts) <= 96
    assert all(len(p) >= 2 for p in pts)
    assert math.isclose(pts[0][0][0], a[0][1], abs_tol=1e-4)
    assert at.thumb_polylines([]) == []


def test_straight_road_is_charted_at_full_resolution():
    sm = _ride(600)
    c = at.chart_series(sm, 'ride')
    assert len(c['times'][0]) == 600
    assert all(abs(v - 8.0 * 3.6) < 0.5 for v in c['speeds'][0][10:-10])


def test_long_activity_is_bucketed_to_the_cap():
    sm = _ride(10 * 3600)
    c = at.chart_series(sm, 'ride', max_points=1500)
    n = len(c['times'][0])
    assert 1400 <= n <= 1501
    # Evenly spaced in time.
    gaps = [b - a for a, b in zip(c['times'][0], c['times'][0][1:])]
    assert max(gaps) - min(gaps) <= 2


def test_speed_noise_is_smoothed():
    sm = _ride(300)
    for i, p in enumerate(sm):
        p[4] = 8.0 + (1.5 if i % 2 else -1.5)  # jitter around 8 m/s
    c = at.chart_series(sm, 'ride')
    mid = c['speeds'][0][20:-20]
    assert max(mid) - min(mid) < 1.0  # km/h, from a 10.8 km/h raw swing


def test_paused_stretch_charts_as_zero():
    moving = _ride(120)
    t_end = moving[-1][0]
    lat, lon = moving[-1][1], moving[-1][2]
    # Two minutes standing still with the smoother's residual wander speed.
    still = [[t_end + 1 + i, lat, lon, 106.0, 0.4, 5.0, 0] for i in range(120)]
    c = at.chart_series(moving + still, 'ride')
    assert max(c['speeds'][0][-60:]) == 0.0


def test_segments_stay_separate_and_coords_line_up():
    a = _ride(100, seg=0)
    b = _ride(100, start_t=a[-1][0] + 300, seg=1)
    c = at.chart_series(a + b, 'ride')
    assert len(c['times']) == 2
    for key in ('speeds', 'elevations', 'coords'):
        assert [len(s) for s in c[key]] == [len(s) for s in c['times']]
    lon, lat = c['coords'][0][0]
    assert math.isclose(lat, 59.33, abs_tol=1e-3) and math.isclose(lon, 18.06, abs_tol=1e-6)


def test_missing_altitude_stays_none():
    sm = _ride(60)
    for p in sm:
        p[3] = None
    c = at.chart_series(sm, 'walk')
    assert all(v is None for v in c['elevations'][0])
