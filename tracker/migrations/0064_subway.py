import django.contrib.gis.db.models.fields
import django.contrib.postgres.indexes
import django.db.models.deletion
from django.db import migrations, models


class Migration(migrations.Migration):
    """Subway geometry + gap-fill support.

    RailSegment/RailStation mirror RoadSegment: PostGIS geometry columns, so
    they are Postgres/PostGIS-only (on SQLite subway routing is unavailable,
    like the local road provider). RailDownloadJob clones RoadDownloadJob.
    DismissedSubwayGap records rejected gap suggestions.

    All four tables are derived/operational or user-intent data — never
    referenced by the backup builders — so the backup meta.version is unchanged.
    The EditBatch.kind AlterField only adds a choice ('subway'); no SQL change.
    """

    dependencies = [
        ('tracker', '0063_siteconfig_turnstile'),
    ]

    operations = [
        migrations.CreateModel(
            name='RailSegment',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('way_id', models.BigIntegerField(unique=True)),
                ('name', models.CharField(blank=True, max_length=200)),
                ('railway', models.CharField(db_index=True, max_length=32)),
                ('geom', django.contrib.gis.db.models.fields.LineStringField(srid=4326)),
            ],
        ),
        migrations.CreateModel(
            name='RailStation',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('node_id', models.BigIntegerField(unique=True)),
                ('name', models.CharField(blank=True, max_length=200)),
                ('geom', django.contrib.gis.db.models.fields.PointField(srid=4326)),
            ],
        ),
        migrations.CreateModel(
            name='RailDownloadJob',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('status', models.CharField(choices=[('running', 'Running'), ('completed', 'Completed'), ('stopped', 'Stopped')], default='running', max_length=20)),
                ('processed', models.IntegerField(default=0)),
                ('total', models.IntegerField(default=0)),
                ('ways', models.IntegerField(default=0)),
                ('stations', models.IntegerField(default=0)),
                ('failed', models.IntegerField(default=0)),
                ('worker_token', models.CharField(blank=True, default='', max_length=32)),
                ('started_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                ('user', models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, related_name='rail_download_job', to='auth.user')),
            ],
        ),
        migrations.CreateModel(
            name='DismissedSubwayGap',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('start_latitude', models.FloatField()),
                ('start_longitude', models.FloatField()),
                ('end_latitude', models.FloatField()),
                ('end_longitude', models.FloatField()),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='dismissed_subway_gaps', to='auth.user')),
            ],
        ),
        migrations.AddIndex(
            model_name='railsegment',
            index=django.contrib.postgres.indexes.GistIndex(fields=['geom'], name='tracker_rail_geom_gist'),
        ),
        migrations.AddIndex(
            model_name='railstation',
            index=django.contrib.postgres.indexes.GistIndex(fields=['geom'], name='tracker_railstn_geom_gist'),
        ),
        migrations.AddIndex(
            model_name='dismissedsubwaygap',
            index=models.Index(fields=['user'], name='tracker_dismsub_user_idx'),
        ),
        migrations.AlterField(
            model_name='editbatch',
            name='kind',
            field=models.CharField(choices=[('route', 'Road route'), ('manual', 'Manual point'), ('run', 'Point run'), ('copy', 'Copied range'), ('delete', 'Deleted points'), ('subway', 'Subway route')], max_length=10),
        ),
    ]
