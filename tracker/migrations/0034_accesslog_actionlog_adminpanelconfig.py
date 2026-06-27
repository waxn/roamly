from django.conf import settings
from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0033_boundary_geom_gist_index'),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.CreateModel(
            name='AdminPanelConfig',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('access_log_retention_days', models.PositiveIntegerField(default=0, help_text='Days to keep access logs. 0 = keep forever.')),
                ('standard_log_retention_days', models.PositiveIntegerField(default=2, help_text='Days to keep action logs.')),
                ('updated_at', models.DateTimeField(auto_now=True)),
            ],
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
                'indexes': [
                    models.Index(fields=['ip_address', '-timestamp'], name='tracker_alog_ip_idx'),
                    models.Index(fields=['user', '-timestamp'], name='tracker_alog_user_idx'),
                ],
            },
        ),
        migrations.CreateModel(
            name='ActionLog',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('action', models.CharField(choices=[('login', 'Login'), ('logout', 'Logout'), ('signup', 'Signup'), ('login_fail', 'Login failed'), ('push_location', 'Push location'), ('push_batch', 'Push location batch'), ('import', 'Import data'), ('delete_data', 'Delete location data'), ('delete_account', 'Delete account'), ('backup_run', 'Backup run'), ('geocode_run', 'Geocode run'), ('admin_toggle', 'Admin toggle'), ('custom_js_save', 'Custom JS saved'), ('api_key_create', 'API key created'), ('api_key_delete', 'API key deleted'), ('other', 'Other')], db_index=True, max_length=30)),
                ('description', models.TextField(blank=True)),
                ('ip_address', models.GenericIPAddressField(blank=True, null=True)),
                ('timestamp', models.DateTimeField(db_index=True)),
                ('user', models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='action_logs', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'ordering': ['-timestamp'],
            },
        ),
    ]
