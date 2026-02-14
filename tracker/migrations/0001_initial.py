import django.contrib.gis.db.models.fields
import django.db.models.deletion
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):

    initial = True

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.CreateModel(
            name='APIKey',
            fields=[
                ('id', models.AutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('key', models.CharField(db_index=True, max_length=64, unique=True)),
                ('name', models.CharField(help_text="Device name, e.g., 'iPhone'", max_length=100)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('last_used', models.DateTimeField(blank=True, null=True)),
                ('is_active', models.BooleanField(default=True)),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='api_keys', to=settings.AUTH_USER_MODEL)),
            ],
        ),
        migrations.CreateModel(
            name='Device',
            fields=[
                ('id', models.AutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('device_id', models.CharField(max_length=100)),
                ('name', models.CharField(blank=True, max_length=100)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='devices', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'unique_together': {('user', 'device_id')},
            },
        ),
        migrations.CreateModel(
            name='Location',
            fields=[
                ('id', models.AutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('latitude', models.FloatField()),
                ('longitude', models.FloatField()),
                ('location', django.contrib.gis.db.models.fields.PointField(blank=True, geography=True, null=True, srid=4326)),
                ('altitude', models.FloatField(blank=True, null=True)),
                ('accuracy', models.FloatField(blank=True, null=True)),
                ('speed', models.FloatField(blank=True, null=True)),
                ('battery', models.FloatField(blank=True, null=True)),
                ('timestamp', models.DateTimeField()),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('city', models.CharField(blank=True, max_length=200)),
                ('state', models.CharField(blank=True, max_length=200)),
                ('country', models.CharField(blank=True, max_length=100)),
                ('country_code', models.CharField(blank=True, max_length=3)),
                ('place_name', models.CharField(blank=True, max_length=300)),
                ('device', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='locations', to='tracker.device')),
            ],
            options={
                'ordering': ['-timestamp'],
                'unique_together': {('device', 'latitude', 'longitude', 'timestamp')},
            },
        ),
        migrations.AddIndex(
            model_name='location',
            index=models.Index(fields=['device', '-timestamp'], name='tracker_loc_device__idx'),
        ),
        migrations.AddIndex(
            model_name='location',
            index=models.Index(fields=['city'], name='tracker_loc_city_idx'),
        ),
        migrations.AddIndex(
            model_name='location',
            index=models.Index(fields=['country'], name='tracker_loc_country_idx'),
        ),
        migrations.AddIndex(
            model_name='location',
            index=models.Index(fields=['timestamp'], name='tracker_loc_timesta_idx'),
        ),
        migrations.CreateModel(
            name='Trip',
            fields=[
                ('id', models.AutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('name', models.CharField(max_length=200)),
                ('description', models.TextField(blank=True)),
                ('start_time', models.DateTimeField()),
                ('end_time', models.DateTimeField()),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('device', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='trips', to='tracker.device')),
            ],
            options={
                'ordering': ['-start_time'],
            },
        ),
        migrations.CreateModel(
            name='TripPlace',
            fields=[
                ('id', models.AutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('name', models.CharField(max_length=200)),
                ('latitude', models.FloatField()),
                ('longitude', models.FloatField()),
                ('notes', models.TextField(blank=True)),
                ('visited_at', models.DateTimeField(blank=True, null=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('trip', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='places', to='tracker.trip')),
            ],
        ),
    ]
