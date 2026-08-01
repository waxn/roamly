"""AI-generated summary emails (daily / weekly / monthly / yearly).

When an instance has SMTP configured (``EMAIL_ENABLED``) *and* a user has set up
their BYO LLM ("AI Ask", ``UserProfile.ai_configured``), the user can opt into
recap emails for one or more cadences. Each email carries a short AI-written
narrative plus designed headline stats for the completed period.

No external scheduler/queue — mirrors ``stats_tasks`` / ``backup_tasks``: a daemon
thread started from ``apps.ready()`` sweeps hourly and sends any cadence that has
rolled over since it was last delivered. Delivery is tracked per cadence on
``UserProfile.summary_last_*`` so the sweep is restart-safe (no missed-cron, no
duplicate sends) exactly like the StatsSnapshot ``computed_at`` cursor.
"""

import re
import json
import time
import logging
import threading
from contextlib import contextmanager
from datetime import datetime, timedelta

from django.conf import settings
from django.db import connection, close_old_connections
from django.db.models import Q
from django.utils import timezone

from . import email_utils

logger = logging.getLogger(__name__)

_scheduler_thread = None
SCHEDULER_CHECK_INTERVAL = 3600   # 1 hour between sweeps

# Advisory-lock namespace (first int4 key). Deliberately DIFFERENT from
# stats_tasks._LOCK_NAMESPACE ('RAML') so summary-email locks can never collide
# with per-user snapshot compute locks in the same database.
_LOCK_NAMESPACE = 0x52414d53       # 'RAMS'

PERIODS = ('daily', 'weekly', 'monthly', 'yearly')

# Dedicated summarisation prompt — intentionally NOT the user's Ask system prompt
# (that one is tuned for interactive Q&A over tools, not for writing a recap).
SUMMARY_SYSTEM_PROMPT = (
    "You are a warm, concise travel journal writer for Roamly, a personal "
    "location-history app. Given a set of statistics about where the user went "
    "during a period, write a short, friendly recap addressed to them in the "
    "second person (\"you\"). Keep it to 2-3 short paragraphs. Do NOT use "
    "headings, bullet points, or any markdown formatting. Refer to the specific "
    "numbers you are given (distance, places, active days). Make it feel personal "
    "and encouraging. If the activity was low or zero, keep it light and gentle "
    "rather than exaggerating. Never invent place names, cities, or facts that "
    "are not present in the data."
)


# ── Period windows ──────────────────────────────────────────────────────────

def _local_midnight(d):
    """Aware datetime at local midnight for date ``d``."""
    naive = datetime(d.year, d.month, d.day)
    if timezone.is_naive(naive):
        return timezone.make_aware(naive)
    return naive


def _period_bounds(period, now):
    """Return ``(cursor_boundary, win_start, win_end, label)`` for ``period``.

    ``cursor_boundary`` is the start of the *current* (in-progress) period — a
    cadence is "due" once its ``summary_last_*`` cursor predates this. ``win_start``
    / ``win_end`` bracket the *previous, completed* period that the email covers
    (``win_end`` exclusive). ``label`` is the human word for the subject line.
    """
    today = now.date()
    if period == 'daily':
        cur_start = _local_midnight(today)
        win_start = _local_midnight(today - timedelta(days=1))
        label = 'day'
    elif period == 'weekly':
        monday = today - timedelta(days=today.weekday())      # Monday of this week
        cur_start = _local_midnight(monday)
        win_start = _local_midnight(monday - timedelta(days=7))
        label = 'week'
    elif period == 'monthly':
        first = today.replace(day=1)
        cur_start = _local_midnight(first)
        prev_first = (first - timedelta(days=1)).replace(day=1)
        win_start = _local_midnight(prev_first)
        label = 'month'
    elif period == 'yearly':
        jan1 = today.replace(month=1, day=1)
        cur_start = _local_midnight(jan1)
        win_start = _local_midnight(jan1.replace(year=jan1.year - 1))
        label = 'year'
    else:
        raise ValueError(f"unknown period {period!r}")
    return cur_start, win_start, cur_start, label


# ── Advisory lock (single-flight per user across workers) ───────────────────

@contextmanager
def _user_email_lock(user_id):
    """Yield ``True`` iff this thread holds the summary-email lock for ``user_id``.

    PostgreSQL session advisory lock (non-blocking) — guarantees at most one
    worker sends a given user's summaries at a time, auto-released if the process
    dies. No-op (always ``True``) on SQLite, where the ``summary_last_*`` cursor
    check is the practical dedupe.
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


# ── One period → one email ──────────────────────────────────────────────────

def _narrative(profile, label, summary):
    """Ask the user's LLM for a recap; returns a list of paragraph strings.

    Best-effort — any provider failure falls back to a single generic line so the
    stats email still goes out.
    """
    from . import ai_tasks
    fallback = [f"Here's a look back at your {label} on Roamly."]
    try:
        data = ai_tasks._provider_chat(profile, [
            {"role": "system", "content": SUMMARY_SYSTEM_PROMPT},
            {"role": "user", "content":
                f"Here are my Roamly location stats for the past {label}. "
                f"Write my recap.\n\n{json.dumps(summary)}"},
        ])
    except ai_tasks.AIProviderError as exc:
        logger.warning("Summary narrative failed for user %s: %s", profile.user_id, exc)
        return fallback
    except Exception:
        logger.exception("Summary narrative crashed for user %s", profile.user_id)
        return fallback
    text = (data.get("choices", [{}])[0].get("message", {}) or {}).get("content") or ""
    paras = [p.strip() for p in re.split(r'\n\s*\n', text) if p.strip()]
    return paras or fallback


def _send_period(profile, period, allow_empty=False):
    """Build and send one period's recap. Returns 'sent' | 'empty' | 'skip'."""
    from .models import Location
    from .views import _compute_overview_from_qs, _compute_distance_from_qs

    user = profile.user
    if not (email_utils.email_enabled() and profile.ai_configured and user.email):
        return 'skip'

    now = timezone.localtime()
    _cur, win_start, win_end, label = _period_bounds(period, now)
    qs = Location.objects.filter(device__user=user,
                                 timestamp__gte=win_start, timestamp__lt=win_end)

    overview = _compute_overview_from_qs(qs, user)
    if not overview['total_points'] and not allow_empty:
        return 'empty'   # nothing tracked — don't send, just advance the cursor

    dist = _compute_distance_from_qs(qs, 'daily')
    active_days = sum(1 for d in dist['distances'] if d > 0)

    summary = {
        "period": label,
        "from": win_start.strftime('%Y-%m-%d'),
        "to": (win_end - timedelta(days=1)).strftime('%Y-%m-%d'),
        "distance_km": dist['total_km'],
        "points_logged": overview['total_points'],
        "cities": overview['cities'],
        "states": overview['states'],
        "countries": overview['countries'],
        "active_days": active_days,
    }

    stat_rows = [
        ("Distance", f"{dist['total_km']:,.0f} km"),
        ("Points logged", f"{overview['total_points']:,}"),
        ("Cities", str(overview['cities'])),
        ("Countries", str(overview['countries'])),
    ]
    if overview['states']:
        stat_rows.append(("States / regions", str(overview['states'])))

    narrative = _narrative(profile, label, summary)

    token = email_utils._ensure_unsub_token(profile)
    unsubscribe_url = f"{settings.SITE_URL}/email/unsubscribe/{token}/?period={period}"

    email_utils.send_summary_email(
        user.email,
        period_label=label,
        heading=f"Your {label} on Roamly",
        narrative_paras=narrative,
        stat_rows=stat_rows,
        unsubscribe_url=unsubscribe_url,
    )
    logger.info("Summary (%s) email sent to user %s", period, user.id)
    return 'sent'


# ── Per-user processing + scheduler ─────────────────────────────────────────

def _process_user(user_id, only_period=None, force=False, stamp=True, allow_empty=False):
    """Send any due cadences for one user, holding the per-user lock."""
    from .models import UserProfile
    profile = UserProfile.objects.filter(user_id=user_id).select_related('user').first()
    if not profile:
        return
    with _user_email_lock(user_id) as got:
        if not got:
            return
        now = timezone.localtime()
        periods = (only_period,) if only_period else PERIODS
        for period in periods:
            # A forced send (the "Send test" button) previews a cadence even if the
            # user hasn't toggled it on yet; the scheduler path requires the flag.
            if not force and not getattr(profile, f'summary_{period}'):
                continue
            if not force:
                cur_start, _ws, _we, _lbl = _period_bounds(period, now)
                last = getattr(profile, f'summary_last_{period}')
                if last is not None and last >= cur_start:
                    continue   # already delivered for the current period
            result = _send_period(profile, period, allow_empty=allow_empty)
            if stamp and result in ('sent', 'empty'):
                setattr(profile, f'summary_last_{period}', timezone.now())
                profile.save(update_fields=[f'summary_last_{period}'])


def send_summary_now(user_id, period='daily'):
    """Fire an immediate test send for one cadence (Settings → 'Send test').

    Runs in a daemon thread, bypasses the due-check, and does NOT advance the
    cursor, so a test never consumes the real scheduled send.
    """
    if period not in PERIODS:
        period = 'daily'

    def _run():
        close_old_connections()
        try:
            _process_user(user_id, only_period=period, force=True, stamp=False, allow_empty=True)
        except Exception:
            logger.exception("Summary test send failed for user %s", user_id)
        finally:
            close_old_connections()

    threading.Thread(target=_run, daemon=True).start()
    return 'started'


def _summary_scheduler_loop():
    from .models import UserProfile
    while True:
        try:
            # Long-lived daemon thread: force a fresh DB connection each sweep so a
            # stale/broken connection can't silently freeze the loop (same reasoning
            # as stats_tasks — no request cycle enforces CONN_MAX_AGE here).
            close_old_connections()
            if email_utils.email_enabled():
                user_ids = list(
                    UserProfile.objects.filter(
                        Q(summary_daily=True) | Q(summary_weekly=True)
                        | Q(summary_monthly=True) | Q(summary_yearly=True)
                    ).values_list('user_id', flat=True)
                )
                for uid in user_ids:
                    _process_user(uid)
        except Exception:
            logger.exception("Summary email scheduler error")
        time.sleep(SCHEDULER_CHECK_INTERVAL)


def start_summary_email_scheduler():
    """Start the hourly summary-email sweep thread (called once on app startup)."""
    global _scheduler_thread
    if _scheduler_thread is not None and _scheduler_thread.is_alive():
        return
    _scheduler_thread = threading.Thread(target=_summary_scheduler_loop, daemon=True)
    _scheduler_thread.start()
    logger.info("Summary email scheduler started")
