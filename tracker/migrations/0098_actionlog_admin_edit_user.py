"""ActionLog.action gained the admin_edit_user choice (Admin Panel → Users →
Edit). Choices-only — no SQL change.
"""

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0097_share_protection'),
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
                ('admin_edit_user', 'Admin edited user'),
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
