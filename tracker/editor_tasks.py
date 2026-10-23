"""The map editor's data operations.

Everything here runs synchronously inside a request: the editor is deliberate,
one action at a time, so there is no job row, no thread registry and no progress
polling. That is the whole point of the change — automatic gap filling guessed
what to do in the background and got it wrong; this only ever does what someone
just asked for.

Two places output can land:

  * ``InferredLocation`` (the default) — reconstruction. No aggregate in the app
    reads that table, so distance, visits, stats, fog and backups are untouched
    by anything the editor draws.
  * ``Location`` — real data, chosen explicitly with "count as real data" when
    pasting a stretch that genuinely happened. These are ordinary rows, already
    covered by the backup format, so nothing about the backup contract changes.

Every action records an ``EditBatch`` so it can be undone as a unit, and every
deleted *real* point is snapshotted into ``TrashedLocation`` first — a Location
is the primary record and cannot be recomputed, so a tombstone would not be
enough to bring it back.
"""

import logging
from datetime import timedelta

from django.db import transaction
from django.utils import timezone

from . import roads

logger = logging.getLogger(__name__)

# One point per 10 seconds of travel. A short connection therefore gets a handful
# of points rather than a fixed budget spread thinly — the old 60s spacing with a
# 500-point cap put 500 points on a two-minute hop, which is nonsense.
FILL_INTERVAL_S = 10
# Still a ceiling, but it only bites on very long connections.
MAX_POINTS_PER_ROUTE = 500
# ...and a floor on how close two generated points may end up. A time-based
# interval alone cannot bound density: a long gap over a short distance means 10
# seconds of travel is only a few metres, and the points pile into a solid band
# — 40 minutes over 2km put 239 of them 8m apart. Real tracking is never that
# dense, and dots closer than this just overlap and say nothing extra.
MIN_POINT_SPACING_M = 25.0
# A route this much longer than the straight line is usually A* looping around a
# hole in the downloaded road network. The editor *warns* rather than refuses —
# a person picked both ends, so they get to decide.
DETOUR_WARN_FACTOR = 2.5

try:                                            # PostGIS-only; mirrors views.py
    from django.contrib.gis.geos import Point
    HAS_POSTGIS = True
except Exception:                               # pragma: no cover
    Point = None
    HAS_POSTGIS = False


def _polyline_len(coords):
    return sum(roads._haversine_m(coords[i][0], coords[i][1],
                                  coords[i + 1][0], coords[i + 1][1])
               for i in range(len(coords) - 1))


def points_along(route, start_dt, end_dt, interval_s=FILL_INTERVAL_S,
                 v_start=None, v_end=None):
    """Space points along `route`, accelerating from `v_start` to `v_end`.

    Endpoints are excluded — the two real fixes already sit there. Returns
    ``([(lng, lat, when), …], route_length_m)``.

    Constant average speed put points at even *distances*, which reads wrong
    whenever the two ends were moving at different speeds: leaving a junction and
    joining a motorway should show points bunched at the start and stretched out
    at the end. Assuming speed varies linearly between the two recorded fixes,

        v(t) = v0 + (v1 - v0)·t/T
        s(t) = v0·t + (v1 - v0)·t²/(2T)

    and scaling s so s(T) equals the route length gives exactly that, while still
    arriving at the right place at the right time. With both ends stationary (or
    no Doppler at all) it degrades to the old even spacing.
    """
    cum = [0.0]
    for i in range(len(route) - 1):
        cum.append(cum[-1] + roads._haversine_m(
            route[i][0], route[i][1], route[i + 1][0], route[i + 1][1]))
    total = cum[-1]
    if total <= 0:
        return [], 0.0

    dt_total = (end_dt - start_dt).total_seconds()
    if dt_total <= 0:
        return [], total
    # The epsilon keeps dt / (dt/2) from flooring to 1 on a rounding error.
    n = int(dt_total / interval_s + 1e-9) - 1
    if n < 1:
        return [], total
    n = min(n, MAX_POINTS_PER_ROUTE)
    step = dt_total / (n + 1)

    v0 = max(0.0, v_start or 0.0)
    v1 = max(0.0, v_end or 0.0)
    # Area under the speed ramp. Zero means we know nothing useful about speed.
    area = dt_total * (v0 + v1) / 2.0
    scale = (total / area) if area > 0 else 0.0

    out, seg, last = [], 0, None
    for k in range(1, n + 1):
        elapsed = step * k
        if scale > 0:
            travelled = v0 * elapsed + (v1 - v0) * elapsed * elapsed / (2.0 * dt_total)
            target = min(total, scale * travelled)
        else:
            target = (elapsed / dt_total) * total
        while seg < len(cum) - 2 and cum[seg + 1] < target:
            seg += 1
        span = cum[seg + 1] - cum[seg]
        f = 0.0 if span <= 0 else (target - cum[seg]) / span
        lng = route[seg][0] + (route[seg + 1][0] - route[seg][0]) * f
        lat = route[seg][1] + (route[seg + 1][1] - route[seg][1]) * f
        # Thin as we go rather than up front: under a speed ramp the spacing
        # varies along the route, so the slow end is where points bunch and only
        # a running check catches it. Dropping one never moves the others — each
        # kept point stays at the position its own timestamp implies.
        if last is not None and roads._haversine_m(last[0], last[1], lng, lat) < MIN_POINT_SPACING_M:
            continue
        last = (lng, lat)
        out.append((lng, lat, start_dt + timedelta(seconds=elapsed)))
    return out, total


def _make_location(device, lng, lat, when, **extra):
    """Build an unsaved Location with the PostGIS column already populated.

    ``bulk_create`` bypasses ``Location.save()``, which is what normally derives
    the ``location`` PointField from lat/lon. Skipping it leaves the column NULL
    and the point invisible to every spatial query — Places membership, "have I
    been here", search, the map's my-places layer. That has already been a
    production bug once (see the note in ``views.push_location_batch``), so every
    bulk path in the editor goes through here.
    """
    from .models import Location
    obj = Location(device=device, latitude=lat, longitude=lng, timestamp=when, **extra)
    if HAS_POSTGIS and Point:
        obj.location = Point(lng, lat, srid=4326)
    return obj


def _save_points(batch, device, pts, target):
    """Persist generated points to whichever table the action chose."""
    from .models import InferredLocation, Location

    if target == 'location':
        objs = [_make_location(device, lng, lat, when) for (lng, lat, when) in pts]
        created = Location.objects.bulk_create(objs, ignore_conflicts=True)
        # ignore_conflicts means a clash with an existing fix is silently skipped,
        # so re-running an action can't duplicate a point.
        ids = [o.id for o in created if o.id is not None]
        batch.location_ids = ids
        return len(ids) or len(objs)

    InferredLocation.objects.bulk_create([
        InferredLocation(batch=batch, device=device, latitude=lat, longitude=lng, timestamp=when)
        for (lng, lat, when) in pts
    ])
    return len(pts)


@transaction.atomic
def create_batch(user, device, kind, pts, target='inferred', note=''):
    """Record one editor action and its generated points."""
    from .models import EditBatch

    batch = EditBatch.objects.create(
        user=user, device=device, kind=kind, target=target, note=note[:200])
    batch.point_count = _save_points(batch, device, pts, target)
    batch.save(update_fields=['point_count', 'location_ids'])
    return batch


@transaction.atomic
def trash_locations(user, qs, batch=None, reason='deleted', overlap=None):
    """Snapshot real points into the trash, then delete them. Returns the count.

    ``reason='overlap'`` (with its ``overlap``) is the device-overlap hide: the
    same snapshot, but kept until unhidden rather than purged with the trash.

    The snapshot has to be taken before the delete and has to be complete: unlike
    the old ``dismissed`` markers, whose "restore" merely let a job regenerate
    equivalent data, nothing can recompute a Location.
    """
    from .models import Location, TrashedLocation

    rows = list(qs.values(
        'id', 'device_id', 'latitude', 'longitude', 'altitude', 'accuracy',
        'speed', 'battery', 'timestamp', 'city', 'state', 'country',
        'country_code', 'place_name'))
    if not rows:
        return 0

    TrashedLocation.objects.bulk_create([
        TrashedLocation(
            user=user, device_id=r['device_id'], batch=batch, original_id=r['id'],
            latitude=r['latitude'], longitude=r['longitude'], altitude=r['altitude'],
            accuracy=r['accuracy'], speed=r['speed'], battery=r['battery'],
            timestamp=r['timestamp'], city=r['city'], state=r['state'],
            country=r['country'], country_code=r['country_code'],
            place_name=r['place_name'], reason=reason, overlap=overlap)
        for r in rows
    ])
    Location.objects.filter(id__in=[r['id'] for r in rows]).delete()
    return len(rows)


@transaction.atomic
def restore_trashed(user, ids):
    """Recreate trashed points as real Locations again. Returns the count.

    Idempotent by construction: the recreated row carries its original
    ``(device, latitude, longitude, timestamp)``, which is exactly
    ``Location.unique_together``, so a double restore collides and is ignored.
    The new row gets a *new* id — nothing may rely on the old one.
    """
    from .models import Location, TrashedLocation

    rows = list(TrashedLocation.objects.filter(user=user, id__in=ids)
                .select_related('device'))
    if not rows:
        return 0

    objs = [
        _make_location(
            r.device, r.longitude, r.latitude, r.timestamp,
            altitude=r.altitude, accuracy=r.accuracy, speed=r.speed,
            battery=r.battery, city=r.city, state=r.state, country=r.country,
            country_code=r.country_code, place_name=r.place_name)
        for r in rows
    ]
    Location.objects.bulk_create(objs, ignore_conflicts=True)
    TrashedLocation.objects.filter(id__in=[r.id for r in rows]).delete()
    return len(rows)


@transaction.atomic
def undo_batch(user, batch):
    """Reverse one editor action.

    A delete is undone by restoring from the trash; anything that *created*
    points is undone by removing them. Marked rather than deleted so the action
    list keeps a record of what happened.
    """
    from .models import Location, TrashedLocation

    if batch.kind == 'delete':
        restored = restore_trashed(
            user, list(TrashedLocation.objects.filter(batch=batch).values_list('id', flat=True)))
        batch.undone = True
        batch.save(update_fields=['undone'])
        return restored

    removed = 0
    if batch.target == 'location' and batch.location_ids:
        # Straight to the trash rather than a hard delete: undoing a paste is
        # itself an edit, and should be as reversible as any other.
        removed = trash_locations(
            user, Location.objects.filter(id__in=batch.location_ids, device__user=user))
    else:
        removed = batch.points.all().count()
        batch.points.all().delete()
    batch.undone = True
    batch.save(update_fields=['undone'])
    return removed


def route_between_points(profile, a, b):
    """Road route between two real fixes, as (points, route_len, straight, warn).

    `a` and `b` are dicts with lng/lat/timestamp. Raises roads.RoadProviderError
    when the provider itself is misconfigured; returns an empty list when there
    is simply no road route, which the caller reports rather than papering over
    with a straight line.
    """
    route = roads.route_between(profile, (a['lng'], a['lat']), (b['lng'], b['lat']),
                                a.get('mode') or b.get('mode') or '')
    straight = roads._haversine_m(a['lng'], a['lat'], b['lng'], b['lat'])
    if not route:
        return [], 0.0, straight, ''

    pts, route_len = points_along(route, a['timestamp'], b['timestamp'])
    warn = ''
    if straight > 0 and route_len > max(1500.0, straight * DETOUR_WARN_FACTOR):
        warn = (f'The road route is {route_len / 1000:.1f}km for a '
                f'{straight / 1000:.1f}km gap — it may have detoured around '
                f'missing road data.')
    return pts, route_len, straight, warn


# ── Connecting through what was recorded ────────────────────────────────────
# A drive with spotty tracking still has a few real fixes along the way. Routing
# the two ends straight to each other ignores them — the road chosen can miss
# them entirely, and the generated points are timed as if the car went at one
# steady pace. Treating every recorded fix in between as a waypoint fixes both:
# each leg is routed through the next fix, and timed by that leg's own two
# timestamps and Doppler speeds.

# A leg this short in time is ordinary tracking, not a gap: the fixes on either
# side are already close enough together that filling between them adds nothing
# but a routing call each. Without this, a stretch that was tracked well for a
# while would cost one route per fix. The default; the editor's slider passes
# its own, clamped to the bounds below.
LEG_MIN_S = 60
LEG_MIN_S_BOUNDS = (5, 900)
# Nor is one this short in distance — every point would be thinned away.
_LEG_MIN_M = 2 * MIN_POINT_SPACING_M
# A leg the router can't serve and that is this short is drawn as a straight
# line: at this length a chord and the road are indistinguishable.
_LEG_STRAIGHT_OK_M = 250.0
# Each leg is a routing call; a stretch with this many gaps is better split up.
MAX_ROUTED_LEGS = 150


def _leg_route(profile, a, b, mode, max_anchor_m):
    cands = roads.route_alternatives(profile, (a['longitude'], a['latitude']),
                                     (b['longitude'], b['latitude']), mode, k=1,
                                     max_anchor_m=max_anchor_m)
    return cands[0]['coords'] if cands else None


# A phone that loses its fix mid-drive keeps re-logging the last known position
# with a fresh timestamp (dwell points). The recorded stretch then reads "parked
# at A for five minutes, then 3km to B in fifteen seconds" — and a leg that short
# in time has no room for a single generated point, so the fill came out empty
# however the line was routed. When a leg implies a speed nothing on a road
# reaches and its start is the tail of a run of fixes at one spot, the leg is
# re-anchored to the *first* fix of that run: that is when the car really left.
# A genuine stop followed by a normal drive never trips it, since its departure
# leg has a plausible speed.
_SAME_SPOT_M = 30.0
_PLAUSIBLE_MPS = 50.0


def _same_spot_runs(rows):
    """For each index, the earliest index of the run of fixes at its position."""
    run = list(range(len(rows)))
    for k in range(1, len(rows)):
        anchor = rows[run[k - 1]]
        if roads._haversine_m(anchor['longitude'], anchor['latitude'],
                              rows[k]['longitude'], rows[k]['latitude']) <= _SAME_SPOT_M:
            run[k] = run[k - 1]
    return run


def plan_through(profile, rows, mode='', max_anchor_m=None, min_gap_s=LEG_MIN_S):
    """Route from rows[0] to rows[-1] through every row in between.

    `rows` are Location value dicts, oldest first. Returns ``(legs, skipped,
    warnings, held)`` where each leg is ``{'a': idx, 'b': idx, 'coords': [...],
    'fill': bool}`` — ``fill`` False for a leg too short to need points. A fix the
    router cannot reach (usually a GPS glitch off the network) is skipped rather
    than failing the whole connection, and reported in ``skipped``. Fixes less
    than `min_gap_s` apart are treated as tracked and left unfilled. ``held`` is
    the ids of repeated fixes a leg was re-anchored across (see above). Raises
    ``ValueError`` with a user-facing message when no route can be made.
    """
    legs, skipped, warnings, held = [], [], [], []
    run = _same_spot_runs(rows)
    routed = 0
    i = 0
    n = len(rows)
    while i < n - 1:
        j = i + 1
        while True:
            a, b = rows[i], rows[j]
            dt = (b['timestamp'] - a['timestamp']).total_seconds()
            straight = roads._haversine_m(a['longitude'], a['latitude'],
                                          b['longitude'], b['latitude'])
            if (run[i] < i and straight > _SAME_SPOT_M
                    and (dt <= 0 or straight / dt > _PLAUSIBLE_MPS)):
                s = run[i]
                held.extend(r['id'] for r in rows[s + 1:i + 1])
                legs = [l for l in legs if l['a'] < s]
                i = s
                continue
            line = [[a['longitude'], a['latitude']], [b['longitude'], b['latitude']]]
            if dt < min_gap_s or straight < _LEG_MIN_M:
                legs.append({'a': i, 'b': j, 'coords': line, 'fill': False})
                break
            routed += 1
            if routed > MAX_ROUTED_LEGS:
                raise ValueError(
                    f'That stretch has more than {MAX_ROUTED_LEGS} gaps to route. '
                    'Connect it in shorter pieces, or raise "fill gaps longer than".')
            coords = _leg_route(profile, a, b, mode, max_anchor_m)
            if coords:
                length = _polyline_len(coords)
                if straight > 0 and length > max(1500.0, straight * DETOUR_WARN_FACTOR):
                    warnings.append(
                        f'{a["timestamp"]:%H:%M}–{b["timestamp"]:%H:%M}: road route is '
                        f'{length / 1000:.1f}km for a {straight / 1000:.1f}km gap')
                legs.append({'a': i, 'b': j, 'coords': coords, 'fill': True})
                break
            if straight <= _LEG_STRAIGHT_OK_M:
                legs.append({'a': i, 'b': j, 'coords': line, 'fill': True})
                break
            # Unreachable. If the far end is an intermediate fix, route past it;
            # if it is the chosen end point, there is nothing left to try.
            if j == n - 1:
                raise ValueError(
                    f'No road route found between {a["timestamp"]:%H:%M} and '
                    f'{b["timestamp"]:%H:%M}. The area may not have road data '
                    'downloaded yet — try Download Roads in Settings.')
            skipped.append(rows[j]['id'])
            j += 1
        i = j
    return legs, skipped, warnings, held


def points_through(rows, legs):
    """Generated points for a plan from `plan_through`, timed leg by leg."""
    out, total = [], 0.0
    for leg in legs:
        a, b = rows[leg['a']], rows[leg['b']]
        if not leg['fill']:
            total += _polyline_len(leg['coords'])
            continue
        # The 10s spacing assumes room for it. A leg the user chose to fill
        # that is shorter than two intervals would otherwise get nothing at
        # all, so tighten the spacing until it has at least one point.
        dt = (b['timestamp'] - a['timestamp']).total_seconds()
        interval = max(1.0, min(FILL_INTERVAL_S, dt / 2.0))
        pts, length = points_along(leg['coords'], a['timestamp'], b['timestamp'],
                                   interval_s=interval,
                                   v_start=a['speed'], v_end=b['speed'])
        out.extend(pts)
        total += length
    return out[:MAX_POINTS_PER_ROUTE * 4], total
