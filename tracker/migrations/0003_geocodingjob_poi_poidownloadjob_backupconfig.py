"""Register GeocodingJob, POI, POIDownloadJob, and BackupConfig models.

Uses CREATE TABLE IF NOT EXISTS so it's safe to run on databases where
the tables were already created outside migrations.
"""
import django.db.models.deletion
from django.conf import settings
from django.db import migrations, models


CREATE_TABLES_SQL = """
CREATE TABLE IF NOT EXISTS tracker_geocodingjob (
    id bigserial PRIMARY KEY,
    status varchar(20) NOT NULL DEFAULT 'running',
    processed integer NOT NULL DEFAULT 0,
    errors integer NOT NULL DEFAULT 0,
    total integer NOT NULL DEFAULT 0,
    started_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW(),
    user_id integer NOT NULL UNIQUE REFERENCES auth_user(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS tracker_poi (
    id bigserial PRIMARY KEY,
    name varchar(300) NOT NULL,
    latitude double precision NOT NULL,
    longitude double precision NOT NULL,
    category varchar(100) NOT NULL DEFAULT '',
    address varchar(500) NOT NULL DEFAULT '',
    UNIQUE (name, latitude, longitude)
);
CREATE INDEX IF NOT EXISTS tracker_poi_latitud_idx ON tracker_poi (latitude, longitude);
CREATE INDEX IF NOT EXISTS tracker_poi_name_idx ON tracker_poi (name);

CREATE TABLE IF NOT EXISTS tracker_poidownloadjob (
    id bigserial PRIMARY KEY,
    status varchar(20) NOT NULL DEFAULT 'running',
    processed integer NOT NULL DEFAULT 0,
    total integer NOT NULL DEFAULT 0,
    pois_added integer NOT NULL DEFAULT 0,
    started_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW(),
    user_id integer NOT NULL UNIQUE REFERENCES auth_user(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS tracker_backupconfig (
    id bigserial PRIMARY KEY,
    endpoint_url varchar(500) NOT NULL,
    bucket_name varchar(200) NOT NULL,
    access_key varchar(200) NOT NULL,
    secret_key varchar(200) NOT NULL,
    prefix varchar(200) NOT NULL DEFAULT 'roamly-backups/',
    region varchar(100) NOT NULL DEFAULT 'auto',
    interval varchar(20) NOT NULL DEFAULT 'disabled',
    last_backup_at timestamptz,
    last_backup_status varchar(20) NOT NULL DEFAULT 'never',
    last_backup_error text NOT NULL DEFAULT '',
    last_backup_size integer,
    user_id integer NOT NULL UNIQUE REFERENCES auth_user(id) ON DELETE CASCADE
);
"""


class Migration(migrations.Migration):

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
        ('tracker', '0002_tripplace_radius'),
    ]

    operations = [
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
                migrations.RunSQL(CREATE_TABLES_SQL, migrations.RunSQL.noop),
            ],
        ),
    ]
