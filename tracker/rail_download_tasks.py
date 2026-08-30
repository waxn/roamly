"""Download OSM subway geometry into RailSegment/RailStation (background thread).

A sibling of road_download_tasks — same daemon-thread + Job-row + worker-token
mechanics, same Overpass politeness, and it reuses that module's `_visited_boxes`
verbatim for area selection (the ~2km corridor cells anyone on the instance
travelled through, *including the bridged cells between consecutive fixes* —
which is exactly where a subway ride left its gap). Rail is far sparser than
roads, so downloading it for every visited cell is cheap; most cells return
nothing.

**Admin-only, instance-wide singleton** (`RailDownloadJob`, pk=1) — same reason
as road_download_tasks: RailSegment/RailStation have no user FK and are shared
by everyone on the instance.

Two differences from the road version:
  * the Overpass query fetches `railway=subway` running tracks (service tracks —
    yards, sidings, crossovers — excluded) **and** subway station nodes in one
    union; stations are stored separately in RailStation because the router
    stitches lines together at them so a ride across a transfer still routes.
  * there is no highway/oneway concept — a RailSegment is just name + geometry.
"""

import logging
import secrets
import threading
import time

from django.db import close_old_connections
from django.utils import timezone

# Area selection is identical to roads — reuse it rather than fork it. Road
# and subway coverage are tracked independently (kind='road' vs 'subway')
# even though they share the same cell grid, since a cell can have one
# without the other — see _visited_boxes's docstring.
from . import overpass
from .road_download_tasks import (
    _visited_boxes, _mark_covered,
    OVERPASS_TIMEOUT, MAX_TIMEOUT_BISECT_DEPTH,
    BOXES_PER_REQUEST, MAX_ATTEMPTS, RETRY_BACKOFF_S, REQUEST_SLEEP_S,
    STALE_AFTER_S, INSERT_CHUNK, CONNECTIVITY_FAIL_LIMIT,
)

logger = logging.getLogger(__name__)

# Single background thread for the whole instance — there is only ever one
# RailDownloadJob row (pk=1) to run, unlike the old per-user dict.
_running_thread = None


def _download_boxes(boxes, attempt=1, beat=None, depth=0):
    """Fetch subway track ways + station nodes intersecting any of `boxes`.

    Returns (way_dicts, station_dicts, failed_box_count, unreachable). Same
    retry-then-bisect-on-HTTPError, fail-whole-batch-fast-on-unreachable
    strategy as road_download_tasks._download_boxes — see that function's
    docstring for why the two failure modes need different treatment: bisecting
    a batch that never reached Overpass at all just reproduces the identical
    failure at every leaf, slower.
    """
    clauses = []
    for (s, w, n, e) in boxes:
        bb = f'({s:.5f},{w:.5f},{n:.5f},{e:.5f})'
        # Running tracks only — service tracks (yards/sidings/crossovers) carry a
        # `service` tag and would add shortcuts that were never ridden.
        clauses.append(f'  way["railway"="subway"]["service"!~"."]{bb};')
        # Station nodes, both the modern (station=subway) and older
        # (railway=station + subway=yes) tagging. Used for the proximity test and
        # for transfer stitching in the router.
        clauses.append(f'  node["station"="subway"]{bb};')
        clauses.append(f'  node["railway"="station"]["subway"="yes"]{bb};')
    query = (
        f'[out:json][timeout:{OVERPASS_TIMEOUT}];\n'
        f'(\n' + '\n'.join(clauses) + f'\n);\n'
        f'out body geom;\n'
    )
    res = overpass.overpass_query(query, beat=beat)

    if res.kind in ('http', 'timeout'):
        # Overpass was reached — it answered with an error status, or was too
        # slow to answer at all. Both mean a smaller query has a real chance, so
        # both retry and then bisect. See road_download_tasks._download_boxes.
        if attempt < MAX_ATTEMPTS:
            time.sleep(RETRY_BACKOFF_S * attempt)
            return _download_boxes(boxes, attempt + 1, beat, depth)
        if len(boxes) > 1 and (res.kind == 'http' or depth < MAX_TIMEOUT_BISECT_DEPTH):
            mid = len(boxes) // 2
            lw, ls, lf, lu = _download_boxes(boxes[:mid], beat=beat, depth=depth + 1)
            rw, rs, rf, ru = _download_boxes(boxes[mid:], beat=beat, depth=depth + 1)
            return lw + rw, ls + rs, lf + rf, lu and ru
        logger.warning('Overpass %s for %d subway box(es) via %s: %s',
                       res.kind, len(boxes), res.endpoint, res.error)
        # Deliberately NOT unreachable — an endpoint answered.
        return [], [], len(boxes), False

    if res.kind == 'unreachable':
        # No endpoint in the pool could be reached; every leaf of a bisection
        # would fail identically, so fail the whole batch at once.
        if attempt < MAX_ATTEMPTS:
            time.sleep(RETRY_BACKOFF_S * attempt)
            return _download_boxes(boxes, attempt + 1, beat, depth)
        logger.warning('No Overpass endpoint reachable, failing batch of %d boxes: %s',
                       len(boxes), res.error)
        return [], [], len(boxes), True

    result = res.data
    if result.get('remark'):
        logger.warning('Overpass remark on subway download: %s', result['remark'])
        if len(boxes) > 1:
            mid = len(boxes) // 2
            lw, ls, lf, lu = _download_boxes(boxes[:mid], beat=beat, depth=depth + 1)
            rw, rs, rf, ru = _download_boxes(boxes[mid:], beat=beat, depth=depth + 1)
            return lw + rw, ls + rs, lf + rf, lu and ru

    ways, stations = [], []
    for el in result.get('elements', []):
        etype = el.get('type')
        tags = el.get('tags') or {}
        if etype == 'way':
            geom = el.get('geometry') or []
            coords = [(g['lon'], g['lat']) for g in geom
                      if g and g.get('lon') is not None and g.get('lat') is not None]
            if len(coords) < 2:
                continue
            ways.append({
                'way_id': el['id'],
                'name': (tags.get('name') or '')[:200],
                'railway': (tags.get('railway') or 'subway')[:32],
                'coords': coords,
            })
        elif etype == 'node':
            if el.get('lon') is None or el.get('lat') is None:
                continue
            stations.append({
                'node_id': el['id'],
                'name': (tags.get('name') or '')[:200],
                'lon': el['lon'],
                'lat': el['lat'],
            })
    return ways, stations, 0, False


def _store(ways, stations):
    """bulk_create both tables with ignore_conflicts. Returns (ways, stations) added."""
    from django.contrib.gis.geos import LineString, Point
    from .models import RailSegment, RailStation

    w_added = 0
    for i in range(0, len(ways), INSERT_CHUNK):
        objs = []
        for w in ways[i:i + INSERT_CHUNK]:
            try:
                geom = LineString(w['coords'], srid=4326)
            except Exception:
                continue
            objs.append(RailSegment(
                way_id=w['way_id'], name=w['name'], railway=w['railway'], geom=geom))
        if objs:
            w_added += len(RailSegment.objects.bulk_create(objs, ignore_conflicts=True))

    s_added = 0
    for i in range(0, len(stations), INSERT_CHUNK):
        objs = []
        for st in stations[i:i + INSERT_CHUNK]:
            try:
                geom = Point(st['lon'], st['lat'], srid=4326)
            except Exception:
                continue
            objs.append(RailStation(node_id=st['node_id'], name=st['name'], geom=geom))
        if objs:
            s_added += len(RailStation.objects.bulk_create(objs, ignore_conflicts=True))
    return w_added, s_added


def _rail_download_worker(token):
    """Download subway data for the whole instance. Exits when the job stops
    naming it."""
    from .models import RailDownloadJob

    processed = 0
    ways_added = 0
    stations_added = 0
    failed = 0

    def _beat(_n=None):
        RailDownloadJob.objects.filter(pk=1, worker_token=token).update(
            updated_at=timezone.now())

    try:
        # Only cells not already in DownloadedRegion(kind='subway') — see
        # road_download_tasks._visited_boxes.
        boxes, box_cells = _visited_boxes('subway', beat=_beat)
        batches = [boxes[i:i + BOXES_PER_REQUEST]
                   for i in range(0, len(boxes), BOXES_PER_REQUEST)]
        cell_batches = [box_cells[i:i + BOXES_PER_REQUEST]
                        for i in range(0, len(box_cells), BOXES_PER_REQUEST)]

        try:
            job = RailDownloadJob.objects.get(pk=1)
        except RailDownloadJob.DoesNotExist:
            return
        if job.worker_token != token:
            return
        job.total = len(batches)
        job.save(update_fields=['total', 'updated_at'])
        logger.info('Subway download: %d new areas in %d batches', len(boxes), len(batches))

        consecutive_unreachable = 0
        for batch, cell_batch in zip(batches, cell_batches):
            try:
                job.refresh_from_db()
                if job.status != 'running' or job.worker_token != token:
                    return
            except RailDownloadJob.DoesNotExist:
                return

            ways, stations, failed_boxes, unreachable = _download_boxes(batch, beat=_beat)
            if ways or stations:
                w, s = _store(ways, stations)
                ways_added += w
                stations_added += s
            failed += failed_boxes
            processed += 1
            consecutive_unreachable = consecutive_unreachable + 1 if unreachable else 0
            if failed_boxes == 0:
                _mark_covered('subway', [k for keys in cell_batch for k in keys])

            written = RailDownloadJob.objects.filter(
                pk=1, worker_token=token, status='running'
            ).update(processed=processed, ways=ways_added, stations=stations_added,
                     failed=failed, updated_at=timezone.now())
            if not written:
                return

            if consecutive_unreachable >= CONNECTIVITY_FAIL_LIMIT:
                remaining = len(batches) - processed
                if remaining:
                    failed += remaining
                    processed = len(batches)
                    RailDownloadJob.objects.filter(
                        pk=1, worker_token=token, status='running'
                    ).update(processed=processed, failed=failed, updated_at=timezone.now())
                logger.warning(
                    'Subway download: Overpass unreachable for %d consecutive '
                    'batches, giving up early (%d of %d batches skipped)',
                    CONNECTIVITY_FAIL_LIMIT, remaining, len(batches))
                return

            time.sleep(REQUEST_SLEEP_S)
    except Exception:
        logger.exception('Subway download crashed')
    finally:
        try:
            RailDownloadJob.objects.filter(
                pk=1, worker_token=token, status='running'
            ).update(processed=processed, ways=ways_added, stations=stations_added,
                     failed=failed, status='completed', updated_at=timezone.now())
        except Exception:
            pass
        try:
            from django.core.cache import cache
            from .models import SUBWAY_AVAILABLE_CACHE_KEY
            cache.delete(SUBWAY_AVAILABLE_CACHE_KEY)
        except Exception:
            pass
        global _running_thread
        _running_thread = None
        close_old_connections()
        logger.info('Subway download done: %s batches, %s ways, %s '
                    'stations, %s areas failed',
                    processed, ways_added, stations_added, failed)


def _is_thread_alive():
    return _running_thread is not None and _running_thread.is_alive()


def _start_thread(token):
    global _running_thread
    t = threading.Thread(target=_rail_download_worker, args=(token,), daemon=True)
    _running_thread = t
    t.start()


def _claim():
    from .models import RailDownloadJob
    token = secrets.token_hex(8)
    RailDownloadJob.objects.filter(pk=1).update(
        worker_token=token, updated_at=timezone.now())
    return token


def start_subway_download():
    from .models import RailDownloadJob

    job, created = RailDownloadJob.objects.get_or_create(
        pk=1, defaults={'status': 'running', 'total': 0},
    )
    if not created:
        if job.status == 'running' and _is_thread_alive():
            return job
        job.status = 'running'
        job.total = 0
        job.processed = 0
        job.ways = 0
        job.stations = 0
        job.failed = 0
        job.save(update_fields=['status', 'total', 'processed', 'ways', 'stations',
                                'failed', 'updated_at'])
    _start_thread(_claim())
    return job


def stop_subway_download():
    from .models import RailDownloadJob
    stopped = RailDownloadJob.objects.filter(
        pk=1, status='running'
    ).update(status='stopped', worker_token='', updated_at=timezone.now())
    return bool(stopped)


def get_subway_download_status():
    from .models import RailDownloadJob, RailSegment, RailStation

    total_ways = RailSegment.objects.count()
    total_stations = RailStation.objects.count()
    try:
        job = RailDownloadJob.objects.get(pk=1)
    except RailDownloadJob.DoesNotExist:
        return {'status': 'idle', 'phase': '', 'processed': 0, 'total': 0,
                'ways': 0, 'stations': 0, 'failed': 0,
                'total_ways': total_ways, 'total_stations': total_stations}

    if job.status == 'running' and not _is_thread_alive():
        if (timezone.now() - job.updated_at).total_seconds() > STALE_AFTER_S:
            _start_thread(_claim())

    return {
        'status': job.status,
        'phase': 'scanning' if (job.status == 'running' and not job.total) else 'downloading',
        'processed': job.processed or 0,
        'total': job.total or 0,
        'ways': job.ways or 0,
        'stations': job.stations or 0,
        'failed': job.failed or 0,
        'total_ways': total_ways,
        'total_stations': total_stations,
    }
