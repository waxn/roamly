from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0006_backupconfig_image_backup'),
    ]

    operations = [
        migrations.AddField(
            model_name='backupconfig',
            name='last_backup_started_at',
            field=models.DateTimeField(blank=True, null=True),
        ),
    ]
