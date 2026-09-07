"""Activity recording: a deliberately captured ride / walk / run.

Purely additive, and deliberately thin. An Activity stores only the envelope —
(device, start_time, end_time) plus the user's chosen kind and title — and the
track is derived from Location on demand, exactly as Adventure.locations does.
The recorded points stay ordinary fixes that the normal map, distance and stats
already count, so the activity is a *view over* the track rather than a silo,
and deleting one deletes a view, never a fix.

That is why Location gains no grouping column here. It is queried from ~75
places across the app, and a per-point activity id would be stale on backlog
replay regardless, since bulk_create(ignore_conflicts=True) skips a re-pushed
point rather than updating it.

The stats columns are a cache, not a source of truth: NULL means "never
computed", and stats_point_count is the staleness key that lets a read notice
points which arrived after the activity was saved (the phone uploads
offline-first, so that is the normal case, not an edge one).

Non-spatial — the geometry lives on the Location rows this points at — so the
model is defined once rather than behind the HAS_POSTGIS branch, like the health
models in 0074.

Activities are user content that cannot be re-derived (a chosen kind, a typed
title), so they join the backup format: meta.version 11 -> 12.
"""

from django.conf import settings
from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
        ('tracker', '0080_incremental_recompute'),
    ]

    operations = [
        migrations.CreateModel(
            name='Activity',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True,
                                           serialize=False, verbose_name='ID')),
                ('client_id', models.CharField(max_length=64)),
                ('kind', models.CharField(choices=[
                    ('ride', 'Ride'), ('run', 'Run'), ('walk', 'Walk'),
                    ('hike', 'Hike'), ('other', 'Other'),
                ], default='other', max_length=16)),
                ('title', models.CharField(blank=True, default='', max_length=200)),
                ('notes', models.TextField(blank=True, default='')),
                ('start_time', models.DateTimeField()),
                ('end_time', models.DateTimeField()),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('distance_km', models.FloatField(blank=True, null=True)),
                ('moving_seconds', models.IntegerField(blank=True, null=True)),
                ('elapsed_seconds', models.IntegerField(default=0)),
                ('avg_speed_kmh', models.FloatField(blank=True, null=True)),
                ('max_speed_kmh', models.FloatField(blank=True, null=True)),
                ('point_count', models.IntegerField(default=0)),
                ('stats_computed_at', models.DateTimeField(blank=True, null=True)),
                ('stats_point_count', models.IntegerField(default=0)),
                ('device', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE,
                                             related_name='activities',
                                             to='tracker.device')),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE,
                                           related_name='activities',
                                           to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'ordering': ['-start_time'],
                # client_id is phone-minted, so this is what makes save-on-stop
                # idempotent: a retry of a POST that actually landed is free.
                'unique_together': {('user', 'client_id')},
            },
        ),
        migrations.AddIndex(
            model_name='activity',
            index=models.Index(fields=['user', '-start_time'],
                               name='tracker_act_user_start_idx'),
        ),
    ]
