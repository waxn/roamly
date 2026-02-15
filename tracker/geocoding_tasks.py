import time
import threading
import logging
from django.utils import timezone

logger = logging.getLogger(__name__)

# In-memory reference to the running thread so we can check if it's alive
_running_threads = {}

BATCH_SIZE = 500
UPDATE_INTERVAL = 10  # Save progress to DB every N points


def _geocode_worker(user_id):
    """Worker that geocodes all un-geocoded locations for a user."""
    from .views import reverse_geocode
    from .models import Location, GeocodingJob

    processed = 0
    errors = 0

    try:
        while True:
            # Check if we should stop
            try:
                job = GeocodingJob.objects.get(user_id=user_id)
            except GeocodingJob.DoesNotExist:
                break
            if job.status != 'running':
                break

            batch = list(
                Location.objects.filter(
                    device__user_id=user_id, city=''
                ).order_by('-timestamp')[:BATCH_SIZE]
            )
            if not batch:
                break

            for loc in batch:
                # Re-check stop flag periodically
                if processed > 0 and processed % UPDATE_INTERVAL == 0:
                    try:
                        job.refresh_from_db()
                        if job.status != 'running':
                            break
                        job.processed = processed
                        job.errors = errors
                        job.save(update_fields=['processed', 'errors', 'updated_at'])
                    except GeocodingJob.DoesNotExist:
                        break

                try:
                    result = reverse_geocode(loc.latitude, loc.longitude)
                    if result:
                        loc.city = result['city']
                        loc.state = result['state']
                        loc.country = result['country']
                        loc.country_code = result['country_code']
                        loc.place_name = result['place_name']
                        loc.save(update_fields=[
                            'city', 'state', 'country',
                            'country_code', 'place_name',
                        ])
                        processed += 1
                    else:
                        errors += 1
                except Exception as e:
                    logger.error(f"Geocoding error: {e}")
                    errors += 1

                time.sleep(1.1)

            # Check if inner loop broke due to stop
            try:
                job.refresh_from_db()
                if job.status != 'running':
                    break
            except GeocodingJob.DoesNotExist:
                break

    finally:
        # Final save
        try:
            job = GeocodingJob.objects.get(user_id=user_id)
            job.processed = processed
            job.errors = errors
            if job.status == 'running':
                job.status = 'completed'
            job.save(update_fields=['processed', 'errors', 'status', 'updated_at'])
        except GeocodingJob.DoesNotExist:
            pass

        _running_threads.pop(user_id, None)
        logger.info(f"Geocoding done for user {user_id}: {processed} processed, {errors} errors")


def start_geocoding(user_id, total):
    """Start (or resume) geocoding for a user. Returns the job."""
    from .models import GeocodingJob

    # Clean up any finished job
    GeocodingJob.objects.filter(
        user_id=user_id, status__in=['completed', 'stopped']
    ).delete()

    # Create or get running job
    job, created = GeocodingJob.objects.get_or_create(
        user_id=user_id,
        defaults={'status': 'running', 'total': total},
    )

    if not created:
        # Job row exists — already running?
        if job.status == 'running' and _is_thread_alive(user_id):
            return job
        # Stale job (server restarted) — reset and restart
        job.status = 'running'
        job.total = total
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

    # If DB says running but thread is dead, the server restarted — auto-resume
    if job.status == 'running' and not _is_thread_alive(user_id):
        remaining = Location.objects.filter(
            device__user_id=user_id, city=''
        ).count()
        if remaining > 0:
            job.total = job.processed + remaining
            job.save(update_fields=['total'])
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
