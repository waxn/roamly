from django.db import migrations, models


class Migration(migrations.Migration):
    """Opt-in "no data received" tracking alerts (see CLAUDE.md's
    "Tracking alerts" section).

    Additive only: alert_no_data_enabled defaults False, so no existing account
    starts receiving mail because of this migration. The two cursor fields are
    null until the first alert for an outage actually goes out.
    """

    dependencies = [
        ('tracker', '0072_remove_valhalla'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='alert_no_data_enabled',
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='alert_no_data_hours',
            field=models.PositiveIntegerField(default=3),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='alert_no_data_last_point',
            field=models.DateTimeField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='alert_no_data_sent_at',
            field=models.DateTimeField(blank=True, null=True),
        ),
    ]
