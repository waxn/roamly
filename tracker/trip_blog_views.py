"""Trip blog views — member management + posting, and the public share page.

See ``trip_blog.py`` for the shared logic and the CLAUDE.md "Trip blog" entry
for the design. Two audiences hit the same data endpoints:

* **members** (travellers, signed in): ``/api/tripblog/<id>/…``
* **viewers** (anyone with a share link): ``/api/tripblog/v/<token>/…``

``_audience`` resolves either into ``(blog, link, viewer)`` so the read and
comment/react handlers are written once.
"""
import json
import os
import re
import secrets
import uuid
from types import SimpleNamespace
from datetime import date, timedelta

from django.conf import settings
from django.contrib.auth.decorators import login_required
from django.contrib.auth.hashers import check_password, make_password
from django.core.cache import cache
from django.db import transaction
from django.db.models import Count
from django.http import Http404, JsonResponse
from django.shortcuts import get_object_or_404, redirect, render
from django.urls import reverse
from django.utils import timezone
from django.utils.dateparse import parse_datetime
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_GET, require_http_methods, require_POST

from . import trip_blog as tb
from . import video_tasks
from .models import (TripBlog, TripBlogMember, TripComment, TripPost, TripPostMedia,
                     TripReaction, TripShareLink, TripShareUse, TripSubscriber)
from .views import (_client_ip, _looks_like_video, _rate_limited, _require_api_intent,
                    _require_json, _verify_turnstile, _video_ext)

_TEXT_MAX = 10000
_COMMENT_MAX = 2000
_NAME_MAX = 60
_PHOTO_MAX_BYTES = 30 * 1024 * 1024
_VIDEO_MAX_BYTES = 2 * 1024 * 1024 * 1024
_CHUNK_MAX_BYTES = 16 * 1024 * 1024
_MEDIA_PER_POST = 30
_LINK_MAX_DAYS = 3650


def _body(request):
    try:
        data = json.loads(request.body or b'{}')
        return data if isinstance(data, dict) else {}
    except (ValueError, TypeError):
        return None


def _bad(msg, status=400, **extra):
    return JsonResponse(dict({'error': msg}, **extra), status=status)


def _member(request, blog_id):
    m = tb.member_of(blog_id, request.user)
    if not m:
        raise Http404
    return m


def _set_featured(user, blog_id):
    with transaction.atomic():
        TripBlogMember.objects.filter(user=user, featured=True).exclude(blog_id=blog_id).update(featured=False)
        TripBlogMember.objects.filter(user=user, blog_id=blog_id).update(featured=True)
    cache.delete(f"tripfeatured:{user.id}")


def featured_for(user):
    """{id, name} of the user's featured trip blog, or None. Cached (context
    processor reads it on every page)."""
    if not (user and user.is_authenticated):
        return None
    key = f"tripfeatured:{user.id}"
    hit = cache.get(key)
    if hit is not None:
        return hit or None
    m = (TripBlogMember.objects.filter(user=user, featured=True, accepted_at__isnull=False)
         .select_related('blog').first())
    val = {'id': m.blog_id, 'name': m.blog.name} if m else {}
    cache.set(key, val, 3600)
    return val or None


def _parse_date(s):
    try:
        return date.fromisoformat(str(s)[:10]) if s else None
    except ValueError:
        return None


def _blog_payload(blog, me):
    members = list(blog.members.select_related('user').order_by('joined_at'))
    return {
        'id': blog.id, 'name': blog.name, 'subtitle': blog.subtitle,
        'start_date': blog.start_date.isoformat(),
        'end_date': blog.end_date.isoformat() if blog.end_date else None,
        'cover': blog.cover_image.url if blog.cover_image else None,
        'cover_thumb': blog.cover_thumbnail.url if blog.cover_thumbnail else None,
        'is_owner': me.role == 'owner',
        'me': {'share_location': me.share_location, 'featured': me.featured},
        'members': [{'id': m.user_id, 'name': tb.display_name(m.user), 'username': m.user.username,
                     'role': m.role, 'accepted': m.is_accepted, 'share_location': m.share_location}
                    for m in members],
        'invite_url': (f"{settings.SITE_URL}/trip/join/{blog.invite_token}/"
                       if blog.invite_token else None),
        'post_count': blog.posts.filter(published=True).count(),
        'subscriber_count': blog.subscribers.filter(confirmed_at__isnull=False,
                                                     unsubscribed_at__isnull=True).count(),
    }


# ── pages ───────────────────────────────────────────────────────────────────

def _page_cfg(mode, api, blog_id=None, **extra):
    """What trip-blog.js needs, rendered with json_script."""
    from django.templatetags.static import static
    from .context_processors import get_carto_tile_qs
    return dict({'mode': mode, 'api': api, 'blog_id': blog_id, 'emoji': TripReaction.EMOJI,
                 'carto_qs': get_carto_tile_qs(),
                 'countries_url': static('tracker/data/world-countries.json')}, **extra)


@login_required
def trip_blog_page(request, blog_id):
    m = _member(request, blog_id)
    from django.middleware.csrf import get_token
    return render(request, 'tracker/trip_blog.html', {
        'blog': m.blog, 'membership': m, 'mode': 'member',
        'cfg': _page_cfg('member', f"/api/tripblog/{blog_id}", blog_id, csrf=get_token(request)),
    })


@login_required
def trip_featured_view(request):
    f = featured_for(request.user)
    if f:
        return redirect('tracker:trip_blog', blog_id=f['id'])
    return redirect(reverse('tracker:adventures') + '#trip-blogs')


def trip_share_page(request, token):
    """The page a share link opens. Renders a shell only — the data, and the
    spending of a use, come from the page's own JS (``/enter/``), so a chat
    app's link-preview fetch cannot burn a one-time link."""
    link = tb.active_link(token)
    if not link:
        resp = render(request, 'tracker/trip_share.html', {'invalid': True, 'standalone': True}, status=404)
        resp['Cache-Control'] = 'private, no-store'
        return resp
    gate = tb.link_gate(request, link)
    use = None
    vh = tb.viewer_hash(tb.viewer_raw(request))
    if vh:
        use = TripShareUse.objects.filter(link=link, viewer_key=vh).first()
    resp = render(request, 'tracker/trip_share.html', {
        'standalone': True, 'gate': gate, 'token': link.token,
        'blog': link.blog, 'mode': 'viewer',
        'api_base': f"/api/tripblog/v/{link.token}",
        'email_enabled': _email_enabled(),
        'cfg': _page_cfg('viewer', f"/api/tripblog/v/{link.token}", link.blog_id,
                         guest_name=use.guest_name if use else ''),
    })
    resp['Cache-Control'] = 'private, no-store'
    return resp


def _email_enabled():
    from .email_utils import email_enabled
    return email_enabled()


def trip_join_view(request, token):
    """Invite landing. Joining is a POST from a page that says what it means,
    never a state-changing GET (the AdventureMember lesson)."""
    blog = TripBlog.objects.filter(invite_token=token).first()
    if not blog:
        return render(request, 'tracker/trip_join.html', {'invalid': True}, status=404)
    nxt = f"/trip/join/{token}/"
    if not request.user.is_authenticated:
        return render(request, 'tracker/trip_join.html', {'blog': blog, 'next': nxt})
    m = TripBlogMember.objects.filter(blog=blog, user=request.user).first()
    if m and m.is_accepted:
        return redirect('tracker:trip_blog', blog_id=blog.id)
    if request.method == 'POST':
        m, _ = TripBlogMember.objects.get_or_create(blog=blog, user=request.user,
                                                    defaults={'role': 'member'})
        m.accepted_at = timezone.now()
        m.share_location = str(request.POST.get('share_location', '')).lower() in ('1', 'true', 'on')
        m.save(update_fields=['accepted_at', 'share_location'])
        if not TripBlogMember.objects.filter(user=request.user, featured=True).exists():
            _set_featured(request.user, blog.id)
        tb.bump(blog.id)
        return redirect(reverse('tracker:trip_blog', args=[blog.id]) + '?flash=joined')
    return render(request, 'tracker/trip_join.html', {'blog': blog, 'confirm': True, 'next': nxt})


# ── blog management (members) ───────────────────────────────────────────────

@login_required
@csrf_exempt
@require_http_methods(["GET", "POST"])
def trip_blogs_api(request):
    if request.method == 'GET':
        rows = (TripBlogMember.objects.filter(user=request.user)
                .select_related('blog').order_by('-blog__start_date'))
        return JsonResponse({'blogs': [{
            'id': m.blog_id, 'name': m.blog.name, 'subtitle': m.blog.subtitle,
            'start_date': m.blog.start_date.isoformat(),
            'end_date': m.blog.end_date.isoformat() if m.blog.end_date else None,
            'cover': m.blog.cover_thumbnail.url if m.blog.cover_thumbnail else None,
            'accepted': m.is_accepted, 'featured': m.featured, 'role': m.role,
            'live': tb.is_live(m.blog),
        } for m in rows], 'featured': featured_for(request.user)})
    err = _require_api_intent(request)
    if err:
        return err
    data = _body(request)
    if data is None:
        return _bad('Invalid JSON')
    name = str(data.get('name') or '').strip()[:200]
    start = _parse_date(data.get('start_date')) or timezone.localdate()
    end = _parse_date(data.get('end_date'))
    if not name:
        return _bad('Name required')
    if end and end < start:
        return _bad('End date is before the start date')
    blog = TripBlog.objects.create(name=name, subtitle=str(data.get('subtitle') or '')[:300],
                                   creator=request.user, start_date=start, end_date=end,
                                   invite_token=secrets.token_hex(12))
    TripBlogMember.objects.create(blog=blog, user=request.user, role='owner',
                                  accepted_at=timezone.now(),
                                  share_location=bool(data.get('share_location', True)))
    if data.get('featured', True):
        _set_featured(request.user, blog.id)
    return JsonResponse({'id': blog.id, 'url': reverse('tracker:trip_blog', args=[blog.id])})


@login_required
@csrf_exempt
@require_http_methods(["GET", "POST"])
def trip_blog_api(request, blog_id):
    m = _member(request, blog_id)
    blog = m.blog
    if request.method == 'GET':
        return JsonResponse(_blog_payload(blog, m))
    err = _require_api_intent(request)
    if err:
        return err
    data = _body(request)
    if data is None:
        return _bad('Invalid JSON')
    fields = []
    if 'name' in data:
        name = str(data.get('name') or '').strip()[:200]
        if not name:
            return _bad('Name required')
        blog.name = name
        fields.append('name')
    if 'subtitle' in data:
        blog.subtitle = str(data.get('subtitle') or '')[:300]
        fields.append('subtitle')
    if 'start_date' in data:
        d = _parse_date(data.get('start_date'))
        if not d:
            return _bad('Invalid start date')
        blog.start_date = d
        fields.append('start_date')
    if 'end_date' in data:
        blog.end_date = _parse_date(data.get('end_date'))
        fields.append('end_date')
    if blog.end_date and blog.end_date < blog.start_date:
        return _bad('End date is before the start date')
    if fields:
        blog.save(update_fields=fields)
        for mem in blog.members.all():
            cache.delete(f"tripfeatured:{mem.user_id}")
    me_fields = []
    if 'share_location' in data:
        m.share_location = bool(data.get('share_location'))
        me_fields.append('share_location')
    if me_fields:
        m.save(update_fields=me_fields)
    if 'featured' in data:
        if data.get('featured'):
            _set_featured(request.user, blog.id)
        else:
            TripBlogMember.objects.filter(pk=m.pk).update(featured=False)
            cache.delete(f"tripfeatured:{request.user.id}")
        m.refresh_from_db()
    tb.bump(blog.id)
    return JsonResponse(_blog_payload(blog, m))


@login_required
@csrf_exempt
@require_POST
def trip_blog_delete_api(request, blog_id):
    err = _require_api_intent(request)
    if err:
        return err
    m = _member(request, blog_id)
    if m.role != 'owner':
        return _bad('Only the owner can delete this trip', 403)
    blog = m.blog
    user_ids = list(blog.members.values_list('user_id', flat=True))
    for media in TripPostMedia.objects.filter(post__blog=blog):
        video_tasks.delete_media_files(media)
    for f in (blog.cover_image, blog.cover_thumbnail):
        if f:
            f.delete(save=False)
    blog.delete()
    for uid in user_ids:
        cache.delete(f"tripfeatured:{uid}")
    return JsonResponse({'status': 'ok'})


@login_required
@csrf_exempt
@require_POST
def trip_blog_cover_api(request, blog_id):
    err = _require_api_intent(request)
    if err:
        return err
    m = _member(request, blog_id)
    up = request.FILES.get('image')
    blog = m.blog
    if request.POST.get('remove'):
        for f in (blog.cover_image, blog.cover_thumbnail):
            if f:
                f.delete(save=False)
        blog.cover_image = blog.cover_thumbnail = None
        blog.save(update_fields=['cover_image', 'cover_thumbnail'])
        tb.bump(blog.id)
        return JsonResponse({'cover': None})
    if not up or up.size > _PHOTO_MAX_BYTES:
        return _bad('Choose an image under 30 MB')
    try:
        full, thumb, _w, _h, _exif = tb.process_photo(up)
    except ValueError as exc:
        return _bad(str(exc))
    for f in (blog.cover_image, blog.cover_thumbnail):
        if f:
            f.delete(save=False)
    blog.cover_image, blog.cover_thumbnail = full, thumb
    blog.save(update_fields=['cover_image', 'cover_thumbnail'])
    tb.bump(blog.id)
    return JsonResponse({'cover': blog.cover_image.url})


@login_required
@csrf_exempt
@require_POST
def trip_blog_invite_api(request, blog_id):
    err = _require_api_intent(request)
    if err:
        return err
    m = _member(request, blog_id)
    data = _body(request) or {}
    blog = m.blog
    if data.get('rotate') or not blog.invite_token:
        blog.invite_token = secrets.token_hex(12)
        blog.save(update_fields=['invite_token'])
    return JsonResponse({'invite_url': f"{settings.SITE_URL}/trip/join/{blog.invite_token}/"})


@login_required
@csrf_exempt
@require_POST
def trip_blog_leave_api(request, blog_id):
    err = _require_api_intent(request)
    if err:
        return err
    m = _member(request, blog_id)
    if m.role == 'owner':
        return _bad('The owner cannot leave; delete the trip instead', 400)
    m.delete()
    cache.delete(f"tripfeatured:{request.user.id}")
    tb.bump(blog_id)
    return JsonResponse({'status': 'ok'})


@login_required
@csrf_exempt
@require_POST
def trip_blog_member_remove_api(request, blog_id, user_id):
    err = _require_api_intent(request)
    if err:
        return err
    m = _member(request, blog_id)
    if m.role != 'owner':
        return _bad('Only the owner can remove travellers', 403)
    if int(user_id) == request.user.id:
        return _bad('You cannot remove yourself')
    TripBlogMember.objects.filter(blog_id=blog_id, user_id=user_id).delete()
    cache.delete(f"tripfeatured:{user_id}")
    tb.bump(blog_id)
    return JsonResponse({'status': 'ok'})


@login_required
@require_GET
def trip_featured_api(request):
    return JsonResponse({'featured': featured_for(request.user)})


# ── share links (members) ───────────────────────────────────────────────────

def _link_payload(link):
    return {
        'id': link.id, 'label': link.label, 'url': f"{settings.SITE_URL}/t/{link.token}/",
        'expires_at': link.expires_at.isoformat() if link.expires_at else None,
        'max_uses': link.max_uses, 'use_count': link.use_count,
        'has_pin': link.has_pin, 'active': link.is_active,
        'revoked': bool(link.revoked_at), 'created_at': link.created_at.isoformat(),
    }


def _apply_link(link, data):
    if 'label' in data:
        link.label = str(data.get('label') or '')[:120]
    if 'expires_in_days' in data:
        v = data.get('expires_in_days')
        if v in (None, '', 'never', 0):
            link.expires_at = None
        else:
            try:
                days = max(1, min(int(v), _LINK_MAX_DAYS))
            except (TypeError, ValueError):
                return 'Invalid expiry'
            link.expires_at = timezone.now() + timedelta(days=days)
    if 'max_uses' in data:
        v = data.get('max_uses')
        if v in (None, '', 'any', 0):
            link.max_uses = None
        else:
            try:
                link.max_uses = max(1, min(int(v), 100000))
            except (TypeError, ValueError):
                return 'Invalid use limit'
    if 'pin' in data:
        pin = str(data.get('pin') or '').strip()
        if pin == '':
            link.pin_hash = ''
        elif re.fullmatch(r'\d{4}', pin):
            link.pin_hash = make_password(pin)
        else:
            return 'PIN must be exactly 4 digits.'
    return None


@login_required
@csrf_exempt
@require_http_methods(["GET", "POST"])
def trip_links_api(request, blog_id):
    m = _member(request, blog_id)
    if request.method == 'GET':
        return JsonResponse({'links': [_link_payload(link) for link in m.blog.share_links.all()]})
    err = _require_api_intent(request)
    if err:
        return err
    data = _body(request)
    if data is None:
        return _bad('Invalid JSON')
    link = TripShareLink(blog=m.blog, created_by=request.user)
    msg = _apply_link(link, data)
    if msg:
        return _bad(msg)
    link.save()
    return JsonResponse(_link_payload(link))


@login_required
@csrf_exempt
@require_POST
def trip_link_update_api(request, blog_id, link_id):
    err = _require_api_intent(request)
    if err:
        return err
    _member(request, blog_id)
    link = get_object_or_404(TripShareLink, pk=link_id, blog_id=blog_id)
    data = _body(request)
    if data is None:
        return _bad('Invalid JSON')
    if data.get('revoke'):
        link.revoked_at = link.revoked_at or timezone.now()
    elif data.get('restore'):
        link.revoked_at = None
    msg = _apply_link(link, data)
    if msg:
        return _bad(msg)
    link.save()
    return JsonResponse(_link_payload(link))


@login_required
@csrf_exempt
@require_POST
def trip_link_delete_api(request, blog_id, link_id):
    err = _require_api_intent(request)
    if err:
        return err
    _member(request, blog_id)
    TripShareLink.objects.filter(pk=link_id, blog_id=blog_id).delete()
    return JsonResponse({'status': 'ok'})


@login_required
@require_GET
def trip_views_api(request, blog_id):
    """Who viewed: every browser that opened a link, per link. Members only."""
    m = _member(request, blog_id)
    out = []
    for link in m.blog.share_links.all():
        uses = list(link.uses.all()[:500])
        out.append({'link': _link_payload(link), 'viewers': [{
            'id': u.id, 'name': u.guest_name or f"Viewer ·{u.viewer_key[:4]}",
            'named': bool(u.guest_name), 'first_at': u.first_at.isoformat(),
            'last_at': u.last_at.isoformat(), 'visits': u.visits,
        } for u in uses]})
    return JsonResponse({'links': out})


@login_required
@csrf_exempt
@require_POST
def trip_view_remove_api(request, blog_id, use_id):
    """Remove one browser's access (it would need the link + a free use again)."""
    err = _require_api_intent(request)
    if err:
        return err
    _member(request, blog_id)
    TripShareUse.objects.filter(pk=use_id, link__blog_id=blog_id).delete()
    return JsonResponse({'status': 'ok'})


# ── posts (members) ─────────────────────────────────────────────────────────

def _apply_location(post, data, author_tz):
    """Set location + labels + local date from ``data`` (lat/lon may be null)."""
    if 'lat' in data or 'lon' in data:
        lat, lon = data.get('lat'), data.get('lon')
        if lat in (None, '') or lon in (None, ''):
            post.latitude = post.longitude = None
            post.city = post.state = post.country = post.country_code = post.place_name = ''
        else:
            try:
                lat, lon = float(lat), float(lon)
            except (TypeError, ValueError):
                return 'Invalid location'
            if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                return 'Invalid location'
            post.latitude, post.longitude = round(lat, 6), round(lon, 6)
            lab = tb.place_label(lat, lon)
            post.city, post.state = lab['city'][:200], lab['state'][:200]
            post.country, post.country_code = lab['country'][:200], lab['country_code'][:2].upper()
            post.place_name = (str(data.get('place_name') or '') or lab['place_name'])[:200]
    if 'place_name' in data and post.has_location and data.get('place_name'):
        post.place_name = str(data['place_name'])[:200]
    if 'taken_at' in data and data.get('taken_at'):
        t = parse_datetime(str(data['taken_at']))
        if t is None:
            return 'Invalid time'
        if timezone.is_naive(t):
            t = timezone.make_aware(t, author_tz)
        post.taken_at = t
    post.local_date = tb.local_date_for(post.taken_at, post.latitude, post.longitude, author_tz)
    return None


def _own_post(request, blog_id, post_id):
    _member(request, blog_id)
    post = get_object_or_404(TripPost, pk=post_id, blog_id=blog_id)
    if post.author_id != request.user.id:
        raise Http404
    return post


def _post_out(post, request):
    viewer = {'user': request.user, 'vh': '', 'member': True}
    post = (TripPost.objects.filter(pk=post.pk).select_related('author', 'author__profile')
            .prefetch_related('media').annotate(n_comments=Count('comments')).first())
    return tb.posts_payload([post], viewer)[0]


@login_required
@csrf_exempt
@require_POST
def trip_post_create_api(request, blog_id):
    """Create a post (or a draft to attach media to). Idempotent on client_id."""
    err = _require_api_intent(request)
    if err:
        return err
    m = _member(request, blog_id)
    data = _body(request)
    if data is None:
        return _bad('Invalid JSON')
    cid = str(data.get('client_id') or '')[:64]
    if cid:
        existing = TripPost.objects.filter(author=request.user, client_id=cid).first()
        if existing:
            if existing.blog_id != m.blog_id:
                return _bad('client_id belongs to another trip', 409)
            return JsonResponse(_post_out(existing, request))
    from .tz_utils import timezone_for_user
    post = TripPost(blog=m.blog, author=request.user, client_id=cid,
                    text=str(data.get('text') or '')[:_TEXT_MAX],
                    published=bool(data.get('publish', True)))
    msg = _apply_location(post, data, timezone_for_user(request.user))
    if msg:
        return _bad(msg)
    if post.published:
        post.published_at = timezone.now()
    post.save()
    if post.published:
        tb.bump(m.blog_id)
    return JsonResponse(_post_out(post, request))


@login_required
@csrf_exempt
@require_POST
def trip_post_update_api(request, blog_id, post_id):
    err = _require_api_intent(request)
    if err:
        return err
    post = _own_post(request, blog_id, post_id)
    data = _body(request)
    if data is None:
        return _bad('Invalid JSON')
    from .tz_utils import timezone_for_user
    was_published = post.published
    if 'text' in data:
        post.text = str(data.get('text') or '')[:_TEXT_MAX]
    msg = _apply_location(post, data, timezone_for_user(request.user))
    if msg:
        return _bad(msg)
    if data.get('publish') and not was_published:
        post.published = True
        post.published_at = timezone.now()
        if not data.get('taken_at') and not post.media.exists():
            post.taken_at = timezone.now()
            post.local_date = tb.local_date_for(post.taken_at, post.latitude, post.longitude,
                                                timezone_for_user(request.user))
    if was_published:
        post.edited_at = timezone.now()
    post.save()
    tb.bump(blog_id)
    return JsonResponse(_post_out(post, request))


@login_required
@csrf_exempt
@require_POST
def trip_post_delete_api(request, blog_id, post_id):
    err = _require_api_intent(request)
    if err:
        return err
    post = _own_post(request, blog_id, post_id)
    for media in post.media.all():
        video_tasks.delete_media_files(media)
    post.delete()
    tb.bump(blog_id)
    return JsonResponse({'status': 'ok'})


def _exif_out(exif):
    t = tb.resolve_exif_time(exif)
    return {'lat': exif.get('lat'), 'lon': exif.get('lon'),
            'taken_at': t.isoformat() if t else None}


@login_required
@csrf_exempt
@require_POST
def trip_post_photo_api(request, blog_id, post_id):
    """Upload one photo to a post. Returns the media row plus what its EXIF said
    (location + time), for the composer to offer."""
    err = _require_api_intent(request)
    if err:
        return err
    post = _own_post(request, blog_id, post_id)
    up = request.FILES.get('file')
    if not up:
        return _bad('No file')
    if up.size > _PHOTO_MAX_BYTES:
        return _bad('Photos must be under 30 MB')
    if post.media.count() >= _MEDIA_PER_POST:
        return _bad(f'At most {_MEDIA_PER_POST} photos and videos per post')
    try:
        full, thumb, w, h, exif = tb.process_photo(up)
    except ValueError as exc:
        return _bad(str(exc))
    order = int(request.POST.get('order') or post.media.count())
    media = TripPostMedia.objects.create(post=post, media_type='image', image=full,
                                         thumbnail=thumb, width=w, height=h, order=order)
    if post.published:
        tb.bump(blog_id)
    return JsonResponse({'media': tb.media_payload(media), 'exif': _exif_out(exif)})


@login_required
@csrf_exempt
@require_POST
def trip_post_video_api(request, blog_id, post_id):
    """Chunked video upload, idempotent by chunk index.

    Chunk 0 (no ``media_id``) creates the row and returns its id; every later
    chunk names it. A chunk the server already has answers ``duplicate`` with
    ``next_chunk``, one from the future 409s with ``next_chunk`` — so a client
    that lost track simply resumes where the server is. Chunks exist because a
    Cloudflare tunnel refuses a request body over 100 MB, and phone video is
    routinely bigger.
    """
    err = _require_api_intent(request)
    if err:
        return err
    post = _own_post(request, blog_id, post_id)
    chunk = request.FILES.get('chunk')
    try:
        index = int(request.POST.get('index'))
        total = int(request.POST.get('total'))
    except (TypeError, ValueError):
        return _bad('index and total required')
    if not chunk or chunk.size > _CHUNK_MAX_BYTES or total < 1 or not (0 <= index < total):
        return _bad('Bad chunk')
    if total * _CHUNK_MAX_BYTES > _VIDEO_MAX_BYTES + _CHUNK_MAX_BYTES:
        return _bad('Videos must be under 2 GB')
    media_id = request.POST.get('media_id')
    if not media_id:
        if index != 0:
            return _bad('media_id required after the first chunk')
        ext = _video_ext(SimpleNamespace(name=request.POST.get('name') or ''))
        if not ext:
            return _bad('Unsupported video type')
        if not _looks_like_video(chunk):
            return _bad('That file does not look like a video')
        if post.media.count() >= _MEDIA_PER_POST:
            return _bad(f'At most {_MEDIA_PER_POST} photos and videos per post')
        media = TripPostMedia.objects.create(
            # The extension rides in upload_id: it is all that is needed of the
            # original name, and the name itself is never stored.
            post=post, media_type='video', status='uploading',
            upload_id=f"{uuid.uuid4().hex[:24]}{ext}", total_chunks=total,
            order=int(request.POST.get('order') or post.media.count()))
    else:
        media = get_object_or_404(TripPostMedia, pk=media_id, post=post, media_type='video')
        if media.status != 'uploading':
            return JsonResponse({'status': 'complete', 'media': tb.media_payload(media)})
        if media.total_chunks != total:
            return _bad('total does not match the upload', 409)
    if index < media.chunks_received:
        return JsonResponse({'status': 'duplicate', 'media_id': media.id,
                             'next_chunk': media.chunks_received})
    if index > media.chunks_received:
        return JsonResponse({'status': 'out_of_order', 'media_id': media.id,
                             'next_chunk': media.chunks_received}, status=409)
    path = video_tasks.chunk_path(media.upload_id)
    # Claim the index first so two retries of the same chunk cannot both append.
    if not TripPostMedia.objects.filter(pk=media.pk, chunks_received=index).update(
            chunks_received=index + 1):
        media.refresh_from_db()
        return JsonResponse({'status': 'duplicate', 'media_id': media.id,
                             'next_chunk': media.chunks_received})
    try:
        if index == 0 and os.path.exists(path):
            os.remove(path)
        with open(path, 'ab') as fh:
            for part in chunk.chunks():
                fh.write(part)
        if os.path.getsize(path) > _VIDEO_MAX_BYTES:
            raise ValueError('too big')
    except Exception:
        TripPostMedia.objects.filter(pk=media.pk).update(chunks_received=index)
        return _bad('Could not store that chunk', 500)
    if index + 1 < total:
        return JsonResponse({'status': 'ok', 'media_id': media.id, 'next_chunk': index + 1})

    ext = os.path.splitext(media.upload_id)[1] or '.mp4'
    rel = f"tripblog/videos/originals/{uuid.uuid4().hex}{ext}"
    dst = os.path.join(settings.MEDIA_ROOT, rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    os.replace(path, dst)
    media.video.name = rel
    media.status = 'pending'
    media.upload_id = ''
    media.save(update_fields=['video', 'status', 'upload_id'])
    video_tasks.kick()
    if post.published:
        tb.bump(blog_id)
    return JsonResponse({'status': 'complete', 'media': tb.media_payload(media)})


@login_required
@csrf_exempt
@require_POST
def trip_media_delete_api(request, blog_id, media_id):
    err = _require_api_intent(request)
    if err:
        return err
    _member(request, blog_id)
    media = get_object_or_404(TripPostMedia, pk=media_id, post__blog_id=blog_id,
                              post__author=request.user)
    video_tasks.delete_media_files(media)
    media.delete()
    tb.bump(blog_id)
    return JsonResponse({'status': 'ok'})


@login_required
@require_GET
def trip_my_location_api(request, blog_id):
    """The poster's latest fix, for the composer's location button."""
    _member(request, blog_id)
    fix = tb.latest_fix(request.user.id)
    if not fix:
        return JsonResponse({'location': None})
    age = (timezone.now() - fix['timestamp']).total_seconds()
    return JsonResponse({'location': {
        'lat': fix['latitude'], 'lon': fix['longitude'], 'city': fix['city'],
        'country': fix['country'], 'at': fix['timestamp'].isoformat(), 'age_s': int(age)}})


# ── shared read/write: members by id, viewers by token ──────────────────────

def _audience(request, blog_id=None, token=None):
    """(blog, link, viewer, error_response)."""
    if token is not None:
        link = tb.active_link(token)
        if not link:
            return None, None, None, _bad('This link is no longer active.', 404)
        gate = tb.link_gate(request, link)
        if gate != 'ok':
            return None, None, None, _bad('Locked', 403, gate=gate)
        member = tb.member_of(link.blog_id, request.user)
        vh = tb.viewer_hash(tb.viewer_raw(request))
        if vh and not member:
            tb.record_visit(link, vh)
        return link.blog, link, {'user': request.user if member else None, 'vh': vh,
                                 'member': bool(member)}, None
    if not request.user.is_authenticated:
        return None, None, None, _bad('Sign in', 401)
    m = tb.member_of(blog_id, request.user)
    if not m:
        return None, None, None, _bad('Not found', 404)
    return m.blog, None, {'user': request.user, 'vh': '', 'member': True}, None


def _throttled(request, scope, limit, window):
    return _rate_limited(request, scope, limit, window)


@require_GET
def trip_status_api(request, blog_id=None, token=None):
    if token is not None and _throttled(request, 'tripread', 600, 300):
        return _bad('Too many requests', 429)
    blog, _link, _viewer, err = _audience(request, blog_id, token)
    if err:
        return err
    resp = JsonResponse(tb.status_payload(blog))
    resp['Cache-Control'] = 'private, no-store'
    return resp


@require_GET
def trip_feed_api(request, blog_id=None, token=None):
    if token is not None and _throttled(request, 'tripread', 600, 300):
        return _bad('Too many requests', 429)
    blog, _link, viewer, err = _audience(request, blog_id, token)
    if err:
        return err
    before = _parse_date(request.GET.get('before'))
    resp = JsonResponse(tb.feed_payload(blog, viewer, before))
    resp['Cache-Control'] = 'private, no-store'
    return resp


@require_GET
def trip_comments_api(request, post_id, blog_id=None, token=None):
    blog, _link, viewer, err = _audience(request, blog_id, token)
    if err:
        return err
    post = get_object_or_404(TripPost, pk=post_id, blog=blog, published=True)
    rows = post.comments.select_related('member').order_by('created_at')[:500]
    return JsonResponse({'comments': [tb.comment_payload(c, viewer) for c in rows]})


@csrf_exempt
@require_POST
def trip_comment_api(request, blog_id=None, token=None):
    bad = _require_json(request)
    if bad:
        return bad
    blog, link, viewer, err = _audience(request, blog_id, token)
    if err:
        return err
    if _rate_limited(request, 'tripcomment', 20, 900):
        return _bad('Slow down a little — try again in a few minutes.', 429)
    data = _body(request)
    if data is None:
        return _bad('Invalid JSON')
    if data.get('website'):                       # honeypot
        return JsonResponse({'status': 'ok'})
    text = str(data.get('text') or '').strip()[:_COMMENT_MAX]
    if not text:
        return _bad('Write something first')
    try:
        post = TripPost.objects.get(pk=int(data.get('post')), blog=blog, published=True)
    except (TypeError, ValueError, TripPost.DoesNotExist):
        return _bad('Post not found', 404)
    if viewer['member']:
        c = TripComment.objects.create(post=post, member=viewer['user'], text=text)
    else:
        name = str(data.get('name') or '').strip()[:_NAME_MAX]
        if not name:
            return _bad('Add your name')
        c = TripComment.objects.create(post=post, guest_name=name, text=text,
                                       viewer_key=viewer['vh'])
        if link and viewer['vh']:
            TripShareUse.objects.filter(link=link, viewer_key=viewer['vh']).update(guest_name=name)
    return JsonResponse({'comment': tb.comment_payload(c, viewer)})


@csrf_exempt
@require_POST
def trip_comment_delete_api(request, comment_id, blog_id=None, token=None):
    bad = _require_json(request)
    if bad:
        return bad
    blog, _link, viewer, err = _audience(request, blog_id, token)
    if err:
        return err
    c = get_object_or_404(TripComment, pk=comment_id, post__blog=blog)
    if not tb.comment_payload(c, viewer)['can_delete']:
        return _bad('Not yours', 403)
    c.delete()
    return JsonResponse({'status': 'ok'})


@csrf_exempt
@require_POST
def trip_react_api(request, blog_id=None, token=None):
    """Toggle one reaction on a post for this viewer (or member)."""
    bad = _require_json(request)
    if bad:
        return bad
    blog, _link, viewer, err = _audience(request, blog_id, token)
    if err:
        return err
    if _rate_limited(request, 'tripreact', 120, 300):
        return _bad('Too many requests', 429)
    data = _body(request) or {}
    emoji = str(data.get('emoji') or '')
    if emoji not in TripReaction.EMOJI:
        return _bad('Unknown reaction')
    key = tb.reaction_key(viewer)
    if not key:
        return _bad('Reload the page and try again')
    try:
        post = TripPost.objects.get(pk=int(data.get('post')), blog=blog, published=True)
    except (TypeError, ValueError, TripPost.DoesNotExist):
        return _bad('Post not found', 404)
    row = TripReaction.objects.filter(post=post, viewer_key=key, emoji=emoji).first()
    if row:
        row.delete()
        on = False
    else:
        TripReaction.objects.get_or_create(post=post, viewer_key=key, emoji=emoji)
        on = True
    counts = dict(post.reactions.values_list('emoji').annotate(n=Count('id')).order_by())
    return JsonResponse({'on': on, 'reactions': counts})


# ── viewer-only: enter, unlock, name, subscribe ─────────────────────────────

def _grant(request, link):
    """Spend a use on this browser (minting its key if needed). Response or error."""
    raw = tb.viewer_raw(request) or tb.new_viewer_raw()
    if not tb.consume_use(link, tb.viewer_hash(raw)):
        return _bad('This link has been used the maximum number of times.', 403, gate='full')
    resp = JsonResponse({'status': 'ok'})
    tb.set_viewer_cookie(resp, raw)
    return resp


@csrf_exempt
@require_POST
def trip_enter_api(request, token):
    bad = _require_json(request)
    if bad:
        return bad
    link = tb.active_link(token)
    if not link:
        return _bad('This link is no longer active.', 404)
    gate = tb.link_gate(request, link)
    if gate == 'ok':
        return JsonResponse({'status': 'ok'})
    if gate == 'enter':
        return _grant(request, link)
    if gate == 'pin':
        return _bad('PIN required', 403, gate='pin')
    return _bad('This link has been used the maximum number of times.', 403, gate='full')


@csrf_exempt
@require_POST
def trip_unlock_api(request, token):
    """Check a link's PIN — LocationShare's share_unlock_api pattern: throttled
    per IP and per link, lockouts, honeypot, Turnstile when enabled."""
    bad = _require_json(request)
    if bad:
        return bad
    link = tb.active_link(token)
    if not link:
        return _bad('This link is no longer active.', 404)
    if _rate_limited(request, 'trip_unlock', 20, 900, identifier=f"triplink{link.id}"):
        return _bad('Too many attempts. Try again later.', 429)
    data = _body(request)
    if data is None:
        return _bad('Invalid JSON')
    if data.get('website'):
        return _bad('Incorrect PIN.', 403)
    gate = tb.link_gate(request, link)
    if gate == 'ok':
        return JsonResponse({'status': 'ok'})
    if gate == 'enter':
        return _grant(request, link)
    if gate == 'full':
        return _bad('This link has been used the maximum number of times.', 403, gate='full')
    ip_key = f"trippinfail:{link.id}:{_client_ip(request)}"
    all_key = f"trippinfail_all:{link.id}"
    if (cache.get(ip_key) or 0) >= 5 or (cache.get(all_key) or 0) >= 30:
        return _bad('Too many wrong PINs. Try again in a while.', 429)
    if not _verify_turnstile(request, allow_app=False, token=data.get('cf-turnstile-response', '')):
        return _bad('Please complete the verification.', 400, captcha=True)
    if not check_password(str(data.get('pin') or ''), link.pin_hash):
        for k, ttl in ((ip_key, 900), (all_key, 3600)):
            cache.add(k, 0, ttl)
            try:
                cache.incr(k)
            except ValueError:
                cache.set(k, 1, ttl)
        return _bad('Incorrect PIN.', 403)
    return _grant(request, link)


@csrf_exempt
@require_POST
def trip_name_api(request, token):
    bad = _require_json(request)
    if bad:
        return bad
    blog, link, viewer, err = _audience(request, token=token)
    if err:
        return err
    name = str((_body(request) or {}).get('name') or '').strip()[:_NAME_MAX]
    if viewer['vh']:
        TripShareUse.objects.filter(link=link, viewer_key=viewer['vh']).update(guest_name=name)
    return JsonResponse({'name': name})


@csrf_exempt
@require_POST
def trip_subscribe_api(request, token):
    """Ask for the daily digest. Sends a confirmation mail; nothing is sent to
    an address until its owner clicks it."""
    bad = _require_json(request)
    if bad:
        return bad
    blog, link, viewer, err = _audience(request, token=token)
    if err:
        return err
    if not _email_enabled():
        return _bad('Email is not set up on this server.', 400)
    data = _body(request) or {}
    if data.get('website'):
        return JsonResponse({'status': 'sent'})
    from django.core.validators import validate_email
    from django.core.exceptions import ValidationError
    email = str(data.get('email') or '').strip().lower()[:254]
    try:
        validate_email(email)
    except ValidationError:
        return _bad('That email address does not look right.')
    if (_rate_limited(request, 'tripsub', 5, 3600)
            or _rate_limited(request, 'tripsub_to', 3, 86400, identifier=email, include_ip=False)):
        return _bad('Too many requests. Try again later.', 429)
    sub, _ = TripSubscriber.objects.get_or_create(blog=blog, email=email, defaults={'link': link})
    if sub.confirmed_at and not sub.unsubscribed_at:
        return JsonResponse({'status': 'already'})
    sub.link = link
    sub.unsubscribed_at = None
    sub.save(update_fields=['link', 'unsubscribed_at'])
    from .email_utils import send_trip_confirm_email
    send_trip_confirm_email(email, blog.name, f"{settings.SITE_URL}/t/sub/{sub.token}/confirm/")
    return JsonResponse({'status': 'sent'})


def trip_subscribe_confirm_view(request, sub_token):
    """GET shows a button, POST confirms — mail scanners follow GET links."""
    sub = TripSubscriber.objects.filter(token=sub_token).select_related('blog', 'link').first()
    if not sub:
        return render(request, 'tracker/trip_subscribe.html', {'invalid': True}, status=404)
    done = False
    if request.method == 'POST':
        sub.confirmed_at = sub.confirmed_at or timezone.now()
        sub.unsubscribed_at = None
        # Start the digest from now, not from the start of the trip.
        sub.last_sent_at = sub.last_sent_at or timezone.now()
        sub.save(update_fields=['confirmed_at', 'unsubscribed_at', 'last_sent_at'])
        done = True
    return render(request, 'tracker/trip_subscribe.html', {
        'sub': sub, 'blog': sub.blog, 'action': 'confirm', 'done': done,
        'trip_url': f"/t/{sub.link.token}/"})


def trip_unsubscribe_view(request, sub_token):
    sub = TripSubscriber.objects.filter(token=sub_token).select_related('blog', 'link').first()
    if not sub:
        return render(request, 'tracker/trip_subscribe.html', {'invalid': True}, status=404)
    done = False
    if request.method == 'POST':
        sub.unsubscribed_at = timezone.now()
        sub.save(update_fields=['unsubscribed_at'])
        done = True
    return render(request, 'tracker/trip_subscribe.html', {
        'sub': sub, 'blog': sub.blog, 'action': 'unsubscribe', 'done': done,
        'trip_url': f"/t/{sub.link.token}/"})
