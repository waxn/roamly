from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0005_alter_apikey_id_alter_device_id_alter_location_id_and_more'),
    ]

    operations = [
        migrations.AddField(
            model_name='backupconfig',
            name='image_backup_enabled',
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name='backupconfig',
            name='image_use_same_creds',
            field=models.BooleanField(default=True),
        ),
        migrations.AddField(
            model_name='backupconfig',
            name='image_endpoint_url',
            field=models.URLField(blank=True, default='', max_length=500),
        ),
        migrations.AddField(
            model_name='backupconfig',
            name='image_bucket_name',
            field=models.CharField(blank=True, default='', max_length=200),
        ),
        migrations.AddField(
            model_name='backupconfig',
            name='image_access_key',
            field=models.CharField(blank=True, default='', max_length=200),
        ),
        migrations.AddField(
            model_name='backupconfig',
            name='image_secret_key',
            field=models.CharField(blank=True, default='', max_length=200),
        ),
        migrations.AddField(
            model_name='backupconfig',
            name='image_prefix',
            field=models.CharField(blank=True, default='roamly-media/', max_length=200),
        ),
        migrations.AddField(
            model_name='backupconfig',
            name='image_region',
            field=models.CharField(blank=True, default='auto', max_length=100),
        ),
        migrations.AddField(
            model_name='backupconfig',
            name='last_image_backup_at',
            field=models.DateTimeField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='backupconfig',
            name='last_image_backup_status',
            field=models.CharField(blank=True, default='', max_length=20),
        ),
        migrations.AddField(
            model_name='backupconfig',
            name='last_image_backup_error',
            field=models.TextField(blank=True, default=''),
        ),
        migrations.AddField(
            model_name='backupconfig',
            name='last_image_backup_size',
            field=models.BigIntegerField(blank=True, null=True),
        ),
    ]
