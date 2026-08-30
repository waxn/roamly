"""Admin-editable Overpass endpoint list.

The endpoints the road/subway/POI downloads query were env-only
(OVERPASS_URL), which is awkward on a real deployment: docker-compose.yml is
gitignored and enumerates its env vars explicitly, so adding one to .env alone
never reaches the container. Moving the list into SiteConfig makes switching to
a reachable mirror an edit in Admin Panel -> Downloads.

Blank keeps the previous behaviour exactly (settings.OVERPASS_URLS).
"""

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0075_zepp'),
    ]

    operations = [
        migrations.AddField(
            model_name='siteconfig',
            name='overpass_urls',
            field=models.TextField(blank=True, default=''),
        ),
    ]
