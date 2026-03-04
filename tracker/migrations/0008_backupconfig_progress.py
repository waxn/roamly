from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0007_backupconfig_last_backup_started_at'),
    ]

    operations = [
        migrations.AlterField(
            model_name='backupconfig',
            name='last_backup_size',
            field=models.BigIntegerField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='backupconfig',
            name='last_backup_bytes_uploaded',
            field=models.BigIntegerField(blank=True, null=True),
        ),
    ]
