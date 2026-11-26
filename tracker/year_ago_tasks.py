"""Write the header's "a year ago" lines at night instead of on the first page load.

A daemon thread from ``apps.ready()`` sweeps every ``CHECK_INTERVAL_S``. For each
account with AI set up that looked at the chip in the last ``ACTIVE_DAYS`` days
(a ``YearAgoRecap.viewed_at`` stamp, since the chip's on/off switch lives only in
the browser), once it is ``PREGEN_HOUR`` or later in that user's own zone, it
writes the line for today and for tomorrow. A year-ago day is long over, so
tomorrow's line can be written now, and it is already saved when midnight comes:
the first load of the day reads a row instead of waiting on the provider.

Each line is a ``YearAgoRecap`` row, so a day that is already saved costs one
query. A per-account Postgres advisory lock (namespace ``'RAMY'``) keeps the three
gunicorn workers from writing the same line twice, and a provider failure is
retried on later sweeps up to ``MAX_ATTEMPTS`` times a day, then left to the
on-demand path.
"""
import logging
import threading
import time
from contextlib import contextmanager
from datetime import timedelta

from django.core.cache import cache
from django.db import close_old_connections, connection
from django.utils import timezone

logger = logging.getLogger(__name__)

PREGEN_HOUR = 2            # local hour the night's lines are written
PREGEN_UNTIL_HOUR = 6      # a server that was down at 2am still catches up before morning
CHECK_INTERVAL_S = 15 * 60
ACTIVE_DAYS = 14
MAX_ATTEMPTS = 3
_LOCK_NAMESPACE = 0x52414d59   # 'RAMY'

_scheduler_thread = None


@contextmanager
def _user_lock(user_id):
    if connection.vendor != 'postgresql':
        yield True
        return
    got = False
    try:
        with connection.cursor() as cur:
            cur.execute("SELECT pg_try_advisory_lock(%s, %s)", [_LOCK_NAMESPACE, user_id])
            got = bool(cur.fetchone()[0])
        yield got
    finally:
        if got:
            with connection.cursor() as cur:
                cur.execute("SELECT pg_advisory_unlock(%s, %s)", [_LOCK_NAMESPACE, user_id])


def _attempts_key(user_id, today):
    return f'year_ago:pregen_tries:{user_id}:{today.isoformat()}'


def pregenerate_for(profile, force=False):
    """Write today's and tomorrow's lines for one account, in its own zone.

    Returns how many lines were written. Outside the night window this does
    nothing unless ``force`` (tests). Raises nothing: a failure is logged and
    counted, and the chip falls back to writing the line on demand.
    """
    from . import ai_tasks, year_ago
    from .tz_utils import user_timezone

    written = 0
    with user_timezone(profile.user_id):
        local = timezone.localtime()
        if not force and not (PREGEN_HOUR <= local.hour < PREGEN_UNTIL_HOUR):
            return 0
        today = local.date()
        key = _attempts_key(profile.user_id, today)
        try:
            tries = cache.get(key) or 0
        except Exception:
            tries = 0
        if tries >= MAX_ATTEMPTS:
            return 0
        for when in (today, today + timedelta(days=1)):
            day = year_ago.year_ago(when)
            if year_ago.saved_line(profile, day) is not None or year_ago.is_known_empty(profile, day):
                continue
            try:
                result = year_ago.build_year_ago(profile, today=when)
            except (ai_tasks.AIProviderError, year_ago.YearAgoError) as e:
                logger.warning("Year-ago line for user %s (%s) failed: %s", profile.user_id, day, e)
                try:
                    cache.set(key, tries + 1, 24 * 3600)
                except Exception:
                    pass
                break   # the provider is down or misconfigured; don't try tomorrow's too
            if result.get('status') == 'ok':
                written += 1
    return written


def _sweep():
    from .models import UserProfile, YearAgoRecap
    cutoff = timezone.now() - timedelta(days=ACTIVE_DAYS)
    active = set(YearAgoRecap.objects.filter(viewed_at__gte=cutoff)
                 .values_list('user_id', flat=True).distinct())
    if not active:
        return
    profiles = UserProfile.objects.filter(user_id__in=active, ai_ask_enabled=True).select_related('user')
    for profile in profiles:
        if not profile.ai_configured:
            continue
        try:
            with _user_lock(profile.user_id) as got:
                if got:
                    pregenerate_for(profile)
        except Exception:
            logger.exception("Year-ago pre-generation failed for user %s", profile.user_id)


def _scheduler_loop():
    # Started from apps.ready(), i.e. during apps.populate(); see the helper.
    from .scheduler_utils import wait_for_app_registry
    wait_for_app_registry()
    while True:
        try:
            close_old_connections()
            _sweep()
        except Exception:
            logger.exception("Year-ago scheduler error")
        time.sleep(CHECK_INTERVAL_S)


def start_year_ago_scheduler():
    """Start the nightly year-ago sweep thread (called once on app startup)."""
    global _scheduler_thread
    if _scheduler_thread is not None and _scheduler_thread.is_alive():
        return
    _scheduler_thread = threading.Thread(target=_scheduler_loop, daemon=True)
    _scheduler_thread.start()
    logger.info("Year-ago scheduler started")
