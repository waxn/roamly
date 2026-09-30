import json
import os
import tempfile
import threading
import logging
import time
import zipfile
from datetime import timedelta

from django.db import close_old_connections
from django.utils import timezone

logger = logging.getLogger(__name__)

_scheduler_thread = None
_backup_threads = {}  # user_id -> thread

SCHEDULER_CHECK_INTERVAL = 900  # 15 minutes

# How often a running backup refreshes its heartbeat, and how long without one
# before a status poll calls the run dead. The gap between them is the slack
# for one slow step (a large media file, a stalled multipart part).
HEARTBEAT_EVERY_S = 30
STALE_AFTER_S = 600

INTERVAL_DELTAS = {
    'daily': timedelta(days=1),
    'weekly': timedelta(weeks=1),
    'monthly': timedelta(days=30),
}


def _build_adventures_data(user):
    """Build serializable adventures data including all social content."""
    from .models import Adventure

    adventures = Adventure.objects.filter(device__user=user).select_related(
        'device', 'creator'
    ).prefetch_related(
        'members__user',
        'blurbs__author',
        'blurbs__photos',
        'blurbs__comments__author',
        'milestones__author',
        'planned_stops',
        'day_notes__author',
        'day_notes__photos',
    )

    result = []
    for adv in adventures:
        blurbs = []
        for b in adv.blurbs.all():
            blurbs.append({
                # Original ids, so a restore can rewrite the Story body's
                # [^pin:ID] / location_card.place_id / photo_grid.photo_ids
                # references to point at the newly-created rows instead of
                # ids that no longer exist.
                'id': b.id,
                'author_username': b.author.username,
                'title': b.title,
                'text': b.text,
                'latitude': b.latitude,
                'longitude': b.longitude,
                'location_name': b.location_name,
                'rating': b.rating,
                'category': b.category,
                'created_at': b.created_at,
                'photos': [
                    {'id': p.id, 'image': p.image.name, 'thumbnail': p.thumbnail.name if p.thumbnail else '', 'order': p.order,
                     'media_type': p.media_type, 'video': p.video.name if p.video else ''}
                    for p in b.photos.all()
                ],
                'comments': [
                    {
                        'author_username': c.author.username if c.author else None,
                        'guest_name': c.guest_name,
                        'text': c.text,
                        'created_at': c.created_at,
                    }
                    for c in b.comments.all()
                ],
            })

        result.append({
            'device_id': adv.device.device_id,
            'name': adv.name,
            'description': adv.description,
            'subtitle': adv.subtitle,
            'access_pin': adv.access_pin,
            'start_time': adv.start_time,
            'end_time': adv.end_time,
            'creator_username': adv.creator.username if adv.creator else None,
            'public_slug': adv.public_slug,
            'created_at': adv.created_at,
            'cover_image': adv.cover_image.name if adv.cover_image else '',
            'cover_image_thumbnail': adv.cover_image_thumbnail.name if adv.cover_image_thumbnail else '',
            'body': adv.body,
            'members': [
                {'username': m.user.username, 'role': m.role,
                 'accepted_at': m.accepted_at, 'share_track': m.share_track}
                for m in adv.members.all()
            ],
            'blurbs': blurbs,
            'milestones': [
                {
                    'author_username': m.author.username,
                    'title': m.title,
                    'description': m.description,
                    'emoji': m.emoji,
                    'date': m.date,
                    'created_at': m.created_at,
                }
                for m in adv.milestones.all()
            ],
            'planned_stops': [
                {
                    'name': s.name,
                    'latitude': s.latitude,
                    'longitude': s.longitude,
                    'location_name': s.location_name,
                    'arrival_date': s.arrival_date,
                    'nights': s.nights,
                    'transport': s.transport,
                    'notes': s.notes,
                    'accommodation': s.accommodation,
                    'order': s.order,
                }
                for s in adv.planned_stops.all()
            ],
            'day_notes': [
                {
                    'author_username': n.author.username,
                    'date': n.date,
                    'title': n.title,
                    'body': n.body,
                    'created_at': n.created_at,
                    # Place link serialized by natural key (title + coords) so a
                    # restore can re-link to the recreated blurb.
                    'place_ref': ({'title': n.place.title, 'latitude': n.place.latitude,
                                   'longitude': n.place.longitude} if n.place_id and n.place else None),
                    'photos': [
                        {'image': p.image.name, 'thumbnail': p.thumbnail.name if p.thumbnail else '', 'order': p.order,
                         'media_type': p.media_type, 'video': p.video.name if p.video else ''}
                        for p in n.photos.all()
                    ],
                }
                for n in adv.day_notes.all()
            ],
        })

    return result


def _build_journals_data(user):
    """Build serializable journal entries (with photo metadata) for a user's backup."""
    from .models import JournalEntry

    entries = (
        JournalEntry.objects.filter(user=user)
        .prefetch_related('photos')
        .order_by('date')
    )

    result = []
    for e in entries:
        result.append({
            'date': e.date,
            'title': e.title,
            'body': e.body,
            'mood': e.mood,
            'weather': e.weather,
            'is_favorite': e.is_favorite,
            'pin_latitude': e.pin_latitude,
            'pin_longitude': e.pin_longitude,
            'location_name': e.location_name,
            'created_at': e.created_at,
            'updated_at': e.updated_at,
            'photos': [
                {
                    'image': p.image.name if p.image else '',
                    'thumbnail': p.thumbnail.name if p.thumbnail else '',
                    'caption': p.caption,
                    'order': p.order,
                }
                for p in e.photos.all()
            ],
        })

    return result


def _build_custom_places_data(user):
    """Build serializable custom places (user-defined geofences) for a user's backup."""
    from .models import CustomPlace

    return [
        {
            'name': p.name,
            'latitude': p.latitude,
            'longitude': p.longitude,
            'radius_m': p.radius_m,
            'color': p.color,
            'notes': p.notes,
            'created_at': p.created_at,
        }
        for p in CustomPlace.objects.filter(user=user)
    ]


def _build_health_workouts_data(user):
    """Build serializable imported workouts for a user's backup.

    Shared with views._write_backup_json so the download and the S3 backup stay
    identical by construction, the same arrangement _build_adventures_data /
    _build_journals_data / _build_custom_places_data already have.

    There is deliberately no matching helper for health *samples*: they are the
    only health section large enough to matter, and the download streams them
    row-by-row to avoid materialising a whole history in memory. A shared
    list-building helper would defeat exactly that.
    """
    from .models import HealthWorkout

    return [
        {
            'external_id': w.external_id,
            'source': w.source,
            'device_id': w.device_id,
            'start_time': w.start_time,
            'end_time': w.end_time,
            'zone_offset_seconds': w.zone_offset_seconds,
            'exercise_type': w.exercise_type,
            'exercise_slug': w.exercise_slug,
            'title': w.title,
            'notes': w.notes,
            'duration_s': w.duration_s,
            'steps': w.steps,
            'distance_m': w.distance_m,
            'calories_kcal': w.calories_kcal,
            'avg_heart_rate': w.avg_heart_rate,
        }
        for w in HealthWorkout.objects.filter(user=user).order_by('start_time')
    ]


def _build_activities_data(user):
    """Build serializable recorded activities for a user's backup.

    Shared with views._write_backup_json, like the adventures / journals /
    places / health-workouts builders, so the download and the S3 backup stay
    identical by construction.

    A list builder rather than a stream: the streaming carve-out exists only for
    locations and health samples, which are whole-history sized. Activities are
    a handful per week.

    Only the envelope is stored. The derived stats are deliberately left out —
    they are a cache over Location rows that the restore will recompute anyway,
    and a restore into a partially-populated history should report what is
    actually there rather than a figure from another database.
    """
    from .models import Activity

    return [
        {
            'client_id': a.client_id,
            'device_id': a.device.device_id if a.device_id else '',
            'kind': a.kind,
            'title': a.title,
            'notes': a.notes,
            'start_time': a.start_time,
            'end_time': a.end_time,
        }
        for a in Activity.objects.filter(user=user).select_related('device').order_by('start_time')
    ]


def _build_family_data(user):
    """Build serializable Family Circles for a user's backup.

    Mirrors _build_adventures_data's shape: only circles this user *created*
    are exported, each with its full member/place/alert set — the same "you
    back up what you own" limitation Adventure backups already have. A
    member's own backup does not carry their membership in a circle someone
    else created, exactly like a shared Adventure they didn't create.

    Shared with views._write_backup_json, like every other list-built
    section here, so the download and the S3 backup stay identical.
    """
    from .models import FamilyCircle

    circles = FamilyCircle.objects.filter(creator=user).prefetch_related(
        'members__user', 'places__alerts__user',
    )
    result = []
    for circle in circles:
        result.append({
            'name': circle.name,
            'created_at': circle.created_at,
            'members': [
                {'username': m.user.username, 'role': m.role,
                 'accepted_at': m.accepted_at, 'share_location': m.share_location}
                for m in circle.members.all()
            ],
            'places': [
                {
                    'creator_username': p.creator.username if p.creator_id else None,
                    'name': p.name,
                    'latitude': p.latitude,
                    'longitude': p.longitude,
                    'radius_m': p.radius_m,
                    'color': p.color,
                    'notes': p.notes,
                    'created_at': p.created_at,
                    'alerts': [
                        {'username': al.user.username, 'on_enter': al.on_enter, 'on_exit': al.on_exit}
                        for al in p.alerts.all()
                    ],
                }
                for p in circle.places.all()
            ],
        })
    return result


def _get_s3_client(config):
    """Create a boto3 S3 client from a BackupConfig."""
    import boto3
    from botocore.config import Config
    return boto3.client(
        's3',
        endpoint_url=config.endpoint_url,
        aws_access_key_id=config.access_key,
        aws_secret_access_key=config.secret_key,
        region_name=config.region or 'auto',
        config=Config(
            signature_version='s3v4',
            s3={'addressing_style': 'path'},
            connect_timeout=30,
            read_timeout=120,
        ),
    )


def test_s3_connection(config):
    """Test S3 connection by uploading and deleting a small test file. Returns (success, error_msg)."""
    try:
        client = _get_s3_client(config)
        test_key = f"{config.prefix}{config.user.username}/.connection_test"
        client.put_object(
            Bucket=config.bucket_name,
            Key=test_key,
            Body=b'roamly connection test',
        )
        client.delete_object(Bucket=config.bucket_name, Key=test_key)
        return True, None
    except Exception as e:
        return False, str(e)


def _prune_old_backups(client, config, username):
    """Delete oldest backups beyond max_backups limit."""
    try:
        prefix = f"{config.prefix}{username}/"
        response = client.list_objects_v2(Bucket=config.bucket_name, Prefix=prefix)
        objects = response.get('Contents', [])
        # Backup archives only: .zip since S3 started uploading the same file
        # as the Settings download, .json for runs from before that — which
        # must keep counting toward max_backups or they'd never rotate out.
        backups = [o for o in objects if o['Key'].endswith(('.zip', '.json'))]
        if len(backups) <= config.max_backups:
            return
        # Sort by last modified, oldest first
        backups.sort(key=lambda o: o['LastModified'])
        to_delete = backups[:len(backups) - config.max_backups]
        client.delete_objects(
            Bucket=config.bucket_name,
            Delete={'Objects': [{'Key': o['Key']} for o in to_delete]},
        )
        logger.info(f"Pruned {len(to_delete)} old backup(s) for {username}")
    except Exception as e:
        logger.warning(f"Failed to prune old backups for {username}: {e}")


def _run_backup(user_id):
    """Generate and upload a backup for a user."""
    from .models import BackupConfig
    from django.contrib.auth.models import User

    try:
        config = BackupConfig.objects.select_related('user').get(user_id=user_id)
    except BackupConfig.DoesNotExist:
        return

    config.last_backup_status = 'running'
    config.last_backup_error = 'building'
    config.last_backup_started_at = timezone.now()
    config.last_backup_heartbeat_at = config.last_backup_started_at
    config.last_backup_bytes_uploaded = 0
    config.last_backup_size = None
    config.save(update_fields=[
        'last_backup_status', 'last_backup_error', 'last_backup_started_at',
        'last_backup_heartbeat_at', 'last_backup_bytes_uploaded', 'last_backup_size',
    ])

    # Throttled liveness stamp; see BackupConfig.last_backup_heartbeat_at.
    _last_beat = [time.monotonic()]

    def _beat(**fields):
        now = time.monotonic()
        if not fields and now - _last_beat[0] < HEARTBEAT_EVERY_S:
            return
        _last_beat[0] = now
        try:
            BackupConfig.objects.filter(pk=config.pk).update(
                last_backup_heartbeat_at=timezone.now(), **fields)
        except Exception:
            pass

    tmp_zip = None
    try:
        user = config.user

        # Phase 1: build the archive to a private temp file — the same zip the
        # Settings download produces (build_backup_zip is shared), streamed
        # row-by-row so a large history never has to fit in memory.
        tmp_fd, tmp_zip = tempfile.mkstemp(
            prefix='roamly_s3_backup_', suffix='.zip', dir=_backup_tmp_dir())
        os.close(tmp_fd)   # build_backup_zip reopens it 0600
        result = build_backup_zip(user, tmp_zip, include_media=config.include_media,
                                  progress=lambda *_a: _beat())
        total = result['size']

        # Store total so the UI can show X / Y progress
        config.last_backup_error = 'uploading'
        config.last_backup_size = total
        config.last_backup_bytes_uploaded = 0
        config.save(update_fields=['last_backup_error', 'last_backup_size', 'last_backup_bytes_uploaded'])

        filename = f"{config.prefix}{user.username}/backup_{timezone.now().strftime('%Y-%m-%d_%H%M%S')}.zip"

        # Phase 2: upload with progress callback (updates DB every ~2 MB)
        _uploaded = [0]
        _last_saved = [0]
        UPDATE_EVERY = 2 * 1024 * 1024  # 2 MB

        def _progress(bytes_transferred):
            _uploaded[0] += bytes_transferred
            if _uploaded[0] - _last_saved[0] >= UPDATE_EVERY:
                _last_saved[0] = _uploaded[0]
                _beat(last_backup_bytes_uploaded=_uploaded[0])

        client = _get_s3_client(config)
        # From the file, not an in-memory BytesIO of the whole thing.
        with open(tmp_zip, 'rb') as fh:
            client.upload_fileobj(
                fh,
                config.bucket_name,
                filename,
                ExtraArgs={'ContentType': 'application/zip'},
                Callback=_progress,
            )

        config.last_backup_at = timezone.now()
        config.last_backup_status = 'success'
        config.last_backup_error = ''
        config.last_backup_size = total
        config.last_backup_bytes_uploaded = total
        config.last_backup_file_count = result['file_count']
        config.last_backup_warning = result['warning']
        config.save(update_fields=[
            'last_backup_at', 'last_backup_status', 'last_backup_error',
            'last_backup_size', 'last_backup_bytes_uploaded', 'last_backup_file_count',
            'last_backup_warning',
        ])

        logger.info(f"Backup completed for {user.username}: {total} bytes -> {filename}")

        # Prune old backups if max_backups is set
        if config.max_backups > 0:
            _prune_old_backups(client, config, user.username)
    except Exception as e:
        logger.error(f"Backup failed for user {user_id}: {e}")
        try:
            config.refresh_from_db()
            config.last_backup_status = 'failed'
            config.last_backup_error = str(e)[:500]
            config.save(update_fields=['last_backup_status', 'last_backup_error'])
        except Exception:
            pass
    finally:
        _backup_threads.pop(user_id, None)
        # Always remove the intermediate file — it is the user's whole history
        # sitting unencrypted on disk.
        if tmp_zip:
            try:
                os.unlink(tmp_zip)
            except OSError:
                pass


def run_backup_now(user_id):
    """Trigger an immediate backup in a background thread."""
    if user_id in _backup_threads and _backup_threads[user_id].is_alive():
        return 'already_running'

    thread = threading.Thread(target=_run_backup, args=(user_id,), daemon=True)
    _backup_threads[user_id] = thread
    thread.start()
    return 'started'


def _backup_scheduler_loop():
    """Periodically check all backup configs and run due backups."""
    from .models import BackupConfig

    # Started from apps.ready(), i.e. during apps.populate() — so the first
    # sweep below would otherwise race the tail of startup. See the helper.
    from .scheduler_utils import wait_for_app_registry
    wait_for_app_registry()

    while True:
        try:
            # This daemon thread has no request cycle, so Django never fires the
            # signals that enforce CONN_MAX_AGE / discard broken connections. The
            # sweep sleeps 900s (> conn_max_age 600s), so the reused connection is
            # stale by the next pass, and a DB restart leaves it errored. Without
            # this the first stale connection makes every later sweep raise (caught
            # below), silently stopping scheduled backups while manual "backup now"
            # (request-driven) still works. Force a fresh connection each sweep.
            close_old_connections()

            now = timezone.now()
            configs = BackupConfig.objects.filter(
                interval__in=['daily', 'weekly', 'monthly'],
            ).select_related('user')

            for config in configs:
                delta = INTERVAL_DELTAS.get(config.interval)
                if not delta:
                    continue

                # Skip if already running
                if config.user_id in _backup_threads and _backup_threads[config.user_id].is_alive():
                    continue

                # Check if backup is due
                if config.last_backup_at is None or (now - config.last_backup_at) >= delta:
                    # Atomically claim the backup slot to prevent duplicate runs across workers
                    claimed = BackupConfig.objects.filter(pk=config.pk).exclude(
                        last_backup_status='running'
                    ).update(last_backup_status='running', last_backup_error='')
                    if claimed:
                        logger.info(f"Scheduled backup starting for {config.user.username}")
                        run_backup_now(config.user_id)

        except Exception as e:
            logger.error(f"Backup scheduler error: {e}")

        time.sleep(SCHEDULER_CHECK_INTERVAL)


def start_backup_scheduler():
    """Start the backup scheduler thread (called once on app startup)."""
    global _scheduler_thread
    if _scheduler_thread is not None and _scheduler_thread.is_alive():
        return

    _scheduler_thread = threading.Thread(target=_backup_scheduler_loop, daemon=True)
    _scheduler_thread.start()
    logger.info("Backup scheduler started")


def stop_backup_now(user_id):
    """Force-stop a running backup by resetting its status."""
    from .models import BackupConfig

    updated = BackupConfig.objects.filter(
        user_id=user_id, last_backup_status='running'
    ).update(last_backup_status='stopped', last_backup_error='Manually stopped')
    return updated > 0


def _get_user_media_files(user):
    """Collect all media file paths (relative to MEDIA_ROOT) belonging to a user."""
    from .models import (
        UserProfile, AdventureBlurbPhoto, AdventureDayPhoto, Adventure, JournalPhoto,
    )

    files = []

    try:
        profile = UserProfile.objects.get(user=user)
        if profile.profile_picture:
            files.append(profile.profile_picture.name)
        if profile.profile_picture_thumbnail:
            files.append(profile.profile_picture_thumbnail.name)
    except UserProfile.DoesNotExist:
        pass

    for adv in Adventure.objects.filter(device__user=user):
        if adv.cover_image:
            files.append(adv.cover_image.name)
        if adv.cover_image_thumbnail:
            files.append(adv.cover_image_thumbnail.name)

    for photo in AdventureBlurbPhoto.objects.filter(blurb__adventure__device__user=user):
        if photo.image:
            files.append(photo.image.name)
        if photo.thumbnail:
            files.append(photo.thumbnail.name)
        if photo.video:
            files.append(photo.video.name)

    for photo in AdventureDayPhoto.objects.filter(day_note__adventure__device__user=user):
        if photo.image:
            files.append(photo.image.name)
        if photo.thumbnail:
            files.append(photo.thumbnail.name)
        if photo.video:
            files.append(photo.video.name)

    for photo in JournalPhoto.objects.filter(entry__user=user):
        if photo.image:
            files.append(photo.image.name)
        if photo.thumbnail:
            files.append(photo.thumbnail.name)

    return files


# Share of the progress bar given to the small sections before the locations
# scan. The data (JSON) phase fills up to _DATA_PHASE_PCT; the media-zipping
# phase fills the rest, so one bar covers both halves of a single backup.
_BACKUP_PREP_PCT = 8
_DATA_PHASE_PCT = 55
_BACKUP_STAGES = ['Counting locations', 'Collecting devices', 'Collecting adventures',
                  'Collecting journals', 'Collecting places', 'Collecting health',
                  'Collecting activities', 'Collecting family circles',
                  'Writing locations', 'Writing health', 'Writing cell samples']


def _backup_tmp_dir():
    """Private directory for in-progress backups.

    Not bare gettempdir(): these files are the user's complete location history,
    and open(path, 'wb') creates them 0644 under the default umask — so on any
    shared host every local user could read them. A 0700 directory inside the
    temp dir keeps that off the table without needing a writable path elsewhere.
    """
    d = os.path.join(tempfile.gettempdir(), 'roamly_backups')
    os.makedirs(d, mode=0o700, exist_ok=True)
    try:
        os.chmod(d, 0o700)   # makedirs won't tighten an existing directory
    except OSError:
        pass
    return d


def _open_private(path):
    """open(path, 'wb') that is 0600 from the moment it exists, not after."""
    return os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'wb')


def build_backup_zip(user, out_path, *, include_media=True, progress=None):
    """Write the full backup archive to out_path: backup.json + media/<path>.

    The one builder behind both the Settings download and the S3 auto-backup,
    so the file a user downloads and the file that lands in their bucket are
    the same by construction. include_media=False writes a data-only zip
    (backup.json alone), which restore_backup reads exactly the same way.

    progress(stage, pct, done, total) is called as the run advances; pct is
    0-100 across both phases. Returns {'size', 'file_count', 'warning'};
    warning is '' unless the archive exceeds what restore_backup accepts.
    """
    from django.conf import settings
    from .views import _write_backup_json

    def report(stage, pct, done=None, total=None):
        if progress:
            progress(stage, pct, done, total)

    def data_progress(stage, done, total):
        try:
            idx = _BACKUP_STAGES.index(stage)
        except ValueError:
            idx = 0
        if total:
            pct = _BACKUP_PREP_PCT + (_DATA_PHASE_PCT - _BACKUP_PREP_PCT) * done / total
        else:
            pct = _BACKUP_PREP_PCT * idx / len(_BACKUP_STAGES)
        report(stage, pct, done, total)

    # Intermediate plain-JSON file, zipped into the final archive and then
    # discarded — the zip's own deflate handles compression.
    json_fd, json_tmp_path = tempfile.mkstemp(
        prefix='roamly_backup_', suffix='.json', dir=_backup_tmp_dir())
    try:
        with os.fdopen(json_fd, 'wb') as f:   # mkstemp creates it 0600
            _write_backup_json(user, f, progress=data_progress)
        json_bytes = os.path.getsize(json_tmp_path)

        media_files = []
        if include_media:
            report('Collecting media', _DATA_PHASE_PCT)
            media_files = _get_user_media_files(user)
        media_total = len(media_files)

        # ZIP_STORED for media: jpg/mp4 are already compressed, so deflating
        # them again just burns CPU. backup.json (text) still deflates well.
        with _open_private(out_path) as _zf_fh, \
                zipfile.ZipFile(_zf_fh, 'w', zipfile.ZIP_DEFLATED) as zf:
            zf.write(json_tmp_path, arcname='backup.json')
            written = 0
            media_bytes = 0
            for relative_path in media_files:
                abs_path = os.path.join(settings.MEDIA_ROOT, relative_path)
                if os.path.exists(abs_path):
                    zf.write(abs_path, arcname=f'media/{relative_path}',
                             compress_type=zipfile.ZIP_STORED)
                    media_bytes += os.path.getsize(abs_path)
                written += 1
                if written % 20 == 0 or written == media_total:
                    report('Zipping media',
                           _DATA_PHASE_PCT + (100 - _DATA_PHASE_PCT) * written / media_total,
                           written, media_total)
    finally:
        try:
            os.unlink(json_tmp_path)
        except OSError:
            pass

    return {'size': os.path.getsize(out_path), 'file_count': media_total,
            'warning': restore_limit_warning(json_bytes, media_bytes)}


def restore_limit_warning(json_bytes, media_bytes):
    """Say so when an archive is bigger than restore_backup will accept.

    restore_backup refuses a zip whose backup.json or media total exceeds its
    decompression caps (a zip-bomb guard). A backup that can't be restored
    from Settings is worth knowing about when it's made, not on the day it's
    needed — the archive is still complete and extractable by hand.
    """
    from .views import _RESTORE_MAX_JSON_BYTES, _RESTORE_MAX_TOTAL_BYTES

    def gib(n):
        return f'{n / 1024 ** 3:g} GiB'

    if media_bytes > _RESTORE_MAX_TOTAL_BYTES:
        return (f'Media exceeds the {gib(_RESTORE_MAX_TOTAL_BYTES)} restore limit — '
                'this backup can\'t be restored from Settings as-is.')
    if json_bytes > _RESTORE_MAX_JSON_BYTES:
        return (f'Data exceeds the {gib(_RESTORE_MAX_JSON_BYTES)} restore limit — '
                'this backup can\'t be restored from Settings as-is.')
    return ''


def get_backup_status(user_id):
    """Get the current backup status for a user."""
    from .models import BackupConfig

    try:
        config = BackupConfig.objects.get(user_id=user_id)
    except BackupConfig.DoesNotExist:
        return {'configured': False}

    is_running = user_id in _backup_threads and _backup_threads[user_id].is_alive()

    # Auto-clear stale "running" status: no thread alive *in this process* and
    # no heartbeat for STALE_AFTER_S. Judged on the heartbeat, not the start
    # time — the run's thread usually lives in another gunicorn worker, and a
    # long, healthy backup would otherwise be failed ten minutes in.
    if config.last_backup_status == 'running' and not is_running:
        last_sign = config.last_backup_heartbeat_at or config.last_backup_started_at
        stale = (
            last_sign is None or
            (timezone.now() - last_sign).total_seconds() > STALE_AFTER_S
        )
        if stale:
            config.last_backup_status = 'failed'
            config.last_backup_error = 'Backup process was interrupted (timed out or server restarted)'
            config.save(update_fields=['last_backup_status', 'last_backup_error'])

    # Re-read progress fields fresh from DB (written by upload callback)
    config.refresh_from_db(fields=['last_backup_bytes_uploaded', 'last_backup_size', 'last_backup_error'])

    return {
        'configured': True,
        'interval': config.interval,
        'last_backup_at': config.last_backup_at.isoformat() if config.last_backup_at else None,
        'last_backup_status': 'running' if is_running else config.last_backup_status,
        'last_backup_phase': config.last_backup_error if is_running else '',
        'last_backup_bytes_uploaded': config.last_backup_bytes_uploaded,
        'last_backup_size': config.last_backup_size,
        'last_backup_error': config.last_backup_error if not is_running else '',
        'last_backup_file_count': config.last_backup_file_count,
        'last_backup_warning': config.last_backup_warning,
        'include_media': config.include_media,
    }
