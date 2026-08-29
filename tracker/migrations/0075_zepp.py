"""Zepp (Amazfit) cloud sync, and a rename that makes room for it.

`HealthSample.hc_id`/`HealthWorkout.hc_id` become `external_id`. The column was
named for Health Connect when that was the only source; Zepp has no per-record
UUID of its own, so its rows carry a deterministic synthesised key instead
(`zepp:steps:2026-08-27:540`). Renaming keeps the dedupe guarantee honest rather
than storing a Zepp key in a field called hc_id.

The wire format still accepts `hc_id` — mobile v1.21.0 shipped before this and
is already installed, so the ingest endpoint reads either name.

Zepp's official REST API is corporate-only, so the config here is an `apptoken`
lifted from the Zepp app rather than an OAuth credential Roamly could mint.
Config, so excluded from backups like the AI/summary/alert settings.
"""

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0074_health_connect'),
    ]

    operations = [
        migrations.RenameField(
            model_name='healthsample', old_name='hc_id', new_name='external_id'),
        migrations.RenameField(
            model_name='healthworkout', old_name='hc_id', new_name='external_id'),
        migrations.AlterField(
            model_name='healthsample',
            name='external_id',
            field=models.CharField(max_length=120),
        ),
        migrations.AlterField(
            model_name='healthworkout',
            name='external_id',
            field=models.CharField(max_length=120),
        ),
        migrations.AlterUniqueTogether(
            name='healthsample', unique_together={('user', 'external_id')}),
        migrations.AlterUniqueTogether(
            name='healthworkout', unique_together={('user', 'external_id')}),
        migrations.AddField(
            model_name='userprofile', name='zepp_enabled',
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name='userprofile', name='zepp_token',
            field=models.CharField(blank=True, default='', max_length=255),
        ),
        migrations.AddField(
            model_name='userprofile', name='zepp_user_id',
            field=models.CharField(blank=True, default='', max_length=64),
        ),
        migrations.AddField(
            model_name='userprofile', name='zepp_host',
            field=models.CharField(blank=True, default='api-mifit.huami.com', max_length=128),
        ),
        migrations.AddField(
            model_name='userprofile', name='zepp_last_sync',
            field=models.DateTimeField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='userprofile', name='zepp_last_error',
            field=models.TextField(blank=True, default=''),
        ),
    ]
