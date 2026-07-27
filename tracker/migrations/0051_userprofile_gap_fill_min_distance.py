from django.db import migrations, models


class Migration(migrations.Migration):
    """Distance trigger for gap filling.

    A time-only threshold missed the common case: a stretch of road where
    tracking thinned out to one fix every kilometre or two still leaves an
    obvious hole in the drawn track, whatever the clock says.
    """

    dependencies = [
        ('tracker', '0050_roaddownloadjob_failed'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='gap_fill_min_distance_m',
            field=models.IntegerField(default=500),
        ),
    ]
