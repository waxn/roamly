"""Record why a download run's Overpass requests failed.

The admin panel could only say how many areas failed, which made a slow mirror,
a rate-limited one and a genuinely offline server all look identical. These
carry the classified reason (tracker/overpass.py: http / timeout / unreachable),
the message, and which endpoint produced it.

Operational data, like the rest of the download job rows — excluded from
backups, so no backup meta.version bump.
"""

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0076_siteconfig_overpass_urls'),
    ]

    operations = [
        migrations.AddField(
            model_name='roaddownloadjob',
            name='last_error',
            field=models.CharField(blank=True, default='', max_length=300),
        ),
        migrations.AddField(
            model_name='roaddownloadjob',
            name='last_error_kind',
            field=models.CharField(blank=True, default='', max_length=16),
        ),
        migrations.AddField(
            model_name='roaddownloadjob',
            name='last_error_endpoint',
            field=models.CharField(blank=True, default='', max_length=300),
        ),
        migrations.AddField(
            model_name='raildownloadjob',
            name='last_error',
            field=models.CharField(blank=True, default='', max_length=300),
        ),
        migrations.AddField(
            model_name='raildownloadjob',
            name='last_error_kind',
            field=models.CharField(blank=True, default='', max_length=16),
        ),
        migrations.AddField(
            model_name='raildownloadjob',
            name='last_error_endpoint',
            field=models.CharField(blank=True, default='', max_length=300),
        ),
        migrations.AddField(
            model_name='poidownloadjob',
            name='last_error',
            field=models.CharField(blank=True, default='', max_length=300),
        ),
        migrations.AddField(
            model_name='poidownloadjob',
            name='last_error_kind',
            field=models.CharField(blank=True, default='', max_length=16),
        ),
        migrations.AddField(
            model_name='poidownloadjob',
            name='last_error_endpoint',
            field=models.CharField(blank=True, default='', max_length=300),
        ),
    ]
