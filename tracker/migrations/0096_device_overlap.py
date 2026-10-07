"""Device-overlap detection: DeviceOverlap + TrashedLocation.reason/overlap.

Purely additive. Existing trash rows default to reason='deleted', which is what
every one of them is.
"""
import django.db.models.deletion
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
        ('tracker', '0095_activity_track'),
    ]

    operations = [
        migrations.CreateModel(
            name='DeviceOverlap',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('start_time', models.DateTimeField()),
                ('end_time', models.DateTimeField()),
                ('status', models.CharField(choices=[('pending', 'Pending'), ('hidden', 'Hidden'), ('dismissed', 'Dismissed')], default='pending', max_length=10)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('resolved_at', models.DateTimeField(blank=True, null=True)),
                ('device_a', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='+', to='tracker.device')),
                ('device_b', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='+', to='tracker.device')),
                ('hidden_device', models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='+', to='tracker.device')),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='device_overlaps', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'ordering': ['-start_time'],
                'indexes': [
                    models.Index(fields=['user', 'status'], name='tracker_ovl_user_status_idx'),
                    models.Index(fields=['device_a', 'device_b', 'start_time'], name='tracker_ovl_pair_idx'),
                ],
            },
        ),
        migrations.AddField(
            model_name='trashedlocation',
            name='reason',
            field=models.CharField(choices=[('deleted', 'Deleted'), ('overlap', 'Device overlap')], db_index=True, default='deleted', max_length=10),
        ),
        migrations.AddField(
            model_name='trashedlocation',
            name='overlap',
            field=models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='hidden_points', to='tracker.deviceoverlap'),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='overlap_scan_last_id',
            field=models.BigIntegerField(blank=True, null=True),
        ),
    ]
