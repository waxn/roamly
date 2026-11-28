"""Data for the Hearth layout's card home (/home/, tracker/layouts/hearth/home.html).

``GET /api/layout/home/`` answers every card in one response so the page paints
from a single fetch. Each section is computed on its own inside a try/except:
a section that fails comes back as ``{"error": true}`` and the rest of the
response is unaffected, so one broken card never blanks the page or 500s.

Everything is the requesting user's own data, except ``family``, which follows
the Family Circle consent model (docs/claude/family-circle.md): only circles
the viewer has *accepted*, only members who have accepted *and* turned
``share_location`` on, never the viewer themself, never coordinates or trails.

Heavy whole-history numbers come from the nightly ``StatsSnapshot`` (never
recomputed here); per-day numbers are bounded to one local day. "Today" and
every day boundary follow the zone ``UserTimezoneMiddleware`` activated (the
user's newest fix, see tz_utils), except the health card, which buckets by the
browser's ``tz_offset`` exactly like the Health page does.

Query parameters:
  tz_offset   browser ``Date.getTimezoneOffset()`` in minutes (health only)
  only        comma-separated section names to compute just those (a card's
              Retry); bypasses the cache

Response shape (datetimes ISO 8601 strings, dates "YYYY-MM-DD", distances in
km unless the name ends ``_m``, durations in seconds, battery 0-100 int):

  {
    "today": {                       # the user's local day (zone of their newest fix)
      "date", "distance_km": float, "moving_s": int, "point_count": int,
      "places": [str, ...],          # first 6 distinct "City, State" labels, in visit order
      "place_count": int, "first_at": iso|null, "last_at": iso|null,
      "shape": Shape|null, "geo": Geo|null, "map_url": "/map/?date=YYYY-MM-DD"
    },
    "recent": {                      # newest Activity or own Adventure, by start time
      "kind": "ride"|"run"|"walk"|"hike"|"row"|"other"|"trip",
      "source": "activity"|"adventure", "title": str, "start": iso, "end": iso,
      "distance_km": float|null, "moving_s": int|null, "elapsed_s": int|null,
      "elevation_gain_m": float|null, "url": str, "shape": Shape|null, "geo": Geo|null
    } | null,                        # null: no activity and no adventure
    "on_this_day": {
      "date": today,
      "years": [{                    # newest first; only years with points or a journal entry
        "year": int, "date", "years_ago": int, "distance_km": float|null,
        "point_count": int, "cities": [str, ...],    # top 3 by points
        "journal": {"title": str, "excerpt": str, "mood": str} | null,
        "recap": {"headline": str, "summary": str} | null,   # saved year-ago AI line only
        "map_url": str
      }, ...]
    } | null,                        # null: nothing on this date in any earlier year
    "stats": {                       # nightly StatsSnapshot; pending=true means none finished yet
      "pending": bool,               # when true every field below except badges is null / []
      "computed_at": iso|null, "ytd_km": float|null, "ytd_active_days": int|null,
      "total_km": float|null, "countries": int|null,          # all time
      "countries_ytd": int|null, "cities_ytd": int|null,
      "streak_days": int|null,       # consecutive days >= 50 m, ending today or yesterday
      "months": [{"month": "YYYY-MM", "km": float}],          # 12, oldest first, current last
      "badges": [{"id", "name", "icon", "tier": 1-4,           # newest unlocks, max 4,
                  "tier_name": "bronze"|"silver"|"gold"|"platinum", "unlocked_at": iso}]
    },
    "health": {                      # bucketed by the browser's tz_offset day
      "today": {"date", "steps": int|null, "distance_m": int|null, "active_kcal": int|null},
      "week": [{"date", "steps": int}],                       # 7 days, oldest first, today last
      "last_sync": iso|null,
      "last_workout": {"title", "slug", "start": iso, "duration_s": int,
                       "distance_m": float|null, "calories_kcal": float|null, "url"} | null
    } | null,                        # null: no health samples and no imported workouts
    "devices": [{"id": int, "name": str, "last_seen": iso|null, "battery": int|null,
                 "place": str}],     # max 8, newest registered first
    "family": [{"user_id": int, "name": str, "circles": [str], "last_seen": iso|null,
                "place": str,        # a circle place name when at_place, else "City, State"
                "at_place": bool, "battery": int|null}],      # max 12, by username
    "sync": {"last_point_at": iso|null},                      # newest fix over all devices
    "generated_at": iso
  }

Any section (including ``devices`` / ``family``, which are then objects, not
arrays, and ``sync``) may instead be ``{"error": true}``. With ``?only=a,b``
the body holds just those keys plus ``sync`` and ``generated_at``. ``Shape`` is
a route already projected for an SVG: ``{"w": float, "h": float, "segments":
[[[x, y], ...], ...]}`` with ``max(w, h) == 100``, y growing downward (north
up); a one-point segment is a dot. ``Geo`` is the same route in real
coordinates for drawing on a basemap (Chronicle's Overview): ``{"segments":
[[[lon, lat], ...], ...], "bounds": [west, south, east, north]}``, decimated
to the same vertex budget as ``Shape``. Responses are cached 60s per user, day and
tz_offset, and never when a section failed.
"""
import logging
import math
from datetime import datetime, timedelta
from datetime import timezone as dt_timezone
from itertools import groupby

from django.contrib.auth.decorators import login_required
from django.core.cache import cache
from django.http import JsonResponse
from django.urls import reverse
from django.utils import timezone
from django.views.decorators.http import require_GET

logger = logging.getLogger(__name__)

CACHE_TTL_S = 60
OTD_CACHE_TTL_S = 30 * 60
ADV_CACHE_TTL_S = 6 * 3600

SHAPE_MAX_POINTS = 300          # vertices in a drawn route, all segments together
SEGMENT_GAP_S = 20 * 60         # a longer gap breaks the drawn line (same as Journals)
TELEPORT_MPS = 305.0            # a faster hop is a bad fix, not travel (same as Journals)
MOVE_RUN_GAP_S = 300            # credited distance windows closer than this are one run of moving
MIN_SHAPE_SPAN_M = 300.0        # a stationary day draws as a dot in the middle, not a zoomed scribble
STREAK_MIN_KM = 0.05
OTD_MAX_YEARS = 25
MAX_DEVICES = 8
MAX_FAMILY = 12
MAX_BADGES = 4
MAX_PLACES = 6


# ── Small helpers ────────────────────────────────────────────────────────────

def _iso(dt):
    return dt.isoformat() if dt else None


def _num(value, digits=2):
    """Round a float for JSON; None (and NaN/inf) stay None."""
    if value is None:
        return None
    try:
        f = float(value)
    except (TypeError, ValueError):
        return None
    if math.isnan(f) or math.isinf(f):
        return None
    return round(f, digits)


def _same_day_years_back(d, years):
    """``d`` moved back ``years`` years (Feb 29 becomes Feb 28)."""
    try:
        return d.replace(year=d.year - years)
    except ValueError:
        return d.replace(year=d.year - years, day=28)


def _place_label(city, state):
    city = (city or '').strip()
    if not city:
        return ''
    state = (state or '').strip()
    return f'{city}, {state}' if state else city


def decode_polyline(encoded, precision=5):
    """Google encoded polyline -> [(lat, lon), ...]. A truncated string ends early."""
    coords = []
    index = lat = lon = 0
    factor = 10 ** precision
    n = len(encoded or '')
    while index < n:
        deltas = []
        for _ in range(2):
            shift = result = 0
            while True:
                if index >= n:
                    return coords
                b = ord(encoded[index]) - 63
                index += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            deltas.append(~(result >> 1) if result & 1 else result >> 1)
        lat += deltas[0]
        lon += deltas[1]
        coords.append((lat / factor, lon / factor))
    return coords


def project_shape(segments, size=100.0, min_span_m=MIN_SHAPE_SPAN_M):
    """Project lat/lon segments into an SVG-ready box.

    Equirectangular with cos(mid-latitude) on x, y flipped so north is up.
    The box is padded to at least ``min_span_m`` so a day spent in one place
    draws as a small cluster rather than GPS jitter blown up to fill the card.
    Returns None when there is nothing to draw. Routes across the antimeridian
    are drawn the long way round (rare enough not to special-case).
    """
    segs = [[(float(a), float(b)) for a, b in s if a is not None and b is not None] for s in segments or []]
    segs = [s for s in segs if s]
    if not segs:
        return None
    lats = [p[0] for s in segs for p in s]
    lons = [p[1] for s in segs for p in s]
    min_lat, max_lat, min_lon, max_lon = min(lats), max(lats), min(lons), max(lons)
    kx = max(0.01, math.cos(math.radians((min_lat + max_lat) / 2.0)))
    span_x = (max_lon - min_lon) * kx
    span_y = max_lat - min_lat
    floor = min_span_m / 111320.0
    sx, sy = max(span_x, floor), max(span_y, floor)
    scale = size / max(sx, sy)
    ox, oy = (sx - span_x) / 2.0, (sy - span_y) / 2.0
    out = []
    for s in segs:
        out.append([[round(((lon - min_lon) * kx + ox) * scale, 1),
                     round(((max_lat - lat) + oy) * scale, 1)] for lat, lon in s])
    return {'w': round(sx * scale, 1), 'h': round(sy * scale, 1), 'segments': out}


def _decimate(segments, max_points=SHAPE_MAX_POINTS):
    """Thin segments to about ``max_points`` vertices, keeping each one's ends."""
    total = sum(len(s) for s in segments)
    if total <= max_points:
        return segments
    stride = int(math.ceil(total / float(max_points)))
    out = []
    for s in segments:
        if len(s) <= 2:
            out.append(s)
            continue
        kept = s[::stride]
        if (len(s) - 1) % stride:
            kept.append(s[-1])
        out.append(kept)
    return out


def _geo(segments):
    """Lat/lon segments -> ``{"segments": [[[lon, lat], ...]], "bounds": [w, s, e, n]}``
    (GeoJSON order, 5 decimals), or None when there is nothing to draw."""
    out = []
    for seg in segments or []:
        pts = [[round(float(lon), 5), round(float(lat), 5)] for lat, lon in seg
               if lat is not None and lon is not None]
        if pts:
            out.append(pts)
    if not out:
        return None
    lons = [p[0] for s in out for p in s]
    lats = [p[1] for s in out for p in s]
    return {'segments': out, 'bounds': [min(lons), min(lats), max(lons), max(lats)]}


def _latest_fix(device_pk, fields=('timestamp', 'battery', 'city', 'state')):
    """Newest Location of one device: an index seek on tracker_loc_device__idx.

    A user-wide ``order_by('-timestamp')`` across ``device__user`` has no
    matching index and scans the whole history, so latest-fix lookups are
    always done per device (as tz_utils._latest_point does)."""
    from .models import Location
    return (Location.objects.filter(device_id=device_pk)
            .order_by('-timestamp').values(*fields).first())


class _Ctx:
    """Per-request facts several sections share, each computed at most once."""

    def __init__(self, request):
        self.request = request
        self.user = request.user
        self.now = timezone.now()
        self.local_now = timezone.localtime(self.now)
        self.today = self.local_now.date()
        try:
            self.tz_offset = max(-1440, min(1440, int(request.GET.get('tz_offset'))))
        except (TypeError, ValueError, OverflowError):
            off = self.local_now.utcoffset()
            self.tz_offset = -int(off.total_seconds() // 60) if off else 0
        self._devices = None
        self._snapshot = False

    @property
    def devices(self):
        """[(Device, newest fix dict | None)], newest-registered first."""
        if self._devices is None:
            from .models import Device
            rows = []
            for d in Device.objects.filter(user=self.user).order_by('-created_at'):
                rows.append((d, _latest_fix(d.pk)))
            self._devices = rows
        return self._devices

    @property
    def snapshot(self):
        """``{'stats_json', 'yearly_json', 'computed_at'}`` of the last finished
        snapshot, or None.

        Read whenever one was ever completed, whatever ``status`` says now: a
        recompute flips the row to 'running' for its whole duration (and the
        JSON is only replaced, atomically, when it finishes), so gating on
        'done' as _get_snapshot_or_kick does would blank the card nightly.
        Only an account with no finished snapshot at all asks for one."""
        if self._snapshot is False:
            from .models import StatsSnapshot
            from .views import _get_snapshot_or_kick
            row = (StatsSnapshot.objects.filter(user=self.user, computed_at__isnull=False)
                   .values('stats_json', 'yearly_json', 'computed_at').first())
            if row is None:
                _get_snapshot_or_kick(self.user)
                self._snapshot = None
            else:
                row['stats_json'] = row['stats_json'] if isinstance(row['stats_json'], dict) else {}
                row['yearly_json'] = row['yearly_json'] if isinstance(row['yearly_json'], dict) else {}
                self._snapshot = row
        return self._snapshot

    def daily_km(self):
        """{'YYYY-MM-DD': km} from the snapshot's local-day distance series."""
        snap = self.snapshot
        if not snap:
            return {}
        dist = snap['stats_json'].get('distance') or {}
        days = dist.get('days') or []
        kms = dist.get('distances') or []
        out = {}
        for day, km in zip(days, kms, strict=False):  # a malformed snapshot just yields fewer days
            if isinstance(day, str) and isinstance(km, (int, float)):
                out[day] = float(km)
        return out


# ── Sections ─────────────────────────────────────────────────────────────────

def _section_today(ctx):
    """Today so far, in the user's local day: gated distance (the same pass
    distance_api runs), time moving, towns, and the track as an SVG shape."""
    from .models import Location
    from .summary_email_tasks import _local_midnight
    from .views import _gated_distance_segments, _haversine_km, _DIST_WINDOW_S

    start = _local_midnight(ctx.today)
    rows = list(
        Location.objects
        .filter(device__user=ctx.user, timestamp__gte=start, timestamp__lte=ctx.now,
                latitude__isnull=False, longitude__isnull=False)
        .order_by('device_id', 'timestamp')
        .values_list('device_id', 'latitude', 'longitude', 'timestamp', 'accuracy', 'city', 'state')
    )
    base = {
        'date': ctx.today.isoformat(),
        'distance_km': 0.0, 'moving_s': 0, 'point_count': len(rows),
        'places': [], 'place_count': 0, 'first_at': None, 'last_at': None, 'shape': None, 'geo': None,
        'map_url': f"{reverse('tracker:map')}?date={ctx.today.isoformat()}",
    }
    if not rows:
        return base

    distance_km = 0.0
    moving_s = 0.0
    for _dev, grp in groupby(rows, key=lambda r: r[0]):
        pts = ((lat, lon, ts, acc) for _d, lat, lon, ts, acc, _c, _s in grp)
        prev = None
        for cdt, km in _gated_distance_segments(pts):
            distance_km += km
            t = cdt.timestamp()
            # A run of credited windows is time spent moving; a lone window
            # (or the first of a run) counts as one resample window.
            moving_s += (t - prev) if prev is not None and 0 < t - prev <= MOVE_RUN_GAP_S else _DIST_WINDOW_S
            prev = t

    # Drawn line: split on device change and long gaps, drop teleport fixes.
    segments, cur, prev = [], None, None
    for dev, lat, lon, ts, _acc, _c, _s in rows:
        if prev is None or dev != prev[0] or (ts - prev[3]).total_seconds() > SEGMENT_GAP_S:
            cur = [(lat, lon)]
            segments.append(cur)
            prev = (dev, lat, lon, ts)
            continue
        dt = (ts - prev[3]).total_seconds()
        if dt > 0 and _haversine_km(prev[1], prev[2], lat, lon) * 1000.0 / dt > TELEPORT_MPS:
            continue
        cur.append((lat, lon))
        prev = (dev, lat, lon, ts)

    drawn = _decimate(segments)
    chrono = sorted(rows, key=lambda r: r[3])
    places, seen = [], set()
    for r in chrono:
        label = _place_label(r[5], r[6])
        if label and label.lower() not in seen:
            seen.add(label.lower())
            places.append(label)

    base.update({
        'distance_km': round(distance_km, 2),
        'moving_s': int(moving_s),
        'places': places[:MAX_PLACES],
        'place_count': len(places),
        'first_at': _iso(chrono[0][3]),
        'last_at': _iso(chrono[-1][3]),
        'shape': project_shape(drawn),
        'geo': _geo(drawn),
    })
    return base


def _activity_recent(ctx):
    from .models import Activity
    from .views import _activity_payload, _activity_stats, _activity_thumbs

    act = (Activity.objects.filter(user=ctx.user, start_time__lte=ctx.now)
           .select_related('device').order_by('-start_time').first())
    if act is None:
        return None
    try:
        _activity_stats(act)
    except Exception:
        # Stale stats are better than no card; the Activities page will retry.
        logger.exception('home: activity stats failed for %s', act.id)
    p = _activity_payload(act)
    shape = geo = None
    try:
        thumbs, _complete = _activity_thumbs(ctx.user.id, [act])
        lines = thumbs.get(act.id) or []
        segs = _decimate([decode_polyline(s) for s in lines if isinstance(s, str)])
        shape = project_shape(segs, min_span_m=100.0)
        geo = _geo(segs)
    except Exception:
        logger.exception('home: activity thumb failed for %s', act.id)
    return {
        'kind': act.kind,
        'title': act.title or act.get_kind_display(),
        'start': p['start'], 'end': p['end'],
        'distance_km': _num(p['distance_km']),
        'moving_s': p['moving_seconds'],
        'elapsed_s': p['elapsed_seconds'],
        'elevation_gain_m': _num(p['elevation_gain_m'], 0),
        'source': 'activity',
        'url': reverse('tracker:activity_detail', args=[act.id]),
        'shape': shape,
        'geo': geo,
        '_sort': act.start_time,
    }


def _adventure_recent(ctx):
    """The user's own most recent adventure (their device's track only)."""
    from .models import Adventure
    from .views import _compute_distance_from_qs, _slow_gen

    adv = (Adventure.objects.filter(device__user=ctx.user, start_time__lte=ctx.now)
           .order_by('-start_time').first())
    if adv is None:
        return None
    end = min(adv.end_time, ctx.now)
    key = (f'lx_home:adv:v2:{ctx.user.id}:{adv.id}:{int(adv.start_time.timestamp())}:'
           f'{int(end.timestamp()) // 600}:{_slow_gen(ctx.user.id)}')
    cached = cache.get(key)
    if cached is None:
        qs = adv.locations.filter(timestamp__lte=end, latitude__isnull=False, longitude__isnull=False)
        n = qs.count()
        segments = []
        if n:
            stride = max(1, int(math.ceil(n / float(SHAPE_MAX_POINTS))))
            cur, prev_ts = None, None
            for i, (lat, lon, ts) in enumerate(qs.values_list('latitude', 'longitude', 'timestamp')
                                               .iterator(chunk_size=5000)):
                gap = prev_ts is not None and (ts - prev_ts).total_seconds() > 6 * 3600
                prev_ts = ts
                if cur is None or gap:
                    cur = []
                    segments.append(cur)
                if i % stride == 0 or gap:
                    cur.append((lat, lon))
        km = _compute_distance_from_qs(qs)['total_km'] if n else None
        cached = {'km': km, 'shape': project_shape(segments, min_span_m=1000.0), 'geo': _geo(segments)}
        cache.set(key, cached, ADV_CACHE_TTL_S)
    return {
        'kind': 'trip',
        'title': adv.name,
        'start': _iso(adv.start_time), 'end': _iso(adv.end_time),
        'distance_km': _num(cached.get('km')),
        'moving_s': None,
        'elapsed_s': int(max(0, (end - adv.start_time).total_seconds())),
        'elevation_gain_m': None,
        'source': 'adventure',
        'url': reverse('tracker:adventure_edit', args=[adv.id]),
        'shape': cached.get('shape'),
        'geo': cached.get('geo'),
        '_sort': adv.start_time,
    }


def _section_recent(ctx):
    """The latest recorded activity or own adventure, whichever started last."""
    picks = []
    for fn in (_activity_recent, _adventure_recent):
        try:
            got = fn(ctx)
        except Exception:
            logger.exception('home: %s failed', fn.__name__)
            got = None
        if got:
            picks.append(got)
    if not picks:
        return None
    best = max(picks, key=lambda p: p['_sort'])
    best.pop('_sort', None)
    return best


def _section_on_this_day(ctx):
    """This calendar date in every earlier year with something recorded.

    Never calls the AI provider (the header chip's year_ago_api does that, and a
    miss spends the user's credits). A line it already wrote and saved is shown
    via year_ago.saved_line, which also honours the journal-consent rule."""
    from .models import Device, JournalEntry, Location, UserProfile
    from .summary_email_tasks import _local_midnight
    from .views import _compute_distance_from_qs, _slow_gen
    from . import year_ago
    from django.db.models import Count

    key = f'lx_home:otd:v1:{ctx.user.id}:{ctx.today.isoformat()}:{_slow_gen(ctx.user.id)}'
    cached = cache.get(key)
    if cached is not None:
        return cached or None

    dev_ids = list(Device.objects.filter(user=ctx.user).values_list('id', flat=True))
    firsts = []
    for pk in dev_ids:
        ts = (Location.objects.filter(device_id=pk).order_by('timestamp')
              .values_list('timestamp', flat=True).first())
        if ts:
            firsts.append(timezone.localtime(ts).year)
    first_journal = (JournalEntry.objects.filter(user=ctx.user).order_by('date')
                     .values_list('date', flat=True).first())
    if first_journal:
        firsts.append(first_journal.year)
    if not firsts:
        cache.set(key, {}, OTD_CACHE_TTL_S)
        return None

    first_year = max(min(firsts), ctx.today.year - OTD_MAX_YEARS)
    profile = UserProfile.objects.filter(user=ctx.user).first()
    daily = ctx.daily_km()
    years = []
    for back in range(1, ctx.today.year - first_year + 1):
        d = _same_day_years_back(ctx.today, back)
        start, end = _local_midnight(d), _local_midnight(d + timedelta(days=1))
        qs = Location.objects.filter(device_id__in=dev_ids, timestamp__gte=start, timestamp__lt=end) \
            if dev_ids else Location.objects.none()
        n = qs.count()
        entry = (JournalEntry.objects.filter(user=ctx.user, date=d)
                 .values('title', 'quick_note', 'body', 'mood').first())
        if not n and not entry:
            continue
        km = None
        if n:
            km = daily.get(d.isoformat())
            if km is None and not daily:
                km = _compute_distance_from_qs(qs)['total_km']
            if km is None:
                km = 0.0
        cities = []
        if n:
            for r in (qs.exclude(city='').order_by().values('city', 'state')
                      .annotate(c=Count('id')).order_by('-c')[:3]):
                label = _place_label(r['city'], r['state'])
                if label:
                    cities.append(label)
        journal = None
        if entry:
            text = entry['quick_note'] or entry['body'] or ''
            text = ' '.join(text.split())
            journal = {
                'title': (entry['title'] or '').strip()[:300],
                'excerpt': (text[:177] + '…') if len(text) > 180 else text,
                'mood': (entry['mood'] or '').strip()[:20],
            }
        recap = None
        if profile is not None:
            row = year_ago.saved_line(profile, d)
            if row is not None:
                recap = {'headline': row.headline, 'summary': row.summary}
        years.append({
            'year': d.year,
            'date': d.isoformat(),
            'years_ago': back,
            'distance_km': _num(km),
            'point_count': n,
            'cities': cities,
            'journal': journal,
            'recap': recap,
            'map_url': f"{reverse('tracker:map')}?date={d.isoformat()}",
        })
    result = {'date': ctx.today.isoformat(), 'years': years} if years else {}
    cache.set(key, result, OTD_CACHE_TTL_S)
    return result or None


def _badges(ctx):
    from .models import AchievementUnlock
    from . import achievement_defs as adefs
    out, seen = [], set()
    for u in (AchievementUnlock.objects.filter(user=ctx.user)
              .order_by('-unlocked_at', '-tier')[:MAX_BADGES * 6]):
        b = adefs.BADGE_BY_ID.get(u.badge_id)
        if b is None or b.id in seen:
            continue
        seen.add(b.id)
        tier = int(u.tier or 1)
        out.append({
            'id': b.id, 'name': b.name, 'icon': b.icon,
            'tier': tier,
            'tier_name': adefs.TIER_NAMES[tier - 1] if 1 <= tier <= len(adefs.TIER_NAMES) else '',
            'unlocked_at': _iso(u.unlocked_at),
        })
        if len(out) >= MAX_BADGES:
            break
    return out


def _section_stats(ctx):
    """Year to date and the last 12 months, all from the nightly snapshot."""
    badges = _badges(ctx)
    empty = {
        'pending': True, 'computed_at': None, 'ytd_km': None, 'ytd_active_days': None,
        'total_km': None, 'countries': None, 'countries_ytd': None, 'cities_ytd': None,
        'streak_days': None, 'months': [], 'badges': badges,
    }
    snap = ctx.snapshot
    if not snap:
        return empty
    stats = snap['stats_json']
    yearly = snap['yearly_json']
    this_year = yearly.get('this_year') if isinstance(yearly.get('this_year'), dict) else {}
    daily = ctx.daily_km()

    prefix = f'{ctx.today.year}-'
    ytd = [km for day, km in daily.items() if day.startswith(prefix)]

    y, m = ctx.today.year, ctx.today.month
    keys = []
    for _ in range(12):
        keys.append(f'{y:04d}-{m:02d}')
        m -= 1
        if m == 0:
            y, m = y - 1, 12
    keys.reverse()
    sums = {k: 0.0 for k in keys}
    for day, km in daily.items():
        k = day[:7]
        if k in sums:
            sums[k] += km
    months = [{'month': k, 'km': round(sums[k], 1)} for k in keys]

    active = {day for day, km in daily.items() if km >= STREAK_MIN_KM}
    cursor = ctx.today if ctx.today.isoformat() in active else ctx.today - timedelta(days=1)
    streak = 0
    while cursor.isoformat() in active and streak < 3660:
        streak += 1
        cursor -= timedelta(days=1)

    dist = stats.get('distance') if isinstance(stats.get('distance'), dict) else {}
    return {
        'pending': False,
        'computed_at': _iso(snap['computed_at']),
        'ytd_km': round(sum(ytd), 1),
        'ytd_active_days': sum(1 for km in ytd if km >= STREAK_MIN_KM),
        'total_km': _num(dist.get('total_km'), 1),
        'countries': stats.get('countries') if isinstance(stats.get('countries'), int) else None,
        'countries_ytd': this_year.get('countries') if isinstance(this_year.get('countries'), int) else None,
        'cities_ytd': this_year.get('cities') if isinstance(this_year.get('cities'), int) else None,
        'streak_days': streak,
        'months': months,
        'badges': badges,
    }


def _section_health(ctx):
    """Today's steps / distance / active calories and the last 7 days of steps,
    one source per day and metric (the Health page's rule). None when the
    account has never synced health data or imported a workout."""
    from .models import Activity, HealthSample, HealthWorkout
    from .views import _health_daily_totals, _health_preferred

    has_samples = HealthSample.objects.filter(user=ctx.user).exists()
    has_workouts = HealthWorkout.objects.filter(user=ctx.user).exists()
    if not has_samples and not has_workouts:
        return None

    delta = timedelta(minutes=ctx.tz_offset)
    client_today = (ctx.now.astimezone(dt_timezone.utc).replace(tzinfo=None) - delta).date()
    first_day = client_today - timedelta(days=6)
    # Local midnight of the first day, expressed in UTC the same way
    # _health_daily_totals buckets (start_time - tz_offset).
    start = datetime.combine(first_day, datetime.min.time()).replace(tzinfo=dt_timezone.utc) + delta

    totals = {}
    if has_samples:
        totals, _chosen = _health_daily_totals(ctx.user, start, None, ctx.tz_offset, _health_preferred(ctx.user))

    def val(day, kind):
        v = totals.get((day, kind))
        return round(v) if isinstance(v, (int, float)) else None

    today_key = client_today.isoformat()
    week = []
    for i in range(7):
        d = (first_day + timedelta(days=i)).isoformat()
        week.append({'date': d, 'steps': val(d, 'steps') or 0})

    last_sync = None
    if has_samples:
        last_sync = (HealthSample.objects.filter(user=ctx.user).order_by('-start_time')
                     .values_list('end_time', flat=True).first())

    workout = None
    w = HealthWorkout.objects.filter(user=ctx.user).order_by('-start_time').first()
    a = Activity.objects.filter(user=ctx.user).order_by('-start_time').first()
    if w is not None and (a is None or w.start_time >= a.start_time):
        local_day = (w.start_time.astimezone(dt_timezone.utc).replace(tzinfo=None) - delta).date()
        workout = {
            'title': w.title or (w.exercise_slug or 'workout').replace('_', ' ').capitalize(),
            'slug': w.exercise_slug or 'workout',
            'start': _iso(w.start_time),
            'duration_s': w.duration_s,
            'distance_m': _num(w.distance_m, 0),
            'calories_kcal': _num(w.calories_kcal, 0),
            'url': f"{reverse('tracker:health')}?day={local_day.isoformat()}",
        }
    elif a is not None:
        workout = {
            'title': a.title or a.get_kind_display(),
            'slug': a.kind,
            'start': _iso(a.start_time),
            'duration_s': a.elapsed_seconds or int(max(0, (a.end_time - a.start_time).total_seconds())),
            'distance_m': _num(a.distance_km * 1000.0 if a.distance_km else None, 0),
            'calories_kcal': None,
            'url': reverse('tracker:activity_detail', args=[a.id]),
        }

    return {
        'today': {
            'date': today_key,
            'steps': val(today_key, 'steps'),
            'distance_m': val(today_key, 'distance'),
            'active_kcal': val(today_key, 'calories_active'),
        },
        'week': week,
        'last_sync': _iso(last_sync),
        'last_workout': workout,
    }


def _section_devices(ctx):
    out = []
    for d, fix in ctx.devices[:MAX_DEVICES]:
        battery = fix.get('battery') if fix else None
        out.append({
            'id': d.id,
            'name': d.name or d.device_id,
            'last_seen': _iso(fix['timestamp']) if fix else None,
            'battery': int(round(battery)) if isinstance(battery, (int, float)) and 0 <= battery <= 100 else None,
            'place': _place_label(fix.get('city'), fix.get('state')) if fix else '',
        })
    return out


def _section_family(ctx):
    """Circle members the viewer may already see on the Family map.

    Stricter than family_locations_api on one point: the viewer's own
    membership must be accepted (an invite alone shows nothing)."""
    from .models import Device, FamilyMemberPlaceState, FamilyMembership

    circles = dict(FamilyMembership.objects
                   .filter(user=ctx.user, accepted_at__isnull=False)
                   .values_list('circle_id', 'circle__name'))
    if not circles:
        return []
    by_user = {}
    for m in (FamilyMembership.objects
              .filter(circle_id__in=list(circles), accepted_at__isnull=False, share_location=True)
              .exclude(user=ctx.user).select_related('user').order_by('user__username')):
        entry = by_user.setdefault(m.user_id, {'user': m.user, 'circle_ids': []})
        entry['circle_ids'].append(m.circle_id)

    out = []
    for uid, info in list(by_user.items())[:MAX_FAMILY]:
        member = info['user']
        best = None
        for pk in Device.objects.filter(user_id=uid).values_list('id', flat=True):
            fix = _latest_fix(pk)
            if fix and (best is None or fix['timestamp'] > best['timestamp']):
                best = fix
        inside = (FamilyMemberPlaceState.objects
                  .filter(user_id=uid, is_inside=True, place__circle_id__in=info['circle_ids'])
                  .select_related('place').order_by('-updated_at').first())
        place = inside.place.name if inside else (_place_label(best.get('city'), best.get('state')) if best else '')
        battery = best.get('battery') if best else None
        out.append({
            'user_id': uid,
            'name': member.first_name or member.username,
            'circles': [circles[c] for c in info['circle_ids'] if c in circles],
            'last_seen': _iso(best['timestamp']) if best else None,
            'place': place,
            'at_place': bool(inside),
            'battery': int(round(battery)) if isinstance(battery, (int, float)) and 0 <= battery <= 100 else None,
        })
    return out


_SECTIONS = {
    'today': _section_today,
    'recent': _section_recent,
    'on_this_day': _section_on_this_day,
    'stats': _section_stats,
    'health': _section_health,
    'devices': _section_devices,
    'family': _section_family,
}


def _sync(ctx):
    latest = [fix['timestamp'] for _d, fix in ctx.devices if fix and fix.get('timestamp')]
    return {'last_point_at': _iso(max(latest)) if latest else None}


@login_required
@require_GET
def home_api(request):
    only = [s for s in (request.GET.get('only') or '').split(',') if s in _SECTIONS]
    try:
        ctx = _Ctx(request)
    except Exception:
        logger.exception('home: context failed')
        body = {name: {'error': True} for name in (only or _SECTIONS)}
        body.update({'sync': {'error': True}, 'generated_at': timezone.now().isoformat()})
        return JsonResponse(body)

    key = f'lx_home:v2:{ctx.user.id}:{ctx.today.isoformat()}:{ctx.tz_offset}'
    if not only:
        hit = cache.get(key)
        if hit is not None:
            return _respond(hit)

    out, failed = {}, False
    for name in (only or _SECTIONS):
        try:
            out[name] = _SECTIONS[name](ctx)
        except Exception:
            logger.exception('home: section %s failed for user %s', name, ctx.user.id)
            out[name] = {'error': True}
            failed = True
    try:
        out['sync'] = _sync(ctx)
    except Exception:
        logger.exception('home: sync status failed')
        out['sync'] = {'error': True}
        failed = True
    out['generated_at'] = ctx.now.isoformat()
    if not only and not failed:
        cache.set(key, out, CACHE_TTL_S)
    return _respond(out)


def _respond(body):
    resp = JsonResponse(body)
    resp['Cache-Control'] = 'private, no-store'
    return resp
