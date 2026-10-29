"""Trip-blog video transcoding: any phone video → 1080p H.264 MP4 + poster.

H.264/AAC in MP4 is the one combination every browser a grandparent might own
plays (AV1 is still absent on pre-2023 iPhones and iPads). One transcode runs at
a time per instance — ``cache.add`` on ``LOCK_KEY`` — under ``nice`` and two
threads, so a long clip slows the server down rather than stalling it.

The upload stays in ``media.video`` until the transcode has succeeded, then is
replaced by the result and deleted. A failure keeps it (status ``failed``) and
the page plays it as uploaded, which most browsers still manage.
"""
import json
import logging
import os
import shutil
import subprocess
import threading
import time
import uuid

from django.conf import settings
from django.core.cache import cache
from django.db import close_old_connections

from .scheduler_utils import db_thread, wait_for_app_registry

logger = logging.getLogger(__name__)

LOCK_KEY = 'tripvideo:lock'
LOCK_TTL = 3 * 3600
TRANSCODE_TIMEOUT_S = 2 * 3600
SWEEP_INTERVAL_S = 600
STALE_PROCESSING_S = 3 * 3600
DRAFT_MAX_AGE_S = 7 * 86400
UPLOAD_MAX_AGE_S = 2 * 86400

_scheduler_thread = None


def ffmpeg_available():
    return bool(shutil.which('ffmpeg') and shutil.which('ffprobe'))


def _probe(path):
    """(width, height, duration_s) after rotation, or Nones."""
    try:
        out = subprocess.run(
            ['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
             'stream=width,height:stream_side_data=rotation:stream_tags=rotate:format=duration',
             '-of', 'json', path],
            capture_output=True, timeout=60, check=True).stdout
        data = json.loads(out or b'{}')
        st = (data.get('streams') or [{}])[0]
        w, h = st.get('width'), st.get('height')
        rot = 0
        for sd in st.get('side_data_list') or []:
            if 'rotation' in sd:
                rot = int(sd['rotation'])
        rot = rot or int((st.get('tags') or {}).get('rotate') or 0)
        if abs(rot) % 180 == 90:
            w, h = h, w
        dur = float((data.get('format') or {}).get('duration') or 0) or None
        return w, h, dur
    except Exception:
        return None, None, None


def _transcode(src, dst, poster):
    # Long side ≤ 1920 (so a 16:9 clip lands at 1080p, portrait or landscape),
    # never upscaled; -2 keeps the other side even, which yuv420p requires.
    vf = ("scale='if(gte(iw,ih),min(1920,iw),-2)':'if(gte(iw,ih),-2,min(1920,ih))',"
          "format=yuv420p")
    cmd = ['nice', '-n', '10', 'ffmpeg', '-y', '-v', 'error', '-i', src,
           '-map', '0:v:0', '-map', '0:a:0?', '-vf', vf,
           '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '23', '-profile:v', 'high',
           '-c:a', 'aac', '-b:a', '128k', '-ac', '2',
           '-movflags', '+faststart', '-threads', '2', dst]
    subprocess.run(cmd, capture_output=True, timeout=TRANSCODE_TIMEOUT_S, check=True)
    subprocess.run(['ffmpeg', '-y', '-v', 'error', '-ss', '1', '-i', dst, '-frames:v', '1',
                    '-vf', "scale='min(1280,iw)':-2", '-q:v', '4', poster],
                   capture_output=True, timeout=120, check=False)
    if not os.path.exists(poster) or os.path.getsize(poster) == 0:
        subprocess.run(['ffmpeg', '-y', '-v', 'error', '-i', dst, '-frames:v', '1',
                        '-vf', "scale='min(1280,iw)':-2", '-q:v', '4', poster],
                       capture_output=True, timeout=120, check=False)


def _process(media_id):
    from .models import TripPostMedia
    from . import trip_blog
    claimed = TripPostMedia.objects.filter(pk=media_id, status='pending').update(status='processing')
    if not claimed:
        return
    m = TripPostMedia.objects.select_related('post').get(pk=media_id)
    src = m.video.path
    uid = uuid.uuid4().hex
    rel_v = f"tripblog/videos/{uid}.mp4"
    rel_p = f"tripblog/videos/posters/{uid}.jpg"
    dst = os.path.join(settings.MEDIA_ROOT, rel_v)
    poster = os.path.join(settings.MEDIA_ROOT, rel_p)
    os.makedirs(os.path.dirname(poster), exist_ok=True)
    try:
        _transcode(src, dst, poster)
        w, h, dur = _probe(dst)
        old = m.video.name
        m.video.name = rel_v
        if os.path.exists(poster) and os.path.getsize(poster):
            m.poster.name = rel_p
        m.width, m.height, m.duration_s = w, h, dur
        m.status, m.error = 'ready', ''
        m.save(update_fields=['video', 'poster', 'width', 'height', 'duration_s', 'status', 'error'])
        if old and old != rel_v:
            try:
                os.remove(os.path.join(settings.MEDIA_ROOT, old))
            except OSError:
                pass
    except Exception as exc:
        for p in (dst, poster):
            try:
                os.remove(p)
            except OSError:
                pass
        msg = str(getattr(exc, 'stderr', b'') or exc)[-300:]
        logger.warning("trip video %s transcode failed: %s", media_id, msg)
        w, h, dur = _probe(src)
        TripPostMedia.objects.filter(pk=media_id).update(
            status='failed', error=msg[:300], width=w, height=h, duration_s=dur)
    trip_blog.bump(m.post.blog_id)


def _drain():
    from .models import TripPostMedia
    if not cache.add(LOCK_KEY, 1, LOCK_TTL):
        return
    try:
        while True:
            nxt = (TripPostMedia.objects.filter(media_type='video', status='pending')
                   .order_by('created_at').values_list('id', flat=True).first())
            if not nxt:
                break
            cache.set(LOCK_KEY, 1, LOCK_TTL)
            _process(nxt)
    finally:
        cache.delete(LOCK_KEY)


def kick():
    """Start the transcode queue if nothing is draining it already."""
    if not ffmpeg_available():
        _mark_unavailable()
        return
    db_thread(_drain, name='trip-video').start()


def _mark_unavailable():
    from .models import TripPostMedia
    TripPostMedia.objects.filter(media_type='video', status='pending').update(
        status='failed', error='ffmpeg is not installed on this server.')


def _sweep():
    """Recover rows a dead worker left in 'processing', drop abandoned drafts and
    half-finished uploads, then drain."""
    from django.utils import timezone
    from datetime import timedelta
    from .models import TripPost, TripPostMedia
    now = timezone.now()
    if not cache.get(LOCK_KEY):
        TripPostMedia.objects.filter(media_type='video', status='processing').update(status='pending')
    for m in TripPostMedia.objects.filter(status='uploading',
                                          created_at__lt=now - timedelta(seconds=UPLOAD_MAX_AGE_S)):
        delete_media_files(m)
        m.delete()
    for p in TripPost.objects.filter(published=False,
                                     created_at__lt=now - timedelta(seconds=DRAFT_MAX_AGE_S)):
        for m in p.media.all():
            delete_media_files(m)
        p.delete()
    if TripPostMedia.objects.filter(media_type='video', status='pending').exists():
        if ffmpeg_available():
            _drain()
        else:
            _mark_unavailable()


def delete_media_files(m):
    """Remove a TripPostMedia row's files from disk (the row itself is the caller's)."""
    for f in (m.image, m.thumbnail, m.video, m.poster):
        try:
            if f and f.name:
                f.storage.delete(f.name)
        except Exception:
            pass
    if m.upload_id:
        try:
            os.remove(chunk_path(m.upload_id))
        except OSError:
            pass


def chunk_path(upload_id):
    d = os.path.join(settings.MEDIA_ROOT, 'tripblog', 'uploads')
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, f"{upload_id}.part")


def _loop():
    wait_for_app_registry()
    time.sleep(90)
    while True:
        try:
            close_old_connections()
            _sweep()
        except Exception:
            logger.exception("trip video sweep failed")
        time.sleep(SWEEP_INTERVAL_S)


def start_trip_video_scheduler():
    global _scheduler_thread
    if _scheduler_thread is not None:
        return
    _scheduler_thread = threading.Thread(target=_loop, daemon=True, name='trip-video-sweep')
    _scheduler_thread.start()
