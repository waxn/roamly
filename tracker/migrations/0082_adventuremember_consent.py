from django.db import migrations, models


def accept_existing(apps, schema_editor):
    """Grandfather every existing membership.

    These rows predate the consent model, so there is no acceptance timestamp
    to recover. Treating them as accepted-at-join keeps every current shared
    adventure working exactly as it does today; the new gate applies from here
    on. share_track is likewise left on for them, since their tracks are
    already visible and silently blanking a live adventure would read as data
    loss rather than as a security fix.
    """
    AdventureMember = apps.get_model('tracker', 'AdventureMember')
    AdventureMember.objects.filter(accepted_at__isnull=True).update(
        accepted_at=models.F('joined_at'), share_track=True,
    )


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0081_activities'),
    ]

    operations = [
        migrations.AddField(
            model_name='adventuremember',
            name='accepted_at',
            field=models.DateTimeField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='adventuremember',
            name='share_track',
            field=models.BooleanField(default=False),
        ),
        migrations.RunPython(accept_existing, migrations.RunPython.noop),
    ]
