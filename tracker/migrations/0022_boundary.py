import django.contrib.gis.db.models.fields
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0021_journalentry_journalphoto'),
    ]

    operations = [
        migrations.CreateModel(
            name='Boundary',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('name', models.CharField(db_index=True, max_length=200)),
                ('state', models.CharField(blank=True, max_length=100)),
                ('country_code', models.CharField(default='US', max_length=3)),
                ('kind', models.CharField(max_length=20)),
                ('geom', django.contrib.gis.db.models.fields.MultiPolygonField(srid=4326)),
            ],
        ),
        migrations.AddIndex(
            model_name='boundary',
            index=models.Index(fields=['kind'], name='tracker_boundary_kind_idx'),
        ),
    ]
