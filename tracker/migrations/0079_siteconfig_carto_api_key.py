"""Admin-editable CARTO basemap API key.

The app's built-in Streets/Dark basemaps are CARTO raster tiles
(basemaps.cartocdn.com), which were free and key-less for years. CARTO now
requires an API key on that endpoint and serves every tile stamped with a
repeated "API KEY REQUIRED" watermark to callers without one, so the default
basemap on every map in the app degrades on its own with no code change.

Blank keeps the previous key-less tile URLs exactly as they were, so an
instance that hasn't got a key yet is no worse off than before.
"""

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0078_drop_regional_overpass_endpoint'),
    ]

    operations = [
        migrations.AddField(
            model_name='siteconfig',
            name='carto_api_key',
            field=models.CharField(blank=True, default='', max_length=255),
        ),
    ]
