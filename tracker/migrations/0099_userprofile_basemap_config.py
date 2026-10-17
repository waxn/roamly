"""UserProfile.basemap_config — per-user basemap picker config (enabled
basemaps, tile keys, Mapbox Studio style, custom XYZ URLs). See
tracker/basemaps.py. Additive; an empty dict means "catalog defaults".
"""

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0098_actionlog_admin_edit_user'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='basemap_config',
            field=models.JSONField(blank=True, default=dict),
        ),
    ]
