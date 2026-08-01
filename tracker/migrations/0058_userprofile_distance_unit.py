from django.db import migrations, models


class Migration(migrations.Migration):
    """Server-side mirror of the roamly_speed_unit display preference, so the
    request-less summary-email scheduler can honor it. See
    UserProfile.distance_unit and profile_distance_unit_api in views.py."""

    dependencies = [
        ('tracker', '0057_userprofile_email_unsub_token'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='distance_unit',
            field=models.CharField(blank=True, default='kmh', max_length=10),
        ),
    ]
