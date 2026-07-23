"""Background log-cleanup + rollup scheduler.

Sweeps hourly and:
  1. Rolls up per-day activity into DailyLogRollup (kept forever) *before* any
     pruning, so the admin panel's long-range graphs survive row deletion.
  2. Prunes AccessLog older than SiteConfig.access_log_retention_days (the
     high-volume "most stuff").
  3. Prunes ActionLog older than SiteConfig.event_log_retention_days (the
     low-volume logins/signups/errors/admin actions — 0 = keep forever).

Mirrors the daemon-thread pattern in stats_tasks.py: one long-lived thread,
close_old_connections() each pass (no request cycle means Django never recycles
the connection otherwise), a PostgreSQL advisory lock so only one gunicorn
worker sweeps, and every exception swallowed so the loop never dies.
"""

import logging
import threading
import time
from contextlib import contextmanager
from datetime import datetime, time as dtime, timedelta

from django.db import close_old_connections, connection
from django.utils import timezone

logger = logging.getLogger(__name__)

_SWEEP_INTERVAL_S = 3600  # 1 hour — longer than CONN_MAX_AGE (600s) so the held
                          # connection is always recycled between passes.
_cleanup_thread = None
_cleanup_lock = threading.Lock()

# Advisory-lock namespace, distinct from stats (0x52414d4c 'RAML') and summary
# emails (0x52414d53 'RAMS'). 'RAMG' — the log-cleanup single-flight guard.
_LOCK_NAMESPACE = 0x52414d47
_LOCK_KEY = 0  # global sweep (not per-user), so a fixed second key.


@contextmanager
def _sweep_lock():
    """Yield True iff this worker holds the exclusive cleanup lock.

    On PostgreSQL a session advisory lock guarantees a single sweeping worker
    across all gunicorn processes; auto-released if the holder dies. On SQLite
    (single process, no advisory locks) it's a no-op that always yields True.
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


def _run_cleanup():
    # Run one sweep shortly after boot so a fresh instance rolls up / prunes
    # without waiting a full hour, then settle into the hourly cadence.
    time.sleep(60)
    while True:
        try:
            close_old_connections()
            with _sweep_lock() as got:
                if got:
                    _do_sweep()
        except Exception:
            logger.exception("log cleanup sweep failed")
        finally:
            close_old_connections()
        time.sleep(_SWEEP_INTERVAL_S)


def _rollup_days(today):
    """Upsert DailyLogRollup for every day from the last rolled-up day through
    yesterday (today is still accumulating, so it's rolled up on a later pass).
    Idempotent via update_or_create on the unique `date`."""
    from django.db.models import Count
    from django.db.models.functions import TruncDate
    from .models import AccessLog, ActionLog, DailyLogRollup

    yesterday = today - timedelta(days=1)

    last = DailyLogRollup.objects.order_by('-date').values_list('date', flat=True).first()
    if last is None:
        # First ever run: seed from the earliest access-log row (fall back to the
        # earliest action-log row), else nothing to roll up yet.
        earliest = (
            AccessLog.objects.order_by('timestamp').values_list('timestamp', flat=True).first()
            or ActionLog.objects.order_by('timestamp').values_list('timestamp', flat=True).first()
        )
        if earliest is None:
            return
        start = timezone.localtime(earliest).date()
    else:
        start = last + timedelta(days=1)

    day = start
    guard = 0
    while day <= yesterday and guard < 3660:  # ~10y ceiling, defensive
        guard += 1
        naive_start = datetime.combine(day, dtime.min)
        day_start = timezone.make_aware(naive_start) if timezone.is_naive(naive_start) else naive_start
        day_end = day_start + timedelta(days=1)

        acc = AccessLog.objects.filter(timestamp__gte=day_start, timestamp__lt=day_end)
        act = ActionLog.objects.filter(timestamp__gte=day_start, timestamp__lt=day_end)

        requests = acc.count()
        unique_ips = acc.exclude(ip_address__isnull=True).values('ip_address').distinct().count()
        unique_users = acc.filter(user__isnull=False).values('user').distinct().count()
        logins = act.filter(action='login').count()
        signups = act.filter(action='signup').count()
        errors = act.filter(action='error').count()

        DailyLogRollup.objects.update_or_create(
            date=day,
            defaults={
                'requests': requests,
                'unique_ips': unique_ips,
                'unique_users': unique_users,
                'logins': logins,
                'signups': signups,
                'errors': errors,
            },
        )
        day += timedelta(days=1)


def _do_sweep():
    from .models import AccessLog, ActionLog, SiteConfig

    config = SiteConfig.load()
    now = timezone.now()
    today = timezone.localtime(now).date()

    # (a) Roll up completed days first, so pruning can't erase history from graphs.
    try:
        _rollup_days(today)
    except Exception:
        logger.exception("daily log rollup failed")

    # (b) Prune the high-volume access log.
    if config.access_log_retention_days and config.access_log_retention_days > 0:
        cutoff = now - timedelta(days=config.access_log_retention_days)
        deleted, _ = AccessLog.objects.filter(timestamp__lt=cutoff).delete()
        if deleted:
            logger.info("log cleanup: pruned %d access log rows older than %d days",
                        deleted, config.access_log_retention_days)

    # (c) Prune the low-volume event log (0 = keep forever).
    if config.event_log_retention_days and config.event_log_retention_days > 0:
        cutoff = now - timedelta(days=config.event_log_retention_days)
        deleted, _ = ActionLog.objects.filter(timestamp__lt=cutoff).delete()
        if deleted:
            logger.info("log cleanup: pruned %d action log rows older than %d days",
                        deleted, config.event_log_retention_days)


def start_log_cleanup_scheduler():
    global _cleanup_thread
    with _cleanup_lock:
        if _cleanup_thread and _cleanup_thread.is_alive():
            return
        _cleanup_thread = threading.Thread(target=_run_cleanup, daemon=True, name="log-cleanup")
        _cleanup_thread.start()
        logger.info("log cleanup scheduler started")
