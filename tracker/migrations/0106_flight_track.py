"""Flight: link to the recorded GPS track (device + time window)."""
from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0105_flight_boarding_pass'),
    ]

    operations = [
        migrations.AddField(
            model_name='flight', name='track_device',
            field=models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.SET_NULL,
                                    related_name='flight_tracks', to='tracker.device'),
        ),
        migrations.AddField(model_name='flight', name='track_start',
                            field=models.DateTimeField(blank=True, null=True)),
        migrations.AddField(model_name='flight', name='track_end',
                            field=models.DateTimeField(blank=True, null=True)),
        migrations.AddField(model_name='flight', name='track_distance_km',
                            field=models.FloatField(blank=True, null=True)),
        migrations.AddField(model_name='flight', name='track_auto',
                            field=models.BooleanField(default=True)),
    ]
