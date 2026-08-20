from django.db import migrations, models


def _clear_download_jobs(apps, schema_editor):
    """Job *status* has no value worth carrying across the per-user -> singleton
    shape change — a stale "running: 40/82" from the old per-user model would
    be meaningless once there's no user left to attribute it to. Fresh rows
    are recreated on demand by RoadDownloadJob.load() etc."""
    for name in ('RoadDownloadJob', 'RailDownloadJob', 'POIDownloadJob'):
        apps.get_model('tracker', name).objects.all().delete()


class Migration(migrations.Migration):
    """Road/subway/POI download jobs become admin-only, instance-wide
    singletons (pk=1, SiteConfig-style) instead of one row per user — the
    data they populate (RoadSegment, RailSegment/RailStation, POI) was
    already instance-wide with no user FK; only the job tracking and
    area-selection scoping were tied to a single user. See
    tracker/road_download_tasks.py, rail_download_tasks.py, poi_tasks.py,
    and the admin panel's Downloads tab."""

    dependencies = [
        ('tracker', '0066_userprofile_valhalla_url'),
    ]

    operations = [
        migrations.RunPython(_clear_download_jobs, migrations.RunPython.noop),
        migrations.RemoveField(model_name='roaddownloadjob', name='user'),
        migrations.RemoveField(model_name='raildownloadjob', name='user'),
        migrations.RemoveField(model_name='poidownloadjob', name='user'),
        migrations.AddField(
            model_name='poidownloadjob',
            name='worker_token',
            field=models.CharField(max_length=32, blank=True, default=''),
        ),
        migrations.AddField(
            model_name='poidownloadjob',
            name='failed',
            field=models.IntegerField(default=0),
        ),
    ]
