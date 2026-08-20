from django.db import migrations, models


class Migration(migrations.Migration):
    """Adds DownloadedRegion (per-cell/per-city coverage tracking so a
    download re-run only asks Overpass about growth since last time) and
    three SiteConfig toggles (auto_download_roads/subway/pois, default on)
    that let a background sweep start those downloads automatically instead
    of waiting for an admin to click the button. See
    tracker/auto_download_tasks.py and CLAUDE.md's "Downloads" section."""

    dependencies = [
        ('tracker', '0067_download_jobs_singleton'),
    ]

    operations = [
        migrations.CreateModel(
            name='DownloadedRegion',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('kind', models.CharField(choices=[('road', 'Road'), ('subway', 'Subway'), ('poi', 'POI')], max_length=10)),
                ('key', models.CharField(max_length=200)),
            ],
        ),
        migrations.AddIndex(
            model_name='downloadedregion',
            index=models.Index(fields=['kind'], name='tracker_downloadedregion_kind_idx'),
        ),
        migrations.AlterUniqueTogether(
            name='downloadedregion',
            unique_together={('kind', 'key')},
        ),
        migrations.AddField(
            model_name='siteconfig',
            name='auto_download_roads',
            field=models.BooleanField(default=True),
        ),
        migrations.AddField(
            model_name='siteconfig',
            name='auto_download_subway',
            field=models.BooleanField(default=True),
        ),
        migrations.AddField(
            model_name='siteconfig',
            name='auto_download_pois',
            field=models.BooleanField(default=True),
        ),
    ]
