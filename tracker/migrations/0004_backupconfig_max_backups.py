from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0003_geocodingjob_poi_poidownloadjob_backupconfig'),
    ]

    operations = [
        migrations.AddField(
            model_name='backupconfig',
            name='max_backups',
            field=models.IntegerField(default=0, help_text='Max backups to keep (0 = unlimited)'),
        ),
    ]
