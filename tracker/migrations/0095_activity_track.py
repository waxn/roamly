"""Dedicated recorded track per Activity + elevation and algo-version stats.

Purely additive: existing activities keep no ActivityTrack and render through
the history fallback; stats_algo_version defaults to 0, so every existing ride
is re-scored by the new pipeline on its next read.
"""
import django.db.models.deletion
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0094_hardwaretracker'),
    ]

    operations = [
        migrations.AlterField(
            model_name='activity',
            name='kind',
            field=models.CharField(choices=[('ride', 'Ride'), ('run', 'Run'), ('walk', 'Walk'), ('hike', 'Hike'), ('row', 'Row'), ('other', 'Other')], default='other', max_length=16),
        ),
        migrations.AddField(
            model_name='activity',
            name='stats_algo_version',
            field=models.IntegerField(default=0),
        ),
        migrations.AddField(
            model_name='activity',
            name='elevation_gain_m',
            field=models.FloatField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='activity',
            name='elevation_loss_m',
            field=models.FloatField(blank=True, null=True),
        ),
        migrations.CreateModel(
            name='ActivityTrack',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('raw', models.BinaryField(default=b'')),
                ('raw_count', models.IntegerField(default=0)),
                ('chunks_received', models.IntegerField(default=0)),
                ('complete', models.BooleanField(default=False)),
                ('smoothed', models.BinaryField(blank=True, null=True)),
                ('algo_version', models.IntegerField(default=0)),
                ('mirrored', models.BooleanField(default=False)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                ('activity', models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, related_name='track', to='tracker.activity')),
            ],
        ),
    ]
