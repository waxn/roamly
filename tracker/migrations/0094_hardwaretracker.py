"""Pairing record for the Roamly hardware tracker (see /trackerhw)."""
import django.db.models.deletion
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
        ('tracker', '0093_backupconfig_last_backup_warning'),
    ]

    operations = [
        migrations.CreateModel(
            name='HardwareTracker',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('hw_id', models.CharField(max_length=32)),
                ('model', models.CharField(blank=True, max_length=64)),
                ('firmware', models.CharField(blank=True, max_length=32)),
                ('paired_at', models.DateTimeField(auto_now_add=True)),
                ('last_seen_at', models.DateTimeField(blank=True, null=True)),
                ('last_upload_at', models.DateTimeField(blank=True, null=True)),
                ('last_status', models.JSONField(blank=True, default=dict)),
                ('points_uploaded', models.PositiveBigIntegerField(default=0)),
                ('api_key', models.OneToOneField(blank=True, null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='hardware_tracker', to='tracker.apikey')),
                ('device', models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, related_name='hardware_tracker', to='tracker.device')),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='hardware_trackers', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'ordering': ['-paired_at'],
                'unique_together': {('user', 'hw_id')},
            },
        ),
    ]
