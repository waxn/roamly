"""Health Connect: raw activity samples + manually imported workouts.

Purely additive. Nothing in the tracking, stats, visits or distance pipeline
reads these tables, and they read nothing from it — an account that never
enabled GPS tracking can still use health, which is why neither model carries a
Device FK.

Both models are non-spatial, so unlike Location/Visit/Boundary they are defined
once rather than behind the HAS_POSTGIS branch. That split exists only to keep a
geometry column and its GiST index off SQLite; there is no geometry here.

HealthSample carries user content that becomes irreplaceable: Health Connect
only serves the trailing 30 days without READ_HEALTH_DATA_HISTORY, and a source
app can retract records at any time, so the Roamly copy is the long-term
archive. Both models are therefore in the backup format (meta.version 9 -> 10).
"""

from django.conf import settings
from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
        ('tracker', '0073_no_data_alerts'),
    ]

    operations = [
        migrations.CreateModel(
            name='HealthSample',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True,
                                           serialize=False, verbose_name='ID')),
                ('kind', models.CharField(choices=[
                    ('steps', 'Steps'), ('distance', 'Distance'),
                    ('calories_active', 'Active calories'),
                    ('calories_total', 'Total calories'),
                ], max_length=16)),
                ('value', models.FloatField()),
                ('start_time', models.DateTimeField()),
                ('end_time', models.DateTimeField()),
                ('zone_offset_seconds', models.IntegerField(blank=True, null=True)),
                ('source', models.CharField(blank=True, default='', max_length=128)),
                ('device_id', models.CharField(blank=True, default='', max_length=100)),
                ('hc_id', models.CharField(max_length=64)),
                ('last_modified', models.DateTimeField(blank=True, null=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE,
                                           related_name='health_samples',
                                           to=settings.AUTH_USER_MODEL)),
            ],
            options={
                # No `ordering` on purpose — Meta.ordering leaks into the GROUP BY
                # of .values().annotate() and would shatter the daily rollup.
                'unique_together': {('user', 'hc_id')},
            },
        ),
        migrations.CreateModel(
            name='HealthWorkout',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True,
                                           serialize=False, verbose_name='ID')),
                ('hc_id', models.CharField(max_length=64)),
                ('source', models.CharField(blank=True, default='', max_length=128)),
                ('device_id', models.CharField(blank=True, default='', max_length=100)),
                ('start_time', models.DateTimeField()),
                ('end_time', models.DateTimeField()),
                ('zone_offset_seconds', models.IntegerField(blank=True, null=True)),
                ('exercise_type', models.IntegerField(default=0)),
                ('exercise_slug', models.CharField(blank=True, default='', max_length=40)),
                ('title', models.CharField(blank=True, default='', max_length=200)),
                ('notes', models.TextField(blank=True, default='')),
                ('duration_s', models.IntegerField(default=0)),
                ('steps', models.IntegerField(blank=True, null=True)),
                ('distance_m', models.FloatField(blank=True, null=True)),
                ('calories_kcal', models.FloatField(blank=True, null=True)),
                ('avg_heart_rate', models.FloatField(blank=True, null=True)),
                ('imported_at', models.DateTimeField(auto_now_add=True)),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE,
                                           related_name='health_workouts',
                                           to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'unique_together': {('user', 'hc_id')},
            },
        ),
        migrations.AddField(
            model_name='userprofile',
            name='health_preferred_source',
            field=models.CharField(blank=True, default='', max_length=128),
        ),
        migrations.AddIndex(
            model_name='healthsample',
            index=models.Index(fields=['user', 'kind', 'start_time'],
                               name='tracker_hs_user_kind_idx'),
        ),
        migrations.AddIndex(
            model_name='healthsample',
            index=models.Index(fields=['user', '-start_time'],
                               name='tracker_hs_user_start_idx'),
        ),
        migrations.AddIndex(
            model_name='healthworkout',
            index=models.Index(fields=['user', '-start_time'],
                               name='tracker_hw_user_start_idx'),
        ),
    ]
