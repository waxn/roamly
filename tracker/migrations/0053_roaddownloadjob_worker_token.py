from django.db import migrations, models


class Migration(migrations.Migration):
    """Give a road-download run an owner.

    tracker.road_download_tasks._running_threads is per-process, so with several
    gunicorn workers the "is the thread still alive?" check could not see a
    thread running in another process and resurrected a duplicate. Each worker
    kept its own progress counter, so they overwrote each other and the reported
    percentage jumped around and went backwards.
    """

    dependencies = [
        ('tracker', '0052_map_editor'),
    ]

    operations = [
        migrations.AddField(
            model_name='roaddownloadjob',
            name='worker_token',
            field=models.CharField(blank=True, default='', max_length=32),
        ),
    ]
