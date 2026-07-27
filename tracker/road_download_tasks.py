"""Download OSM road geometry into the RoadSegment table (background thread).

Mirrors poi_tasks.py — same daemon thread, same Job row for progress, same
Overpass politeness — but differs in one important way: **the download area is
derived from where the user actually has fixes**, not from a blanket radius
around each city centroid.

That matters because RoadSegment is the largest table in the app. A 15km disc
around a city centre is ~700 km², and a driver has been on maybe 5% of it. Taking
the union of the ~2km grid cells the user has points in, merged into runs, covers
the same roads for roughly 5-10x less area and storage.

Other size controls:
  * only drivable highway classes — no service roads (driveways and parking
    aisles are a large share of all OSM ways and you never snap a drive to one),
    no footways, paths or cycleways
  * `node_ids` is left empty: the routing graph identifies a junction by the
    shared *coordinate* (roads._vkey), since connected ways share a node and so
    carry an identical position, which makes the id list dead weight on the
    biggest table in the app
  * way_id is unique, so overlapping areas dedupe instead of duplicating

Geometry is stored **unsimplified**, deliberately. Douglas-Peucker would shave
20-30% of vertices, but a junction is recognised *by its coordinate*, and a
simplifier has no way of knowing which vertices are shared with another way —
dropping one silently disconnects the graph there. The area reduction above
already saves far more than simplification would.
"""

import json
import logging
import threading
import time
import urllib.parse
import urllib.request

from django.db import close_old_connections

# Reused rather than reimplemented — note it takes **lat first**, unlike
# roads._haversine_m which takes lng first.
from .transport_tasks import _haversine_m

logger = logging.getLogger(__name__)

_running_threads = {}

OVERPASS_URL = 'https://overpass-api.de/api/interpreter'
OVERPASS_HEADERS = {'User-Agent': 'Roamly (self-hosted location tracker)'}

# Drivable classes only. `service` is excluded on purpose: it is one of the most
# numerous highway values in OSM (driveways, parking aisles, alleys) and snapping
# a road journey to one is almost always wrong.
HIGHWAY_RE = r'^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)(_link)?$'

# Visited-cell size in degrees. ~2.2km of latitude; small enough that the union
# of cells traces the corridors actually travelled rather than a bounding blob.
CELL_DEG = 0.02
# Pad each queried box so roads just outside a cell edge are still available to
# snap against, and a route can leave the corridor briefly.
CELL_PAD_DEG = 0.004
# Limits on bridging the cells between two consecutive fixes. Deliberately the
# same rules gapfill_tasks applies before it will route a gap (_GAP_MAX_MPS and
# roads._ROUTE_MAX_KM), so the download covers what routing will attempt and no
# more — without them a flight would rasterise a transcontinental line of cells.
MAX_BRIDGE_M = 150_000.0
MAX_BRIDGE_MPS = 60.0
# Overpass clauses per request. Each is a bbox; more clauses means fewer
# round-trips but a longer server-side query.
# Fewer, smaller batches: a big merged corridor is exactly what makes Overpass
# time out, and a bisecting retry costs less when the batch is small to begin with.
BOXES_PER_REQUEST = 20
OVERPASS_TIMEOUT = 180
MAX_ATTEMPTS = 3          # retries per batch before bisecting it
RETRY_BACKOFF_S = 5
REQUEST_SLEEP_S = 3       # be a good Overpass citizen between requests
INSERT_CHUNK = 2000


def _line_cells(gy0, gx0, gy1, gx1):
    """Every grid cell the straight line between two cells passes through.

    Integer DDA over the cell grid — a supercover walk, so no cell the segment
    clips is missed.
    """
    dy, dx = gy1 - gy0, gx1 - gx0
    steps = max(abs(dy), abs(dx))
    if steps == 0:
        return [(gy0, gx0)]
    out = []
    for i in range(steps + 1):
        f = i / steps
        out.append((round(gy0 + dy * f), round(gx0 + dx * f)))
    return out


def _visited_boxes(user_id):
    """Bounding boxes covering the ~2km cells the user has travelled through.

    Includes the cells *between* consecutive fixes, not only the cells containing
    them. This is essential rather than a refinement: a tracking gap is by
    definition a stretch with no fixes, so a fix-only area contributed no cells
    there, Overpass was never asked for roads along that corridor, and
    roads._load_graph then had nothing to connect the two anchors with. The gaps
    the fill job exists to close were precisely the ones it could never route —
    endpoints snapped, middle permanently empty.

    Cells are merged along each latitude row afterwards, so a corridor collapses
    into a handful of wide boxes instead of hundreds of squares — fewer Overpass
    clauses for identical coverage.
    """
    from .models import Location

    cells = set()
    # Ordered by (device, timestamp) so "consecutive" means something. Backed by
    # the existing tracker_loc_device__idx index on ('device', '-timestamp').
    qs = (Location.objects.filter(device__user_id=user_id)
          .order_by('device_id', 'timestamp')
          .values_list('device_id', 'latitude', 'longitude', 'timestamp')
          .iterator(chunk_size=20000))

    prev = None
    for dev_id, lat, lng, ts in qs:
        if lat is None or lng is None:
            continue
        cell = (int(lat // CELL_DEG), int(lng // CELL_DEG))
        cells.add(cell)

        if prev is not None and prev[0] == dev_id:
            _, p_lat, p_lng, p_ts, p_cell = prev
            # Only bridge what the gap filler would actually try to route, so the
            # download covers exactly what routing needs and nothing more.
            span_m = _haversine_m(p_lat, p_lng, lat, lng)
            dt = (ts - p_ts).total_seconds() if (ts and p_ts) else 0
            plausible = span_m <= MAX_BRIDGE_M and not (dt > 0 and span_m / dt > MAX_BRIDGE_MPS)
            if plausible and cell != p_cell:
                cells.update(_line_cells(p_cell[0], p_cell[1], cell[0], cell[1]))

        prev = (dev_id, lat, lng, ts, cell)

    rows = {}
    for gy, gx in cells:
        rows.setdefault(gy, []).append(gx)

    boxes = []
    for gy, xs in rows.items():
        xs.sort()
        run_start = prev = xs[0]
        for gx in xs[1:]:
            if gx == prev + 1:
                prev = gx
                continue
            boxes.append(_cell_box(gy, run_start, prev))
            run_start = prev = gx
        boxes.append(_cell_box(gy, run_start, prev))
    return boxes


def _cell_box(gy, gx_from, gx_to):
    """(south, west, north, east) for a run of cells in one row, padded."""
    south = gy * CELL_DEG - CELL_PAD_DEG
    north = (gy + 1) * CELL_DEG + CELL_PAD_DEG
    west = gx_from * CELL_DEG - CELL_PAD_DEG
    east = (gx_to + 1) * CELL_DEG + CELL_PAD_DEG
    return (south, west, north, east)


def _download_boxes(boxes, attempt=1):
    """Fetch every drivable way intersecting any of `boxes`.

    Returns (way_dicts, failed_box_count). A whole batch used to degrade silently
    to an empty list on any Overpass error — a timeout on one busy corridor then
    left that corridor with no roads at all, and nothing in the UI said so, which
    reads exactly like snapping and gap routing being broken. So: retry with
    backoff for transient errors, then bisect the batch (a timeout is usually one
    dense box, not the whole set), and report what could not be fetched.
    """
    clauses = '\n'.join(
        f'  way["highway"~"{HIGHWAY_RE}"]({s:.5f},{w:.5f},{n:.5f},{e:.5f});'
        for (s, w, n, e) in boxes
    )
    query = (
        f'[out:json][timeout:{OVERPASS_TIMEOUT}];\n'
        f'(\n{clauses}\n);\n'
        f'out body geom;\n'
    )
    try:
        body = urllib.parse.urlencode({'data': query}).encode()
        req = urllib.request.Request(OVERPASS_URL, body, OVERPASS_HEADERS)
        with urllib.request.urlopen(req, timeout=OVERPASS_TIMEOUT + 20) as resp:
            result = json.loads(resp.read().decode())
    except Exception as exc:
        if attempt < MAX_ATTEMPTS:
            # Overpass rate-limits and sheds load routinely; back off and retry
            # the same batch before concluding anything is wrong with it.
            time.sleep(RETRY_BACKOFF_S * attempt)
            return _download_boxes(boxes, attempt + 1)
        if len(boxes) > 1:
            mid = len(boxes) // 2
            left, lf = _download_boxes(boxes[:mid])
            right, rf = _download_boxes(boxes[mid:])
            return left + right, lf + rf
        logger.warning('Overpass road download failed for box %s: %s', boxes[0], exc)
        return [], 1

    if result.get('remark'):
        # A remark means the query was truncated or hit a server limit, so the
        # result is incomplete even though the request "succeeded".
        logger.warning('Overpass remark on road download: %s', result['remark'])
        if len(boxes) > 1:
            mid = len(boxes) // 2
            left, lf = _download_boxes(boxes[:mid])
            right, rf = _download_boxes(boxes[mid:])
            return left + right, lf + rf

    ways = []
    for el in result.get('elements', []):
        if el.get('type') != 'way':
            continue
        geom = el.get('geometry') or []
        coords = [(g['lon'], g['lat']) for g in geom
                  if g and g.get('lon') is not None and g.get('lat') is not None]
        if len(coords) < 2:
            continue
        tags = el.get('tags') or {}
        ways.append({
            'way_id': el['id'],
            'name': (tags.get('name') or '')[:200],
            'highway': (tags.get('highway') or '')[:32],
            'oneway': tags.get('oneway') in ('yes', 'true', '1', '-1'),
            # Vestigial. The routing graph keys vertices on the coordinates
            # themselves (roads._vkey), because connected ways share a node and
            # therefore share an identical coordinate. Depending on node ids
            # instead used to break routing outright: Overpass returns incomplete
            # geometry for ways clipped at a query-box edge, those rows got no
            # node ids, and every one was silently dropped from the graph. Left
            # unpopulated so it costs nothing on the largest table in the app.
            'node_ids': [],
            'coords': coords,
        })
    return ways, 0


def _store_ways(ways):
    """bulk_create with ignore_conflicts on the unique way_id. Returns rows added."""
    from django.contrib.gis.geos import LineString
    from .models import RoadSegment

    added = 0
    for i in range(0, len(ways), INSERT_CHUNK):
        objs = []
        for w in ways[i:i + INSERT_CHUNK]:
            try:
                geom = LineString(w['coords'], srid=4326)
            except Exception:
                continue
            objs.append(RoadSegment(
                way_id=w['way_id'], name=w['name'], highway=w['highway'],
                oneway=w['oneway'], node_ids=w['node_ids'], geom=geom,
            ))
        if objs:
            created = RoadSegment.objects.bulk_create(objs, ignore_conflicts=True)
            added += len(created)
    return added


def _road_download_worker(user_id):
    from .models import RoadDownloadJob

    processed = 0
    ways_added = 0
    failed = 0
    job = None
    try:
        boxes = _visited_boxes(user_id)
        batches = [boxes[i:i + BOXES_PER_REQUEST]
                   for i in range(0, len(boxes), BOXES_PER_REQUEST)]

        try:
            job = RoadDownloadJob.objects.get(user_id=user_id)
        except RoadDownloadJob.DoesNotExist:
            return
        job.total = len(batches)
        job.save(update_fields=['total', 'updated_at'])

        for batch in batches:
            try:
                job.refresh_from_db()
                if job.status != 'running':
                    break
            except RoadDownloadJob.DoesNotExist:
                break

            ways, failed_boxes = _download_boxes(batch)
            if ways:
                ways_added += _store_ways(ways)
            failed += failed_boxes
            processed += 1

            try:
                job.refresh_from_db()
                job.processed = processed
                job.ways = ways_added
                job.failed = failed
                job.save(update_fields=['processed', 'ways', 'failed', 'updated_at'])
            except RoadDownloadJob.DoesNotExist:
                break

            time.sleep(REQUEST_SLEEP_S)
    except Exception:
        logger.exception('Road download crashed for user %s', user_id)
    finally:
        try:
            job = RoadDownloadJob.objects.get(user_id=user_id)
            job.processed = processed
            job.ways = ways_added
            job.failed = failed
            if job.status == 'running':
                job.status = 'completed'
            job.save(update_fields=['processed', 'ways', 'failed', 'status', 'updated_at'])
        except Exception:
            pass
        # The local provider only reports itself available once rows exist, and
        # that check is cached — bust it so the new roads are usable immediately.
        try:
            from django.core.cache import cache
            from .models import ROADS_AVAILABLE_CACHE_KEY
            cache.delete(ROADS_AVAILABLE_CACHE_KEY)
        except Exception:
            pass
        _running_threads.pop(user_id, None)
        close_old_connections()
        logger.info('Road download done for user %s: %s batches, %s ways added, '
                    '%s areas failed', user_id, processed, ways_added, failed)


def _is_thread_alive(user_id):
    t = _running_threads.get(user_id)
    return t is not None and t.is_alive()


def _start_thread(user_id):
    t = threading.Thread(target=_road_download_worker, args=(user_id,), daemon=True)
    _running_threads[user_id] = t
    t.start()


def start_road_download(user_id):
    from .models import RoadDownloadJob

    job, created = RoadDownloadJob.objects.get_or_create(
        user_id=user_id, defaults={'status': 'running', 'total': 0},
    )
    if not created:
        if job.status == 'running' and _is_thread_alive(user_id):
            return job
        job.status = 'running'
        job.total = 0
        job.processed = 0
        job.ways = 0
        job.failed = 0
        job.save(update_fields=['status', 'total', 'processed', 'ways', 'failed', 'updated_at'])
    _start_thread(user_id)
    return job


def stop_road_download(user_id):
    from .models import RoadDownloadJob
    try:
        job = RoadDownloadJob.objects.get(user_id=user_id, status='running')
        job.status = 'stopped'
        job.save(update_fields=['status', 'updated_at'])
        return True
    except RoadDownloadJob.DoesNotExist:
        return False


def get_road_download_status(user_id):
    from django.utils import timezone
    from .models import RoadDownloadJob, RoadSegment

    total_ways = RoadSegment.objects.count()
    try:
        job = RoadDownloadJob.objects.get(user_id=user_id)
    except RoadDownloadJob.DoesNotExist:
        return {'status': 'idle', 'total_ways': total_ways}

    # Restart a thread that died mid-run (e.g. a worker recycle), same 30s grace
    # as the other job workers.
    if job.status == 'running' and not _is_thread_alive(user_id):
        if (timezone.now() - job.updated_at).total_seconds() > 30:
            _start_thread(user_id)

    return {
        'status': job.status,
        'processed': job.processed,
        'total': job.total,
        'ways': job.ways,
        'failed': job.failed,
        'total_ways': total_ways,
    }
