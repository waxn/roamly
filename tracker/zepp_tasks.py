"""Zepp (Amazfit) cloud sync — pull steps / distance / calories into HealthSample.

Why this exists at all: Zepp's *official* REST API is corporate-only (their own
docs say data cooperation "only supports corporate users, not individual users"),
and Zepp's Health Connect integration is too patchy to rely on. So this talks to
the same endpoint the Zepp app itself uses, authenticated with an ``apptoken``
the user lifts out of the app.

That makes it **unofficial and breakable**. Two consequences are designed for
rather than hoped away: every failure is recorded in
``UserProfile.zepp_last_error`` and surfaced in Settings, so a broken sync looks
broken rather than looking like an empty account; and nothing here can corrupt
existing data, since every row is written through the same idempotent
``external_id`` dedupe the Health Connect path uses.

Response shape (``GET /v1/data/band_data.json``)::

    {"data": [{"date_time": "2026-08-27",
               "summary": "<base64 JSON>",
               "data_hr": "<base64 blob>"}, ...]}

The decoded ``summary`` carries ``stp`` (steps) with a daily total *and* a
``stage`` array of activity segments with start/stop minute offsets. Those
segments are what let Zepp data populate the intraday day view instead of
landing as one flat daily total.
"""

import base64
import json
import logging
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone as dt_timezone

from django.db import close_old_connections
from django.utils import timezone

logger = logging.getLogger(__name__)

# Zepp shards accounts by region; the wrong host authenticates but returns no
# data, which is why the host is configurable per user rather than hardcoded.
DEFAULT_HOST = 'api-mifit.huami.com'
KNOWN_HOSTS = [
    'api-mifit.huami.com',
    'api-mifit.zepp.com',
    'api-mifit-de2.zepp.com',
    'api-mifit-us2.zepp.com',
]

BAND_DATA_PATH = '/v1/data/band_data.json'
REQUEST_TIMEOUT = 30

# How far back a sync reaches when there is no cursor. Zepp serves a long
# history, but asking for years in one request is how you get a timeout.
DEFAULT_BACKFILL_DAYS = 90
MAX_WINDOW_DAYS = 30          # per request, to keep responses small
SWEEP_INTERVAL_S = 6 * 3600   # matches the mobile Health Connect worker

_running = set()              # user ids with a sync in flight, per process
_lock = threading.Lock()


class ZeppError(Exception):
    """Anything that stopped a sync — surfaced to the user verbatim."""


def _fetch_band_data(host, token, user_id, from_date, to_date):
    """One band_data.json call. Returns the decoded ``data`` list."""
    params = urllib.parse.urlencode({
        'query_type': 'detail',
        'device_type': 'android_phone',
        'userid': user_id,
        'from_date': from_date.strftime('%Y-%m-%d'),
        'to_date': to_date.strftime('%Y-%m-%d'),
    })
    url = f"https://{host}{BAND_DATA_PATH}?{params}"
    req = urllib.request.Request(url, headers={
        'apptoken': token,
        'User-Agent': 'Roamly',
    })
    try:
        with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as resp:
            payload = json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as exc:
        # 401 is by far the most likely failure and the most actionable, so it
        # gets its own message rather than a bare status code.
        if exc.code in (401, 403):
            raise ZeppError(
                "Zepp rejected the token (HTTP %d). App tokens expire — "
                "re-capture it from the Zepp app." % exc.code)
        raise ZeppError("Zepp returned HTTP %d" % exc.code)
    except Exception as exc:
        raise ZeppError("Could not reach Zepp: %s" % exc)

    if not isinstance(payload, dict) or 'data' not in payload:
        raise ZeppError("Unexpected response from Zepp (no data field)")
    return payload.get('data') or []


def _decode_summary(day_entry):
    """base64 -> dict for one day's summary blob, or None if unusable."""
    raw = day_entry.get('summary')
    if not raw:
        return None
    try:
        return json.loads(base64.b64decode(raw))
    except Exception:
        return None


def _day_bounds_utc(day):
    """Naive local-midnight bounds for a YYYY-MM-DD string, as aware datetimes.

    Zepp reports a calendar day with minute offsets into it and no timezone, so
    the day is interpreted in the server's timezone — the same assumption the
    rest of the app makes for a date with no offset attached.
    """
    start = datetime.strptime(day, '%Y-%m-%d')
    if timezone.is_naive(start):
        start = timezone.make_aware(start)
    return start, start + timedelta(days=1)


def parse_day(day_entry, samples_out):
    """Turn one day's Zepp entry into HealthSample kwargs, appended to ``samples_out``.

    Steps and calories come from the ``stage`` segments where present, so the
    intraday view has real shape. Because segments can under-count the day's
    total (steps taken outside a detected activity), a remainder row covers the
    difference — so the day still sums to exactly Zepp's own total while keeping
    the detail. Distance has no per-segment figure in the payload, so it lands as
    a single day-spanning row.
    """
    day = day_entry.get('date_time')
    if not day:
        return
    summary = _decode_summary(day_entry)
    if not summary:
        return
    stp = summary.get('stp') or {}
    if not isinstance(stp, dict):
        return

    day_start, day_end = _day_bounds_utc(day)

    def add(kind, value, start, end, suffix):
        try:
            value = float(value)
        except (TypeError, ValueError):
            return
        if value <= 0:
            return
        samples_out.append({
            'kind': kind,
            'value': value,
            'start_time': start,
            'end_time': end,
            'source': 'zepp',
            'external_id': f'zepp:{kind}:{day}:{suffix}',
        })

    stage_steps = 0.0
    stage_cals = 0.0
    for seg in (stp.get('stage') or []):
        if not isinstance(seg, dict):
            continue
        try:
            start_min = int(seg.get('start', 0))
            stop_min = int(seg.get('stop', start_min))
        except (TypeError, ValueError):
            continue
        seg_start = day_start + timedelta(minutes=start_min)
        seg_end = day_start + timedelta(minutes=max(stop_min, start_min))
        steps = seg.get('step') or 0
        cals = seg.get('cal') or 0
        add('steps', steps, seg_start, seg_end, str(start_min))
        add('calories_active', cals, seg_start, seg_end, str(start_min))
        try:
            stage_steps += float(steps)
            stage_cals += float(cals)
        except (TypeError, ValueError):
            pass

    # Reconcile the segments back to Zepp's own daily total.
    total_steps = float(stp.get('ttl') or 0)
    total_cals = float(stp.get('cal') or 0)
    if total_steps > stage_steps:
        add('steps', total_steps - stage_steps, day_start, day_end, 'rem')
    if total_cals > stage_cals:
        add('calories_active', total_cals - stage_cals, day_start, day_end, 'rem')

    # Distance is only ever a daily figure in this payload.
    add('distance', stp.get('dis') or 0, day_start, day_end, 'day')


def sync_user(user, days=None):
    """Pull Zepp data for one user. Returns (written, days_seen). Raises ZeppError."""
    from .models import HealthSample, UserProfile
    from . import views  # for _bust_health_cache

    profile = UserProfile.objects.filter(user=user).first()
    if not profile or not profile.zepp_configured:
        raise ZeppError("Zepp is not configured for this account")

    host = (profile.zepp_host or DEFAULT_HOST).strip()
    today = timezone.localdate()
    if days is None:
        # Resume from a little before the last sync so a day that was still in
        # progress gets its final numbers, rather than being frozen mid-day.
        if profile.zepp_last_sync:
            days = max(2, (today - timezone.localtime(profile.zepp_last_sync).date()).days + 2)
        else:
            days = DEFAULT_BACKFILL_DAYS
    days = max(1, min(int(days), 730))

    samples = []
    days_seen = 0
    window_end = today
    remaining = days
    while remaining > 0:
        span = min(remaining, MAX_WINDOW_DAYS)
        window_start = window_end - timedelta(days=span - 1)
        entries = _fetch_band_data(host, profile.zepp_token, profile.zepp_user_id,
                                   window_start, window_end)
        for entry in entries:
            days_seen += 1
            parse_day(entry, samples)
        remaining -= span
        window_end = window_start - timedelta(days=1)

    written = 0
    if samples:
        objs = [HealthSample(user=user, **row) for row in samples]
        before = HealthSample.objects.filter(user=user).count()
        HealthSample.objects.bulk_create(objs, ignore_conflicts=True)
        written = max(0, HealthSample.objects.filter(user=user).count() - before)

        # A re-sync of a day already stored must update it, not silently keep the
        # stale value — a day still in progress when it was first pulled would
        # otherwise stay frozen at its mid-day figure forever.
        existing = {
            s.external_id: s
            for s in HealthSample.objects.filter(
                user=user, external_id__in=[r['external_id'] for r in samples])
        }
        stale = []
        for row in samples:
            obj = existing.get(row['external_id'])
            if obj is not None and obj.value != row['value']:
                obj.value = row['value']
                stale.append(obj)
        if stale:
            HealthSample.objects.bulk_update(stale, ['value'])

    profile.zepp_last_sync = timezone.now()
    profile.zepp_last_error = ''
    profile.save(update_fields=['zepp_last_sync', 'zepp_last_error'])
    views._bust_health_cache(user.id)
    return written, days_seen


def sync_user_safe(user, days=None):
    """sync_user, recording any failure on the profile instead of raising."""
    from .models import UserProfile
    try:
        return sync_user(user, days=days)
    except ZeppError as exc:
        UserProfile.objects.filter(user=user).update(zepp_last_error=str(exc))
        logger.warning("Zepp sync failed for user %s: %s", user.id, exc)
        return 0, 0
    except Exception as exc:
        UserProfile.objects.filter(user=user).update(zepp_last_error=str(exc))
        logger.exception("Zepp sync crashed for user %s", user.id)
        return 0, 0


def start_sync(user, days=None):
    """Kick a background sync for one user. No-op if one is already running."""
    with _lock:
        if user.id in _running:
            return False
        _running.add(user.id)

    def worker():
        try:
            close_old_connections()
            sync_user_safe(user, days=days)
        finally:
            close_old_connections()
            with _lock:
                _running.discard(user.id)

    threading.Thread(target=worker, daemon=True).start()
    return True


def is_running(user_id):
    with _lock:
        return user_id in _running


def _sweep():
    """Sync every account with Zepp enabled."""
    from .models import UserProfile
    for profile in UserProfile.objects.filter(zepp_enabled=True).select_related('user'):
        if not profile.zepp_configured:
            continue
        if is_running(profile.user_id):
            continue
        sync_user_safe(profile.user)


def _scheduler():
    """Daemon loop, mirroring alert_tasks/summary_email_tasks."""
    while True:
        try:
            close_old_connections()
            _sweep()
        except Exception:
            logger.exception("Zepp sweep failed")
        finally:
            close_old_connections()
        time.sleep(SWEEP_INTERVAL_S)


def start_zepp_scheduler():
    t = threading.Thread(target=_scheduler, daemon=True, name='zepp-sync')
    t.start()
    return t
