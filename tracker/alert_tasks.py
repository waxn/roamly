"""Tracking alerts — "no location data received" email notifications.

An opt-in, per-user notice that fires when *no* device on the account has
reported a fix for a configurable stretch of time (``UserProfile
.alert_no_data_hours``, default 3). It catches the failure mode users actually
hit: a phone that quietly stopped tracking — killed by an OEM battery manager,
denied background location after an OS update, or simply left with the app
force-stopped — which is otherwise invisible until you open the map days later
and find a hole.

Gated on ``EMAIL_ENABLED`` + the user having an address, but deliberately NOT on
``ai_configured`` the way ``summary_email_tasks`` is: this is an operational
notice with nothing to narrate, so requiring a BYO LLM to receive it would be
absurd.

No external scheduler/queue — mirrors ``summary_email_tasks`` / ``stats_tasks``:
a daemon thread started from ``apps.ready()`` sweeps every 15 minutes and sends
to any user who has crossed their threshold. The sweep interval bounds how late
an alert can be, so it is well under the 1-hour minimum threshold rather than
matching the hourly recap cadence.

Restart-safe and spam-safe via ``UserProfile.alert_no_data_last_point``: it
records the newest fix at the moment an alert went out, so a user is skipped
while that is still the newest fix (one email per outage) and re-arms by itself
the moment a newer point lands.
"""

import time
import logging
import threading
from contextlib import contextmanager

from django.conf import settings
from django.db import connection, close_old_connections
from django.utils import timezone

from . import email_utils

logger = logging.getLogger(__name__)

_scheduler_thread = None

# 15 minutes. Deliberately finer than summary_email_tasks' hourly sweep — this
# bounds how stale an alert can be, and the minimum threshold a user can pick is
# one hour, so an hourly sweep could deliver a "1 hour" alert nearly 2 hours late.
SCHEDULER_CHECK_INTERVAL = 900

# Advisory-lock namespace (first int4 key). Distinct from stats_tasks ('RAML'),
# summary_email_tasks ('RAMS'), log_cleanup_tasks ('RAMG') and
# auto_download_tasks ('RAMD') so per-user alert locks can never collide with
# any of those in the same database.
_LOCK_NAMESPACE = 0x52414d41       # 'RAMA'

# Bounds for the user-configurable threshold. One hour is the floor because the
# sweep runs every 15 minutes and a shorter window would alert on ordinary
# tunnels/parking garages; one week is the ceiling — past that the alert has
# stopped being an alert.
MIN_HOURS = 1
MAX_HOURS = 168
DEFAULT_HOURS = 3


def clamp_hours(value, default=DEFAULT_HOURS):
    """Coerce a submitted threshold to a sane integer hour count.

    Applied both at the API boundary and again in the worker — the value rides
    in from the client, and a row written before a future bound change (or by
    hand) must not be able to produce a nonsensical alert.
    """
    try:
        hours = int(value)
    except (TypeError, ValueError):
        return default
    return max(MIN_HOURS, min(MAX_HOURS, hours))


def _human_duration(seconds):
    """A short "3d 4h" / "4h 20m" / "35m" duration."""
    seconds = max(int(seconds), 0)
    days, rem = divmod(seconds, 86400)
    hours, rem = divmod(rem, 3600)
    minutes = rem // 60
    if days:
        return f"{days}d {hours}h" if hours else f"{days}d"
    if hours:
        return f"{hours}h {minutes}m" if minutes else f"{hours}h"
    return f"{minutes}m"


def _threshold_label(hours):
    if hours == 1:
        return "1 hour"
    if hours % 24 == 0 and hours >= 24:
        days = hours // 24
        if days == 7:
            return "1 week"        # matches the Settings dropdown's own label
        return "1 day" if days == 1 else f"{days} days"
    return f"{hours} hours"


def latest_fix(user):
    """``(timestamp, device_name)`` of the newest fix across the user's devices.

    Returns ``(None, '')`` for an account that has never logged a point.

    Queries per device rather than a single ``aggregate(Max(...))`` across the
    whole user: ``tracker_loc_device__idx`` is ``(device, -timestamp)``, so one
    ordered lookup per device is an index seek, whereas a user-wide aggregate
    has no matching index and degrades into a scan on a large history — exactly
    the shape of table this sweep touches every 15 minutes.
    """
    from .models import Device, Location

    best_ts, best_name = None, ''
    for device in Device.objects.filter(user=user):
        row = (Location.objects.filter(device=device)
               .order_by('-timestamp').values('timestamp').first())
        if not row:
            continue
        ts = row['timestamp']
        if best_ts is None or ts > best_ts:
            best_ts, best_name = ts, (device.name or device.device_id or '')
    return best_ts, best_name


@contextmanager
def _user_alert_lock(user_id):
    """Yield ``True`` iff this thread holds the alert lock for ``user_id``.

    PostgreSQL session advisory lock (non-blocking) — with several gunicorn
    workers each running their own sweep thread, this is what stops two of them
    sending the same user the same alert in the same minute. Auto-released if
    the process dies. No-op (always ``True``) on SQLite, where the
    ``alert_no_data_last_point`` cursor is the practical dedupe.
    """
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


def _send_alert(profile, last_ts, device_name, hours, silent_seconds, is_test=False):
    """Build and send one alert email. Returns True if it went out."""
    user = profile.user
    token = email_utils._ensure_unsub_token(profile)
    unsubscribe_url = f"{settings.SITE_URL}/email/unsubscribe/{token}/?period=alerts"
    last_label = (timezone.localtime(last_ts).strftime('%b %-d, %Y at %-I:%M %p')
                  if last_ts else None)
    return email_utils.send_no_data_alert_email(
        user.email,
        silent_for=_human_duration(silent_seconds),
        last_point_at=last_label,
        device_name=device_name,
        threshold_label=_threshold_label(hours),
        map_url=f"{settings.SITE_URL}/map/",
        settings_url=f"{settings.SITE_URL}/settings/",
        unsubscribe_url=unsubscribe_url,
        is_test=is_test,
    )


def _process_user(user_id, force=False, stamp=True):
    """Send a no-data alert to one user if they're due, holding the per-user lock.

    ``force`` (the Settings "Send test" button) skips the enabled flag, the
    threshold check and the per-outage dedupe so the user can preview the mail
    against their real current data; ``stamp=False`` keeps that preview from
    consuming the real alert for an outage that is genuinely underway.
    """
    from .models import UserProfile

    profile = (UserProfile.objects.filter(user_id=user_id)
               .select_related('user').first())
    if not profile:
        return 'skip'
    if not force and not profile.alert_no_data_enabled:
        return 'skip'
    if not (email_utils.email_enabled() and profile.user.email):
        return 'skip'

    with _user_alert_lock(user_id) as got:
        if not got:
            return 'locked'

        hours = clamp_hours(profile.alert_no_data_hours)
        last_ts, device_name = latest_fix(profile.user)
        now = timezone.now()

        if last_ts is None:
            # Never tracked anything. There is no outage to report — alerting
            # here would mean every freshly-created account that ticks the box
            # gets mailed immediately, before it has ever pushed a point.
            if not force:
                return 'no-data'
            silent_seconds = 0
        else:
            silent_seconds = (now - last_ts).total_seconds()
            if not force:
                if silent_seconds < hours * 3600:
                    return 'ok'      # still reporting within the threshold
                if (profile.alert_no_data_last_point is not None
                        and profile.alert_no_data_last_point >= last_ts):
                    return 'already' # already alerted for this same outage

        sent = _send_alert(profile, last_ts, device_name, hours,
                           silent_seconds, is_test=force)
        if sent and stamp:
            profile.alert_no_data_last_point = last_ts
            profile.alert_no_data_sent_at = now
            profile.save(update_fields=['alert_no_data_last_point',
                                        'alert_no_data_sent_at'])
        if sent:
            logger.info("No-data alert sent to user %s (silent %ss)",
                        user_id, int(silent_seconds))
        return 'sent' if sent else 'failed'


def send_alert_now(user_id):
    """Fire an immediate test alert (Settings → 'Send test').

    Runs in a daemon thread, bypasses every due-check, and does NOT advance the
    cursor, so a test never consumes the real alert for an ongoing outage.
    """
    def _run():
        close_old_connections()
        try:
            _process_user(user_id, force=True, stamp=False)
        except Exception:
            logger.exception("Test no-data alert failed for user %s", user_id)
        finally:
            close_old_connections()

    threading.Thread(target=_run, daemon=True).start()
    return 'started'


def _alert_scheduler_loop():
    from .models import UserProfile
    while True:
        try:
            # Long-lived daemon thread: force a fresh DB connection each sweep so
            # a stale/broken connection can't silently freeze the loop (same
            # reasoning as stats_tasks / summary_email_tasks — no request cycle
            # enforces CONN_MAX_AGE here).
            close_old_connections()
            if email_utils.email_enabled():
                user_ids = list(
                    UserProfile.objects.filter(alert_no_data_enabled=True)
                    .values_list('user_id', flat=True)
                )
                for uid in user_ids:
                    try:
                        _process_user(uid)
                    except Exception:
                        logger.exception("No-data alert failed for user %s", uid)
        except Exception:
            logger.exception("Tracking alert scheduler error")
        time.sleep(SCHEDULER_CHECK_INTERVAL)


def start_alert_scheduler():
    """Start the 15-minute no-data alert sweep thread (called once on startup)."""
    global _scheduler_thread
    if _scheduler_thread is not None and _scheduler_thread.is_alive():
        return
    _scheduler_thread = threading.Thread(target=_alert_scheduler_loop, daemon=True)
    _scheduler_thread.start()
    logger.info("Tracking alert scheduler started")
