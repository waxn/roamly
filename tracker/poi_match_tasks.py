"""Background task: match the nearest named POI to every GPS point.

Manually triggered from Settings → Background Jobs. For each of the user's
location points it finds the nearest POI (within ``RADIUS_M``) and stores it on
``Location.poi`` so the data table's Place column shows the store/venue the
point sits at — anything that would show up in search.

This is *not* the dwell-based Visit computation: it labels every point, not just
stays. POIs come from the local POI table (download them first via the POI
download job if empty). Matching uses an in-memory degree-grid so a 600k-point
history resolves without a DB round-trip per point and without numpy/scipy.
"""

import time
import math
import threading
import logging
from collections import defaultdict

from django.utils import timezone

logger = logging.getLogger(__name__)

_running_threads = {}

RADIUS_M = 150          # max distance a point can be from a POI to be labelled
CELL_DEG = 0.002        # ~222m grid cell; a 3×3 neighbourhood covers RADIUS_M
BATCH_SIZE = 5000       # points loaded/updated per chunk


def _haversine_m(lat1, lon1, lat2, lon2):
    R = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlam / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def _build_grid(pois):
    """Bucket (id, lat, lon) POI tuples into a degree grid keyed by cell."""
    grid = defaultdict(list)
    for pid, lat, lon in pois:
        grid[(int(lat / CELL_DEG), int(lon / CELL_DEG))].append((pid, lat, lon))
    return grid


def _nearest_poi_id(grid, lat, lon):
    """Return the id of the nearest POI within RADIUS_M, or None."""
    cy, cx = int(lat / CELL_DEG), int(lon / CELL_DEG)
    best_id, best_d = None, RADIUS_M
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            for pid, plat, plon in grid.get((cy + dy, cx + dx), ()):  # noqa: E501
                d = _haversine_m(lat, lon, plat, plon)
                if d <= best_d:
                    best_d, best_id = d, pid
    return best_id


def _match_worker(user_id):
    from .models import Location, POI, POIMatchJob
    from django.db.models import Min, Max
    from django.db import close_old_connections

    # This runs in a thread spawned from a request, which never fires the
    # request_started/finished signals that normally enforce CONN_MAX_AGE —
    # so without this the connection it opens is never returned. log_writer.py
    # documents the same hazard.
    close_old_connections()

    matched = 0
    processed = 0

    try:
        try:
            job = POIMatchJob.objects.get(user_id=user_id)
        except POIMatchJob.DoesNotExist:
            return

        # Bound the POI set to where this user has actually been.
        #
        # POI is instance-wide reference data covering every city anyone on the
        # instance has visited, so after a few hundred cities this list is
        # millions of 3-tuples — roughly 300MB resident once the grid is built
        # on top of it, held for the whole job, inside a gunicorn worker. The
        # matcher only ever looks within _MATCH_RADIUS_M of one of this user's
        # own points, so every POI outside their travel bbox was loaded to be
        # ignored.
        bounds = Location.objects.filter(device__user_id=user_id).aggregate(
            min_lat=Min('latitude'), max_lat=Max('latitude'),
            min_lon=Min('longitude'), max_lon=Max('longitude'),
        )
        poi_qs = POI.objects.all()
        if bounds['min_lat'] is not None:
            # Pad by a degree so a POI just outside the bbox but within match
            # range of an edge point is still a candidate.
            pad = 1.0
            poi_qs = poi_qs.filter(
                latitude__gte=bounds['min_lat'] - pad, latitude__lte=bounds['max_lat'] + pad,
                longitude__gte=bounds['min_lon'] - pad, longitude__lte=bounds['max_lon'] + pad,
            )
        pois = list(poi_qs.values_list('id', 'latitude', 'longitude'))
        if not pois:
            job.status = 'completed'
            job.total = 0
            job.save(update_fields=['status', 'total', 'updated_at'])
            logger.info(f"POI match for user {user_id}: no POIs downloaded yet")
            return

        grid = _build_grid(pois)

        total = Location.objects.filter(device__user_id=user_id).count()
        job.total = total
        job.processed = 0
        job.matched = 0
        job.save(update_fields=['total', 'processed', 'matched', 'updated_at'])
        logger.info(f"POI match for user {user_id}: {total} points, {len(pois)} POIs")

        last_id = 0
        while True:
            try:
                job.refresh_from_db()
                if job.status != 'running':
                    break
            except POIMatchJob.DoesNotExist:
                break

            chunk = list(
                Location.objects
                .filter(device__user_id=user_id, id__gt=last_id)
                .order_by('id')
                .values('id', 'latitude', 'longitude')[:BATCH_SIZE]
            )
            if not chunk:
                break

            # Group resulting point-ids by matched POI so each POI is one UPDATE.
            by_poi = defaultdict(list)
            cleared = []
            for row in chunk:
                last_id = row['id']
                pid = _nearest_poi_id(grid, row['latitude'], row['longitude'])
                if pid is None:
                    cleared.append(row['id'])
                else:
                    by_poi[pid].append(row['id'])

            for pid, ids in by_poi.items():
                Location.objects.filter(id__in=ids).update(poi_id=pid)
                matched += len(ids)
            if cleared:
                # Re-running after POIs changed: clear stale labels too.
                Location.objects.filter(id__in=cleared).exclude(poi__isnull=True).update(poi=None)

            processed += len(chunk)
            job.processed = processed
            job.matched = matched
            job.save(update_fields=['processed', 'matched', 'updated_at'])
            time.sleep(0.02)

    finally:
        try:
            job = POIMatchJob.objects.get(user_id=user_id)
            if job.status == 'running':
                job.status = 'completed'
            job.save(update_fields=['status', 'updated_at'])
        except POIMatchJob.DoesNotExist:
            pass
        _running_threads.pop(user_id, None)
        logger.info(f"POI match done for user {user_id}: {processed} points, {matched} matched")
        # Return the connection this thread opened — nothing else will.
        close_old_connections()


def _is_thread_alive(user_id):
    t = _running_threads.get(user_id)
    return t is not None and t.is_alive()


def _start_thread(user_id):
    t = threading.Thread(target=_match_worker, args=(user_id,), daemon=True)
    _running_threads[user_id] = t
    t.start()


def start_poi_match(user_id):
    from .models import POIMatchJob

    job, created = POIMatchJob.objects.get_or_create(
        user_id=user_id, defaults={'status': 'running', 'total': 0},
    )
    if not created:
        if job.status == 'running' and _is_thread_alive(user_id):
            return job
        job.status = 'running'
        job.total = 0
        job.processed = 0
        job.matched = 0
        job.save(update_fields=['status', 'total', 'processed', 'matched', 'updated_at'])
    _start_thread(user_id)
    return job


def stop_poi_match(user_id):
    from .models import POIMatchJob
    try:
        job = POIMatchJob.objects.get(user_id=user_id, status='running')
        job.status = 'stopped'
        job.save(update_fields=['status', 'updated_at'])
        return True
    except POIMatchJob.DoesNotExist:
        return False


def get_poi_match_status(user_id):
    from .models import POIMatchJob

    try:
        job = POIMatchJob.objects.get(user_id=user_id)
    except POIMatchJob.DoesNotExist:
        return {'status': 'idle'}

    # Restart a thread that died mid-run (e.g. a worker recycle).
    if job.status == 'running' and not _is_thread_alive(user_id):
        if (timezone.now() - job.updated_at).total_seconds() > 30:
            _start_thread(user_id)

    return {
        'status': job.status,
        'processed': job.processed,
        'total': job.total,
        'matched': job.matched,
    }
