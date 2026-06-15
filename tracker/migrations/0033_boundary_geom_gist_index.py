import django.contrib.postgres.indexes
from django.db import migrations


class Migration(migrations.Migration):
    """Add the missing GiST spatial index on Boundary.geom.

    Every reverse-geocode does a `geom__contains=point` lookup against this
    table. Without a spatial index that is a sequential scan running
    ST_Contains against every polygon — and because tracking pushes points
    continuously, the background geocoder ran that full-table scan around the
    clock, pinning DB CPU. This makes it an index probe instead.
    """

    dependencies = [
        ('tracker', '0032_userprofile_ai_allow_journals'),
    ]

    operations = [
        migrations.AddIndex(
            model_name='boundary',
            index=django.contrib.postgres.indexes.GistIndex(
                fields=['geom'], name='tracker_boundary_geom_gist'
            ),
        ),
    ]
