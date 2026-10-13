"""Location share protection: PIN / login gate, selectable history windows,
view style, remembered-PIN grants and a per-visit access log.

Existing shares keep working unchanged: they stay open (no PIN, no login) and
get ``allowed_windows=[window_hours]`` so the viewer sees the one window the
link always had.
"""
import django.db.models.deletion
import django.utils.timezone
from django.conf import settings
from django.db import migrations, models


def backfill_windows(apps, schema_editor):
    LocationShare = apps.get_model('tracker', 'LocationShare')
    for sh in LocationShare.objects.all():
        sh.allowed_windows = [sh.window_hours]
        sh.default_window_hours = sh.window_hours
        sh.save(update_fields=['allowed_windows', 'default_window_hours'])


class Migration(migrations.Migration):

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
        ('tracker', '0096_device_overlap'),
    ]

    operations = [
        migrations.AddField('locationshare', 'require_login', models.BooleanField(default=False)),
        migrations.AddField('locationshare', 'pin_hash',
                            models.CharField(blank=True, default='', max_length=128)),
        migrations.AddField('locationshare', 'allowed_windows',
                            models.JSONField(blank=True, default=list)),
        migrations.AddField('locationshare', 'default_window_hours',
                            models.IntegerField(default=0)),
        migrations.AddField('locationshare', 'view_style',
                            models.CharField(default='lines', max_length=8)),
        migrations.CreateModel(
            name='LocationShareGrant',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('granted_at', models.DateTimeField(auto_now_add=True)),
                ('share', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='grants', to='tracker.locationshare')),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='location_share_grants', to=settings.AUTH_USER_MODEL)),
            ],
            options={'unique_together': {('share', 'user')}},
        ),
        migrations.CreateModel(
            name='LocationShareView',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('viewed_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('share', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='views', to='tracker.locationshare')),
                ('user', models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='+', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'ordering': ['-viewed_at'],
                'indexes': [models.Index(fields=['share', '-viewed_at'], name='tracker_shview_idx')],
            },
        ),
        migrations.RunPython(backfill_windows, migrations.RunPython.noop),
    ]
