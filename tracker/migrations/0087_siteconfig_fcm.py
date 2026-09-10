"""Admin-editable Firebase Cloud Messaging credentials.

Powers push notifications for Family Circle place enter/exit alerts (see
0086_family_circles / tracker/push_tasks.py). Unlike carto_api_key this is a
server-side signing secret (a service-account key file), so
fcm_service_account_json is masked the same way turnstile_secret_key already
is, never exposed to a template context. fcm_project_id is parsed out of the
JSON on save so push_tasks.py doesn't re-parse it on every send.

Config only -> excluded from backups, no meta.version bump.
"""

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0086_family_circles'),
    ]

    operations = [
        migrations.AddField(
            model_name='siteconfig',
            name='fcm_project_id',
            field=models.CharField(blank=True, default='', max_length=200),
        ),
        migrations.AddField(
            model_name='siteconfig',
            name='fcm_service_account_json',
            field=models.TextField(blank=True, default=''),
        ),
    ]
