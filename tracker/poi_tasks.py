import json
import time
import threading
import logging
import urllib.parse
import urllib.request

from django.db.models import Avg, Count

logger = logging.getLogger(__name__)

_running_threads = {}

OVERPASS_URL = 'https://overpass-api.de/api/interpreter'
OVERPASS_HEADERS = {'User-Agent': 'Roamly/0.6.1 (self-hosted location tracker)'}
SEARCH_RADIUS = 15000  # 15km around each city center
POI_TAGS = [
    'shop', 'amenity', 'aeroway', 'tourism', 'leisure',
    'historic', 'office', 'craft', 'healthcare', 'sport',
    'military', 'natural', 'man_made', 'railway', 'club',
]


def _download_city_pois(lat, lng, radius=SEARCH_RADIUS):
    """Download named POIs near a point from Overpass. Returns list of dicts."""
    tag_lines = []
    for tag in POI_TAGS:
        tag_lines.append(f'  nwr["name"]["{tag}"](around:{radius},{lat},{lng});')
    # Also get branded places
    tag_lines.append(f'  nwr["brand"](around:{radius},{lat},{lng});')

    query = (
        f'[out:json][timeout:30];\n'
        f'(\n' + '\n'.join(tag_lines) + f'\n);\n'
        f'out center 5000;\n'
    )

    try:
        body = urllib.parse.urlencode({'data': query}).encode()
        req = urllib.request.Request(OVERPASS_URL, body, OVERPASS_HEADERS)
        with urllib.request.urlopen(req, timeout=35) as resp:
            result = json.loads(resp.read().decode())

        if result.get('remark'):
            logger.warning(f"Overpass remark for ({lat},{lng}): {result['remark']}")
            return []

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
        return pois
    except Exception as e:
        logger.warning(f"Overpass POI download failed for ({lat},{lng}): {e}")
        return []


def _poi_download_worker(user_id):
    """Worker that downloads POIs for all cities a user has visited."""
    from .models import Location, POI, POIDownloadJob

    processed = 0
    pois_added = 0

    try:
        # Get distinct city centroids
        cities = list(
            Location.objects.filter(device__user_id=user_id)
            .exclude(city='')
            .values('city', 'state')
            .annotate(
                avg_lat=Avg('latitude'),
                avg_lng=Avg('longitude'),
                cnt=Count('id'),
            )
            .order_by('-cnt')
        )

        total = len(cities)

        try:
            job = POIDownloadJob.objects.get(user_id=user_id)
            job.total = total
            job.save(update_fields=['total'])
        except POIDownloadJob.DoesNotExist:
            return

        for city in cities:
            # Check if we should stop
            try:
                job.refresh_from_db()
                if job.status != 'running':
                    break
            except POIDownloadJob.DoesNotExist:
                break

            pois = _download_city_pois(city['avg_lat'], city['avg_lng'])

            # Bulk insert, ignoring duplicates
            poi_objects = []
            for p in pois:
                poi_objects.append(POI(
                    name=p['name'][:300],
                    latitude=p['latitude'],
                    longitude=p['longitude'],
                    category=p['category'][:100],
                    address=p['address'][:500],
                ))

            if poi_objects:
                created = POI.objects.bulk_create(
                    poi_objects, ignore_conflicts=True
                )
                pois_added += len(created)

            processed += 1

            # Save progress
            try:
                job.refresh_from_db()
                job.processed = processed
                job.pois_added = pois_added
                job.save(update_fields=['processed', 'pois_added', 'updated_at'])
            except POIDownloadJob.DoesNotExist:
                break

            # Rate limit: wait between city downloads
            time.sleep(2)

    finally:
        try:
            job = POIDownloadJob.objects.get(user_id=user_id)
            job.processed = processed
            job.pois_added = pois_added
            if job.status == 'running':
                job.status = 'completed'
            job.save(update_fields=['processed', 'pois_added', 'status', 'updated_at'])
        except POIDownloadJob.DoesNotExist:
            pass

        _running_threads.pop(user_id, None)
        logger.info(f"POI download done for user {user_id}: {processed} cities, {pois_added} POIs added")


def start_poi_download(user_id):
    """Start POI download for a user. Returns the job."""
    from .models import POIDownloadJob

    # Clean up finished jobs
    POIDownloadJob.objects.filter(
        user_id=user_id, status__in=['completed', 'stopped']
    ).delete()

    job, created = POIDownloadJob.objects.get_or_create(
        user_id=user_id,
        defaults={'status': 'running', 'total': 0},
    )

    if not created:
        if job.status == 'running' and _is_thread_alive(user_id):
            return job
        job.status = 'running'
        job.total = 0
        job.processed = 0
        job.pois_added = 0
        job.save()

    thread = threading.Thread(
        target=_poi_download_worker, args=(user_id,), daemon=True
    )
    _running_threads[user_id] = thread
    thread.start()
    return job


def _is_thread_alive(user_id):
    thread = _running_threads.get(user_id)
    return thread is not None and thread.is_alive()


def get_poi_status(user_id):
    """Get POI download status."""
    from .models import POIDownloadJob, POI

    try:
        job = POIDownloadJob.objects.get(user_id=user_id)
    except POIDownloadJob.DoesNotExist:
        return {
            'status': 'idle',
            'total_pois': POI.objects.count(),
        }

    # Only mark stale if no update for 60s (handles multi-worker gunicorn)
    if job.status == 'running':
        from django.utils import timezone
        age = (timezone.now() - job.updated_at).total_seconds()
        if age > 60:
            job.status = 'completed'
            job.save(update_fields=['status'])

    return {
        'status': job.status,
        'processed': job.processed,
        'total': job.total,
        'pois_added': job.pois_added,
        'total_pois': POI.objects.count(),
    }


def stop_poi_download(user_id):
    """Signal the POI download to stop."""
    from .models import POIDownloadJob

    try:
        job = POIDownloadJob.objects.get(user_id=user_id, status='running')
        job.status = 'stopped'
        job.save(update_fields=['status'])
        return True
    except POIDownloadJob.DoesNotExist:
        return False
