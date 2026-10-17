"""Basemap config moves from per-user (UserProfile, 0099) to instance-wide
(SiteConfig), edited from Admin Panel → Basemaps. Per-user values are dropped:
the field shipped one release earlier and held only picker toggles and keys.
"""

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0099_userprofile_basemap_config'),
    ]

    operations = [
        migrations.RemoveField(
            model_name='userprofile',
            name='basemap_config',
        ),
        migrations.AddField(
            model_name='siteconfig',
            name='basemap_config',
            field=models.JSONField(blank=True, default=dict),
        ),
    ]
