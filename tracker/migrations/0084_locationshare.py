import django.db.models.deletion
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):
    """Shareable read-only links to a slice of a user's track.

    Temporary and permanent are the same row: a permanent share is one with
    expires_at NULL. That is only safe because revoked_at exists.
    """

    dependencies = [
        ('tracker', '0083_location_indexes'),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.CreateModel(
            name='LocationShare',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True,
                                           serialize=False, verbose_name='ID')),
                ('token', models.CharField(db_index=True, max_length=64, unique=True)),
                ('label', models.CharField(blank=True, max_length=120)),
                ('window_hours', models.IntegerField(default=24)),
                ('expires_at', models.DateTimeField(blank=True, null=True)),
                ('revoked_at', models.DateTimeField(blank=True, null=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('last_viewed_at', models.DateTimeField(blank=True, null=True)),
                ('view_count', models.IntegerField(default=0)),
                ('device', models.ForeignKey(blank=True, null=True,
                                             on_delete=django.db.models.deletion.CASCADE,
                                             related_name='location_shares', to='tracker.device')),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE,
                                           related_name='location_shares',
                                           to=settings.AUTH_USER_MODEL)),
            ],
            options={'ordering': ['-created_at']},
        ),
        migrations.AddIndex(
            model_name='locationshare',
            index=models.Index(fields=['user', '-created_at'], name='tracker_share_user_idx'),
        ),
    ]
