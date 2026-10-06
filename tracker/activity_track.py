"""Clean, smooth, score and simplify a recorded activity track.

This is what makes a ride look like a ride. A raw 1 Hz GPS track is a noisy
sample of a smooth path: drawn as-is it zig-zags by a few metres per fix, an
occasional multipath fix lands tens of metres off, and summing hop lengths over
that noise inflates distance on a slow walk while chords still cut corners on a
fast ride. Fixing that takes three steps, all here:

1. ``clean`` — drop fixes that cannot be real: poor accuracy, and anything
   implying a speed the sport cannot reach. The speed check runs forwards *and*
   backwards and keeps only what both passes accept, so a bad FIRST fix (which
   a forward-only pass would trust as its anchor and use to reject everything
   after it) is caught by the backward pass.
2. ``smooth`` — a constant-velocity Kalman filter run forwards, then a
   Rauch-Tung-Striebel pass backwards. Forward-only filtering lags (it rounds a
   corner late and wide); the backward pass lets each estimate see the fixes
   that came after it, so curves are followed rather than cut. Measurement
   noise comes from each fix's own reported accuracy, so a good fix pulls hard
   and a poor one barely nudges. A fix whose innovation is implausible under
   the filter's own uncertainty is skipped outright (Mahalanobis gate).
3. ``simplify`` — Douglas-Peucker, which keeps the vertices that carry shape.
   The old track endpoint kept every n-th fix, which can keep a spike and drop
   the bend around it.

Pure Python and Django-free on purpose: no numpy dependency for a few tens of
thousands of 2x2 matrix updates, and it can be exercised without a database.

Points in: ``(t_seconds, lat, lon, alt|None, acc|None, speed|None)``.
Smoothed points out: ``[t, lat, lon, alt|None, speed_mps, acc|None, segment]``.
"""

import gzip
import json
import math

# Bump whenever the output of clean/smooth/stats changes. Activity rows and
# ActivityTrack.smoothed carry the version they were computed under, and a
# mismatch recomputes on the next read — so an improvement here re-scores every
# ride without a migration or a background job.
ALGO_VERSION = 1

_R = 6371008.8  # mean Earth radius, metres

# Fixes worse than this are dropped before anything else sees them. The phone's
# recorder already gates at 30 m (relaxing to 50 m in a sustained poor-signal
# stretch), so this mostly matters for the history fallback.
MAX_ACCURACY_M = 50.0
_ACC_FLOOR_M = 3.0      # nobody's fix is better than this, whatever it claims
_ACC_DEFAULT_M = 15.0   # imported history often has no accuracy at all

# A gap longer than this splits the track into a new segment: the line is not
# drawn across it and no distance is credited for it. Long enough to ride out a
# tunnel or a batched delivery, short enough that a phone left recording in a
# pocket during a car ride home doesn't draw a straight line across town.
SEGMENT_GAP_S = 60.0

# Per-sport physics. ``ceiling`` is the fastest the sport plausibly goes
# (m/s), used to reject teleports; ``q`` is the Kalman process noise — how hard
# the sport can accelerate/turn (m^2/s^3); ``moving`` is the speed below which
# time counts as paused.
SPORTS = {
    'walk':  {'ceiling': 4.0,  'q': 0.3, 'moving': 0.4},
    'hike':  {'ceiling': 4.0,  'q': 0.3, 'moving': 0.4},
    'run':   {'ceiling': 9.0,  'q': 1.0, 'moving': 0.8},
    'row':   {'ceiling': 8.0,  'q': 0.5, 'moving': 0.5},
    'ride':  {'ceiling': 30.0, 'q': 2.0, 'moving': 1.0},
    'other': {'ceiling': 70.0, 'q': 4.0, 'moving': 0.8},
}

# Consecutive rejections after which the speed check concludes its *anchor* is
# the bad fix (or the user genuinely moved while the signal was gone) and
# re-anchors on the current fix rather than rejecting the rest of the ride.
_REANCHOR_AFTER = 5

# Mahalanobis gate on the Kalman innovation (2 DOF, ~99.9%). Beyond it a fix is
# skipped, up to _GATE_MAX_SKIPS in a row — then it's accepted, since a filter
# that refuses every fix for long has lost track rather than found outliers.
_GATE_CHI2 = 13.8
_GATE_MAX_SKIPS = 3

# Elevation: GPS altitude is noisy (several metres fix to fix), so summing raw
# deltas wildly over-reports climbing. Median -> time-window mean -> a
# hysteresis that only counts a climb/descent once it exceeds this.
_ELEV_HYSTERESIS_M = 3.0
_ELEV_WINDOW_S = 20.0

# Auto-pause looks at displacement across this window (see moving_flags).
_PAUSE_WINDOW_S = 10.0


def sport(kind):
    return SPORTS.get(kind) or SPORTS['other']


def haversine_m(lat1, lon1, lat2, lon2):
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * _R * math.asin(min(1.0, math.sqrt(a)))


def _acc(p):
    a = p[4]
    if a is None or a <= 0:
        return _ACC_DEFAULT_M
    return max(_ACC_FLOOR_M, float(a))


# ── clean ────────────────────────────────────────────────────────────────────

def _speed_pass(points, ceiling):
    """Indices accepted by one directional speed check over ``points``."""
    keep = set()
    anchor = None
    rejected_run = 0
    for i, p in enumerate(points):
        if anchor is None:
            anchor = p
            keep.add(i)
            continue
        dt = abs(p[0] - anchor[0])
        if dt <= 0:
            continue
        d = haversine_m(anchor[1], anchor[2], p[1], p[2])
        # Two fixes each off by their accuracy can look that much further apart
        # without either being wrong, so the slack scales with both.
        allowed = ceiling * dt + _acc(anchor) + _acc(p)
        if d <= allowed or dt > SEGMENT_GAP_S or rejected_run >= _REANCHOR_AFTER:
            anchor = p
            keep.add(i)
            rejected_run = 0
        else:
            rejected_run += 1
    return keep


def clean(points, kind, max_accuracy_m=MAX_ACCURACY_M):
    """Drop fixes that cannot be real. Returns a new, time-sorted list."""
    pts = []
    last_t = None
    for p in sorted(points, key=lambda r: r[0]):
        t, lat, lon = p[0], p[1], p[2]
        if lat is None or lon is None or not (-90 <= lat <= 90 and -180 <= lon <= 180):
            continue
        if lat == 0 and lon == 0:
            continue
        if p[4] is not None and p[4] > max_accuracy_m:
            continue
        if last_t is not None and t <= last_t:
            continue  # duplicate timestamp: the earlier delivery wins
        pts.append(p)
        last_t = t
    if len(pts) < 3:
        return pts

    ceiling = sport(kind)['ceiling']
    fwd = _speed_pass(pts, ceiling)
    rev = _speed_pass(pts[::-1], ceiling)
    n = len(pts)
    bwd = {n - 1 - i for i in rev}
    return [p for i, p in enumerate(pts) if i in fwd and i in bwd]


def split_segments(points, gap_s=SEGMENT_GAP_S):
    segs = []
    cur = []
    for p in points:
        if cur and p[0] - cur[-1][0] > gap_s:
            segs.append(cur)
            cur = []
        cur.append(p)
    if cur:
        segs.append(cur)
    return segs


# ── smooth ───────────────────────────────────────────────────────────────────

def _smooth_axis(zs, rs, dts, q, gated):
    """RTS-smoothed position + velocity for one axis of a constant-velocity model.

    ``zs`` measurements (m), ``rs`` their variances, ``dts[k]`` the time from
    step k-1 to k, ``gated[k]`` True where the measurement is skipped. The two
    axes are independent under an isotropic white-acceleration model, so each
    is a 2-state filter — which keeps every matrix 2x2 and the code explicit.
    """
    n = len(zs)
    # Filtered and predicted means/covariances, per step. Covariance stored as
    # (p00, p01, p11) — it's symmetric.
    xf = [None] * n
    Pf = [None] * n
    xp = [None] * n
    Pp = [None] * n

    x = (zs[0], 0.0)
    P = (rs[0], 0.0, 25.0)  # velocity: unknown, ~5 m/s sigma
    xp[0], Pp[0] = x, P
    xf[0], Pf[0] = x, P
    for k in range(1, n):
        dt = dts[k]
        # Predict: x' = F x, P' = F P F^T + Q
        x0, v0 = xf[k - 1]
        p00, p01, p11 = Pf[k - 1]
        xpk = (x0 + dt * v0, v0)
        q00 = q * dt ** 3 / 3.0
        q01 = q * dt ** 2 / 2.0
        q11 = q * dt
        pp00 = p00 + 2 * dt * p01 + dt * dt * p11 + q00
        pp01 = p01 + dt * p11 + q01
        pp11 = p11 + q11
        xp[k] = xpk
        Pp[k] = (pp00, pp01, pp11)
        if gated[k]:
            xf[k], Pf[k] = xpk, Pp[k]
            continue
        # Update with a position measurement: H = [1, 0]
        s = pp00 + rs[k]
        k0 = pp00 / s
        k1 = pp01 / s
        y = zs[k] - xpk[0]
        xf[k] = (xpk[0] + k0 * y, xpk[1] + k1 * y)
        Pf[k] = (
            (1 - k0) * pp00,
            (1 - k0) * pp01,
            pp11 - k1 * pp01,
        )

    # Rauch-Tung-Striebel backward pass.
    xs = [None] * n
    xs[-1] = xf[-1]
    Ps = Pf[-1]
    for k in range(n - 2, -1, -1):
        dt = dts[k + 1]
        f00, f01, f11 = Pf[k]
        p00, p01, p11 = Pp[k + 1]
        # C = Pf F^T Pp^-1, with F = [[1, dt], [0, 1]]
        a00 = f00 + dt * f01
        a01 = f01
        a10 = f01 + dt * f11
        a11 = f11
        det = p00 * p11 - p01 * p01
        if abs(det) < 1e-12:
            xs[k] = xf[k]
            Ps = Pf[k]
            continue
        i00 = p11 / det
        i01 = -p01 / det
        i11 = p00 / det
        c00 = a00 * i00 + a01 * i01
        c01 = a00 * i01 + a01 * i11
        c10 = a10 * i00 + a11 * i01
        c11 = a10 * i01 + a11 * i11
        dx = xs[k + 1][0] - xp[k + 1][0]
        dv = xs[k + 1][1] - xp[k + 1][1]
        xs[k] = (xf[k][0] + c00 * dx + c01 * dv, xf[k][1] + c10 * dx + c11 * dv)
        # Smoothed covariance is not needed for the output; skip computing it.
    return xs


def _gate_flags(xs_m, ys_m, rs, dts, q):
    """Mark measurements whose innovation fails a 2-D Mahalanobis gate.

    Runs a forward-only filter on both axes together purely to decide which
    fixes to trust; the smoother then treats gated fixes as missing.
    """
    n = len(xs_m)
    gated = [False] * n
    state = []
    for z in (xs_m, ys_m):
        state.append([z[0], 0.0, rs[0], 0.0, 25.0])
    skips = 0
    for k in range(1, n):
        dt = dts[k]
        preds = []
        for st in state:
            x0, v0, p00, p01, p11 = st
            pp00 = p00 + 2 * dt * p01 + dt * dt * p11 + q * dt ** 3 / 3.0
            pp01 = p01 + dt * p11 + q * dt ** 2 / 2.0
            pp11 = p11 + q * dt
            preds.append((x0 + dt * v0, v0, pp00, pp01, pp11))
        d2 = 0.0
        for (xp, _, pp00, _, _), z in zip(preds, (xs_m[k], ys_m[k])):
            d2 += (z - xp) ** 2 / (pp00 + rs[k])
        if d2 > _GATE_CHI2 and skips < _GATE_MAX_SKIPS:
            gated[k] = True
            skips += 1
            for i, pr in enumerate(preds):
                state[i] = list(pr)
            continue
        skips = 0
        for i, (pr, z) in enumerate(zip(preds, (xs_m[k], ys_m[k]))):
            xp, vp, pp00, pp01, pp11 = pr
            s = pp00 + rs[k]
            k0 = pp00 / s
            k1 = pp01 / s
            y = z - xp
            state[i] = [xp + k0 * y, vp + k1 * y,
                        (1 - k0) * pp00, (1 - k0) * pp01, pp11 - k1 * pp01]
    return gated


def _smooth_altitude(ts, alts):
    """Median-of-5 then a centred time-window mean. None where no altitude."""
    idx = [i for i, a in enumerate(alts) if a is not None]
    if len(idx) < 3:
        return [None] * len(alts)
    vals = [alts[i] for i in idx]
    med = []
    for j in range(len(vals)):
        w = sorted(vals[max(0, j - 2):j + 3])
        med.append(w[len(w) // 2])
    out = [None] * len(alts)
    half = _ELEV_WINDOW_S / 2
    lo = 0
    hi = 0
    total = 0.0
    tsv = [ts[i] for i in idx]
    for j, i in enumerate(idx):
        while hi < len(tsv) and tsv[hi] <= tsv[j] + half:
            total += med[hi]
            hi += 1
        while tsv[lo] < tsv[j] - half:
            total -= med[lo]
            lo += 1
        out[i] = total / (hi - lo)
    return out


def smooth(points, kind):
    """Clean -> per-segment gated RTS smoothing. Returns smoothed point lists."""
    q = sport(kind)['q']
    out = []
    for seg_no, seg in enumerate(split_segments(points)):
        n = len(seg)
        if n == 1:
            p = seg[0]
            out.append([p[0], p[1], p[2], p[3], 0.0, p[4], seg_no])
            continue
        lat0 = math.radians(seg[0][1])
        lon0 = seg[0][2]
        kx = math.cos(lat0) * _R * math.pi / 180.0
        ky = _R * math.pi / 180.0
        xs_m = [(p[2] - lon0) * kx for p in seg]
        ys_m = [(p[1] - seg[0][1]) * ky for p in seg]
        # Android's horizontal accuracy is a 68% radius; per-axis sigma is
        # roughly that over 1.5.
        rs = [(_acc(p) / 1.5) ** 2 for p in seg]
        dts = [0.0] + [max(0.001, seg[k][0] - seg[k - 1][0]) for k in range(1, n)]
        gated = _gate_flags(xs_m, ys_m, rs, dts, q)
        sx = _smooth_axis(xs_m, rs, dts, q, gated)
        sy = _smooth_axis(ys_m, rs, dts, q, gated)
        alts = _smooth_altitude([p[0] for p in seg], [p[3] for p in seg])
        for k, p in enumerate(seg):
            if gated[k]:
                continue
            lat = seg[0][1] + sy[k][0] / ky
            lon = lon0 + sx[k][0] / kx
            spd = math.hypot(sx[k][1], sy[k][1])
            out.append([p[0], lat, lon, alts[k], spd, p[4], seg_no])
    return out


def process(points, kind, max_accuracy_m=MAX_ACCURACY_M):
    """The whole pipeline: raw fixes in, smoothed points out."""
    return smooth(clean(points, kind, max_accuracy_m), kind)


# ── stats ────────────────────────────────────────────────────────────────────

def _rolling_median(vals, n=5):
    out = []
    h = n // 2
    for i in range(len(vals)):
        w = sorted(vals[max(0, i - h):i + h + 1])
        out.append(w[len(w) // 2])
    return out


def moving_flags(sm, kind, window_s=_PAUSE_WINDOW_S):
    """Per point: is the track actually going somewhere around this moment?

    Judged on *displacement across a window* rather than hop-by-hop speed.
    Standing still, even a smoothed track keeps wandering a metre or two, and
    summed hop by hop that wander looks like slow walking forever — 10 minutes
    at a trailhead was crediting ~200 m. Across a 10 s window the wander mostly
    cancels while real motion adds up, which is the auto-pause signal.
    """
    thr = sport(kind)['moving']
    n = len(sm)
    flags = [False] * n
    half = window_s / 2
    lo = hi = 0
    for i in range(n):
        seg = sm[i][6]
        while lo < i and (sm[lo][6] != seg or sm[lo][0] < sm[i][0] - half):
            lo += 1
        if hi < i:
            hi = i
        while hi + 1 < n and sm[hi + 1][6] == seg and sm[hi + 1][0] <= sm[i][0] + half:
            hi += 1
        dt = sm[hi][0] - sm[lo][0]
        if dt <= 0:
            continue
        d = haversine_m(sm[lo][1], sm[lo][2], sm[hi][1], sm[hi][2])
        flags[i] = d / dt >= thr
    return flags


def stats(sm, kind):
    """Distance, moving time, speeds and elevation from a smoothed track.

    Distance and moving time only accrue while moving (auto-pause), the way a
    bike computer or Strava reports them; elapsed time is the envelope's job.
    """
    sp = sport(kind)
    dist = 0.0
    moving = 0.0
    gain = 0.0
    loss = 0.0
    flags = moving_flags(sm, kind)
    for i in range(1, len(sm)):
        a, b = sm[i - 1], sm[i]
        if a[6] != b[6] or not (flags[i - 1] or flags[i]):
            continue
        dist += haversine_m(a[1], a[2], b[1], b[2])
        moving += b[0] - a[0]

    # Elevation, with hysteresis per segment: a climb only counts once the
    # smoothed altitude has risen _ELEV_HYSTERESIS_M above the last turning
    # point, which is what keeps residual GPS wobble from accumulating.
    has_alt = False
    ref = None
    seg = None
    for p in sm:
        if p[3] is None:
            continue
        has_alt = True
        if p[6] != seg:
            seg = p[6]
            ref = p[3]
            continue
        delta = p[3] - ref
        if delta >= _ELEV_HYSTERESIS_M:
            gain += delta
            ref = p[3]
        elif delta <= -_ELEV_HYSTERESIS_M:
            loss -= delta
            ref = p[3]

    speeds = [min(p[4], sp['ceiling']) if f else 0.0 for p, f in zip(sm, flags)]
    max_speed = max(_rolling_median(speeds)) if len(speeds) >= 3 else (max(speeds) if speeds else None)
    return {
        'distance_m': dist,
        'moving_s': moving,
        'max_speed_mps': max_speed,
        'avg_speed_mps': (dist / moving) if moving > 0 else None,
        'elevation_gain_m': gain if has_alt else None,
        'elevation_loss_m': loss if has_alt else None,
        'point_count': len(sm),
    }


# ── simplify ─────────────────────────────────────────────────────────────────

def simplify(seg, tolerance_m):
    """Douglas-Peucker over one segment; returns the kept indices, in order.

    Iterative (an explicit stack) so a 36k-point ride cannot hit Python's
    recursion limit. Distances are perpendicular, in a local equirectangular
    projection — accurate to well under a metre over any single activity.
    """
    n = len(seg)
    if n <= 2:
        return list(range(n))
    lat0 = math.radians(seg[0][1])
    kx = math.cos(lat0) * _R * math.pi / 180.0
    ky = _R * math.pi / 180.0
    xs = [p[2] * kx for p in seg]
    ys = [p[1] * ky for p in seg]
    keep = [False] * n
    keep[0] = keep[-1] = True
    stack = [(0, n - 1)]
    tol2 = tolerance_m * tolerance_m
    while stack:
        i, j = stack.pop()
        if j <= i + 1:
            continue
        ax, ay = xs[i], ys[i]
        dx, dy = xs[j] - ax, ys[j] - ay
        l2 = dx * dx + dy * dy
        best = -1.0
        best_k = -1
        for k in range(i + 1, j):
            px, py = xs[k] - ax, ys[k] - ay
            if l2 == 0:
                d2 = px * px + py * py
            else:
                t = max(0.0, min(1.0, (px * dx + py * dy) / l2))
                ex, ey = px - t * dx, py - t * dy
                d2 = ex * ex + ey * ey
            if d2 > best:
                best = d2
                best_k = k
        if best > tol2:
            keep[best_k] = True
            stack.append((i, best_k))
            stack.append((best_k, j))
    return [k for k in range(n) if keep[k]]


def display_payload(sm, tolerance_m=2.0, max_points=6000):
    """Segments of simplified ``[lon, lat]`` plus per-vertex speed/time/elevation.

    Simplification widens until the vertex count fits ``max_points``, so a long
    ride's response stays small without falling back to index striding.
    """
    by_seg = {}
    for p in sm:
        by_seg.setdefault(p[6], []).append(p)
    segs = list(by_seg.values())
    tol = tolerance_m
    while True:
        kept = [simplify(s, tol) for s in segs]
        total = sum(len(k) for k in kept)
        if total <= max_points or tol > 200:
            break
        tol *= 1.6
    out = {'segments': [], 'speeds': [], 'times': [], 'elevations': []}
    for s, idx in zip(segs, kept):
        out['segments'].append([[round(s[i][2], 6), round(s[i][1], 6)] for i in idx])
        out['speeds'].append([round(s[i][4] * 3.6, 2) for i in idx])
        out['times'].append([int(s[i][0]) for i in idx])
        out['elevations'].append([None if s[i][3] is None else round(s[i][3], 1) for i in idx])
    out['tolerance_m'] = round(tol, 1)
    return out


def history_rows(sm, every_s=5.0):
    """Thin a smoothed track to one point per ``every_s`` for the history mirror.

    Segment starts and ends are always kept, so the mirrored track doesn't lose
    the exact place a ride began or a gap opened.
    """
    out = []
    last_t = None
    for i, p in enumerate(sm):
        first = i == 0 or sm[i - 1][6] != p[6]
        last = i == len(sm) - 1 or sm[i + 1][6] != p[6]
        if first or last or last_t is None or p[0] - last_t >= every_s:
            out.append(p)
            last_t = p[0]
    return out


# ── storage encoding ─────────────────────────────────────────────────────────

def pack(rows):
    return gzip.compress(json.dumps(rows, separators=(',', ':')).encode(), compresslevel=6)


def unpack(blob):
    if not blob:
        return []
    return json.loads(gzip.decompress(bytes(blob)).decode())


def round_smoothed(sm):
    return [[round(p[0], 3), round(p[1], 7), round(p[2], 7),
             None if p[3] is None else round(p[3], 1),
             round(p[4], 2), p[5], p[6]] for p in sm]
