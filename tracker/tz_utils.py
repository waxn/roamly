"""Per-user local time, derived from where the user actually is.

Every timestamp in Roamly is stored as an aware UTC instant. What varies is the
zone Django *renders and buckets* them in — ``django.utils.timezone`` reads the
zone activated on the current thread, and this module is what decides it: the
timezone of the user's most recent GPS fix.

That source is deliberate, and it is the one a location tracker can answer
without asking. There is no timezone field to set and keep up to date, no
second source of truth to drift from reality, and the answer follows the user
as they travel. It also resolves identically in a background thread — the
recap-email, alert and snapshot schedulers have no browser to read a
``getTimezoneOffset()`` from, which is exactly why the offset the Health /
Journals / Ask endpoints take on the querystring could never have covered them.

Activating the zone is what makes this reach the call sites, rather than
threading an argument through each one. ``timezone.make_aware`` (the 12
date-range filters), ``timezone.localtime`` / ``localdate`` (the schedulers) and
``TruncDate`` / ``TruncHour`` all consult the active zone already, so the work
is in choosing it correctly and activating it at each entry point.

Lookup is offline via ``timezonefinder``, matching the app's offline-first
stance elsewhere (``offline_geocode``, ``geoip_utils``) — no geolocation API
call. The finder is a lazy module-level singleton, so a gunicorn worker that
never resolves a timezone never pays the load cost.
"""
import logging
from contextlib import contextmanager
from zoneinfo import ZoneInfo

from django.core.cache import cache
from django.utils import timezone

logger = logging.getLogger(__name__)

UTC = ZoneInfo('UTC')

# The resolved zone is cached per user rather than recomputed per request. The
# TTL bounds how long after landing in a new zone the app keeps bucketing by the
# old one; an hour matches the other whole-history caches (``fog:``) and is well
# under the granularity of anything that reads it.
CACHE_TTL_S = 3600

# An unresolved answer ('' — no points yet, or a fix over open ocean) is cached
# for far less. A brand-new account resolves to '' and would otherwise be stuck
# on UTC for a full hour after pushing its very first point, which is exactly
# when someone is looking to see whether tracking works. Caching '' briefly is
# still enough to keep an empty account from re-scanning its devices per request.
EMPTY_CACHE_TTL_S = 300

_CACHE_KEY = 'usertz:{}'

_finder = None
_finder_failed = False


def _get_finder():
    global _finder, _finder_failed
    if _finder is None and not _finder_failed:
        try:
            from timezonefinder import TimezoneFinder
            _finder = TimezoneFinder()
        except Exception:
            logger.exception('Failed to load timezonefinder')
            _finder_failed = True
    return _finder


def zone_name_for_point(lat, lon):
    """IANA zone name containing (lat, lon), or '' if it can't be resolved.

    Returns '' over open ocean, where there is no land timezone polygon to hit.
    """
    if lat is None or lon is None:
        return ''
    finder = _get_finder()
    if finder is None:
        return ''
    try:
        return finder.timezone_at(lat=float(lat), lng=float(lon)) or ''
    except Exception:
        return ''


def _latest_point(user_id):
    """(lat, lon) of the user's newest fix across all devices, or (None, None).

    Queried one device at a time for the same reason ``alert_tasks.latest_fix``
    is: ``tracker_loc_device__idx`` is ``(device, -timestamp)``, so a per-device
    ordered lookup is an index seek, while a user-wide ``aggregate(Max(...))``
    has no matching index and degrades into a scan of the whole history.
    """
    from .models import Device, Location

    best_ts, best_lat, best_lon = None, None, None
    for device_id in Device.objects.filter(user_id=user_id).values_list('id', flat=True):
        row = (Location.objects.filter(device_id=device_id)
               .order_by('-timestamp')
               .values('timestamp', 'latitude', 'longitude').first())
        if not row or row['latitude'] is None:
            continue
        if best_ts is None or row['timestamp'] > best_ts:
            best_ts = row['timestamp']
            best_lat, best_lon = row['latitude'], row['longitude']
    return best_lat, best_lon


def _user_id_of(user_or_id):
    """Accept a User, a user id, or None — the schedulers hold ids, requests
    hold User objects, and neither should have to convert for the other."""
    if user_or_id is None:
        return None
    if isinstance(user_or_id, int):
        return user_or_id
    if not getattr(user_or_id, 'is_authenticated', True):
        return None
    return getattr(user_or_id, 'id', None)


def zone_name_for_user(user_or_id):
    """Cached IANA zone name for a user, or '' to mean UTC.

    '' is cached as readily as a hit: an account with no points yet (or whose
    newest fix landed over open ocean) must not re-scan every device on every
    request just to arrive at the same answer.
    """
    user_id = _user_id_of(user_or_id)
    if user_id is None:
        return ''
    key = _CACHE_KEY.format(user_id)
    name = cache.get(key)
    if name is None:
        try:
            lat, lon = _latest_point(user_id)
            name = zone_name_for_point(lat, lon)
        except Exception:
            logger.exception('Failed to resolve timezone for user %s', user_id)
            name = ''
        cache.set(key, name, CACHE_TTL_S if name else EMPTY_CACHE_TTL_S)
    return name


def timezone_for_user(user_or_id):
    """``ZoneInfo`` for a user, falling back to UTC — never raises."""
    name = zone_name_for_user(user_or_id)
    if not name:
        return UTC
    try:
        return ZoneInfo(name)
    except Exception:
        return UTC


def bust_user_timezone(user_id):
    """Drop the cached zone (used when a user's data is deleted or restored)."""
    cache.delete(_CACHE_KEY.format(user_id))


@contextmanager
def user_timezone(user_or_id):
    """Activate a user's local zone for the duration of the block.

    For the background schedulers, which run per user in a long-lived daemon
    thread: Django's active timezone is thread-local, so a sweep that activated
    one user's zone and moved on would silently apply it to the next user in the
    same pass. Restoring on exit is what keeps each user's pass independent.
    """
    previous = timezone.get_current_timezone()
    try:
        timezone.activate(timezone_for_user(user_or_id))
        yield
    finally:
        timezone.activate(previous)


def _fold_pair(naive, tz):
    """The two readings of a wall-clock time: (fold=0, fold=1).

    They differ only at a DST transition. Which one is *earlier* depends on which
    kind of transition it is, which is why callers must classify before choosing.
    """
    return naive.replace(tzinfo=tz, fold=0), naive.replace(tzinfo=tz, fold=1)


def aware_local(naive, end_of_day=False):
    """Attach the active timezone to a naive local datetime, surviving DST edges.

    ``timezone.make_aware`` raises ValueError for a local wall-clock time that
    DST made imaginary (spring-forward gap) or ambiguous (fall-back fold). Both
    land exactly on midnight in real zones — America/Santiago, America/Havana,
    Asia/Beirut, Asia/Gaza and Asia/Hebron all shift at 00:00, about once a year
    each — and every date-range filter in views.py wraps its make_aware in
    ``except (ValueError, TypeError): pass``. A raise there does not surface as
    an error: it silently drops the date filter and answers with the account's
    entire history for that day. Resolving beats raising.

    None of this was reachable while every request ran in UTC, which has no DST.

    The two cases have to be told apart rather than both handed to ``fold``,
    because fold means opposite things in each:

    * **Fold** (ambiguous — the hour ran twice): ``fold=0`` is the earlier
      instant, ``fold=1`` the later. A start bound takes the earlier and an
      inclusive end bound the later, so the repeated hour is covered once, whole.
    * **Gap** (imaginary — the hour never ran): ``fold=0`` is the *later*
      instant, and it is the transition itself — precisely the moment the local
      day began. ``fold=1`` would land an hour *before* that, i.e. on the
      previous day, which for an end bound would put it before the start.

    So ``fold=0`` is right everywhere except an ambiguous end-of-day bound.
    """
    tz = timezone.get_current_timezone()
    early, late = _fold_pair(naive, tz)
    if early.utcoffset() == late.utcoffset():
        return early                       # ordinary local time, no transition
    # A real-but-repeated time survives a round-trip through UTC; an imaginary
    # one does not, because it maps to an instant whose local time is different.
    round_tripped = early.astimezone(UTC).astimezone(tz).replace(tzinfo=None, fold=0)
    ambiguous = round_tripped == naive.replace(fold=0)
    return late if (ambiguous and end_of_day) else early
