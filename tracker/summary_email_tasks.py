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
from .scheduler_utils import db_thread
from collections import defaultdict
from contextlib import contextmanager
from datetime import datetime, timedelta

from django.conf import settings
from django.db import connection, close_old_connections
from django.db.models import Q
from django.utils import timezone

from . import email_utils
from .tz_utils import aware_local, user_timezone

logger = logging.getLogger(__name__)

_scheduler_thread = None
SCHEDULER_CHECK_INTERVAL = 3600   # 1 hour between sweeps

# Advisory-lock namespace (first int4 key). Deliberately DIFFERENT from
# stats_tasks._LOCK_NAMESPACE ('RAML') so summary-email locks can never collide
# with per-user snapshot compute locks in the same database.
_LOCK_NAMESPACE = 0x52414d53       # 'RAMS'

PERIODS = ('daily', 'weekly', 'monthly', 'yearly')

# How far apart two period boundaries can be and still be the SAME period. The
# boundary is a local midnight, so it moves whenever the user's zone does (the
# zone follows their newest fix): a flight from Virginia to Chicago moves "today
# began" from 04:00Z to 05:00Z. Consecutive periods are always >= 23h apart
# (a DST day), so anything closer than this is one period seen from two zones.
_SAME_PERIOD_TOLERANCE = timedelta(hours=12)

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


def _top_cities(user, win_start, win_end, limit=5):
    """Top ``limit`` cities in [win_start, win_end) by time spent, as
    ``(label, duration)`` rows.

    Deliberately does not just call ``views._compute_visits_from_qs`` on a
    queryset scoped to ``[win_start, win_end)`` the way this used to — that
    query can't see a gap that straddles the window edge. Tracking that stops
    the evening before and resumes at school the next morning has its "prev"
    fix outside today's window, so a strictly-windowed gap walk never has
    both ends of that gap in hand and silently drops it, even though the same
    gap is credited correctly once the *full* history is walked (which is
    what the all-time Visits page does, and why its cumulative total for a
    place can be so much larger than any single day's email ever showed).
    Fetching one extra point on each side of the window restores that
    continuity; each gap's credited seconds are then clipped to their overlap
    with the window so a boundary-straddling stay only contributes the
    portion that actually falls on this email's day.
    """
    from .views import _visits_dwell_gap
    from .models import Location

    fields = ('timestamp', 'city', 'state', 'country', 'country_code', 'latitude', 'longitude')
    base = Location.objects.filter(device__user=user).exclude(city='')

    points = list(
        base.filter(timestamp__gte=win_start, timestamp__lt=win_end)
        .order_by('timestamp').values_list(*fields)
    )
    before = (base.filter(timestamp__lt=win_start)
              .order_by('-timestamp').values_list(*fields).first())
    after = (base.filter(timestamp__gte=win_end)
             .order_by('timestamp').values_list(*fields).first())
    if before:
        points.insert(0, before)
    if after:
        points.append(after)

    time_city = defaultdict(float)
    prev = None
    for cur in points:
        if prev:
            gap = _visits_dwell_gap(prev, cur, 'city')
            if gap > 0:
                overlap_start = max(prev[0], win_start)
                overlap_end = min(prev[0] + timedelta(seconds=gap), win_end)
                overlap = (overlap_end - overlap_start).total_seconds()
                if overlap > 0:
                    time_city[(prev[1], prev[2], prev[3], prev[4])] += overlap
        prev = cur

    ranked = sorted(time_city.items(), key=lambda kv: kv[1], reverse=True)[:limit]
    rows = []
    for (city, state, _country, _cc), seconds in ranked:
        label = f"{city}, {state}" if state else city
        rows.append((label, _human_duration(seconds)))
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


_MIN_STAY_SECONDS = 600       # an unmatched stay shorter than this is a pass-through
_MIN_POI_STAY_SECONDS = 240   # a matched business counts as a real stop sooner


def _part_of_day(dt):
    h = dt.hour
    if h < 5:
        return 'late night'
    if h < 12:
        return 'morning'
    if h < 17:
        return 'afternoon'
    if h < 21:
        return 'evening'
    return 'night'


def _stay_label(v):
    """Most specific name first: the business or place, then the town."""
    town = f"{v.city}, {v.state}" if v.state else v.city
    place = v.poi.name if v.poi_id else v.place_name
    if place and place != town:
        return f"{place} in {town}" if town else place
    return town or place or 'Unknown location'


_TRAVEL_DAY_KM = 100           # first-to-last stay farther apart than this = a travel day
_TRAVEL_MIN_STAY_SECONDS = 1800   # on a travel day only real stops (30 min+) are worth naming


def _km_between(a, b):
    from math import asin, cos, radians, sin, sqrt
    la1, lo1, la2, lo2 = map(radians, (a.latitude, a.longitude, b.latitude, b.longitude))
    h = sin((la2 - la1) / 2) ** 2 + cos(la1) * cos(la2) * sin((lo2 - lo1) / 2) ** 2
    return 6371 * 2 * asin(sqrt(h))


def _journey(visits):
    """Describe a travel day (waking up far from where the day ends), else None.

    The stay list alone reads as a pile of errands — fuel, fast food, a scale —
    so the model is told outright that this was a journey, where it started and
    ended, and which states it crossed, and to treat roadside stops as colour.
    """
    real = [v for v in visits if (v.end_time - v.start_time).total_seconds() >= _MIN_STAY_SECONDS]
    if len(real) < 2:
        return None
    first, last = real[0], real[-1]
    km = _km_between(first, last)
    if km < _TRAVEL_DAY_KM:
        return None
    states = []
    for v in real:
        if v.state and v.state not in states:
            states.append(v.state)
    return {
        'travel_day': True,
        'started_in': _stay_label(first),
        'ended_in': _stay_label(last),
        'start_to_end_km': round(km),
        'states_passed_through': states,
    }


def _stay_timeline(visits):
    """Ordered ``"HH:MM–HH:MM (part of day, duration) place"`` lines for the
    narrative prompts — the chronological story an AI can narrate.

    Pass-through stops (a few minutes in a town the user only drove through)
    are dropped, since mentioning them is what made recaps read like "a quick
    stop in Lincoln"; consecutive stays at the same place are merged. On a
    travel day (see ``_journey``) only 30-minute-plus stays and the day's first
    and last survive: the fuel, scale and fast-food stops aren't the story.
    """
    visits = list(visits)
    travel = _journey(visits) is not None
    keep = []
    for i, v in enumerate(visits):
        seconds = (v.end_time - v.start_time).total_seconds()
        floor = _MIN_POI_STAY_SECONDS if (v.poi_id or v.place_name) else _MIN_STAY_SECONDS
        if travel:
            floor = _TRAVEL_MIN_STAY_SECONDS
        if seconds >= floor or (travel and seconds >= _MIN_STAY_SECONDS and i in (0, len(visits) - 1)):
            keep.append(v)
    merged = []
    for v in keep:
        label = _stay_label(v)
        if merged and merged[-1]['label'] == label:
            merged[-1]['end'] = v.end_time
            continue
        merged.append({'label': label, 'start': v.start_time, 'end': v.end_time})
    lines = []
    for m in merged:
        start_local = timezone.localtime(m['start'])
        end_local = timezone.localtime(m['end'])
        dur = _human_duration((m['end'] - m['start']).total_seconds())
        lines.append(f"{start_local:%H:%M}–{end_local:%H:%M} ({_part_of_day(start_local)}, {dur}) {m['label']}")
    return lines


# Dedicated summarisation prompts — intentionally NOT the user's Ask system
# prompt (that one is tuned for interactive Q&A over tools, not for writing a
# recap). Two variants: daily gets the actual chronological stay timeline and
# is told to narrate it directly ("you went to X, then Y"); weekly/monthly/
# yearly would make an unreadable blow-by-blow out of a timeline that long, so
# they're grounded in the top-cities/top-places breakdown instead and write
# thematically. Both demand something short and concrete over the previous
# generic multi-paragraph recap.
_JOURNEY_RULES = (
    " Understand the purpose of the day: where the data has a `journey` with "
    "travel_day, it was a trip, so tell it as one (where it started, where it "
    "ended, the states crossed, where you slept) and treat gas, food, scales "
    "and chain-store stops as passing colour, one or two at most, never the "
    "point of the day. Do not guess why someone visited a place (a truck "
    "scale on a road trip is not why you were in a state) and never list "
    "every stop."
)
_STYLE_RULES = (
    " Write like a warm friend recounting the day, not a report: flowing, "
    "natural prose in the second person (\"you\"), with parts of the day "
    "(\"in the morning\", \"after lunch\", \"that evening\"). Name the specific "
    "places and businesses you were given (a store or restaurant beats the "
    "town it is in) and say where they are. Lead with where most of the time "
    "was spent. Never mention towns you merely passed through or travelled "
    "between, never say \"a quick stop\", and never list coordinates, raw "
    "times or stats mechanically." + _JOURNEY_RULES + " Only use places, times and figures that "
    "were given; never invent any. Plain text only: no headings, bullet "
    "points or markdown."
)
SUMMARY_SYSTEM_PROMPT_DAILY = (
    "You are the voice of Roamly, a personal location-history app, writing "
    "the user's daily recap. You are given their chronological stays for one "
    "day (time, part of day, duration, place) plus headline stats. Write one "
    "paragraph of 3-5 sentences that tells the day's story in order, e.g. "
    "\"You spent the morning in Waldo, then headed to Hannaford in Bangor "
    "before ending up back home that evening.\" Mention distance travelled "
    "only if it adds something. If the day was quiet or empty, say so "
    "gently in a sentence." + _STYLE_RULES
)
SUMMARY_SYSTEM_PROMPT_PERIOD = (
    "You are the voice of Roamly, a personal location-history app, writing "
    "the user's weekly, monthly or yearly recap. You are given headline "
    "stats plus a ranked breakdown of the cities and places they spent the "
    "most time in. Write one paragraph of 3-5 sentences that captures what "
    "the period was like: where home base was, the places that kept coming "
    "back, any standout trip, and one fitting highlight from the stats. "
    "Ground every claim in the data and never write something that could "
    "apply to anyone. If activity was low, keep it light and gentle."
    + _STYLE_RULES
)


# ── Period windows ──────────────────────────────────────────────────────────

def _local_midnight(d):
    """Aware datetime at local midnight for date ``d``.

    ``aware_local`` rather than ``make_aware``: _process_user activates the
    user's own zone around this, and make_aware raises on a midnight that DST
    made imaginary or ambiguous — which would cost that user their recap on the
    one day a year their zone shifts at 00:00.
    """
    naive = datetime(d.year, d.month, d.day)
    if timezone.is_naive(naive):
        return aware_local(naive)
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

def _narrative(profile, period, label, summary, timeline=None, journey=None):
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
            + (f"\n\nJourney: {json.dumps(journey)}" if journey else "")
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
    top_cities = _top_cities(user, win_start, win_end)
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
    narrative = _narrative(profile, period, label, summary, timeline=timeline,
                           journey=_journey(visits) if period == 'daily' else None)

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
    with _user_email_lock(user_id) as got:
        if not got:
            return
        # Loaded INSIDE the lock: a copy read before it could predate the cursor
        # another worker stamped while holding it, and resend that period.
        profile = UserProfile.objects.filter(user_id=user_id).select_related('user').first()
        if not profile:
            return
        # A "daily" recap has to cover the user's own day, not the server's, and
        # this sweep runs in a daemon thread with no request to inherit a zone
        # from — so activate the user's own before anything reads a boundary.
        # Everything downstream already goes through timezone.localtime /
        # _local_midnight (which is make_aware), so the zone is all that changes.
        # It also has to be released per user: the loop below moves on to the
        # next account in the same thread.
        with user_timezone(user_id):
            now = timezone.localtime()
            periods = (only_period,) if only_period else PERIODS
            for period in periods:
                # A forced send (the "Send test" button) previews a cadence even if the
                # user hasn't toggled it on yet; the scheduler path requires the flag.
                if not force and not getattr(profile, f'summary_{period}'):
                    continue
                cur_start, _ws, _we, _lbl = _period_bounds(period, now)
                if not force:
                    last = getattr(profile, f'summary_last_{period}')
                    # The cursor holds the boundary of the period last delivered
                    # (older rows hold the send time, which is just after it), so
                    # "delivered" means "close to this period's boundary", not
                    # "at or after it": comparing instants exactly resent
                    # yesterday's recap after flying a zone west, because today's
                    # boundary moved an hour later than the stamp.
                    if last is not None and last > cur_start - _SAME_PERIOD_TOLERANCE:
                        continue   # already delivered for the current period
                result = _send_period(profile, period, allow_empty=allow_empty)
                if stamp and result in ('sent', 'empty'):
                    setattr(profile, f'summary_last_{period}', cur_start)
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

    db_thread(_run).start()
    return 'started'


def _summary_scheduler_loop():
    from .models import UserProfile
    # Started from apps.ready(), i.e. during apps.populate() — so the first
    # sweep below would otherwise race the tail of startup. See the helper.
    from .scheduler_utils import wait_for_app_registry
    wait_for_app_registry()

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
