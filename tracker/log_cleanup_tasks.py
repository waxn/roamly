"""Background log-cleanup scheduler.

Sweeps every hour and prunes AccessLog / ActionLog rows older than the
retention thresholds stored in AdminPanelConfig (0 = keep forever).
"""

import logging
import threading
import time

from django.utils import timezone
from datetime import timedelta

logger = logging.getLogger(__name__)

_SWEEP_INTERVAL_S = 3600  # 1 hour
_cleanup_thread = None
_cleanup_lock = threading.Lock()


def _run_cleanup():
    while True:
        time.sleep(_SWEEP_INTERVAL_S)
        try:
            _do_cleanup()
        except Exception:
            logger.exception("log cleanup sweep failed")


def _do_cleanup():
    from .models import AccessLog, ActionLog, AdminPanelConfig

    config = AdminPanelConfig.load()
    now = timezone.now()

    if config.access_log_retention_days > 0:
        cutoff = now - timedelta(days=config.access_log_retention_days)
        deleted, _ = AccessLog.objects.filter(timestamp__lt=cutoff).delete()
        if deleted:
            logger.info("log cleanup: pruned %d access log rows older than %d days", deleted, config.access_log_retention_days)

    if config.standard_log_retention_days > 0:
        cutoff = now - timedelta(days=config.standard_log_retention_days)
        deleted, _ = ActionLog.objects.filter(timestamp__lt=cutoff).delete()
        if deleted:
            logger.info("log cleanup: pruned %d action log rows older than %d days", deleted, config.standard_log_retention_days)


def start_log_cleanup_scheduler():
    global _cleanup_thread
    with _cleanup_lock:
        if _cleanup_thread and _cleanup_thread.is_alive():
            return
        _cleanup_thread = threading.Thread(target=_run_cleanup, daemon=True, name="log-cleanup")
        _cleanup_thread.start()
        logger.info("log cleanup scheduler started")
