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
import secrets
import threading
import time
import urllib.parse
import urllib.request

from django.db import close_old_connections
from django.utils import timezone

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
# same rules the editor applies before it will route between two points, so
# the download covers what routing will attempt and no
# more — without them a flight would rasterise a transcontinental line of cells.
MAX_BRIDGE_M = 150_000.0
MAX_BRIDGE_MPS = 60.0
# Overpass clauses per request. Each is a bbox; more clauses means fewer
# round-trips but a longer server-side query.
# Fewer, smaller batches: a big merged corridor is exactly what makes Overpass
# time out, and a bisecting retry costs less when the batch is small to begin with.
BOXES_PER_REQUEST = 20
# Cap how many cells a merged run may span. Corridor bridging makes long runs of
# adjacent cells, and merging them wholesale produced single bboxes hundreds of
# kilometres wide — asking Overpass for every drivable way in a strip that size
# is what made requests time out in the first place. ~26km per box.
MAX_RUN_CELLS = 12
# Deliberately below Overpass's own patience so a hung request fails fast rather
# than holding a worker for minutes.
OVERPASS_TIMEOUT = 60
MAX_ATTEMPTS = 2          # attempts per batch before bisecting it
RETRY_BACKOFF_S = 4
REQUEST_SLEEP_S = 3       # be a good Overpass citizen between requests
# How long the row may go untouched before a status poll assumes the worker died.
# Must exceed the worst case for one batch by a wide margin — the old 30s was
# shorter than a single stalled Overpass request, so every poll resurrected
# another worker and they all fought over the progress counter.
STALE_AFTER_S = 240
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


def _visited_boxes(user_id, beat=None):
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
    from .models import Device, Location

    cells = set()
    # One device at a time, ordered by timestamp. A single global
    # order_by('device_id', 'timestamp') cannot use tracker_loc_device__idx
    # (which is ('device', '-timestamp')), so Postgres full-sorts every row
    # before yielding the first one — on a large history that is the slowest
    # thing the whole job does, and it happens before any progress is reported.
    # Filtering per device lets the index serve each scan directly.
    scanned = 0
    for dev_id in Device.objects.filter(user_id=user_id).values_list('id', flat=True):
        prev = None
        qs = (Location.objects.filter(device_id=dev_id)
              .order_by('timestamp')
              .values_list('latitude', 'longitude', 'timestamp')
              .iterator(chunk_size=20000))
        for lat, lng, ts in qs:
            scanned += 1
            if beat and scanned % 20000 == 0:
                beat(scanned)
            if lat is None or lng is None:
                continue
            cell = (int(lat // CELL_DEG), int(lng // CELL_DEG))
            cells.add(cell)

            if prev is not None:
                p_lat, p_lng, p_ts, p_cell = prev
                # Only bridge what routing would actually attempt, so the
                # download covers what it needs and nothing more.
                span_m = _haversine_m(p_lat, p_lng, lat, lng)
                dt = (ts - p_ts).total_seconds() if (ts and p_ts) else 0
                plausible = span_m <= MAX_BRIDGE_M and not (dt > 0 and span_m / dt > MAX_BRIDGE_MPS)
                if plausible and cell != p_cell:
                    cells.update(_line_cells(p_cell[0], p_cell[1], cell[0], cell[1]))

            prev = (lat, lng, ts, cell)

    rows = {}
    for gy, gx in cells:
        rows.setdefault(gy, []).append(gx)

    boxes = []
    for gy, xs in rows.items():
        xs.sort()
        run_start = prev = xs[0]
        for gx in xs[1:]:
            # Break the run on a gap OR at MAX_RUN_CELLS, so no single bbox grows
            # wide enough to time Overpass out.
            if gx == prev + 1 and (prev - run_start + 1) < MAX_RUN_CELLS:
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


def _download_boxes(boxes, attempt=1, beat=None):
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
    if beat:
        beat()
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
            return _download_boxes(boxes, attempt + 1, beat)
        if len(boxes) > 1:
            mid = len(boxes) // 2
            left, lf = _download_boxes(boxes[:mid], beat=beat)
            right, rf = _download_boxes(boxes[mid:], beat=beat)
            return left + right, lf + rf
        logger.warning('Overpass road download failed for box %s: %s', boxes[0], exc)
        return [], 1

    if result.get('remark'):
        # A remark means the query was truncated or hit a server limit, so the
        # result is incomplete even though the request "succeeded".
        logger.warning('Overpass remark on road download: %s', result['remark'])
        if len(boxes) > 1:
            mid = len(boxes) // 2
            left, lf = _download_boxes(boxes[:mid], beat=beat)
            right, rf = _download_boxes(boxes[mid:], beat=beat)
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


def _road_download_worker(user_id, token):
    """Download roads for one user. Exits the moment the job stops naming it.

    `token` is the cross-process ownership check. Without it a status poll served
    by another gunicorn worker sees an empty `_running_threads`, assumes the run
    died and starts a second one; each keeps its own progress counter and
    overwrites the other, so the reported percentage jumps around and can go
    backwards. Whoever the row names is the only worker allowed to continue.
    """
    from .models import RoadDownloadJob

    processed = 0
    ways_added = 0
    failed = 0
    job = None

    def _beat(_n=None):
        # Touch the row so a slow-but-healthy phase is never mistaken for a dead
        # worker. Needed during the history scan too, which runs before `total`
        # is known and used to leave the row untouched for its whole duration.
        RoadDownloadJob.objects.filter(user_id=user_id, worker_token=token).update(
            updated_at=timezone.now())

    try:
        # total stays 0 while this runs; the status endpoint reports that as the
        # 'scanning' phase so the UI can say what is happening.
        boxes = _visited_boxes(user_id, beat=_beat)
        batches = [boxes[i:i + BOXES_PER_REQUEST]
                   for i in range(0, len(boxes), BOXES_PER_REQUEST)]

        try:
            job = RoadDownloadJob.objects.get(user_id=user_id)
        except RoadDownloadJob.DoesNotExist:
            return
        if job.worker_token != token:
            return
        job.total = len(batches)
        job.save(update_fields=['total', 'updated_at'])
        logger.info('Road download for user %s: %d areas in %d batches',
                    user_id, len(boxes), len(batches))

        for batch in batches:
            try:
                job.refresh_from_db()
                if job.status != 'running' or job.worker_token != token:
                    return
            except RoadDownloadJob.DoesNotExist:
                return

            ways, failed_boxes = _download_boxes(batch, beat=_beat)
            if ways:
                ways_added += _store_ways(ways)
            failed += failed_boxes
            processed += 1

            # Conditional on the token, so a superseded worker can never write
            # its own counter over the live one.
            written = RoadDownloadJob.objects.filter(
                user_id=user_id, worker_token=token, status='running'
            ).update(processed=processed, ways=ways_added, failed=failed,
                     updated_at=timezone.now())
            if not written:
                return

            time.sleep(REQUEST_SLEEP_S)
    except Exception:
        logger.exception('Road download crashed for user %s', user_id)
    finally:
        # Only the owner finalises; a superseded worker must leave the row alone.
        try:
            RoadDownloadJob.objects.filter(
                user_id=user_id, worker_token=token, status='running'
            ).update(processed=processed, ways=ways_added, failed=failed,
                     status='completed', updated_at=timezone.now())
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


def _start_thread(user_id, token):
    t = threading.Thread(target=_road_download_worker, args=(user_id, token), daemon=True)
    _running_threads[user_id] = t
    t.start()


def _claim(user_id):
    """Take ownership of the run and return the new token.

    Writing a fresh token is also how any worker still running from a previous
    start is retired: it sees the row no longer names it and returns.
    """
    from .models import RoadDownloadJob
    token = secrets.token_hex(8)
    RoadDownloadJob.objects.filter(user_id=user_id).update(
        worker_token=token, updated_at=timezone.now())
    return token


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
    _start_thread(user_id, _claim(user_id))
    return job


def stop_road_download(user_id):
    """Stop the run. Clearing the token retires the worker as well as the row.

    Status alone was not enough: a worker sitting in a retry backoff only re-reads
    it between batches, and a restart flipping the status back to 'running' would
    have let that straggler carry on alongside the new one.
    """
    from .models import RoadDownloadJob
    stopped = RoadDownloadJob.objects.filter(
        user_id=user_id, status='running'
    ).update(status='stopped', worker_token='', updated_at=timezone.now())
    return bool(stopped)


def get_road_download_status(user_id):
    from .models import RoadDownloadJob, RoadSegment

    total_ways = RoadSegment.objects.count()
    try:
        job = RoadDownloadJob.objects.get(user_id=user_id)
    except RoadDownloadJob.DoesNotExist:
        return {'status': 'idle', 'phase': '', 'processed': 0, 'total': 0,
                'ways': 0, 'failed': 0, 'total_ways': total_ways}

    # Resurrect a run whose worker really did die (a process recycle). The grace
    # is long enough to clear the slowest healthy batch, and claiming a new token
    # retires any straggler rather than racing it.
    if job.status == 'running' and not _is_thread_alive(user_id):
        if (timezone.now() - job.updated_at).total_seconds() > STALE_AFTER_S:
            _start_thread(user_id, _claim(user_id))

    return {
        'status': job.status,
        # 'scanning' means the history sweep that works out which areas to fetch
        # is still running, so there is no batch count yet. Without this the UI
        # had nothing to distinguish it from a stalled run.
        'phase': 'scanning' if (job.status == 'running' and not job.total) else 'downloading',
        'processed': job.processed or 0,
        'total': job.total or 0,
        'ways': job.ways or 0,
        'failed': job.failed or 0,
        'total_ways': total_ways,
    }
