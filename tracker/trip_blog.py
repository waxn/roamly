"""Trip blog: the logic behind the share page — gate, live header, feed.

Views live in ``trip_blog_views.py``; this module holds what they share and
what the digest scheduler reuses, and touches no request beyond reading the
viewer cookie.

GPS is only ever a *reference* here. A viewer gets a city name and a fuzzed
spot per traveller, never a coordinate from the history — see ``fuzz_spot``.
"""
import hashlib
import hmac
import io
import math
import secrets
import uuid
from collections import Counter
from datetime import datetime, time as dt_time, timedelta

from django.conf import settings
from django.core.cache import cache
from django.core.files.uploadedfile import InMemoryUploadedFile
from django.db.models import Count, F, Q
from django.utils import timezone

VIEWER_COOKIE = 'rly_tv'
VIEWER_COOKIE_AGE = 2 * 365 * 86400

STATUS_TTL = 60
FEED_DAYS_PER_PAGE = 5
FEED_COMMENTS_INLINE = 3

# Spot fuzz. A traveller's dot is drawn up to FUZZ_TIERS[-1] metres from where
# they really are. The offset is a deterministic function of the stay, so a
# viewer polling for hours sees the same dot rather than a cloud of samples to
# average back to the true spot. The radius widens with time still (a long stay
# is the one worth hiding better — that is where people sleep).
FUZZ_TIERS = ((0, 500), (2 * 3600, 750), (6 * 3600, 1000))   # (seconds still, radius m)
FUZZ_NEW_STAY_M = 1500
FUZZ_ANCHOR_TTL = 14 * 86400
STALE_FIX_S = 12 * 3600

HOME_NIGHT_START_H, HOME_NIGHT_END_H = 0, 5
HOME_CELL = 500          # 1/500° ≈ 220 m, achievement_tasks._HOME_CELL
HOME_NIGHTS_BACK = 3

PHOTO_MAX_PX = 2048
PHOTO_THUMB_PX = 480


# ── viewer identity ─────────────────────────────────────────────────────────

def viewer_raw(request):
    """The browser's random viewer key (cookie), or None if it has none yet."""
    raw = request.COOKIES.get(VIEWER_COOKIE, '')
    return raw if (len(raw) == 32 and all(c in '0123456789abcdef' for c in raw)) else None


def new_viewer_raw():
    return secrets.token_hex(16)


def viewer_hash(raw):
    return hashlib.sha256(f"tv:{raw}".encode()).hexdigest()[:40] if raw else ''


def set_viewer_cookie(resp, raw):
    resp.set_cookie(VIEWER_COOKIE, raw, max_age=VIEWER_COOKIE_AGE, httponly=True,
                    samesite='Lax', secure=not settings.DEBUG)


# ── membership + gate ───────────────────────────────────────────────────────

def member_of(blog_id, user):
    from .models import TripBlogMember
    if not (user and user.is_authenticated):
        return None
    return (TripBlogMember.objects.filter(blog_id=blog_id, user=user, accepted_at__isnull=False)
            .select_related('blog').first())


def active_link(token):
    from .models import TripShareLink
    link = (TripShareLink.objects.filter(token=(token or '').strip())
            .select_related('blog').first())
    return link if (link and link.is_active) else None


def link_gate(request, link):
    """What this browser still needs to do before it may read the blog:

    'ok'    — a member, or a browser that already spent a use of this link
    'enter' — free to come in; spends a use (``consume_use``) on the way
    'pin'   — must enter the link's PIN first, which spends the use
    'full'  — every use is taken
    """
    if member_of(link.blog_id, request.user):
        return 'ok'
    vh = viewer_hash(viewer_raw(request))
    if vh and link.uses.filter(viewer_key=vh).exists():
        return 'ok'
    if link.max_uses is not None and link.use_count >= link.max_uses:
        return 'full'
    return 'pin' if link.has_pin else 'enter'


def consume_use(link, vh):
    """Spend one use of ``link`` on this browser. False when none are left.

    The conditional UPDATE is the whole guard: two browsers racing for the last
    use of a one-time link cannot both win it.
    """
    from .models import TripShareLink, TripShareUse
    if TripShareUse.objects.filter(link=link, viewer_key=vh).exists():
        return True
    q = TripShareLink.objects.filter(pk=link.pk)
    if link.max_uses is not None:
        q = q.filter(use_count__lt=link.max_uses)
    if not q.update(use_count=F('use_count') + 1):
        return False
    TripShareUse.objects.get_or_create(link=link, viewer_key=vh)
    return True


def record_visit(link, vh):
    """Bump last-seen for a browser, at most every 10 minutes."""
    from .models import TripShareUse
    try:
        if not cache.add(f"tripvisit:{link.id}:{vh}", 1, 600):
            return
    except Exception:
        pass
    TripShareUse.objects.filter(link=link, viewer_key=vh).update(
        last_at=timezone.now(), visits=F('visits') + 1)


# ── cache generation ────────────────────────────────────────────────────────

def gen(blog_id):
    return cache.get(f"tripblog_gen:{blog_id}") or 0


def bump(blog_id):
    key = f"tripblog_gen:{blog_id}"
    try:
        cache.add(key, 0, None)
        cache.incr(key)
    except Exception:
        cache.set(key, (cache.get(key) or 0) + 1, None)


# ── time + place ────────────────────────────────────────────────────────────

def blog_zone(blog):
    """The trip's local zone: where its owner's newest fix is."""
    from .tz_utils import timezone_for_user
    return timezone_for_user(blog.creator_id)


def today_local(blog):
    return timezone.localtime(timezone.now(), blog_zone(blog)).date()


def is_live(blog):
    return not blog.end_date or today_local(blog) <= blog.end_date


def zone_for_point(lat, lon):
    from zoneinfo import ZoneInfo
    from .tz_utils import zone_name_for_point
    try:
        name = zone_name_for_point(lat, lon)
        return ZoneInfo(name) if name else None
    except Exception:
        return None


def local_date_for(post_or_ts, lat=None, lon=None, fallback_tz=None):
    ts = post_or_ts
    tz = zone_for_point(lat, lon) if lat is not None and lon is not None else None
    return timezone.localtime(ts, tz or fallback_tz or timezone.get_current_timezone()).date()


def place_label(lat, lon):
    """{city, state, country, country_code, place_name} for a point, cached per
    ~1 km cell (the offline geocoder is cheap, but a status poll asks per member)."""
    key = f"tripgeo:{round(lat, 2)}:{round(lon, 2)}"
    hit = cache.get(key)
    if hit is not None:
        return hit
    from .offline_geocode import local_reverse_geocode
    try:
        res = local_reverse_geocode(lat, lon) or {}
    except Exception:
        res = {}
    out = {k: res.get(k) or '' for k in ('city', 'state', 'country', 'country_code', 'place_name')}
    cache.set(key, out, 7 * 86400)
    return out


def latest_fix(user_id):
    """Newest fix across a user's devices — one index seek per device, like
    alert_tasks.latest_fix — with a city label (geocoded now if the background
    labeller has not reached it yet)."""
    from .models import Device, Location
    best = None
    for did in Device.objects.filter(user_id=user_id).values_list('id', flat=True):
        row = (Location.objects.filter(device_id=did).exclude(flag='suspect')
               .order_by('-timestamp')
               .values('timestamp', 'latitude', 'longitude', 'city', 'country', 'country_code')
               .first())
        if row and row['latitude'] is not None and (best is None or row['timestamp'] > best['timestamp']):
            best = row
    if best and not best['city']:
        lab = place_label(best['latitude'], best['longitude'])
        best.update(city=lab['city'], country=lab['country'], country_code=lab['country_code'])
    return best


def _haversine_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = (math.sin((p2 - p1) / 2) ** 2
         + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2)
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def _offset(lat, lon, dist_m, bearing):
    dlat = dist_m * math.cos(bearing) / 111320.0
    dlon = dist_m * math.sin(bearing) / (111320.0 * max(0.05, math.cos(math.radians(lat))))
    return lat + dlat, lon + dlon


def fuzz_spot(blog_id, user_id, lat, lon, ts):
    """A stable, fuzzed (lat, lon, radius_m) for a traveller's live fix.

    A stay starts at the first fix of it (cached); a fix more than
    FUZZ_NEW_STAY_M from the stay's start begins a new one. Within a stay the
    dot is the person's position — snapped to a ~250 m grid so small moves do
    not show — moved by an HMAC-derived bearing and distance that stays the same
    until the stay ends or its radius tier widens. Polling for hours therefore
    yields the same dot, not a cloud of samples to average back to the truth,
    and the true position is always within the returned radius of the dot.
    """
    key = f"tripfuzz:{blog_id}:{user_id}"
    anchor = cache.get(key)
    now_s = ts.timestamp()
    if (not anchor or _haversine_m(anchor['lat'], anchor['lon'], lat, lon) > FUZZ_NEW_STAY_M
            or now_s < anchor['since']):
        anchor = {'lat': lat, 'lon': lon, 'since': now_s, 'nonce': secrets.token_hex(8)}
    cache.set(key, anchor, FUZZ_ANCHOR_TTL)

    still_s = max(0.0, now_s - anchor['since'])
    tier = 0
    for i, (after, _r) in enumerate(FUZZ_TIERS):
        if still_s >= after:
            tier = i
    radius = FUZZ_TIERS[tier][1]
    # Nonce-keyed, not coordinate-keyed: the anchor coordinates are the secret.
    digest = hmac.new(settings.SECRET_KEY.encode(),
                      f"tripfuzz:{blog_id}:{user_id}:{anchor['nonce']}:{tier}".encode(),
                      hashlib.sha256).digest()
    bearing = int.from_bytes(digest[:4], 'big') / 2 ** 32 * 2 * math.pi
    # ≤ 0.6 r from the grid point, plus ≤ ~200 m of grid snap, stays inside r.
    frac = 0.35 + 0.25 * int.from_bytes(digest[4:8], 'big') / 2 ** 32
    glat, glon = round(lat * 400) / 400, round(lon * 400) / 400    # ~250 m grid
    flat, flon = _offset(glat, glon, frac * radius, bearing)
    return round(flat, 4), round(flon, 4), radius


# ── status (header + map) ───────────────────────────────────────────────────

def _accepted_members(blog):
    from .models import TripBlogMember
    return list(TripBlogMember.objects.filter(blog=blog, accepted_at__isnull=False)
                .select_related('user').order_by('joined_at'))


def display_name(user):
    return (user.first_name or user.username).strip()


def home_city(blog, members):
    """Where the family slept: the ~220 m cell with the most fixes between
    midnight and 5 am local, over the most recent night that has any (up to
    HOME_NIGHTS_BACK nights ago). Returns a place label dict or None."""
    from .models import Location
    tz = blog_zone(blog)
    user_ids = [m.user_id for m in members if m.share_location]
    if not user_ids:
        return None
    today = today_local(blog)
    for back in range(0, HOME_NIGHTS_BACK):
        day = today - timedelta(days=back)
        start = timezone.make_aware(datetime.combine(day, dt_time(HOME_NIGHT_START_H)), tz)
        end = timezone.make_aware(datetime.combine(day, dt_time(HOME_NIGHT_END_H)), tz)
        counts = Counter()
        labels = {}
        rows = (Location.objects.filter(device__user_id__in=user_ids,
                                        timestamp__gte=start, timestamp__lt=end)
                .exclude(flag='suspect')
                .values_list('latitude', 'longitude', 'city', 'country', 'country_code')
                .order_by()[:20000])
        for lat, lon, city, country, cc in rows:
            cell = (round(lat * HOME_CELL), round(lon * HOME_CELL))
            counts[cell] += 1
            if city and cell not in labels:
                labels[cell] = {'city': city, 'country': country, 'country_code': cc}
        if not counts:
            continue
        cell = counts.most_common(1)[0][0]
        lab = labels.get(cell) or place_label(cell[0] / HOME_CELL, cell[1] / HOME_CELL)
        if lab.get('city'):
            return {'city': lab['city'], 'country': lab.get('country', ''),
                    'country_code': lab.get('country_code', ''), 'night': day.isoformat()}
    return None


def _group_line(people):
    """[{name, city}] → "Everyone in Kyoto" / "Linus, Mom in Kyoto · Dad in Osaka"."""
    by_city = {}
    for p in people:
        by_city.setdefault(p['city'] or 'Unknown', []).append(p['name'])
    if len(by_city) == 1 and len(people) > 1:
        return f"Everyone in {next(iter(by_city))}"
    parts = []
    for city, names in sorted(by_city.items(), key=lambda kv: -len(kv[1])):
        parts.append(f"{', '.join(names)} in {city}")
    return ' · '.join(parts)


def _visited_country_codes(blog, members):
    from .models import Location
    user_ids = [m.user_id for m in members if m.share_location]
    if not user_ids:
        return []
    key = f"tripcc:{blog.id}:{gen(blog.id)}:{today_local(blog)}"
    hit = cache.get(key)
    if hit is not None:
        return hit
    tz = blog_zone(blog)
    start = timezone.make_aware(datetime.combine(blog.start_date, dt_time.min), tz)
    q = Location.objects.filter(device__user_id__in=user_ids, timestamp__gte=start)
    if blog.end_date:
        q = q.filter(timestamp__lt=timezone.make_aware(
            datetime.combine(blog.end_date + timedelta(days=1), dt_time.min), tz))
    codes = sorted({(c or '').upper() for c in
                    q.exclude(country_code='').values_list('country_code', flat=True)
                    .order_by().distinct() if c})
    from .models import TripPost
    codes = sorted(set(codes) | {c.upper() for c in TripPost.objects.filter(
        blog=blog, published=True).exclude(country_code='')
        .values_list('country_code', flat=True).distinct() if c})
    cache.set(key, codes, 3600)
    return codes


def status_payload(blog):
    """Header + map data. Cached STATUS_TTL per blog; never contains a raw fix."""
    key = f"tripstatus:{blog.id}:{gen(blog.id)}"
    hit = cache.get(key)
    if hit is not None:
        return hit
    from .models import TripPost
    members = _accepted_members(blog)
    live = is_live(blog)
    now = timezone.now()
    people = []
    if live:
        for m in members:
            if not m.share_location:
                continue
            fix = latest_fix(m.user_id)
            if not fix:
                continue
            age = (now - fix['timestamp']).total_seconds()
            flat, flon, radius = fuzz_spot(blog.id, m.user_id, fix['latitude'],
                                           fix['longitude'], fix['timestamp'])
            people.append({
                'id': m.user_id, 'name': display_name(m.user),
                'city': fix['city'] or '', 'country': fix['country'] or '',
                'seen': fix['timestamp'].isoformat(), 'stale': age > STALE_FIX_S,
                'spot': {'lat': flat, 'lon': flon, 'radius_m': radius},
            })
    home = home_city(blog, members) if live else None
    fresh = [p for p in people if not p['stale']]

    posts = TripPost.objects.filter(blog=blog, published=True)
    pins = [{'id': p['id'], 'lat': round(p['latitude'], 5), 'lon': round(p['longitude'], 5),
             'date': p['local_date'].isoformat() if p['local_date'] else None,
             'city': p['city']}
            for p in posts.filter(latitude__isnull=False)
            .values('id', 'latitude', 'longitude', 'local_date', 'city')
            .order_by('taken_at')[:2000]]
    cities = {(p['city'], p['country_code']) for p in posts.exclude(city='')
              .values('city', 'country_code').order_by().distinct()}
    today = today_local(blog)
    last_day = min(today, blog.end_date) if blog.end_date else today
    day_n = max(0, (last_day - blog.start_date).days + 1) if last_day >= blog.start_date else 0
    codes = _visited_country_codes(blog, members)
    out = {
        'blog': {'id': blog.id, 'name': blog.name, 'subtitle': blog.subtitle,
                 'start_date': blog.start_date.isoformat(),
                 'end_date': blog.end_date.isoformat() if blog.end_date else None,
                 'cover': blog.cover_image.url if blog.cover_image else None,
                 'live': live, 'today': today.isoformat()},
        'home': home,
        'people': people,
        'together': _group_line(fresh) if fresh else '',
        'members': [{'id': m.user_id, 'name': display_name(m.user)} for m in members],
        'pins': pins,
        'countries': codes,
        'stats': {'day': day_n, 'countries': len(codes),
                  'cities': len(cities), 'posts': posts.count(),
                  'upcoming': today < blog.start_date},
    }
    cache.set(key, out, STATUS_TTL)
    return out


# ── feed ────────────────────────────────────────────────────────────────────

def _avatar(user):
    try:
        prof = user.profile
        if prof.profile_picture_thumbnail:
            return prof.profile_picture_thumbnail.url
    except Exception:
        pass
    return None


def media_payload(m):
    if m.media_type == 'video':
        return {'id': m.id, 'type': 'video', 'status': m.status,
                'url': m.video.url if (m.video and m.status in ('ready', 'failed')) else None,
                'poster': m.poster.url if m.poster else None,
                'w': m.width, 'h': m.height, 'duration': m.duration_s}
    return {'id': m.id, 'type': 'image', 'status': 'ready',
            'url': m.image.url if m.image else None,
            'thumb': m.thumbnail.url if m.thumbnail else (m.image.url if m.image else None),
            'w': m.width, 'h': m.height}


def comment_payload(c, viewer=None):
    """``viewer`` = {'user': User|None, 'vh': hash, 'member': bool}."""
    name = display_name(c.member) if c.member_id and c.member else (c.guest_name or 'Guest')
    mine = bool(viewer and ((c.member_id and viewer.get('user') and c.member_id == viewer['user'].id)
                            or (c.viewer_key and c.viewer_key == viewer.get('vh'))))
    return {'id': c.id, 'name': name, 'traveller': bool(c.member_id), 'text': c.text,
            'at': c.created_at.isoformat(),
            'can_delete': bool(mine or (viewer and viewer.get('member')))}


def reaction_key(viewer):
    if viewer and viewer.get('user') and viewer.get('member'):
        return f"u{viewer['user'].id}"
    return (viewer or {}).get('vh') or ''


def post_payload(p, viewer=None, comments=None, reactions=None, mine_reacts=None):
    return {
        'id': p.id,
        'author': {'id': p.author_id, 'name': display_name(p.author), 'avatar': _avatar(p.author)},
        'text': p.text,
        'taken_at': p.taken_at.isoformat(),
        'date': p.local_date.isoformat() if p.local_date else None,
        'place': ({'lat': p.latitude, 'lon': p.longitude, 'city': p.city,
                   'country': p.country, 'name': p.place_name}
                  if p.has_location else None),
        'media': [media_payload(m) for m in p.media.all()],
        'comments': [comment_payload(c, viewer) for c in (comments or [])],
        'comment_count': getattr(p, 'n_comments', None) or 0,
        'reactions': reactions or {},
        'my_reactions': sorted(mine_reacts or []),
        'published': p.published,
        'mine': bool(viewer and viewer.get('user') and viewer['user'].id == p.author_id),
        'edited': bool(p.edited_at),
    }


def posts_payload(posts, viewer):
    """Serialise a list of posts with their inline comments and reactions in a
    fixed number of queries, whatever the list length."""
    from .models import TripComment, TripReaction
    ids = [p.id for p in posts]
    comments = {}
    if ids:
        for c in (TripComment.objects.filter(post_id__in=ids).select_related('member')
                  .order_by('-created_at')):
            lst = comments.setdefault(c.post_id, [])
            if len(lst) < FEED_COMMENTS_INLINE:
                lst.append(c)
    reacts, mine = {}, {}
    rk = reaction_key(viewer)
    if ids:
        for pid, emoji, n in (TripReaction.objects.filter(post_id__in=ids)
                              .values_list('post_id', 'emoji').annotate(n=Count('id')).order_by()):
            reacts.setdefault(pid, {})[emoji] = n
        if rk:
            for pid, emoji in TripReaction.objects.filter(post_id__in=ids, viewer_key=rk) \
                    .values_list('post_id', 'emoji'):
                mine.setdefault(pid, set()).add(emoji)
    return [post_payload(p, viewer, list(reversed(comments.get(p.id, []))),
                         reacts.get(p.id), mine.get(p.id)) for p in posts]


def feed_payload(blog, viewer, before=None, include_drafts_for=None):
    """Up to FEED_DAYS_PER_PAGE days of posts, newest day first.

    Returns {days:[{date, day, city, posts:[…]}], next_before}. Drafts are
    only ever included for their own author (``include_drafts_for``).
    """
    from .models import TripPost
    base = TripPost.objects.filter(blog=blog)
    vis = Q(published=True)
    if include_drafts_for is not None:
        vis |= Q(author=include_drafts_for)
    base = base.filter(vis)
    dq = base.exclude(local_date__isnull=True)
    if before:
        dq = dq.filter(local_date__lt=before)
    dates = list(dq.order_by('-local_date').values_list('local_date', flat=True).distinct()
                 [:FEED_DAYS_PER_PAGE + 1])
    more = len(dates) > FEED_DAYS_PER_PAGE
    dates = dates[:FEED_DAYS_PER_PAGE]
    posts = []
    if dates:
        posts = list(base.filter(local_date__in=dates)
                     .select_related('author', 'author__profile')
                     .prefetch_related('media')
                     .annotate(n_comments=Count('comments', distinct=True))
                     .order_by('-local_date', '-taken_at', '-id'))
    serial = posts_payload(posts, viewer)
    days = []
    for d in dates:
        dp = [s for s, p in zip(serial, posts) if p.local_date == d]
        cities = Counter(s['place']['city'] for s in dp if s['place'] and s['place']['city'])
        days.append({'date': d.isoformat(),
                     'day': (d - blog.start_date).days + 1,
                     'city': cities.most_common(1)[0][0] if cities else '',
                     'posts': dp})
    return {'days': days, 'next_before': dates[-1].isoformat() if (more and dates) else None}


# ── photos ──────────────────────────────────────────────────────────────────

_EXIF_GPS_IFD = 0x8825
_EXIF_IFD = 0x8769
_TAG_DATETIME_ORIGINAL = 0x9003
_TAG_OFFSET_TIME_ORIGINAL = 0x9011
_TAG_DATETIME = 0x0132


def _rat(v):
    try:
        return float(v)
    except (TypeError, ValueError, ZeroDivisionError):
        try:
            return v[0] / v[1]
        except Exception:
            return None


def _dms(vals, ref):
    try:
        d, m, s = (_rat(x) for x in vals)
        deg = d + m / 60.0 + s / 3600.0
    except Exception:
        return None
    if ref in ('S', 'W', b'S', b'W'):
        deg = -deg
    return deg


def read_exif(img):
    """{lat, lon, taken_at} from an opened PIL image's EXIF (each may be None).

    ``taken_at`` is aware when the camera wrote an offset (OffsetTimeOriginal),
    else naive local wall-clock time, which the caller resolves in the zone of
    the photo's location.
    """
    out = {'lat': None, 'lon': None, 'taken_at': None, 'naive': False}
    try:
        exif = img.getexif()
    except Exception:
        return out
    if not exif:
        return out
    try:
        gps = exif.get_ifd(_EXIF_GPS_IFD) or {}
        lat = _dms(gps.get(2), gps.get(1)) if gps.get(2) else None
        lon = _dms(gps.get(4), gps.get(3)) if gps.get(4) else None
        if (lat is not None and lon is not None and -90 <= lat <= 90 and -180 <= lon <= 180
                and not (abs(lat) < 1e-6 and abs(lon) < 1e-6)):
            out['lat'], out['lon'] = round(lat, 6), round(lon, 6)
    except Exception:
        pass
    try:
        sub = exif.get_ifd(_EXIF_IFD) or {}
        raw = sub.get(_TAG_DATETIME_ORIGINAL) or exif.get(_TAG_DATETIME)
        if raw:
            dt = datetime.strptime(str(raw).strip()[:19], '%Y:%m:%d %H:%M:%S')
            off = str(sub.get(_TAG_OFFSET_TIME_ORIGINAL) or '').strip()
            if len(off) == 6 and off[0] in '+-' and off[3] == ':':
                sign = 1 if off[0] == '+' else -1
                from datetime import timezone as dt_tz
                tz = dt_tz(sign * timedelta(hours=int(off[1:3]), minutes=int(off[4:6])))
                out['taken_at'] = dt.replace(tzinfo=tz)
            else:
                out['taken_at'] = dt
                out['naive'] = True
    except Exception:
        pass
    return out


def resolve_exif_time(exif):
    """EXIF time → aware datetime (naive times read in the photo's own zone)."""
    t = exif.get('taken_at')
    if t is None:
        return None
    if exif.get('naive'):
        tz = zone_for_point(exif['lat'], exif['lon']) if exif.get('lat') is not None else None
        if tz is None:
            return None        # no way to know which wall clock it was
        try:
            return timezone.make_aware(t, tz)
        except Exception:
            return None
    return t


def process_photo(upload):
    """Read EXIF from the raw upload, then orient, resize and re-encode.

    Returns (full_file, thumb_file, width, height, exif). Re-encoding drops all
    metadata, so the stored photo carries no GPS — the post's own location (which
    the author confirms) is the only place that ends up being published.
    Raises image_utils.ImageTooLarge / UnsupportedImage.
    """
    from PIL import Image, ImageOps, UnidentifiedImageError
    from .image_utils import ALLOWED_FORMATS, ImageTooLarge, UnsupportedImage
    try:
        img = Image.open(upload, formats=ALLOWED_FORMATS)
        img.load()
    except (Image.DecompressionBombError, Image.DecompressionBombWarning) as exc:
        raise ImageTooLarge('That image is too large to process.') from exc
    except UnidentifiedImageError as exc:
        raise UnsupportedImage('Unsupported image format.') from exc
    exif = read_exif(img)
    try:
        img = ImageOps.exif_transpose(img)
    except Exception:
        pass
    img = img.convert('RGB')
    if max(img.size) > PHOTO_MAX_PX:
        img.thumbnail((PHOTO_MAX_PX, PHOTO_MAX_PX), Image.LANCZOS)
    w, h = img.size
    uid = uuid.uuid4().hex[:16]

    def _file(im, name, q):
        buf = io.BytesIO()
        im.save(buf, format='JPEG', quality=q, optimize=True, progressive=True)
        buf.seek(0)
        return InMemoryUploadedFile(buf, 'ImageField', name, 'image/jpeg', buf.getbuffer().nbytes, None)

    thumb = img.copy()
    thumb.thumbnail((PHOTO_THUMB_PX * 2, PHOTO_THUMB_PX * 2), Image.LANCZOS)
    return _file(img, f'{uid}.jpg', 85), _file(thumb, f'{uid}_t.jpg', 78), w, h, exif
