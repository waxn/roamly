import time
import threading
import logging
from django.utils import timezone

logger = logging.getLogger(__name__)

_active_tasks = {}

BATCH_SIZE = 500


class GeocodingTask:
    """Background task to geocode all un-geocoded locations with rate limiting."""

    def __init__(self, user_id):
        self.user_id = user_id
        self.task_id = f"geocode_{user_id}_{int(time.time())}"
        self.processed = 0
        self.errors = 0
        self.total = 0
        self.status = 'pending'
        self.started_at = None
        self.completed_at = None
        self._stop = False

    def run(self, total):
        self.total = total
        self.status = 'running'
        self.started_at = timezone.now()
        _active_tasks[self.user_id] = self
        thread = threading.Thread(target=self._process)
        thread.daemon = True
        thread.start()
        return self.task_id

    def stop(self):
        self._stop = True

    def _process(self):
        from .views import reverse_geocode
        from .models import Location, Device

        while not self._stop:
            batch = list(
                Location.objects.filter(
                    device__user_id=self.user_id, city=''
                ).order_by('-timestamp')[:BATCH_SIZE]
            )
            if not batch:
                break

            for loc in batch:
                if self._stop:
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
                        self.processed += 1
                    else:
                        self.errors += 1
                except Exception as e:
                    logger.error(f"Geocoding error: {e}")
                    self.errors += 1
                time.sleep(1.1)

        self.status = 'stopped' if self._stop else 'completed'
        self.completed_at = timezone.now()
        logger.info(
            f"Geocoding {self.status}: {self.processed}/{self.total} succeeded"
        )


def get_active_task(user_id):
    task = _active_tasks.get(user_id)
    if task:
        return task, {
            'status': task.status,
            'processed': task.processed,
            'errors': task.errors,
            'total': task.total,
        }
    return None


def stop_active_task(user_id):
    task = _active_tasks.get(user_id)
    if task and task.status == 'running':
        task.stop()
        return True
    return False


def cleanup_old_tasks(user_id):
    task = _active_tasks.get(user_id)
    if task and task.status in ('completed', 'stopped'):
        del _active_tasks[user_id]
