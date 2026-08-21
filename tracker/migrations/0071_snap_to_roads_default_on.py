from django.db import migrations, models


def _enable_snapping(apps, schema_editor):
    """Turn snapping on for every existing profile.

    AlterField's new default only applies to rows created after this migration,
    and every pre-existing profile carries the old False — i.e. the state this
    migration exists to correct. Snapping is display-only and gated by
    UserProfile.road_provider_resolved, so an account with no usable provider is
    unaffected by this; one that already has road data starts drawing curves.
    """
    UserProfile = apps.get_model('tracker', 'UserProfile')
    UserProfile.objects.filter(snap_to_roads=False).update(snap_to_roads=True)


class Migration(migrations.Migration):
    """Road snapping defaults on (see CLAUDE.md's "Road snapping" section).

    Deliberately not reversible in the data direction: flipping everyone back to
    False on a rollback would switch the feature off for accounts that had it on
    before this migration ran, which is worse than leaving it on.
    """

    dependencies = [
        ('tracker', '0070_valhalla_tiles'),
    ]

    operations = [
        migrations.AlterField(
            model_name='userprofile',
            name='snap_to_roads',
            field=models.BooleanField(default=True),
        ),
        migrations.RunPython(_enable_snapping, migrations.RunPython.noop),
    ]
