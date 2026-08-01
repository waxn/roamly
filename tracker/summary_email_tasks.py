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

KM_TO_MI = 0.621371


def _format_distance(km, unit):
    """(value, unit_label) for a km figure in the profile's preferred unit."""
    if unit == 'mph':
        return km * KM_TO_MI, 'mi'
    return km, 'km'


def _human_duration(seconds):
    seconds = max(int(seconds), 0)
    hours, rem = divmod(seconds, 3600)
    minutes = rem // 60
    if hours and minutes:
        return f"{hours}h {minutes}m"
    if hours:
        return f"{hours}h"
    return f"{minutes}m"


def _top_cities(qs, limit=5):
    """Top ``limit`` cities in ``qs`` by time spent, as ``(label, duration)`` rows."""
    from .views import _compute_visits_from_qs
    data = _compute_visits_from_qs(qs.exclude(city=''))
    ranked = sorted(data['cities'], key=lambda c: c['time_spent'], reverse=True)[:limit]
    rows = []
    for c in ranked:
        label = f"{c['city']}, {c['state']}" if c['state'] else c['city']
        rows.append((label, _human_duration(c['time_spent'])))
    return rows


def _period_visits(user, win_start, win_end):
    """This period's Visit rows (all the user's devices), chronological.

    Visit is a small, precomputed, per-device-indexed table — cheap to
    range-scan directly rather than re-deriving stays from raw Location.
    """
    from .models import Visit
    return list(
        Visit.objects.filter(
            device__user=user, start_time__lt=win_end, end_time__gt=win_start,
        ).select_related('poi').order_by('start_time')
    )


def _top_places(visits, limit=5):
    """Top ``limit`` distinct stay locations by total dwell time, as
    ``(label, duration)`` rows.

    Groups by POI when a visit matched one, else by lat/lon rounded to 3
    decimals (~111m grid — the same precision the fog-of-war cells use), so
    repeat stays at one spot collapse into a single ranked entry.
    """
    groups = {}
    for v in visits:
        if v.poi_id:
            key = ('poi', v.poi_id)
            label = v.poi.name
        else:
            key = ('geo', round(v.latitude, 3), round(v.longitude, 3))
            label = v.place_name or v.city or 'Unknown location'
        seconds = (v.end_time - v.start_time).total_seconds()
        g = groups.setdefault(key, {'label': label, 'seconds': 0.0})
        g['seconds'] += seconds
    ranked = sorted(groups.values(), key=lambda g: g['seconds'], reverse=True)[:limit]
    return [(g['label'], _human_duration(g['seconds'])) for g in ranked]


def _stay_timeline(visits):
    """Ordered ``"HH:MM–HH:MM place"`` lines for the daily narrative prompt —
    the chronological story an AI can actually narrate ("you went to X, then
    spent the afternoon near Y"), rather than flat aggregate stats."""
    lines = []
    for v in visits:
        start_local = timezone.localtime(v.start_time)
        end_local = timezone.localtime(v.end_time)
        where = f"{v.city}, {v.state}" if v.state else v.city
        place = v.poi.name if v.poi_id else v.place_name
        if place and place != where:
            label = f"{where} ({place})" if where else place
        else:
            label = where or place or 'Unknown location'
        lines.append(f"{start_local:%H:%M}–{end_local:%H:%M} {label}")
    return lines

# Dedicated summarisation prompts — intentionally NOT the user's Ask system
# prompt (that one is tuned for interactive Q&A over tools, not for writing a
# recap). Two variants: daily gets the actual chronological stay timeline and
# is told to narrate it directly ("you went to X, then Y"); weekly/monthly/
# yearly would make an unreadable blow-by-blow out of a timeline that long, so
# they're grounded in the top-cities/top-places breakdown instead and write
# thematically. Both demand something short and concrete over the previous
# generic multi-paragraph recap.
SUMMARY_SYSTEM_PROMPT_DAILY = (
    "You are a concise travel journal writer for Roamly, a personal "
    "location-history app. You are given the user's chronological stay "
    "timeline for one day (place + time range, in order) plus headline "
    "stats. Write a short recap addressed to them in the second person "
    "(\"you\") that narrates the actual sequence of the day — e.g. \"you "
    "went to X in the morning, then spent the afternoon near Y\". ONE short "
    "paragraph, 2-3 sentences. No headings, bullet points, or markdown. "
    "Reference only the places and times you were given — never invent a "
    "place name, time, or fact not present in the data. If the day was "
    "quiet or the timeline is empty, say so briefly and gently rather than "
    "padding it out."
)
SUMMARY_SYSTEM_PROMPT_PERIOD = (
    "You are a concise travel journal writer for Roamly, a personal "
    "location-history app. You are given headline stats for a completed "
    "week/month/year plus a ranked breakdown of the cities and places the "
    "user spent the most time in. Write a short recap addressed to them in "
    "the second person (\"you\") that names the actual top places — e.g. "
    "\"you mostly stayed around X, with a trip to Y\". ONE short paragraph, "
    "2-3 sentences. No headings, bullet points, or markdown, and no vague "
    "summary that could apply to anyone — ground every claim in the given "
    "data. Never invent a place name or fact not present in the data. If "
    "activity was low, keep it light and gentle rather than exaggerating."
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

def _narrative(profile, period, label, summary, timeline=None):
    """Ask the user's LLM for a recap; returns a list of paragraph strings.

    ``daily`` gets the chronological ``timeline`` (a list of "HH:MM–HH:MM
    place" lines) and is asked to narrate the day's actual sequence;
    weekly/monthly/yearly are grounded in ``summary``'s top_cities/top_places
    breakdown instead — a full timeline over a month would be unreadable.
    Best-effort — any provider failure falls back to a single generic line so
    the stats email still goes out.
    """
    from . import ai_tasks
    fallback = [f"Here's a look back at your {label} on Roamly."]
    if period == 'daily' and timeline:
        system_prompt = SUMMARY_SYSTEM_PROMPT_DAILY
        user_content = (
            f"My chronological stay timeline for {summary['from']} (24h local time):\n"
            + "\n".join(timeline)
            + f"\n\nHeadline stats: {json.dumps(summary)}\n\nWrite my recap."
        )
    else:
        system_prompt = SUMMARY_SYSTEM_PROMPT_PERIOD
        user_content = (
            f"My Roamly location stats for the past {label}, including a ranked "
            f"breakdown of where I spent the most time:\n\n{json.dumps(summary)}"
            f"\n\nWrite my recap."
        )
    try:
        data = ai_tasks._provider_chat(profile, [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_content},
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
    dist_value, dist_unit = _format_distance(dist['total_km'], profile.distance_unit)

    visits = _period_visits(user, win_start, win_end)
    top_cities = _top_cities(qs)
    top_places = _top_places(visits)

    summary = {
        "period": label,
        "from": win_start.strftime('%Y-%m-%d'),
        "to": (win_end - timedelta(days=1)).strftime('%Y-%m-%d'),
        "distance": round(dist_value, 1),
        "distance_unit": dist_unit,
        "points_logged": overview['total_points'],
        "cities": overview['cities'],
        "states": overview['states'],
        "countries": overview['countries'],
        "active_days": active_days,
        "top_cities": [{"name": name, "time": detail} for name, detail in top_cities],
        "top_places": [{"name": name, "time": detail} for name, detail in top_places],
    }

    stat_rows = [
        ("Distance", f"{dist_value:,.0f} {dist_unit}"),
        ("Points logged", f"{overview['total_points']:,}"),
        ("Cities", str(overview['cities'])),
        ("Countries", str(overview['countries'])),
    ]
    if overview['states']:
        stat_rows.append(("States / regions", str(overview['states'])))

    timeline = _stay_timeline(visits) if period == 'daily' else None
    narrative = _narrative(profile, period, label, summary, timeline=timeline)

    token = email_utils._ensure_unsub_token(profile)
    unsubscribe_url = f"{settings.SITE_URL}/email/unsubscribe/{token}/?period={period}"
    date_qs = (f"start={win_start.strftime('%Y-%m-%d')}"
               f"&end={(win_end - timedelta(days=1)).strftime('%Y-%m-%d')}")
    map_url = f"{settings.SITE_URL}/map/?{date_qs}"
    stats_url = f"{settings.SITE_URL}/stats/?{date_qs}"

    email_utils.send_summary_email(
        user.email,
        period_label=label,
        heading=f"Your {label} on Roamly",
        narrative_paras=narrative,
        stat_rows=stat_rows,
        top_cities=top_cities,
        top_places=top_places,
        map_url=map_url,
        stats_url=stats_url,
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
