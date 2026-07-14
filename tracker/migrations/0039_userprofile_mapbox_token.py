from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0038_location_flag'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='mapbox_token',
            field=models.CharField(blank=True, default='', max_length=500),
        ),
    ]
