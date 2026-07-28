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

# Spacing of generated points along a route, and a ceiling so one very long
# connection can't dump thousands of rows. Carried over from the old automatic
# filler, where they were the only two constants worth keeping.
FILL_INTERVAL_S = 60
MAX_POINTS_PER_ROUTE = 500
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


def points_along(route, start_dt, end_dt, interval_s=FILL_INTERVAL_S):
    """Space points along `route` so the traversal is at constant average speed.

    Endpoints are excluded — the two real fixes already sit there. Returns
    ``([(lng, lat, when), …], route_length_m)``.
    """
    cum = [0.0]
    for i in range(len(route) - 1):
        cum.append(cum[-1] + roads._haversine_m(
            route[i][0], route[i][1], route[i + 1][0], route[i + 1][1]))
    total = cum[-1]
    if total <= 0:
        return [], 0.0

    dt_total = (end_dt - start_dt).total_seconds()
    n = int(dt_total // interval_s) - 1
    if n < 1:
        return [], total
    n = min(n, MAX_POINTS_PER_ROUTE)
    step = dt_total / (n + 1)

    out, seg = [], 0
    for k in range(1, n + 1):
        elapsed = step * k
        target = (elapsed / dt_total) * total
        while seg < len(cum) - 2 and cum[seg + 1] < target:
            seg += 1
        span = cum[seg + 1] - cum[seg]
        f = 0.0 if span <= 0 else (target - cum[seg]) / span
        lng = route[seg][0] + (route[seg + 1][0] - route[seg][0]) * f
        lat = route[seg][1] + (route[seg + 1][1] - route[seg][1]) * f
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
def trash_locations(user, qs, batch=None):
    """Snapshot real points into the trash, then delete them. Returns the count.

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
            place_name=r['place_name'])
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
