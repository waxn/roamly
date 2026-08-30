"""Download named OpenStreetMap POIs into the POI table (background thread).

Mirrors road_download_tasks.py — same daemon thread, same Job-row + worker-token
mechanics, same Overpass politeness — but a different area-selection strategy:
POIs are fetched from a 15km disc around every distinct city centroid anyone on
the instance has been to, rather than the road downloader's tighter ~2km
travelled-corridor cells. A city-level radius makes sense here in a way it
wouldn't for roads: POI is a much smaller table (bare name/point/category, no
geometry), and "everywhere reachable from downtown" is a more useful set of
named places than only the streets actually driven.

**Admin-only, instance-wide singleton** (`POIDownloadJob`, pk=1) — POI has no
user FK and is shared by everyone on the instance, so both the job and the
city list it downloads for now cover every user's location history, not just
one user's own.
"""

import logging
import secrets
import threading
import time

from django.db.models import Avg, Count
from django.utils import timezone

from . import overpass

logger = logging.getLogger(__name__)

# Single background thread for the whole instance — there is only ever one
# POIDownloadJob row (pk=1) to run, unlike the old per-user dict.
_running_thread = None

SEARCH_RADIUS = 15000  # 15km around each city center
POI_TAGS = [
    'shop', 'amenity', 'aeroway', 'tourism', 'leisure',
    'historic', 'office', 'craft', 'healthcare', 'sport',
    'military', 'natural', 'man_made', 'railway', 'club',
]
MAX_ATTEMPTS = 2
RETRY_BACKOFF_S = 4
REQUEST_SLEEP_S = 2       # be a good Overpass citizen between cities
STALE_AFTER_S = 240
# Consecutive whole-city "never reached Overpass" results before the run gives
# up early — same reasoning as road_download_tasks.CONNECTIVITY_FAIL_LIMIT,
# though there's no bisection here to amplify: a dead connection would still
# otherwise grind through every remaining city for nothing.
CONNECTIVITY_FAIL_LIMIT = 2


def _download_city_pois(lat, lng, radius=SEARCH_RADIUS, attempt=1, beat=None):
    """Download named POIs near a point from Overpass.

    Returns (poi_dicts, unreachable). `unreachable=True` means no Overpass
    endpoint could be reached at all, as opposed to succeeding with genuinely
    zero results — the two look identical from a bare empty list, but only the
    first is grounds for the caller's circuit breaker to trip.

    A *timeout* is deliberately not unreachable. This query is the heaviest the
    app makes (every POI tag within 15km of a city), and it used to run with its
    own tighter 30s/35s budget while roads had 60s/80s, so against an ordinarily
    slow mirror it timed out on essentially every city — and each of those
    counted as unreachable, which tripped the breaker two cities in and marked
    every remaining city failed. It now shares tracker/overpass.py's timeouts
    and classification with the road and subway downloads.
    """
    tag_lines = []
    for tag in POI_TAGS:
        tag_lines.append(f'  nwr["name"]["{tag}"](around:{radius},{lat},{lng});')
    # Also get branded places
    tag_lines.append(f'  nwr["brand"](around:{radius},{lat},{lng});')

    query = (
        f'[out:json][timeout:{overpass.QUERY_TIMEOUT}];\n'
        f'(\n' + '\n'.join(tag_lines) + f'\n);\n'
        f'out center 5000;\n'
    )

    res = overpass.overpass_query(query, beat=beat)

    if res.kind in ('http', 'timeout'):
        # Overpass was reached but gave no usable answer — an error status, or
        # too slow. One retry with backoff; there is nothing to bisect here,
        # since one city is already one query.
        if attempt < MAX_ATTEMPTS:
            time.sleep(RETRY_BACKOFF_S * attempt)
            return _download_city_pois(lat, lng, radius, attempt + 1, beat)
        logger.warning('Overpass %s for POI download near (%s,%s) via %s: %s',
                       res.kind, lat, lng, res.endpoint, res.error)
        # Deliberately NOT unreachable — an endpoint answered.
        return [], False

    if res.kind == 'unreachable':
        if attempt < MAX_ATTEMPTS:
            time.sleep(RETRY_BACKOFF_S * attempt)
            return _download_city_pois(lat, lng, radius, attempt + 1, beat)
        logger.warning('No Overpass endpoint reachable for POI download near (%s,%s): %s',
                       lat, lng, res.error)
        return [], True

    result = res.data
    if result.get('remark'):
        logger.warning(f"Overpass remark for ({lat},{lng}): {result['remark']}")
        return [], False

    pois = []
    for el in result.get('elements', []):
        el_lat = el.get('lat') or (el.get('center') or {}).get('lat')
        el_lon = el.get('lon') or (el.get('center') or {}).get('lon')
        if not el_lat or not el_lon:
            continue
        tags = el.get('tags', {})
        name = tags.get('name', tags.get('brand', ''))
        if not name:
            continue

        # Determine category from tags
        category = ''
        for t in POI_TAGS:
            if t in tags:
                category = t
                break
        if not category and 'brand' in tags:
            category = 'brand'

        addr_parts = []
        for k in ('addr:housenumber', 'addr:street', 'addr:city', 'addr:state'):
            if tags.get(k):
                addr_parts.append(tags[k])
        address = ', '.join(addr_parts)

        pois.append({
            'name': name,
            'latitude': round(el_lat, 6),
            'longitude': round(el_lon, 6),
            'category': category,
            'address': address,
        })
    return pois, False


def _poi_download_worker(token):
    """Worker that downloads POIs for every distinct city anyone on the
    instance has visited. Exits the moment the job stops naming it."""
    from .models import DownloadedRegion, Location, POI, POIDownloadJob

    processed = 0
    pois_added = 0
    failed = 0

    def _beat():
        POIDownloadJob.objects.filter(pk=1, worker_token=token).update(
            updated_at=timezone.now())

    try:
        # Distinct city centroids across every device on every user's account,
        # minus cities already in DownloadedRegion(kind='poi') — a re-run
        # (manual or the auto-download sweep) only asks Overpass about cities
        # nobody had visited last time, same incremental idea as
        # road_download_tasks._visited_boxes.
        cities = list(
            Location.objects.exclude(city='')
            .values('city', 'state')
            .annotate(
                avg_lat=Avg('latitude'),
                avg_lng=Avg('longitude'),
                cnt=Count('id'),
            )
            .order_by('-cnt')
        )
        covered = set(DownloadedRegion.objects.filter(kind='poi').values_list('key', flat=True))
        cities = [c for c in cities if f"{c['city']}|{c['state']}" not in covered]

        total = len(cities)

        try:
            job = POIDownloadJob.objects.get(pk=1)
        except POIDownloadJob.DoesNotExist:
            return
        if job.worker_token != token:
            return
        job.total = total
        job.save(update_fields=['total', 'updated_at'])
        logger.info(f"POI download: {total} cities")

        consecutive_unreachable = 0
        for city in cities:
            try:
                job.refresh_from_db()
                if job.status != 'running' or job.worker_token != token:
                    return
            except POIDownloadJob.DoesNotExist:
                return

            pois, unreachable = _download_city_pois(
                city['avg_lat'], city['avg_lng'], beat=_beat)
            consecutive_unreachable = consecutive_unreachable + 1 if unreachable else 0
            if unreachable:
                failed += 1
            else:
                # Overpass genuinely answered for this city (even if it found
                # nothing) — mark it covered so a future run doesn't ask again.
                # An unreachable city stays uncovered and gets retried.
                DownloadedRegion.objects.get_or_create(
                    kind='poi', key=f"{city['city']}|{city['state']}")

            poi_objects = [
                POI(
                    name=p['name'][:300],
                    latitude=p['latitude'],
                    longitude=p['longitude'],
                    category=p['category'][:100],
                    address=p['address'][:500],
                )
                for p in pois
            ]
            if poi_objects:
                created = POI.objects.bulk_create(poi_objects, ignore_conflicts=True)
                pois_added += len(created)

            processed += 1

            written = POIDownloadJob.objects.filter(
                pk=1, worker_token=token, status='running'
            ).update(processed=processed, pois_added=pois_added, failed=failed,
                     updated_at=timezone.now())
            if not written:
                return

            if consecutive_unreachable >= CONNECTIVITY_FAIL_LIMIT:
                remaining = total - processed
                if remaining:
                    failed += remaining
                    processed = total
                    POIDownloadJob.objects.filter(
                        pk=1, worker_token=token, status='running'
                    ).update(processed=processed, failed=failed, updated_at=timezone.now())
                logger.warning(
                    'POI download: Overpass unreachable for %d consecutive '
                    'cities, giving up early (%d of %d cities skipped)',
                    CONNECTIVITY_FAIL_LIMIT, remaining, total)
                return

            # Rate limit: wait between city downloads.
            time.sleep(REQUEST_SLEEP_S)

    except Exception:
        logger.exception('POI download crashed')
    finally:
        try:
            POIDownloadJob.objects.filter(
                pk=1, worker_token=token, status='running'
            ).update(processed=processed, pois_added=pois_added, failed=failed,
                     status='completed', updated_at=timezone.now())
        except Exception:
            pass
        global _running_thread
        _running_thread = None
        logger.info(f"POI download done: {processed} cities, {pois_added} POIs added, {failed} failed")


def _is_thread_alive():
    return _running_thread is not None and _running_thread.is_alive()


def _start_thread(token):
    global _running_thread
    t = threading.Thread(target=_poi_download_worker, args=(token,), daemon=True)
    _running_thread = t
    t.start()


def _claim():
    from .models import POIDownloadJob
    token = secrets.token_hex(8)
    POIDownloadJob.objects.filter(pk=1).update(
        worker_token=token, updated_at=timezone.now())
    return token


def start_poi_download():
    """Start the instance-wide POI download. Returns the job."""
    from .models import POIDownloadJob

    job, created = POIDownloadJob.objects.get_or_create(
        pk=1, defaults={'status': 'running', 'total': 0},
    )
    if not created:
        if job.status == 'running' and _is_thread_alive():
            return job
        job.status = 'running'
        job.total = 0
        job.processed = 0
        job.pois_added = 0
        job.failed = 0
        job.save(update_fields=['status', 'total', 'processed', 'pois_added', 'failed', 'updated_at'])
    _start_thread(_claim())
    return job


def get_poi_status():
    """Get POI download status."""
    from .models import POIDownloadJob, POI

    try:
        job = POIDownloadJob.objects.get(pk=1)
    except POIDownloadJob.DoesNotExist:
        return {'status': 'idle', 'processed': 0, 'total': 0, 'pois_added': 0,
                'failed': 0, 'total_pois': POI.objects.count()}

    # Resurrect a run whose worker really did die (a process recycle) — same
    # STALE_AFTER_S grace and _claim()-retires-any-straggler reasoning as
    # road/subway download.
    if job.status == 'running' and not _is_thread_alive():
        if (timezone.now() - job.updated_at).total_seconds() > STALE_AFTER_S:
            _start_thread(_claim())

    return {
        'status': job.status,
        'processed': job.processed,
        'total': job.total,
        'pois_added': job.pois_added,
        'failed': job.failed,
        'total_pois': POI.objects.count(),
    }


def stop_poi_download():
    """Stop the run. Clearing the token retires the worker as well as the row —
    same reasoning as road/subway download's stop function."""
    from .models import POIDownloadJob
    stopped = POIDownloadJob.objects.filter(
        pk=1, status='running'
    ).update(status='stopped', worker_token='', updated_at=timezone.now())
    return bool(stopped)
