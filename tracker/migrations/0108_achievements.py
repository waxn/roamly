"""Achievements: per-user snapshot + badge unlocks; geoBoundaries auto-download."""
import django.db.models.deletion
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
        ('tracker', '0107_flight_opensky'),
    ]

    operations = [
        migrations.CreateModel(
            name='AchievementSnapshot',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('payload_json', models.JSONField(blank=True, default=dict)),
                ('roads_json', models.JSONField(blank=True, default=dict)),
                ('roads_fingerprint', models.CharField(blank=True, default='', max_length=100)),
                ('status', models.CharField(default='idle', max_length=20)),
                ('stage', models.CharField(blank=True, default='', max_length=120)),
                ('pct', models.FloatField(default=0)),
                ('error', models.TextField(blank=True, default='')),
                ('started_at', models.DateTimeField(blank=True, null=True)),
                ('heartbeat_at', models.DateTimeField(blank=True, null=True)),
                ('computed_at', models.DateTimeField(blank=True, null=True)),
                ('user', models.OneToOneField(on_delete=django.db.models.deletion.CASCADE,
                                              related_name='achievement_snapshot',
                                              to=settings.AUTH_USER_MODEL)),
            ],
        ),
        migrations.CreateModel(
            name='AchievementUnlock',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('badge_id', models.CharField(max_length=60)),
                ('tier', models.SmallIntegerField()),
                ('unlocked_at', models.DateTimeField()),
                ('value', models.FloatField(default=0)),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE,
                                           related_name='achievement_unlocks',
                                           to=settings.AUTH_USER_MODEL)),
            ],
            options={'unique_together': {('user', 'badge_id', 'tier')}},
        ),
        migrations.AddField(
            model_name='siteconfig', name='auto_download_boundaries',
            field=models.BooleanField(default=True),
        ),
        migrations.AlterField(
            model_name='downloadedregion', name='kind',
            field=models.CharField(choices=[('road', 'Road'), ('subway', 'Subway'), ('poi', 'POI'),
                                            ('boundary', 'Boundary')], max_length=10),
        ),
    ]
