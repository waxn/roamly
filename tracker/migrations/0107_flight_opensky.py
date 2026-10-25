"""Flight: OpenSky-fetched track; SiteConfig: OpenSky API client."""
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0106_flight_track'),
    ]

    operations = [
        migrations.AddField(model_name='flight', name='icao24',
                            field=models.CharField(blank=True, default='', max_length=6)),
        migrations.AddField(model_name='flight', name='opensky_track',
                            field=models.JSONField(blank=True, null=True)),
        migrations.AddField(model_name='flight', name='opensky_status',
                            field=models.CharField(blank=True, default='', max_length=16)),
        migrations.AddField(model_name='flight', name='opensky_message',
                            field=models.CharField(blank=True, default='', max_length=200)),
        migrations.AddField(model_name='flight', name='opensky_attempts',
                            field=models.PositiveSmallIntegerField(default=0)),
        migrations.AddField(model_name='flight', name='opensky_checked_at',
                            field=models.DateTimeField(blank=True, null=True)),
        migrations.AddField(model_name='siteconfig', name='opensky_client_id',
                            field=models.CharField(blank=True, default='', max_length=200)),
        migrations.AddField(model_name='siteconfig', name='opensky_client_secret',
                            field=models.CharField(blank=True, default='', max_length=255)),
    ]
