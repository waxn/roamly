from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0001_initial'),
    ]

    operations = [
        migrations.AddField(
            model_name='tripplace',
            name='radius',
            field=models.FloatField(default=100, help_text='Radius in meters'),
        ),
    ]
