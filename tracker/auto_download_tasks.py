"""Background scheduler that starts road/subway/POI downloads automatically.

Downloads (see the admin panel's Downloads tab) used to require an admin to
notice someone had travelled somewhere new and click a button. This sweeps
periodically and, for whichever kinds are enabled
(SiteConfig.auto_download_roads/subway/pois — each defaults on), calls the
exact same entry point the manual button uses:
start_road_download()/start_subway_download()/start_poi_download(). There is
no separate "auto" download code path to keep in sync with the manual one.

Those functions are already incremental — road_download_tasks._visited_boxes
and poi_tasks._poi_download_worker both diff the full desired area against
DownloadedRegion for that kind — so a sweep that finds nothing new costs one
DB scan and zero Overpass requests. That's what makes running this
unattended, on a timer, safe by default: an instance whose travel history is
already fully covered barely notices it running.

Mirrors the daemon-thread pattern in log_cleanup_tasks.py: one long-lived
thread, close_old_connections() each pass (no request cycle to recycle the
connection otherwise), a PostgreSQL advisory lock so only one gunicorn worker
sweeps, every exception swallowed so the loop never dies.
"""

import logging
import threading
import time
from contextlib import contextmanager

from django.db import close_old_connections, connection
from django.utils import timezone

logger = logging.getLogger(__name__)

# 6 hours. New regions accumulate slowly (someone has to actually travel
# somewhere new), and each sweep's own incremental design means most ticks
# cost nothing — there's no benefit to polling faster, and every unnecessary
# tick is one more chance to lean on a shared Overpass instance harder than
# it needs to be.
_SWEEP_INTERVAL_S = 6 * 3600
_auto_thread = None
_auto_lock = threading.Lock()

# Advisory-lock namespace, distinct from stats (0x52414d4c 'RAML'), summary
# emails (0x52414d53 'RAMS') and log cleanup (0x52414d47 'RAMG').
# 'RAMD' — the auto-download single-flight guard.
_LOCK_NAMESPACE = 0x52414d44
_LOCK_KEY = 0  # one global sweep, not per-user, so a fixed second key.


@contextmanager
def _sweep_lock():
    """Yield True iff this worker holds the exclusive sweep lock.

    On PostgreSQL a session advisory lock guarantees a single sweeping worker
    across all gunicorn processes; auto-released if the holder dies. On
    SQLite (single process, no advisory locks) it's a no-op that always
    yields True.
    """
    if connection.vendor != 'postgresql':
        yield True
        return
    got = False
    try:
        with connection.cursor() as cur:
            cur.execute("SELECT pg_try_advisory_lock(%s, %s)", [_LOCK_NAMESPACE, _LOCK_KEY])
            got = bool(cur.fetchone()[0])
        yield got
    finally:
        if got:
            with connection.cursor() as cur:
                cur.execute("SELECT pg_advisory_unlock(%s, %s)", [_LOCK_NAMESPACE, _LOCK_KEY])


def _already_running(job):
    """True if `job` (a RoadDownloadJob/RailDownloadJob/POIDownloadJob) looks
    like it's genuinely mid-run — possibly in another process, which this
    sweep has no local thread handle for. Mirrors the staleness grace each
    job's own get_*_status() uses to decide whether to resurrect a run, so
    the sweep and a status poll agree on what "still running" means."""
    from .road_download_tasks import STALE_AFTER_S
    return (job.status == 'running'
            and (timezone.now() - job.updated_at).total_seconds() < STALE_AFTER_S)


def _maybe_start_road():
    from .models import RoadDownloadJob
    from .road_download_tasks import start_road_download
    if _already_running(RoadDownloadJob.load()):
        return
    logger.info('auto-download: checking for new road regions')
    start_road_download()


def _maybe_start_subway():
    from .models import RailDownloadJob
    from .rail_download_tasks import start_subway_download
    if _already_running(RailDownloadJob.load()):
        return
    logger.info('auto-download: checking for new subway regions')
    start_subway_download()


def _maybe_start_poi():
    from .models import POIDownloadJob
    from .poi_tasks import start_poi_download
    if _already_running(POIDownloadJob.load()):
        return
    logger.info('auto-download: checking for new POI cities')
    start_poi_download()


def _do_sweep():
    from .models import SiteConfig

    config = SiteConfig.load()
    # Sequential, not parallel: each start_*_download() call only blocks long
    # enough to kick its own background thread and returns immediately, and
    # running the three checks one after another naturally staggers whatever
    # Overpass load they do end up generating instead of bursting all three
    # at once.
    if config.auto_download_roads:
        try:
            _maybe_start_road()
        except Exception:
            logger.exception('auto-download: road check failed')
    if config.auto_download_subway:
        try:
            _maybe_start_subway()
        except Exception:
            logger.exception('auto-download: subway check failed')
    if config.auto_download_pois:
        try:
            _maybe_start_poi()
        except Exception:
            logger.exception('auto-download: POI check failed')


def _run_scheduler():
    time.sleep(120)  # let migrations/startup settle before the first sweep
    while True:
        try:
            close_old_connections()
            with _sweep_lock() as got:
                if got:
                    _do_sweep()
        except Exception:
            logger.exception('auto-download sweep failed')
        finally:
            close_old_connections()
        time.sleep(_SWEEP_INTERVAL_S)


def start_auto_download_scheduler():
    global _auto_thread
    with _auto_lock:
        if _auto_thread and _auto_thread.is_alive():
            return
        _auto_thread = threading.Thread(target=_run_scheduler, daemon=True, name="auto-download")
        _auto_thread.start()
        logger.info("auto-download scheduler started")
