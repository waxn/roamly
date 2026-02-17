import io
import json
import threading
import logging
import time
from datetime import timedelta

from django.utils import timezone
from django.core.serializers.json import DjangoJSONEncoder

logger = logging.getLogger(__name__)

_scheduler_thread = None
_backup_threads = {}  # user_id -> thread

SCHEDULER_CHECK_INTERVAL = 900  # 15 minutes

INTERVAL_DELTAS = {
    'daily': timedelta(days=1),
    'weekly': timedelta(weeks=1),
    'monthly': timedelta(days=30),
}


def _build_backup_json(user):
    """Build the backup JSON data dict for a user (same format as export_backup view)."""
    from .models import Device, Location, Trip, TripPlace, APIKey

    devices = Device.objects.filter(user=user)
    locations = Location.objects.filter(device__user=user).select_related('device').order_by('timestamp')
    trips = Trip.objects.filter(device__user=user).select_related('device')
    trip_places = TripPlace.objects.filter(trip__device__user=user).select_related('trip', 'trip__device')
    api_keys = APIKey.objects.filter(user=user)

    data = {
        'meta': {
            'version': 1,
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
        'trips': [
            {
                'device_id': t.device.device_id,
                'name': t.name,
                'description': t.description,
                'start_time': t.start_time,
                'end_time': t.end_time,
            }
            for t in trips
        ],
        'trip_places': [
            {
                'trip_name': tp.trip.name,
                'trip_device_id': tp.trip.device.device_id,
                'trip_start_time': tp.trip.start_time,
                'name': tp.name,
                'latitude': tp.latitude,
                'longitude': tp.longitude,
                'radius': tp.radius,
                'notes': tp.notes,
                'visited_at': tp.visited_at,
            }
            for tp in trip_places
        ],
        'api_keys': [
            {
                'name': k.name,
                'key': k.key,
                'is_active': k.is_active,
                'created_at': k.created_at,
            }
            for k in api_keys
        ],
    }
    return json.dumps(data, cls=DjangoJSONEncoder, indent=2)


def _get_s3_client(config):
    """Create a boto3 S3 client from a BackupConfig."""
    import boto3
    return boto3.client(
        's3',
        endpoint_url=config.endpoint_url,
        aws_access_key_id=config.access_key,
        aws_secret_access_key=config.secret_key,
        region_name=config.region or 'auto',
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
    config.last_backup_error = ''
    config.save(update_fields=['last_backup_status', 'last_backup_error'])

    try:
        user = config.user
        backup_json = _build_backup_json(user)
        backup_bytes = backup_json.encode('utf-8')

        filename = f"{config.prefix}{user.username}/backup_{timezone.now().strftime('%Y-%m-%d_%H%M%S')}.json"

        client = _get_s3_client(config)
        client.put_object(
            Bucket=config.bucket_name,
            Key=filename,
            Body=backup_bytes,
            ContentType='application/json',
        )

        config.last_backup_at = timezone.now()
        config.last_backup_status = 'success'
        config.last_backup_error = ''
        config.last_backup_size = len(backup_bytes)
        config.save(update_fields=['last_backup_at', 'last_backup_status', 'last_backup_error', 'last_backup_size'])

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


def get_backup_status(user_id):
    """Get the current backup status for a user."""
    from .models import BackupConfig

    try:
        config = BackupConfig.objects.get(user_id=user_id)
    except BackupConfig.DoesNotExist:
        return {'configured': False}

    is_running = user_id in _backup_threads and _backup_threads[user_id].is_alive()

    return {
        'configured': True,
        'interval': config.interval,
        'last_backup_at': config.last_backup_at.isoformat() if config.last_backup_at else None,
        'last_backup_status': 'running' if is_running else config.last_backup_status,
        'last_backup_error': config.last_backup_error,
        'last_backup_size': config.last_backup_size,
    }
