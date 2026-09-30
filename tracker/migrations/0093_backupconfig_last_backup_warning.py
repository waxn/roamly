"""Record when an S3 backup is larger than restore_backup accepts.

S3 backups now carry media by default, and restore_backup caps a zip's
decompressed size as a zip-bomb guard. A backup that can't be restored from
Settings should say so when it is made, not on the day it's needed.
"""
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0092_remove_backupconfig_image_backup'),
    ]

    operations = [
        migrations.AddField(
            model_name='backupconfig',
            name='last_backup_warning',
            field=models.TextField(blank=True, default=''),
        ),
    ]
