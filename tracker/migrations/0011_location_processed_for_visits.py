from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0010_rename_trip_fk_to_adventure'),
    ]

    operations = [
        migrations.AddField(
            model_name='location',
            name='processed_for_visits',
            field=models.BooleanField(default=False),
        ),
    ]
