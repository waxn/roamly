"""Register GeocodingJob, POI, POIDownloadJob, and BackupConfig models.

Some of these tables may already exist in the database (created outside
migrations or by partial migration runs). We use RunPython to only create
tables that don't yet exist.
"""
import django.db.models.deletion
from django.conf import settings
from django.db import connection, migrations, models


def create_missing_tables(apps, schema_editor):
    """Create only tables that don't already exist in the database."""
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public'"
        )
        existing = {row[0] for row in cursor.fetchall()}

    models_to_check = [
        'tracker_geocodingjob',
        'tracker_poi',
        'tracker_poidownloadjob',
        'tracker_backupconfig',
    ]
    model_names = {
        'tracker_geocodingjob': 'GeocodingJob',
        'tracker_poi': 'POI',
        'tracker_poidownloadjob': 'POIDownloadJob',
        'tracker_backupconfig': 'BackupConfig',
    }

    for table_name in models_to_check:
        if table_name not in existing:
            model = apps.get_model('tracker', model_names[table_name])
            with schema_editor.connection.schema_editor() as editor:
                editor.create_model(model)


class Migration(migrations.Migration):

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
        ('tracker', '0002_tripplace_radius'),
    ]

    operations = [
        # Register all models in Django's migration state
        migrations.SeparateDatabaseAndState(
            state_operations=[
                migrations.CreateModel(
                    name='GeocodingJob',
                    fields=[
                        ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                        ('status', models.CharField(choices=[('running', 'Running'), ('completed', 'Completed'), ('stopped', 'Stopped')], default='running', max_length=20)),
                        ('processed', models.IntegerField(default=0)),
                        ('errors', models.IntegerField(default=0)),
                        ('total', models.IntegerField(default=0)),
                        ('started_at', models.DateTimeField(auto_now_add=True)),
                        ('updated_at', models.DateTimeField(auto_now=True)),
                        ('user', models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, related_name='geocoding_job', to=settings.AUTH_USER_MODEL)),
                    ],
                ),
                migrations.CreateModel(
                    name='POI',
                    fields=[
                        ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                        ('name', models.CharField(db_index=True, max_length=300)),
                        ('latitude', models.FloatField()),
                        ('longitude', models.FloatField()),
                        ('category', models.CharField(blank=True, max_length=100)),
                        ('address', models.CharField(blank=True, max_length=500)),
                    ],
                    options={
                        'indexes': [models.Index(fields=['latitude', 'longitude'], name='tracker_poi_latitud_idx')],
                        'unique_together': {('name', 'latitude', 'longitude')},
                    },
                ),
                migrations.CreateModel(
                    name='POIDownloadJob',
                    fields=[
                        ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                        ('status', models.CharField(choices=[('running', 'Running'), ('completed', 'Completed'), ('stopped', 'Stopped')], default='running', max_length=20)),
                        ('processed', models.IntegerField(default=0)),
                        ('total', models.IntegerField(default=0)),
                        ('pois_added', models.IntegerField(default=0)),
                        ('started_at', models.DateTimeField(auto_now_add=True)),
                        ('updated_at', models.DateTimeField(auto_now=True)),
                        ('user', models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, related_name='poi_job', to=settings.AUTH_USER_MODEL)),
                    ],
                ),
                migrations.CreateModel(
                    name='BackupConfig',
                    fields=[
                        ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                        ('endpoint_url', models.URLField(max_length=500)),
                        ('bucket_name', models.CharField(max_length=200)),
                        ('access_key', models.CharField(max_length=200)),
                        ('secret_key', models.CharField(max_length=200)),
                        ('prefix', models.CharField(blank=True, default='roamly-backups/', max_length=200)),
                        ('region', models.CharField(blank=True, default='auto', max_length=100)),
                        ('interval', models.CharField(choices=[('disabled', 'Disabled'), ('daily', 'Daily'), ('weekly', 'Weekly'), ('monthly', 'Monthly')], default='disabled', max_length=20)),
                        ('last_backup_at', models.DateTimeField(blank=True, null=True)),
                        ('last_backup_status', models.CharField(choices=[('never', 'Never'), ('success', 'Success'), ('failed', 'Failed'), ('running', 'Running')], default='never', max_length=20)),
                        ('last_backup_error', models.TextField(blank=True)),
                        ('last_backup_size', models.IntegerField(blank=True, null=True)),
                        ('user', models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, related_name='backup_config', to=settings.AUTH_USER_MODEL)),
                    ],
                ),
            ],
            database_operations=[
                # Use RunPython to only create tables that don't already exist
                migrations.RunPython(create_missing_tables, migrations.RunPython.noop),
            ],
        ),
    ]
