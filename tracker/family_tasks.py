"""Family Circle geofence enter/exit detection.

Unlike alert_tasks.py's "no data received" alert, this can't be a pure
15-minute sweep — "kid left school" needs to fire within seconds, not up to
15 minutes late. So the primary path is event-driven, kicked from the push
endpoints (see ensure_family_geofence_check, called from views.push_location
and views.push_location_batch right after the existing
ensure_auto_geocode(user.id) call) — mirroring that function's
fire-and-forget-thread shape, but *not* debounced: the whole check is a
couple of small indexed queries plus in-memory haversine against a handful
of places (the same "a user has only a handful of these" cost assumption
CustomPlace's own docstring makes), genuinely cheaper than the geocoding
trigger check it sits next to.

start_family_scheduler() below is a 15-minute reconciliation sweep, the same
role alert_tasks'/stats_tasks' schedulers play elsewhere in this app — a
safety net for a dropped/excepted background thread, and for reseeding a
newly-created place against members already standing inside it, not the
primary delivery path.
"""

import logging
import threading
import time
from contextlib import contextmanager
from math import asin, cos, radians, sin, sqrt

from django.db import close_old_connections, connection, transaction
from django.utils import timezone

from . import push_tasks

logger = logging.getLogger(__name__)

_scheduler_thread = None

# 15 minutes — the reconciliation-sweep interval, not the alert latency (the
# event-driven path below is what actually keeps that fast).
SCHEDULER_CHECK_INTERVAL = 900

# Advisory-lock namespace (first int4 key). Distinct from stats_tasks
# ('RAML'), summary_email_tasks ('RAMS'), log_cleanup_tasks ('RAMG'),
# auto_download_tasks ('RAMD') and alert_tasks ('RAMA').
_LOCK_NAMESPACE = 0x52414d46       # 'RAMF'

# A fix older than this relative to "now" is a backfilled/imported point or a
# stale entry in a backlog-drain batch — an alert about somewhere a phone
# was minutes ago is actively misleading, not merely late.
_MAX_FIX_AGE_S = 600


def _haversine_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = radians(lat1), radians(lat2)
    dphi = radians(lat2 - lat1)
    dlambda = radians(lon2 - lon1)
    a = sin(dphi / 2) ** 2 + cos(p1) * cos(p2) * sin(dlambda / 2) ** 2
    return 2 * r * asin(sqrt(a))


def ensure_family_geofence_check(user_id, fixes):
    """Fire-and-forget geofence check for a set of fresh fixes.

    `fixes` is an iterable of (device_id, lat, lon, timestamp) tuples. Runs
    in a daemon thread so it never adds latency to the ingest path — the
    same reasoning CLAUDE.md gives for keeping geocoding off the push path
    applies here too, even though this check itself is much cheaper.
    """
    fixes = list(fixes)
    if not fixes:
        return
    threading.Thread(target=_run_check, args=(user_id, fixes), daemon=True).start()


def _run_check(user_id, fixes):
    close_old_connections()
    try:
        _check_user_geofences(user_id, fixes)
    except Exception:
        logger.exception("Family geofence check failed for user %s", user_id)
    finally:
        close_old_connections()


def _check_user_geofences(user_id, fixes):
    from .models import FamilyMembership, FamilyMemberPlaceState, FamilyPlace

    circle_ids = list(
        FamilyMembership.objects.filter(
            user_id=user_id, accepted_at__isnull=False, share_location=True,
        ).values_list('circle_id', flat=True)
    )
    if not circle_ids:
        return  # the common case — most pushes are from users in no circle

    places = list(FamilyPlace.objects.filter(circle_id__in=circle_ids))
    if not places:
        return

    now = timezone.now()
    # Only "where are they right now" matters — fold every device down to
    # the single newest usable fix, so a batch upload of a day's backlog
    # can't replay a day of stale transitions.
    newest = None
    for device_id, lat, lon, ts in fixes:
        if ts is None or (now - ts).total_seconds() > _MAX_FIX_AGE_S:
            continue
        if newest is None or ts > newest[2]:
            newest = (lat, lon, ts)
    if newest is None:
        return
    lat, lon, _ts = newest

    states = {
        s.place_id: s
        for s in FamilyMemberPlaceState.objects.filter(user_id=user_id, place__in=places)
    }
    for place in places:
        is_inside = _haversine_m(lat, lon, place.latitude, place.longitude) <= place.radius_m
        _apply_transition(user_id, place, is_inside, states.get(place.id))


def _apply_transition(user_id, place, is_inside, state):
    from .models import FamilyMemberPlaceState

    if state is None:
        # Race-safe seed via get_or_create rather than a bare create(), so a
        # genuinely concurrent check for the same brand-new (member, place)
        # pair can't hit a duplicate-key error. First observation is never a
        # transition — otherwise a brand-new member's first push, or a place
        # created while a member is already standing inside it, would fire a
        # fake "entered" event. Mirrors alert_tasks' "never tracked anything
        # => never mailed" guard.
        _, created = FamilyMemberPlaceState.objects.get_or_create(
            place=place, user_id=user_id, defaults={'is_inside': is_inside},
        )
        if created:
            return
        # else: another thread just seeded it a moment ago — fall through
        # and compare against the real row like any other check.

    with transaction.atomic():
        locked = (FamilyMemberPlaceState.objects
                  .select_for_update()
                  .get(place=place, user_id=user_id))
        if locked.is_inside == is_inside:
            return
        entered = is_inside and not locked.is_inside
        locked.is_inside = is_inside
        locked.save(update_fields=['is_inside', 'updated_at'])

    _notify(place, user_id, entered)


def _notify(place, mover_user_id, entered):
    """Notify every other accepted circle member subscribed to this direction.

    Opt-out, not opt-in: FamilyPlaceAlert.on_enter/on_exit default True on
    the model, and every accepted member is treated as subscribed at those
    defaults unless they've explicitly saved a row turning a direction off.
    Self-serve alerting is only useful to a non-technical family member if it
    works without them first finding a settings screen to turn it on.
    """
    from django.contrib.auth.models import User

    from .models import FamilyMembership, FamilyPlaceAlert

    mover = User.objects.filter(id=mover_user_id).only('username', 'first_name').first()
    mover_name = (mover.first_name or mover.username) if mover else 'Someone'
    verb = 'arrived at' if entered else 'left'
    field = 'on_enter' if entered else 'on_exit'

    overrides = {
        a.user_id: getattr(a, field)
        for a in FamilyPlaceAlert.objects.filter(place=place)
    }
    member_ids = (FamilyMembership.objects
                  .filter(circle_id=place.circle_id, accepted_at__isnull=False)
                  .exclude(user_id=mover_user_id)  # no one is notified of their own move
                  .values_list('user_id', flat=True))
    for user_id in member_ids:
        if not overrides.get(user_id, True):
            continue
        push_tasks.send_push_to_user(
            user_id,
            title=place.name,
            body=f"{mover_name} {verb} {place.name}",
            data={'type': 'family_place', 'place_id': place.id, 'entered': entered},
        )


# ── Reconciliation sweep ─────────────────────────────────────────────────

def _latest_fix_with_coords(user):
    """(device_id, lat, lon, timestamp) for the single newest fix across a
    user's devices, or None.

    Per-device query, not a user-wide aggregate, so tracker_loc_device__idx
    stays an index seek on a large history — the same reasoning
    alert_tasks.latest_fix already documents, which this mirrors but can't
    call directly since it returns a device *label*, not coordinates.
    """
    from .models import Device, Location

    best = None
    for device in Device.objects.filter(user=user):
        row = (Location.objects.filter(device=device)
               .order_by('-timestamp')
               .values_list('device__device_id', 'latitude', 'longitude', 'timestamp')
               .first())
        if row and (best is None or row[3] > best[3]):
            best = row
    return best


@contextmanager
def _user_family_lock(user_id):
    """Yield True iff this thread holds the family-geofence sweep lock for
    user_id. Same PG-advisory-lock/no-op-on-SQLite shape as
    alert_tasks._user_alert_lock — stops several gunicorn workers each
    running their own sweep thread from reconciling the same user at once."""
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


def _reconcile_user(user_id):
    from django.contrib.auth.models import User

    with _user_family_lock(user_id) as got:
        if not got:
            return
        user = User.objects.filter(id=user_id).first()
        if not user:
            return
        fix = _latest_fix_with_coords(user)
        if not fix:
            return
        _check_user_geofences(user_id, [fix])


def _family_scheduler_loop():
    from .models import FamilyMembership

    while True:
        try:
            close_old_connections()
            user_ids = list(
                FamilyMembership.objects.filter(
                    accepted_at__isnull=False, share_location=True,
                ).values_list('user_id', flat=True).distinct()
            )
            for uid in user_ids:
                try:
                    _reconcile_user(uid)
                except Exception:
                    logger.exception("Family geofence reconciliation failed for user %s", uid)
        except Exception:
            logger.exception("Family geofence scheduler error")
        time.sleep(SCHEDULER_CHECK_INTERVAL)


def start_family_scheduler():
    """Start the 15-minute reconciliation sweep thread (called once on startup)."""
    global _scheduler_thread
    if _scheduler_thread is not None and _scheduler_thread.is_alive():
        return
    _scheduler_thread = threading.Thread(target=_family_scheduler_loop, daemon=True)
    _scheduler_thread.start()
    logger.info("Family geofence scheduler started")
