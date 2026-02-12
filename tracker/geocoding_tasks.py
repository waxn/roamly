import time
import threading
import logging
from django.utils import timezone

logger = logging.getLogger(__name__)

_active_tasks = {}


class GeocodingTask:
    """Background task to geocode multiple locations with rate limiting."""

    def __init__(self, user_id):
        self.user_id = user_id
        self.task_id = f"geocode_{user_id}_{int(time.time())}"
        self.processed = 0
        self.errors = 0
        self.total = 0
        self.status = 'pending'
        self.started_at = None
        self.completed_at = None

    def run(self, locations):
        self.total = len(locations)
        self.status = 'running'
        self.started_at = timezone.now()
        _active_tasks[self.user_id] = self
        thread = threading.Thread(target=self._process, args=(locations,))
        thread.daemon = True
        thread.start()
        return self.task_id

    def _process(self, locations):
        from .views import reverse_geocode
        for loc in locations:
            try:
                result = reverse_geocode(loc.latitude, loc.longitude)
                if result:
                    loc.city = result['city']
                    loc.state = result['state']
                    loc.country = result['country']
                    loc.country_code = result['country_code']
                    loc.place_name = result['place_name']
                    loc.save(update_fields=['city', 'state', 'country', 'country_code', 'place_name'])
                    self.processed += 1
                else:
                    self.errors += 1
            except Exception as e:
                logger.error(f"Geocoding error: {e}")
                self.errors += 1
            time.sleep(1.1)
        self.status = 'completed'
        self.completed_at = timezone.now()
        logger.info(f"Geocoding complete: {self.processed}/{self.total} succeeded")


def get_active_task(user_id):
    task = _active_tasks.get(user_id)
    if task:
        return task, {
            'status': task.status,
            'processed': task.processed,
            'errors': task.errors,
            'total': task.total
        }
    return None


def cleanup_old_tasks(user_id):
    task = _active_tasks.get(user_id)
    if task and task.status == 'completed':
        del _active_tasks[user_id]
