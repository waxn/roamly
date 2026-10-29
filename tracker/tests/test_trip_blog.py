"""Trip blog: share-link gate, live-location fuzz, posting, media, comments, digest.

What matters most here is what a viewer must never get: a raw GPS fix, a feed
without having spent a link use, or a second browser through a one-time link.
"""
import io
import json
import math
from datetime import date, datetime, timedelta, timezone as dt_timezone

import pytest
from django.core.cache import cache
from django.utils import timezone
from PIL import Image

from tracker import trip_blog as tb
from tracker.models import (Device, Location, TripBlog, TripBlogMember, TripComment,
                            TripPost, TripPostMedia, TripReaction, TripShareLink,
                            TripShareUse, TripSubscriber)

pytestmark = pytest.mark.django_db

KYOTO = (35.0116, 135.7681)


@pytest.fixture(autouse=True)
def _clean(settings, tmp_path):
    settings.MEDIA_ROOT = str(tmp_path)
    settings.EMAIL_ENABLED = False
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def owner(django_user_model):
    return django_user_model.objects.create_user('linus', password='pw-123456', first_name='Linus')


@pytest.fixture
def blog(owner):
    b = TripBlog.objects.create(name='World trip', creator=owner,
                                start_date=date.today() - timedelta(days=9))
    TripBlogMember.objects.create(blog=b, user=owner, role='owner',
                                  accepted_at=timezone.now(), share_location=True)
    return b


def _fix(user, lat, lon, when=None, city='Kyoto', cc='JP'):
    dev, _ = Device.objects.get_or_create(user=user, device_id='phone')
    return Location.objects.create(device=dev, latitude=lat, longitude=lon,
                                   timestamp=when or timezone.now(), city=city,
                                   country='Japan', country_code=cc)


def _json(client, url, data=None, **kw):
    return client.post(url, data=json.dumps(data or {}), content_type='application/json', **kw)


def _haversine(a, b):
    return tb._haversine_m(a[0], a[1], b[0], b[1])


def _jpeg_with_gps(lat, lon, when='2026:03:04 10:30:00'):
    img = Image.new('RGB', (64, 48), (200, 100, 50))
    exif = Image.Exif()
    gps = exif.get_ifd(0x8825)

    def dms(v):
        v = abs(v)
        d = int(v)
        m = int((v - d) * 60)
        return (float(d), float(m), round(((v - d) * 60 - m) * 60, 2))
    gps[1] = 'N' if lat >= 0 else 'S'
    gps[2] = dms(lat)
    gps[3] = 'E' if lon >= 0 else 'W'
    gps[4] = dms(lon)
    exif.get_ifd(0x8769)[0x9003] = when
    buf = io.BytesIO()
    img.save(buf, format='JPEG', exif=exif)
    buf.seek(0)
    buf.name = 'p.jpg'
    return buf


# ── gate ────────────────────────────────────────────────────────────────────

def test_one_use_link_admits_one_browser(client, blog, owner):
    link = TripShareLink.objects.create(blog=blog, created_by=owner, max_uses=1)
    base = f"/api/tripblog/v/{link.token}"
    # Opening the page spends nothing (a chat link preview must not burn it).
    assert client.get(f"/t/{link.token}/").status_code == 200
    link.refresh_from_db()
    assert link.use_count == 0
    # Data before entering is refused.
    assert client.get(f"{base}/status/").status_code == 403
    assert _json(client, f"{base}/enter/").status_code == 200
    assert client.get(f"{base}/status/").status_code == 200
    assert _json(client, f"{base}/enter/").status_code == 200   # same browser, still fine
    link.refresh_from_db()
    assert link.use_count == 1

    from django.test import Client
    other = Client()
    r = _json(other, f"{base}/enter/")
    assert r.status_code == 403 and r.json()['gate'] == 'full'
    assert other.get(f"{base}/feed/").status_code == 403


def test_pin_link_locks_out(client, blog, owner):
    link = TripShareLink(blog=blog, created_by=owner)
    tb_views = __import__('tracker.trip_blog_views', fromlist=['_apply_link'])
    assert tb_views._apply_link(link, {'pin': '4821'}) is None
    link.save()
    base = f"/api/tripblog/v/{link.token}"
    assert _json(client, f"{base}/enter/").json()['gate'] == 'pin'
    for _ in range(5):
        assert _json(client, f"{base}/unlock/", {'pin': '0000'}).status_code == 403
    assert _json(client, f"{base}/unlock/", {'pin': '4821'}).status_code == 429
    cache.clear()
    assert _json(client, f"{base}/unlock/", {'pin': '4821'}).status_code == 200
    assert client.get(f"{base}/status/").status_code == 200


def test_revoked_and_expired_links(client, blog, owner):
    revoked = TripShareLink.objects.create(blog=blog, created_by=owner, revoked_at=timezone.now())
    expired = TripShareLink.objects.create(blog=blog, created_by=owner,
                                           expires_at=timezone.now() - timedelta(minutes=1))
    for link in (revoked, expired):
        assert client.get(f"/t/{link.token}/").status_code == 404
        assert _json(client, f"/api/tripblog/v/{link.token}/enter/").status_code == 404


def test_member_reads_without_spending(client, blog, owner):
    link = TripShareLink.objects.create(blog=blog, created_by=owner, max_uses=1)
    client.force_login(owner)
    assert client.get(f"/api/tripblog/v/{link.token}/status/").status_code == 200
    assert _json(client, f"/api/tripblog/v/{link.token}/enter/").status_code == 200
    link.refresh_from_db()
    assert link.use_count == 0


# ── location privacy ────────────────────────────────────────────────────────

def test_fuzz_is_stable_and_bounded():
    now = timezone.now()
    real = KYOTO
    a = tb.fuzz_spot(1, 2, *real, now)
    b = tb.fuzz_spot(1, 2, *real, now + timedelta(minutes=5))
    assert a == b                                  # polling gives the same dot
    assert a[2] == 500
    d = _haversine(real, a[:2])
    assert 100 < d <= a[2]
    later = tb.fuzz_spot(1, 2, *real, now + timedelta(hours=7))
    assert later[2] == 1000 and _haversine(real, later[:2]) <= 1000
    # Small moves inside the stay keep it inside the circle too.
    moved = (real[0] + 0.004, real[1])             # ~450 m
    c = tb.fuzz_spot(1, 2, *moved, now + timedelta(hours=7, minutes=1))
    assert _haversine(moved, c[:2]) <= c[2]


def test_status_never_contains_raw_fix(client, blog, owner):
    raw = _fix(owner, 35.012345, 135.768765)
    link = TripShareLink.objects.create(blog=blog, created_by=owner)
    _json(client, f"/api/tripblog/v/{link.token}/enter/")
    body = client.get(f"/api/tripblog/v/{link.token}/status/").content.decode()
    data = json.loads(body)
    assert data['people'][0]['city'] == 'Kyoto'
    assert data['together'] == 'Linus in Kyoto' or data['together'].endswith('in Kyoto')
    for frag in ('35.0123', '135.7687', str(raw.latitude), str(raw.longitude)):
        assert frag not in body
    assert 'JP' in data['countries']


def test_location_hidden_without_consent_and_after_trip(client, blog, owner):
    _fix(owner, *KYOTO)
    TripBlogMember.objects.filter(blog=blog, user=owner).update(share_location=False)
    assert tb.status_payload(blog)['people'] == []
    TripBlogMember.objects.filter(blog=blog, user=owner).update(share_location=True)
    blog.end_date = date.today() - timedelta(days=2)
    blog.save()
    tb.bump(blog.id)
    s = tb.status_payload(blog)
    assert s['people'] == [] and s['home'] is None and not s['blog']['live']


def test_home_city_is_where_they_slept(blog, owner):
    tz = tb.blog_zone(blog)
    today = tb.today_local(blog)
    night = timezone.make_aware(datetime.combine(today, datetime.min.time()), tz) + timedelta(hours=2)
    for i in range(10):
        _fix(owner, 34.6937, 135.5023, night + timedelta(minutes=10 * i), city='Osaka')
    _fix(owner, *KYOTO, when=timezone.now())        # daytime in Kyoto
    assert tb.home_city(blog, tb._accepted_members(blog))['city'] == 'Osaka'


def test_group_line():
    assert tb._group_line([{'name': 'A', 'city': 'Kyoto'}, {'name': 'B', 'city': 'Kyoto'}]) == 'Everyone in Kyoto'
    line = tb._group_line([{'name': 'A', 'city': 'Kyoto'}, {'name': 'B', 'city': 'Kyoto'},
                           {'name': 'C', 'city': 'Osaka'}])
    assert line == 'A, B in Kyoto · C in Osaka'


# ── posting ─────────────────────────────────────────────────────────────────

def test_exif_location_and_time():
    img = Image.open(_jpeg_with_gps(35.0116, 135.7681))
    ex = tb.read_exif(img)
    assert ex['lat'] == pytest.approx(35.0116, abs=1e-4)
    assert ex['lon'] == pytest.approx(135.7681, abs=1e-4)
    t = tb.resolve_exif_time(ex)
    assert t.utcoffset() == timedelta(hours=9) and t.hour == 10


def test_draft_photo_publish_flow(client, blog, owner):
    client.force_login(owner)
    r = _json(client, f"/api/tripblog/{blog.id}/posts/", {'client_id': 'c1', 'publish': False})
    pid = r.json()['id']
    # Idempotent on client_id
    assert _json(client, f"/api/tripblog/{blog.id}/posts/", {'client_id': 'c1'}).json()['id'] == pid
    up = client.post(f"/api/tripblog/{blog.id}/posts/{pid}/photo/",
                     {'file': _jpeg_with_gps(*KYOTO)}, HTTP_X_CSRFTOKEN='x')
    assert up.status_code == 200, up.content
    ex = up.json()['exif']
    assert ex['lat'] == pytest.approx(KYOTO[0], abs=1e-4)
    stored = TripPostMedia.objects.get(post_id=pid)
    assert not Image.open(stored.image.path).getexif().get_ifd(0x8825)   # GPS stripped
    # A viewer can't see the draft.
    link = TripShareLink.objects.create(blog=blog, created_by=owner)
    from django.test import Client
    v = Client()
    _json(v, f"/api/tripblog/v/{link.token}/enter/")
    assert v.get(f"/api/tripblog/v/{link.token}/feed/").json()['days'] == []
    r = _json(client, f"/api/tripblog/{blog.id}/posts/{pid}/",
              {'text': 'Temples!', 'lat': ex['lat'], 'lon': ex['lon'],
               'taken_at': ex['taken_at'], 'publish': True})
    assert r.status_code == 200
    post = TripPost.objects.get(pk=pid)
    assert post.published and post.published_at and post.local_date == date(2026, 3, 4)
    feed = v.get(f"/api/tripblog/v/{link.token}/feed/").json()
    assert feed['days'][0]['posts'][0]['text'] == 'Temples!'
    assert feed['days'][0]['day'] == (date(2026, 3, 4) - blog.start_date).days + 1


def test_other_member_cannot_edit_post(client, blog, owner, django_user_model):
    mom = django_user_model.objects.create_user('mom', password='pw-123456')
    TripBlogMember.objects.create(blog=blog, user=mom, accepted_at=timezone.now())
    post = TripPost.objects.create(blog=blog, author=owner, text='x', published=True,
                                   local_date=date.today())
    client.force_login(mom)
    assert _json(client, f"/api/tripblog/{blog.id}/posts/{post.id}/", {'text': 'y'}).status_code == 404
    outsider = django_user_model.objects.create_user('stranger', password='pw-123456')
    client.force_login(outsider)
    assert client.get(f"/api/tripblog/{blog.id}/feed/").status_code == 404


def test_video_chunks_are_idempotent(client, blog, owner, settings, monkeypatch):
    from tracker import video_tasks
    kicked = []
    monkeypatch.setattr(video_tasks, 'kick', lambda: kicked.append(1))
    client.force_login(owner)
    pid = _json(client, f"/api/tripblog/{blog.id}/posts/", {'publish': False}).json()['id']
    data = b'\x00\x00\x00\x18ftypmp42' + b'x' * 100
    parts = [data[:40], data[40:80], data[80:]]
    url = f"/api/tripblog/{blog.id}/posts/{pid}/video/"

    def send(i, mid=None):
        f = io.BytesIO(parts[i])
        f.name = 'blob'
        payload = {'index': i, 'total': 3, 'chunk': f, 'name': 'clip.MOV'}
        if mid:
            payload['media_id'] = mid
        return client.post(url, payload, HTTP_X_CSRFTOKEN='x')
    r0 = send(0)
    assert r0.status_code == 200, r0.content
    mid = r0.json()['media_id']
    assert send(0, mid).json()['status'] == 'duplicate'
    assert send(2, mid).status_code == 409
    assert send(1, mid).json()['next_chunk'] == 2
    done = send(2, mid).json()
    assert done['status'] == 'complete'
    m = TripPostMedia.objects.get(pk=mid)
    assert m.status == 'pending' and m.video.name.endswith('.mov')
    assert open(m.video.path, 'rb').read() == data
    assert kicked


def test_video_rejects_non_video(client, blog, owner):
    client.force_login(owner)
    pid = _json(client, f"/api/tripblog/{blog.id}/posts/", {'publish': False}).json()['id']
    f = io.BytesIO(b'<html><script>alert(1)</script>')
    f.name = 'blob'
    r = client.post(f"/api/tripblog/{blog.id}/posts/{pid}/video/",
                    {'index': 0, 'total': 1, 'chunk': f, 'name': 'x.mp4'}, HTTP_X_CSRFTOKEN='x')
    assert r.status_code == 400


# ── comments + reactions ────────────────────────────────────────────────────

def test_viewer_comment_react_and_who_viewed(client, blog, owner):
    post = TripPost.objects.create(blog=blog, author=owner, text='hi', published=True,
                                   local_date=date.today())
    link = TripShareLink.objects.create(blog=blog, created_by=owner, label='Grandparents')
    base = f"/api/tripblog/v/{link.token}"
    _json(client, f"{base}/enter/")
    assert _json(client, f"{base}/comment/", {'post': post.id, 'text': 'Lovely'}).status_code == 400
    r = _json(client, f"{base}/comment/", {'post': post.id, 'text': 'Lovely', 'name': 'Grandma'})
    assert r.status_code == 200 and r.json()['comment']['can_delete']
    assert _json(client, f"{base}/react/", {'post': post.id, 'emoji': '❤️'}).json()['on']
    assert TripReaction.objects.filter(post=post).count() == 1
    assert not _json(client, f"{base}/react/", {'post': post.id, 'emoji': '❤️'}).json()['on']
    # honeypot
    _json(client, f"{base}/comment/", {'post': post.id, 'text': 'spam', 'name': 'x', 'website': 'y'})
    assert TripComment.objects.count() == 1

    from django.test import Client
    m = Client()
    m.force_login(owner)
    views = m.get(f"/api/tripblog/{blog.id}/views/").json()['links'][0]['viewers']
    assert views[0]['name'] == 'Grandma'
    # Members can delete any comment.
    cid = TripComment.objects.get().id
    assert _json(m, f"/api/tripblog/{blog.id}/comment/{cid}/delete/").status_code == 200


def test_viewer_cannot_delete_someone_elses_comment(client, blog, owner):
    post = TripPost.objects.create(blog=blog, author=owner, published=True, local_date=date.today())
    c = TripComment.objects.create(post=post, guest_name='Aunt', text='x', viewer_key='other')
    link = TripShareLink.objects.create(blog=blog, created_by=owner)
    _json(client, f"/api/tripblog/v/{link.token}/enter/")
    assert _json(client, f"/api/tripblog/v/{link.token}/comment/{c.id}/delete/").status_code == 403


# ── membership + digest ─────────────────────────────────────────────────────

def test_join_requires_post(client, blog, django_user_model):
    blog.invite_token = 'tok123'
    blog.save()
    kid = django_user_model.objects.create_user('kid', password='pw-123456')
    client.force_login(kid)
    client.get('/trip/join/tok123/')
    assert not TripBlogMember.objects.filter(user=kid).exists()
    client.post('/trip/join/tok123/', {'share_location': 'on'})
    m = TripBlogMember.objects.get(user=kid)
    assert m.is_accepted and m.share_location and m.featured


def test_digest_only_sends_new_posts(blog, owner, settings, monkeypatch):
    from tracker import trip_digest_tasks as td
    from tracker import email_utils
    settings.EMAIL_ENABLED = True
    sent = []
    monkeypatch.setattr(email_utils, 'send_trip_digest_email',
                        lambda to, **kw: sent.append((to, kw)) or True)
    link = TripShareLink.objects.create(blog=blog, created_by=owner)
    sub = TripSubscriber.objects.create(blog=blog, link=link, email='g@example.com',
                                        confirmed_at=timezone.now() - timedelta(days=2),
                                        last_sent_at=timezone.now() - timedelta(days=2))
    assert td.send_digest(blog, force=True) == 0           # nothing new
    TripPost.objects.create(blog=blog, author=owner, text='Day 9', published=True,
                            published_at=timezone.now(), local_date=date.today())
    assert td.send_digest(blog, force=True) == 1
    assert sent[0][0] == 'g@example.com' and sent[0][1]['posts'][0]['text'] == 'Day 9'
    assert td.send_digest(blog, force=True) == 0           # cursor moved
    link.revoked_at = timezone.now()
    link.save()
    TripPost.objects.create(blog=blog, author=owner, text='Day 10', published=True,
                            published_at=timezone.now(), local_date=date.today())
    assert td.send_digest(blog, force=True) == 0           # revoked link ⇒ no mail
    sub.refresh_from_db()
