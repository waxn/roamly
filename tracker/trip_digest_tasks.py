"""Trip blog daily digest: one evening email to each confirmed subscriber when
the trip has new posts.

Mirrors summary_email_tasks: an hourly daemon from apps.ready(), a PG advisory
lock per blog (namespace 'RAMT') so only one gunicorn worker sends a blog's
digest, and a per-subscriber ``last_sent_at`` cursor that makes it restart-safe.

"Evening" is DIGEST_HOUR in the trip's own zone (its owner's newest fix), so
grandparents hear about the day once it is over where the travellers are.
A subscriber whose link was revoked or expired gets nothing.
"""
import logging
import threading
import time
from contextlib import contextmanager
from datetime import datetime, time as dt_time

from django.conf import settings
from django.db import close_old_connections, connection
from django.utils import timezone

logger = logging.getLogger(__name__)

DIGEST_HOUR = 19
CHECK_INTERVAL_S = 3600
MAX_POSTS = 12
_LOCK_NAMESPACE = 0x52414d54   # 'RAMT'

_scheduler_thread = None


@contextmanager
def _blog_lock(blog_id):
    if connection.vendor != 'postgresql':
        yield True
        return
    got = False
    try:
        with connection.cursor() as cur:
            cur.execute("SELECT pg_try_advisory_lock(%s, %s)", [_LOCK_NAMESPACE, blog_id])
            got = bool(cur.fetchone()[0])
        yield got
    finally:
        if got:
            with connection.cursor() as cur:
                cur.execute("SELECT pg_advisory_unlock(%s, %s)", [_LOCK_NAMESPACE, blog_id])


def _abs(url):
    return url if (not url or url.startswith('http')) else f"{settings.SITE_URL}{url}"


def _post_items(posts, link_token, tz):
    from . import trip_blog as tb
    out = []
    for p in posts:
        media = list(p.media.all())
        first = next((m for m in media if (m.media_type == 'image' and m.thumbnail)
                      or (m.media_type == 'video' and m.poster)), None)
        img = None
        if first is not None:
            img = _abs((first.thumbnail or first.poster).url)
        n_ph = sum(1 for m in media if m.media_type == 'image')
        n_vid = sum(1 for m in media if m.media_type == 'video')
        extra = []
        if n_ph > 1:
            extra.append(f"{n_ph} photos")
        if n_vid:
            extra.append(f"{n_vid} video" + ('s' if n_vid > 1 else ''))
        out.append({
            'author': tb.display_name(p.author),
            'when': timezone.localtime(p.taken_at, tz).strftime('%a %b %-d'),
            'place': ', '.join(x for x in (p.city, p.country) if x),
            'text': p.text, 'image': img, 'extra': ' · '.join(extra),
            'url': f"{settings.SITE_URL}/t/{link_token}/#post-{p.id}",
        })
    return out


def send_digest(blog, force=False):
    """Send this blog's digest to every due subscriber. Returns emails sent."""
    from . import trip_blog as tb
    from .email_utils import email_enabled, send_trip_digest_email
    from .models import TripPost, TripSubscriber
    if not email_enabled():
        return 0
    tz = tb.blog_zone(blog)
    now = timezone.now()
    local_now = timezone.localtime(now, tz)
    cutoff = timezone.make_aware(datetime.combine(local_now.date(), dt_time(DIGEST_HOUR)), tz)
    if not force and now < cutoff:
        return 0
    subs = list(TripSubscriber.objects.filter(blog=blog, confirmed_at__isnull=False,
                                              unsubscribed_at__isnull=True)
                .select_related('link'))
    sent = 0
    for sub in subs:
        if not sub.link.is_active:
            continue
        since = sub.last_sent_at or sub.confirmed_at
        if not force and since >= cutoff:
            continue                       # already had today's
        posts = list(TripPost.objects.filter(blog=blog, published=True, published_at__gt=since)
                     .select_related('author').prefetch_related('media')
                     .order_by('taken_at')[:MAX_POSTS + 1])
        if not posts:
            continue
        more = len(posts) > MAX_POSTS
        posts = posts[:MAX_POSTS]
        n = len(posts)
        heading = (f"{n}{'+' if more else ''} new post{'s' if n != 1 or more else ''}")
        home = tb.status_payload(blog).get('home')
        if home and home.get('city'):
            heading += f" · now in {home['city']}"
        ok = send_trip_digest_email(
            sub.email, blog_name=blog.name, heading=heading,
            posts=_post_items(posts, sub.link.token, tz),
            trip_url=f"{settings.SITE_URL}/t/{sub.link.token}/",
            unsubscribe_url=f"{settings.SITE_URL}/t/sub/{sub.token}/unsubscribe/")
        if ok:
            TripSubscriber.objects.filter(pk=sub.pk).update(last_sent_at=now)
            sent += 1
    return sent


def _sweep():
    from .models import TripBlog
    ids = list(TripBlog.objects.filter(subscribers__confirmed_at__isnull=False,
                                       subscribers__unsubscribed_at__isnull=True)
               .values_list('id', flat=True).distinct())
    for bid in ids:
        blog = TripBlog.objects.filter(pk=bid).first()
        if not blog:
            continue
        with _blog_lock(bid) as got:
            if got:
                try:
                    send_digest(blog)
                except Exception:
                    logger.exception("trip digest failed for blog %s", bid)


def _loop():
    from .scheduler_utils import wait_for_app_registry
    wait_for_app_registry()
    while True:
        try:
            close_old_connections()
            _sweep()
        except Exception:
            logger.exception("trip digest scheduler error")
        time.sleep(CHECK_INTERVAL_S)


def start_trip_digest_scheduler():
    global _scheduler_thread
    if _scheduler_thread is not None and _scheduler_thread.is_alive():
        return
    _scheduler_thread = threading.Thread(target=_loop, daemon=True, name='trip-digest')
    _scheduler_thread.start()
