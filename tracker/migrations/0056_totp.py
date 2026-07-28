import django.db.models.deletion
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):
    """TOTP two-factor auth: an alternate 2FA method to the emailed new-device
    code, independent of SMTP. See UserProfile.totp_secret/totp_enabled and
    the new TOTPBackupCode model in tracker/models.py."""

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
        ('tracker', '0055_roaddownloadjob_worker_token'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='totp_secret',
            field=models.CharField(blank=True, default='', max_length=32),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='totp_enabled',
            field=models.BooleanField(default=False),
        ),
        migrations.CreateModel(
            name='TOTPBackupCode',
            fields=[
                ('id', models.AutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('code_hash', models.CharField(db_index=True, max_length=64)),
                ('used_at', models.DateTimeField(blank=True, null=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='totp_backup_codes', to=settings.AUTH_USER_MODEL)),
            ],
        ),
    ]
