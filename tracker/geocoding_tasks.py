import time
import threading
import logging
from collections import defaultdict
from datetime import timedelta

from django.utils import timezone

logger = logging.getLogger(__name__)

# In-memory reference to the running thread so we can check if it's alive
_running_threads = {}

# Round to 3 decimal places ≈ ~111m grid cells
GRID_PRECISION = 3


def _build_clusters(user_id):
    """Load all un-geocoded coordinates and group into spatial clusters.
    Returns list of (representative_lat, representative_lon, [location_ids]).
    Uses values_list to keep memory low.
    """
    from .models import Location

    rows = (
        Location.objects.filter(device__user_id=user_id, city='')
        .values_list('id', 'latitude', 'longitude')
    )

    grid = defaultdict(list)
    rep = {}  # grid_key -> (lat, lon) of first point
    for loc_id, lat, lon in rows:
        key = (round(lat, GRID_PRECISION), round(lon, GRID_PRECISION))
        grid[key].append(loc_id)
        if key not in rep:
            rep[key] = (lat, lon)

    clusters = []
    for key, loc_ids in grid.items():
        lat, lon = rep[key]
        clusters.append((lat, lon, loc_ids))

    return clusters


def _geocode_worker(user_id):
    """Worker that geocodes all un-geocoded locations for a user using spatial clustering."""
    from .views import reverse_geocode
    from .models import Location, GeocodingJob

    points_done = 0
    errors = 0

    try:
        # Build all clusters upfront
        clusters = _build_clusters(user_id)
        if not clusters:
            return

        total_clusters = len(clusters)
        total_points = sum(len(ids) for _, _, ids in clusters)

        try:
            job = GeocodingJob.objects.get(user_id=user_id)
            job.total = total_clusters
            job.processed = 0
            job.errors = 0
            job.save(update_fields=['total', 'processed', 'errors', 'updated_at'])
        except GeocodingJob.DoesNotExist:
            return

        logger.info(
            f"Geocoding user {user_id}: {total_points} points in "
            f"{total_clusters} clusters"
        )

        for i, (lat, lon, loc_ids) in enumerate(clusters):
            # Check stop flag
            try:
                job.refresh_from_db()
                if job.status != 'running':
                    break
            except GeocodingJob.DoesNotExist:
                break

            try:
                result = reverse_geocode(lat, lon)

                if result:
                    Location.objects.filter(id__in=loc_ids).update(
                        city=result['city'],
                        state=result['state'],
                        country=result['country'],
                        country_code=result['country_code'],
                        place_name=result['place_name'],
                    )
                    points_done += len(loc_ids)
                else:
                    errors += 1
            except Exception as e:
                logger.error(f"Geocoding error: {e}")
                errors += 1

            # Update progress after every cluster
            job.processed = i + 1
            job.errors = errors
            job.save(update_fields=['processed', 'errors', 'updated_at'])

            time.sleep(1.1)

    finally:
        try:
            job = GeocodingJob.objects.get(user_id=user_id)
            if job.status == 'running':
                job.status = 'completed'
            job.save(update_fields=['status', 'updated_at'])
        except GeocodingJob.DoesNotExist:
            pass

        _running_threads.pop(user_id, None)
        logger.info(
            f"Geocoding done for user {user_id}: {points_done} points, "
            f"{errors} errors, {job.processed}/{job.total} clusters"
        )


def start_geocoding(user_id, total_points):
    """Start (or resume) geocoding for a user. Returns the job."""
    from .models import GeocodingJob

    # Clean up any finished job
    GeocodingJob.objects.filter(
        user_id=user_id, status__in=['completed', 'stopped']
    ).delete()

    # Create or get running job (total will be updated to cluster count by worker)
    job, created = GeocodingJob.objects.get_or_create(
        user_id=user_id,
        defaults={'status': 'running', 'total': 0},
    )

    if not created:
        if job.status == 'running' and _is_thread_alive(user_id):
            return job
        job.status = 'running'
        job.total = 0
        job.processed = 0
        job.errors = 0
        job.save()

    _start_thread(user_id)
    return job


def _is_thread_alive(user_id):
    thread = _running_threads.get(user_id)
    return thread is not None and thread.is_alive()


def _start_thread(user_id):
    thread = threading.Thread(target=_geocode_worker, args=(user_id,), daemon=True)
    _running_threads[user_id] = thread
    thread.start()


def get_status(user_id):
    """Get geocoding status for a user. Auto-resumes stale tasks."""
    from .models import GeocodingJob, Location

    try:
        job = GeocodingJob.objects.get(user_id=user_id)
    except GeocodingJob.DoesNotExist:
        return {'status': 'idle'}

    # If DB says running but this worker has no thread, check if another worker
    # is actively processing (updated_at refreshed within the last 30s). Only
    # auto-resume if the job is truly stale to avoid spawning duplicate threads
    # across multiple Gunicorn workers.
    if job.status == 'running' and not _is_thread_alive(user_id):
        stale = (timezone.now() - job.updated_at).total_seconds() > 30
        if stale:
            remaining = Location.objects.filter(
                device__user_id=user_id, city=''
            ).count()
            if remaining > 0:
                _start_thread(user_id)
            else:
                job.status = 'completed'
                job.save(update_fields=['status'])

    return {
        'status': job.status,
        'processed': job.processed,
        'errors': job.errors,
        'total': job.total,
    }


def stop_geocoding(user_id):
    """Signal the geocoding task to stop."""
    from .models import GeocodingJob

    try:
        job = GeocodingJob.objects.get(user_id=user_id, status='running')
        job.status = 'stopped'
        job.save(update_fields=['status'])
        return True
    except GeocodingJob.DoesNotExist:
        return False
