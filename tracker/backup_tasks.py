import io
import json
import os
import threading
import logging
import time
from datetime import timedelta

from django.db import close_old_connections
from django.utils import timezone
from django.core.serializers.json import DjangoJSONEncoder

logger = logging.getLogger(__name__)

_scheduler_thread = None
_backup_threads = {}  # user_id -> thread
_image_backup_threads = {}  # user_id -> thread

SCHEDULER_CHECK_INTERVAL = 900  # 15 minutes

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
                {'username': m.user.username, 'role': m.role}
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
            'hc_id': w.hc_id,
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


def _build_backup_json(user):
    """Build the backup JSON data dict for a user (same format as export_backup view)."""
    from .models import Device, Location, APIKey, HealthSample

    devices = Device.objects.filter(user=user)
    locations = Location.objects.filter(device__user=user).select_related('device').order_by('timestamp')
    api_keys = APIKey.objects.filter(user=user)

    data = {
        'meta': {
            'version': 10,
            'exported_at': timezone.now().isoformat(),
            'username': user.username,
        },
        'devices': [
            {'device_id': d.device_id, 'name': d.name}
            for d in devices
        ],
        'locations': [
            {
                'device_id': loc.device.device_id,
                'latitude': loc.latitude,
                'longitude': loc.longitude,
                'altitude': loc.altitude,
                'accuracy': loc.accuracy,
                'speed': loc.speed,
                'battery': loc.battery,
                'timestamp': loc.timestamp,
                'city': loc.city,
                'state': loc.state,
                'country': loc.country,
                'country_code': loc.country_code,
                'place_name': loc.place_name,
            }
            for loc in locations
        ],
        'adventures': _build_adventures_data(user),
        'api_keys': [
            {
                'name': k.name,
                'key': k.key,
                'is_active': k.is_active,
                'created_at': k.created_at,
            }
            for k in api_keys
        ],
        'journals': _build_journals_data(user),
        'custom_places': _build_custom_places_data(user),
        # Health Connect data. Genuinely irreplaceable rather than a cache of
        # something re-derivable: Health Connect only serves the trailing 30 days
        # without READ_HEALTH_DATA_HISTORY, and a source app can retract records
        # at any time, so this copy is the long-term archive.
        'health_workouts': _build_health_workouts_data(user),
        'health_samples': list(
            HealthSample.objects.filter(user=user).order_by('start_time').values(
                'kind', 'value', 'start_time', 'end_time', 'zone_offset_seconds',
                'source', 'device_id', 'hc_id', 'last_modified',
            )
        ),
    }
    return json.dumps(data, cls=DjangoJSONEncoder)


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
        # Only consider .json backup files
        backups = [o for o in objects if o['Key'].endswith('.json')]
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
    config.last_backup_bytes_uploaded = 0
    config.last_backup_size = None
    config.save(update_fields=[
        'last_backup_status', 'last_backup_error', 'last_backup_started_at',
        'last_backup_bytes_uploaded', 'last_backup_size',
    ])

    try:
        user = config.user

        # Phase 1: build JSON
        backup_json = _build_backup_json(user)
        backup_bytes = backup_json.encode('utf-8')
        total = len(backup_bytes)

        # Store total so the UI can show X / Y progress
        config.last_backup_error = 'uploading'
        config.last_backup_size = total
        config.last_backup_bytes_uploaded = 0
        config.save(update_fields=['last_backup_error', 'last_backup_size', 'last_backup_bytes_uploaded'])

        filename = f"{config.prefix}{user.username}/backup_{timezone.now().strftime('%Y-%m-%d_%H%M%S')}.json"

        # Phase 2: upload with progress callback (updates DB every ~2 MB)
        _uploaded = [0]
        _last_saved = [0]
        UPDATE_EVERY = 2 * 1024 * 1024  # 2 MB

        def _progress(bytes_transferred):
            _uploaded[0] += bytes_transferred
            if _uploaded[0] - _last_saved[0] >= UPDATE_EVERY:
                _last_saved[0] = _uploaded[0]
                try:
                    BackupConfig.objects.filter(pk=config.pk).update(
                        last_backup_bytes_uploaded=_uploaded[0]
                    )
                except Exception:
                    pass

        client = _get_s3_client(config)
        client.upload_fileobj(
            io.BytesIO(backup_bytes),
            config.bucket_name,
            filename,
            ExtraArgs={'ContentType': 'application/json'},
            Callback=_progress,
        )

        config.last_backup_at = timezone.now()
        config.last_backup_status = 'success'
        config.last_backup_error = ''
        config.last_backup_size = total
        config.last_backup_bytes_uploaded = total
        config.save(update_fields=[
            'last_backup_at', 'last_backup_status', 'last_backup_error',
            'last_backup_size', 'last_backup_bytes_uploaded',
        ])

        logger.info(f"Backup completed for {user.username}: {len(backup_bytes)} bytes -> {filename}")

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


def _get_image_s3_client(config):
    """Get S3 client for image backup — same creds or separate, depending on config."""
    if config.image_use_same_creds:
        return _get_s3_client(config)
    import boto3
    from botocore.config import Config
    return boto3.client(
        's3',
        endpoint_url=config.image_endpoint_url,
        aws_access_key_id=config.image_access_key,
        aws_secret_access_key=config.image_secret_key,
        region_name=config.image_region or 'auto',
        config=Config(
            signature_version='s3v4',
            s3={'addressing_style': 'path'},
            connect_timeout=30,
            read_timeout=120,
        ),
    )


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


def _run_image_backup(user_id):
    """Upload all user media files to S3."""
    from .models import BackupConfig
    from django.conf import settings

    try:
        config = BackupConfig.objects.select_related('user').get(user_id=user_id)
    except BackupConfig.DoesNotExist:
        return

    config.last_image_backup_status = 'running'
    config.last_image_backup_error = ''
    config.save(update_fields=['last_image_backup_status', 'last_image_backup_error'])

    try:
        user = config.user
        client = _get_image_s3_client(config)
        bucket = config.bucket_name if config.image_use_same_creds else config.image_bucket_name

        media_files = _get_user_media_files(user)
        total_size = 0

        for relative_path in media_files:
            abs_path = os.path.join(settings.MEDIA_ROOT, relative_path)
            if not os.path.exists(abs_path):
                continue
            s3_key = f"{config.image_prefix}{user.username}/{relative_path}"
            with open(abs_path, 'rb') as f:
                client.put_object(Bucket=bucket, Key=s3_key, Body=f.read())
            total_size += os.path.getsize(abs_path)

        config.last_image_backup_at = timezone.now()
        config.last_image_backup_status = 'success'
        config.last_image_backup_error = ''
        config.last_image_backup_size = total_size
        config.save(update_fields=[
            'last_image_backup_at', 'last_image_backup_status',
            'last_image_backup_error', 'last_image_backup_size',
        ])
        logger.info(f"Image backup completed for {user.username}: {len(media_files)} files, {total_size} bytes")

    except Exception as e:
        logger.error(f"Image backup failed for user {user_id}: {e}")
        try:
            config.refresh_from_db()
            config.last_image_backup_status = 'failed'
            config.last_image_backup_error = str(e)[:500]
            config.save(update_fields=['last_image_backup_status', 'last_image_backup_error'])
        except Exception:
            pass
    finally:
        _image_backup_threads.pop(user_id, None)


def run_image_backup_now(user_id):
    """Trigger an immediate image backup in a background thread."""
    if user_id in _image_backup_threads and _image_backup_threads[user_id].is_alive():
        return 'already_running'
    thread = threading.Thread(target=_run_image_backup, args=(user_id,), daemon=True)
    _image_backup_threads[user_id] = thread
    thread.start()
    return 'started'


def get_image_backup_status(user_id):
    """Get image backup status for a user."""
    from .models import BackupConfig

    try:
        config = BackupConfig.objects.get(user_id=user_id)
    except BackupConfig.DoesNotExist:
        return {'configured': False}

    is_running = user_id in _image_backup_threads and _image_backup_threads[user_id].is_alive()

    return {
        'configured': True,
        'enabled': config.image_backup_enabled,
        'last_image_backup_at': config.last_image_backup_at.isoformat() if config.last_image_backup_at else None,
        'last_image_backup_status': 'running' if is_running else config.last_image_backup_status,
        'last_image_backup_error': config.last_image_backup_error,
        'last_image_backup_size': config.last_image_backup_size,
    }


def get_backup_status(user_id):
    """Get the current backup status for a user."""
    from .models import BackupConfig

    try:
        config = BackupConfig.objects.get(user_id=user_id)
    except BackupConfig.DoesNotExist:
        return {'configured': False}

    is_running = user_id in _backup_threads and _backup_threads[user_id].is_alive()

    # Auto-clear stale "running" status: if no thread is alive and it's been
    # more than 10 minutes since the backup started, mark it as failed.
    if config.last_backup_status == 'running' and not is_running:
        stale = (
            config.last_backup_started_at is None or
            (timezone.now() - config.last_backup_started_at).total_seconds() > 600
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
    }
