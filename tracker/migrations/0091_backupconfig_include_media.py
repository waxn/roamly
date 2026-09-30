"""S3 backups upload the full backup zip; per-account media toggle.

include_media defaults True, which also backfills every existing config, so
existing S3 backups switch to the same archive as the Settings download
(backup.json + media/) on their next run. last_backup_file_count records how
many media files that run zipped, for the status line.
"""
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0090_backupconfig_heartbeat'),
    ]

    operations = [
        migrations.AddField(
            model_name='backupconfig',
            name='include_media',
            field=models.BooleanField(default=True),
        ),
        migrations.AddField(
            model_name='backupconfig',
            name='last_backup_file_count',
            field=models.IntegerField(blank=True, null=True),
        ),
    ]
