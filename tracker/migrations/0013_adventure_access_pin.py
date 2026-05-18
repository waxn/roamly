from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0012_location_sort_indexes'),
    ]

    operations = [
        migrations.AddField(
            model_name='adventure',
            name='access_pin',
            field=models.CharField(blank=True, max_length=20),
        ),
    ]
