"""Heartbeat for S3 backup runs.

get_backup_status declares a run interrupted when the polling worker sees no
live thread and the run started more than ten minutes ago. With several
gunicorn workers the thread usually lives in another process, so a long but
healthy backup was marked failed mid-run. The run now stamps this column as it
makes progress, and staleness is judged on it instead.
"""
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0089_cell_samples'),
    ]

    operations = [
        migrations.AddField(
            model_name='backupconfig',
            name='last_backup_heartbeat_at',
            field=models.DateTimeField(blank=True, null=True),
        ),
    ]
