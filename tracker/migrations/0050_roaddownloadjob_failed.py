from django.db import migrations, models


class Migration(migrations.Migration):
    """Track areas Overpass would not return during a road download.

    Without this the job reported only successes, so a corridor whose batch
    timed out ended up with no road data and nothing said so — which presents
    as snapping and gap routing being broken rather than as a download that
    partly failed.
    """

    dependencies = [
        ('tracker', '0049_roads_and_gap_filling'),
    ]

    operations = [
        migrations.AddField(
            model_name='roaddownloadjob',
            name='failed',
            field=models.IntegerField(default=0),
        ),
    ]
