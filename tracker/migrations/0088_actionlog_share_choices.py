"""ActionLog.action gained two choices (share_create/share_revoke) in the
LocationShare feature (migration 0084) with no accompanying AlterField —
choices are tracked in migration state even though nothing enforces them at
the DB level, so makemigrations kept reporting this model as having
unreflected changes on every startup.
"""

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0087_siteconfig_fcm'),
    ]

    operations = [
        migrations.AlterField(
            model_name='actionlog',
            name='action',
            field=models.CharField(choices=[
                ('login', 'Login'), ('logout', 'Logout'), ('signup', 'Signup'),
                ('login_fail', 'Login failed'), ('error', 'Server error'),
                ('import', 'Import data'), ('delete_data', 'Delete location data'),
                ('delete_account', 'Delete account'), ('admin_toggle', 'Admin toggle'),
                ('admin_delete_user', 'Admin deleted user'),
                ('custom_js_save', 'Custom JS saved'),
                ('api_key_create', 'API key created'), ('api_key_delete', 'API key deleted'),
                ('totp_enable', 'TOTP enabled'), ('totp_disable', 'TOTP disabled'),
                ('totp_regen_backup', 'TOTP backup codes regenerated'),
                ('trip_invite', 'Adventure invitation sent'),
                ('trip_join', 'Adventure invitation accepted'),
                ('device_revoke', 'Trusted device revoked'),
                ('share_create', 'Location share link created'),
                ('share_revoke', 'Location share link revoked'),
                ('other', 'Other'),
            ], db_index=True, max_length=30),
        ),
    ]
