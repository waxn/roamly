"""SiteConfig: AeroDataBox provider + key for Flights lookup."""
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0102_flight'),
    ]

    operations = [
        migrations.AddField(
            model_name='siteconfig',
            name='aerodatabox_provider',
            field=models.CharField(choices=[('rapidapi', 'RapidAPI'), ('apimarket', 'API.Market')], default='rapidapi', max_length=12),
        ),
        migrations.AddField(
            model_name='siteconfig',
            name='aerodatabox_key',
            field=models.CharField(blank=True, default='', max_length=255),
        ),
    ]
