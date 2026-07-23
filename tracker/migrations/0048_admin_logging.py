import django.db.models.deletion
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0047_siteconfig_contact_email'),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.AddField(
            model_name='siteconfig',
            name='access_log_retention_days',
            field=models.PositiveIntegerField(default=30),
        ),
        migrations.AddField(
            model_name='siteconfig',
            name='event_log_retention_days',
            field=models.PositiveIntegerField(default=0),
        ),
        migrations.CreateModel(
            name='AccessLog',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('ip_address', models.GenericIPAddressField(blank=True, null=True)),
                ('path', models.CharField(max_length=500)),
                ('method', models.CharField(max_length=10)),
                ('user_agent', models.TextField(blank=True)),
                ('status_code', models.PositiveSmallIntegerField(blank=True, null=True)),
                ('response_ms', models.PositiveIntegerField(blank=True, null=True)),
                ('timestamp', models.DateTimeField(db_index=True)),
                ('user', models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='access_logs', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'ordering': ['-timestamp'],
            },
        ),
        migrations.AddIndex(
            model_name='accesslog',
            index=models.Index(fields=['ip_address', '-timestamp'], name='tracker_alog_ip_idx'),
        ),
        migrations.AddIndex(
            model_name='accesslog',
            index=models.Index(fields=['user', '-timestamp'], name='tracker_alog_user_idx'),
        ),
        migrations.CreateModel(
            name='ActionLog',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('action', models.CharField(choices=[('login', 'Login'), ('logout', 'Logout'), ('signup', 'Signup'), ('login_fail', 'Login failed'), ('error', 'Server error'), ('import', 'Import data'), ('delete_data', 'Delete location data'), ('delete_account', 'Delete account'), ('admin_toggle', 'Admin toggle'), ('admin_delete_user', 'Admin deleted user'), ('custom_js_save', 'Custom JS saved'), ('api_key_create', 'API key created'), ('api_key_delete', 'API key deleted'), ('other', 'Other')], db_index=True, max_length=30)),
                ('description', models.TextField(blank=True)),
                ('ip_address', models.GenericIPAddressField(blank=True, null=True)),
                ('timestamp', models.DateTimeField(db_index=True)),
                ('user', models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='action_logs', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'ordering': ['-timestamp'],
            },
        ),
        migrations.CreateModel(
            name='DailyLogRollup',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('date', models.DateField(db_index=True, unique=True)),
                ('requests', models.PositiveIntegerField(default=0)),
                ('unique_ips', models.PositiveIntegerField(default=0)),
                ('unique_users', models.PositiveIntegerField(default=0)),
                ('logins', models.PositiveIntegerField(default=0)),
                ('signups', models.PositiveIntegerField(default=0)),
                ('errors', models.PositiveIntegerField(default=0)),
                ('computed_at', models.DateTimeField(auto_now=True)),
            ],
            options={
                'ordering': ['-date'],
            },
        ),
    ]
