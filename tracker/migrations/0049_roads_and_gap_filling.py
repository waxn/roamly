import django.contrib.gis.db.models.fields
import django.contrib.postgres.fields
import django.contrib.postgres.indexes
import django.db.models.deletion
from django.db import migrations, models


class Migration(migrations.Migration):
    """Road geometry + inferred gap-fill points.

    Written against the PostGIS model shape (like 0022_boundary), which is the
    supported deployment. RoadSegment.geom and the bigint[] node array are
    PostGIS/Postgres-only; on SQLite the local road provider is unavailable and
    the app falls back to Mapbox/OSRM.

    All five tables are derived/operational data — never referenced by the
    backup builders — so the backup meta.version is deliberately unchanged.
    """

    dependencies = [
        ('tracker', '0048_admin_logging'),
    ]

    operations = [
        migrations.CreateModel(
            name='RoadSegment',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('way_id', models.BigIntegerField(unique=True)),
                ('name', models.CharField(blank=True, max_length=200)),
                ('highway', models.CharField(db_index=True, max_length=32)),
                ('oneway', models.BooleanField(default=False)),
                ('node_ids', django.contrib.postgres.fields.ArrayField(
                    base_field=models.BigIntegerField(), blank=True, default=list, size=None)),
                ('geom', django.contrib.gis.db.models.fields.LineStringField(srid=4326)),
            ],
        ),
        migrations.CreateModel(
            name='RoadDownloadJob',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('status', models.CharField(choices=[('running', 'Running'), ('completed', 'Completed'), ('stopped', 'Stopped')], default='running', max_length=20)),
                ('processed', models.IntegerField(default=0)),
                ('total', models.IntegerField(default=0)),
                ('ways', models.IntegerField(default=0)),
                ('started_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                ('user', models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, related_name='road_download_job', to='auth.user')),
            ],
        ),
        migrations.CreateModel(
            name='GapFillJob',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('status', models.CharField(choices=[('running', 'Running'), ('completed', 'Completed'), ('stopped', 'Stopped')], default='running', max_length=20)),
                ('processed', models.IntegerField(default=0)),
                ('total', models.IntegerField(default=0)),
                ('filled', models.IntegerField(default=0)),
                ('started_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                ('user', models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, related_name='gap_fill_job', to='auth.user')),
            ],
        ),
        migrations.CreateModel(
            name='InferredGap',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('start_location_id', models.BigIntegerField()),
                ('end_location_id', models.BigIntegerField()),
                ('start_time', models.DateTimeField()),
                ('end_time', models.DateTimeField()),
                ('distance_m', models.FloatField(default=0)),
                ('route_distance_m', models.FloatField(default=0)),
                ('provider', models.CharField(blank=True, default='', max_length=10)),
                ('status', models.CharField(choices=[('filled', 'Filled'), ('straight', 'Straight'), ('dismissed', 'Dismissed'), ('failed', 'Failed'), ('skipped', 'Skipped')], db_index=True, default='filled', max_length=12)),
                ('detail', models.CharField(blank=True, default='', max_length=200)),
                ('point_count', models.IntegerField(default=0)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('device', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='inferred_gaps', to='tracker.device')),
            ],
            options={
                'ordering': ['-start_time'],
            },
        ),
        migrations.CreateModel(
            name='InferredLocation',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('latitude', models.FloatField()),
                ('longitude', models.FloatField()),
                ('timestamp', models.DateTimeField()),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('device', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='inferred_locations', to='tracker.device')),
                ('gap', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='points', to='tracker.inferredgap')),
            ],
            options={
                'ordering': ['timestamp'],
            },
        ),
        migrations.AddField(
            model_name='userprofile',
            name='road_provider',
            field=models.CharField(blank=True, default='', max_length=10),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='osrm_url',
            field=models.CharField(blank=True, default='', max_length=300),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='snap_to_roads',
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='gap_fill_enabled',
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='gap_fill_auto',
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='gap_fill_min_minutes',
            field=models.IntegerField(default=2),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='gap_fill_last_run',
            field=models.DateTimeField(blank=True, null=True),
        ),
        migrations.AddIndex(
            model_name='roadsegment',
            index=django.contrib.postgres.indexes.GistIndex(fields=['geom'], name='tracker_road_geom_gist'),
        ),
        migrations.AddIndex(
            model_name='inferredgap',
            index=models.Index(fields=['device', '-start_time'], name='tracker_gap_device__idx'),
        ),
        migrations.AddIndex(
            model_name='inferredlocation',
            index=models.Index(fields=['device', 'timestamp'], name='tracker_inf_device__idx'),
        ),
        migrations.AlterUniqueTogether(
            name='inferredgap',
            unique_together={('device', 'start_location_id', 'end_location_id')},
        ),
    ]
