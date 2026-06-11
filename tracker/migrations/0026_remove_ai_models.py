from django.db import migrations


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0025_location_poi_poimatchjob'),
    ]

    operations = [
        migrations.DeleteModel(name='AISummary'),
        migrations.DeleteModel(name='AIConfig'),
    ]
