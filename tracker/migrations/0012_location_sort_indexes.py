from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0011_location_processed_for_visits'),
    ]

    operations = [
        migrations.AddIndex(
            model_name='location',
            index=models.Index(fields=['state'], name='tracker_loc_state_idx'),
        ),
        migrations.AddIndex(
            model_name='location',
            index=models.Index(fields=['country_code'], name='tracker_loc_ccode_idx'),
        ),
        migrations.AddIndex(
            model_name='location',
            index=models.Index(fields=['speed'], name='tracker_loc_speed_idx'),
        ),
        migrations.AddIndex(
            model_name='location',
            index=models.Index(fields=['battery'], name='tracker_loc_battery_idx'),
        ),
    ]
