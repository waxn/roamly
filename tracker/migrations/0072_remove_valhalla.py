from django.db import migrations, models


def _reset_valhalla_providers(apps, schema_editor):
    """Anyone who explicitly picked 'valhalla' goes back to auto.

    Leaving the string behind would resolve to '' (an unknown provider is not a
    usable one), silently disabling snapping for that account instead of falling
    through to local roads or Mapbox — which is what those profiles want now that
    the provider is gone.
    """
    UserProfile = apps.get_model('tracker', 'UserProfile')
    UserProfile.objects.filter(road_provider='valhalla').update(road_provider='')

    # Coverage rows for a feature that no longer exists.
    DownloadedRegion = apps.get_model('tracker', 'DownloadedRegion')
    DownloadedRegion.objects.filter(kind='valhalla').delete()


class Migration(migrations.Migration):
    """Remove the self-hosted Valhalla provider and its tile-region tracking.

    The local provider (RoadSegment + A*) already produces road-following
    curvature with no second service to run, and OSRM covers the "I want a real
    HMM map matcher" case with a public demo server and a far simpler self-host
    story. See CLAUDE.md's "Road snapping" section.
    """

    dependencies = [
        ('tracker', '0071_snap_to_roads_default_on'),
    ]

    operations = [
        migrations.RunPython(_reset_valhalla_providers, migrations.RunPython.noop),
        migrations.RemoveField(model_name='userprofile', name='valhalla_url'),
        migrations.RemoveField(model_name='siteconfig', name='auto_download_valhalla_tiles'),
        migrations.AlterField(
            model_name='downloadedregion',
            name='kind',
            field=models.CharField(
                choices=[('road', 'Road'), ('subway', 'Subway'), ('poi', 'POI')],
                max_length=10,
            ),
        ),
    ]
