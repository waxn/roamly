"""Remove the separate S3 image backup.

S3 backups now upload the full backup zip, which carries media/ by default
(BackupConfig.include_media, 0091) — the same files the image backup
uploaded loose, and restorable in one step. The image backup and its own
credentials, prefix and status columns are dropped. Objects it already
uploaded to users' buckets are left where they are.
"""
from django.db import migrations


_FIELDS = [
    'image_backup_enabled', 'image_use_same_creds', 'image_endpoint_url',
    'image_bucket_name', 'image_access_key', 'image_secret_key',
    'image_prefix', 'image_region', 'last_image_backup_at',
    'last_image_backup_status', 'last_image_backup_error',
    'last_image_backup_size',
]


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0091_backupconfig_include_media'),
    ]

    operations = [
        migrations.RemoveField(model_name='backupconfig', name=name)
        for name in _FIELDS
    ]
