from django.db import migrations, models


def _backfill_valhalla_url(apps, schema_editor):
    """Existing profiles with an empty valhalla_url get the new default —
    AlterField's new `default` only applies to rows created after this
    migration, not ones that already exist with the old empty-string default."""
    UserProfile = apps.get_model('tracker', 'UserProfile')
    UserProfile.objects.filter(valhalla_url='').update(valhalla_url='http://valhalla:8002')


class Migration(migrations.Migration):
    """Self-hosted Valhalla tile-region auto-detection (see
    tracker/valhalla_tiles_tasks.py and CLAUDE.md's "Downloads" section):
    DownloadedRegion gains a 'valhalla' kind, SiteConfig gains a fourth
    auto-download toggle, and UserProfile.valhalla_url now defaults to the
    optional docker-compose service's own internal address so picking that
    provider needs no manual URL entry."""

    dependencies = [
        ('tracker', '0069_rename_tracker_downloadedregion_kind_idx_tracker_dow_kind_9ff204_idx_and_more'),
    ]

    operations = [
        migrations.AlterField(
            model_name='downloadedregion',
            name='kind',
            field=models.CharField(
                choices=[('road', 'Road'), ('subway', 'Subway'), ('poi', 'POI'), ('valhalla', 'Valhalla')],
                max_length=10,
            ),
        ),
        migrations.AddField(
            model_name='siteconfig',
            name='auto_download_valhalla_tiles',
            field=models.BooleanField(default=True),
        ),
        migrations.AlterField(
            model_name='userprofile',
            name='valhalla_url',
            field=models.CharField(blank=True, default='http://valhalla:8002', max_length=300),
        ),
        migrations.RunPython(_backfill_valhalla_url, migrations.RunPython.noop),
    ]
