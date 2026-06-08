import time
import threading
import logging
from collections import defaultdict

from django.utils import timezone

logger = logging.getLogger(__name__)

# In-memory reference to the running thread so we can check if it's alive
_running_threads = {}

# How many points to reverse-geocode per loop iteration. Offline lookups are
# instant, so this is purely a memory/progress-granularity knob, not a rate limit.
_CHUNK = 2000


def _geocode_worker(user_id):
    """Label every un-geocoded point for a user via offline reverse geocoding.

    Offline lookups are instant and unlimited (no Nominatim, no `429`), so this
    drains the entire backlog in chunks with no per-request network call and no
    rate-limit sleep. Points are grouped by resulting label so each distinct place
    is a single UPDATE, and the user's cache is busted after each chunk so labels
    surface in the UI promptly.
    """
    from .models import Location, GeocodingJob
    from .offline_geocode import offline_reverse_geocode
    from .views import _bust_user_cache

    points_done = 0
    errors = 0
    job = None

    try:
        try:
            job = GeocodingJob.objects.get(user_id=user_id)
        except GeocodingJob.DoesNotExist:
            return

        total = Location.objects.filter(device__user_id=user_id, city='').count()
        if total == 0:
            return
        job.total = total
        job.processed = 0
        job.errors = 0
        job.save(update_fields=['total', 'processed', 'errors', 'updated_at'])

        logger.info(f"Offline-geocoding user {user_id}: {total} points")

        while True:
            # Honour a stop request raised from the UI.
            try:
                job.refresh_from_db()
                if job.status != 'running':
                    break
            except GeocodingJob.DoesNotExist:
                break

            rows = list(
                Location.objects
                .filter(device__user_id=user_id, city='')
                .values_list('id', 'latitude', 'longitude')[:_CHUNK]
            )
            if not rows:
                break

            coords = [(lat, lon) for _id, lat, lon in rows]
            try:
                results = offline_reverse_geocode(coords)
            except Exception as e:
                # e.g. ImportError if the image hasn't been rebuilt with the new
                # requirement yet — fail loudly into the job rather than spinning.
                logger.error(f"Offline geocoding failed: {e}")
                errors += len(rows)
                job.errors = errors
                job.save(update_fields=['errors', 'updated_at'])
                break

            # Group ids by resulting label → one UPDATE per distinct place.
            groups = defaultdict(list)
            for (loc_id, _lat, _lon), res in zip(rows, results):
                # Never leave city='' or the filter above would re-select it forever.
                city = res['city'] or 'Unknown'
                key = (city, res['state'], res['country'], res['country_code'], res['place_name'])
                groups[key].append(loc_id)

            updated = 0
            for (city, state, country, cc, place), ids in groups.items():
                Location.objects.filter(id__in=ids).update(
                    city=city, state=state, country=country,
                    country_code=cc, place_name=place,
                )
                updated += len(ids)

            points_done += updated
            job.processed = points_done
            job.save(update_fields=['processed', 'updated_at'])
            _bust_user_cache(user_id)

            if updated == 0:
                break  # safety: nothing advanced, don't loop forever

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
            f"Offline-geocoding done for user {user_id}: {points_done} points, {errors} errors"
        )


# Per-user debounce for the auto-trigger fired from the location push path.
_last_auto_trigger = {}
_AUTO_TRIGGER_DEBOUNCE_S = 30


def ensure_auto_geocode(user_id):
    """Fire-and-forget background geocode for freshly-pushed points.

    Keeps the /api/push/ request path free of any blocking Nominatim call: new
    points land with city='' and get labelled here, by the rate-limited cluster
    worker, instead of stalling each upload for up to 10s (which was wedging the
    mobile uploader into long backoff gaps). Debounced per user and a no-op while
    a geocode thread (manual or auto) is already running, so a burst of pushes
    spawns at most one worker. Never blocks the caller.
    """
    from .models import GeocodingJob, Location

    now = time.monotonic()
    if now - _last_auto_trigger.get(user_id, 0.0) < _AUTO_TRIGGER_DEBOUNCE_S:
        return
    if _is_thread_alive(user_id):
        return
    # Nothing to do if every point is already labelled.
    if not Location.objects.filter(device__user_id=user_id, city='').exists():
        return

    _last_auto_trigger[user_id] = now
    job, created = GeocodingJob.objects.get_or_create(
        user_id=user_id, defaults={'status': 'running', 'total': 0},
    )
    if not created and job.status != 'running':
        job.status = 'running'
        job.total = 0
        job.processed = 0
        job.errors = 0
        job.save(update_fields=['status', 'total', 'processed', 'errors', 'updated_at'])
    _start_thread(user_id)


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
