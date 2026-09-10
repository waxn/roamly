"""Family Circle: mutual location sharing + shared place enter/exit alerts.

New models only — see tracker/models.py for the full design rationale
(FamilyCircle/FamilyMembership mirror AdventureMember's consent model from
migration 0082; FamilyPlace is deliberately not a retrofit of CustomPlace).

FamilyCircle, FamilyMembership, FamilyPlace and FamilyPlaceAlert are user
content and join the backup format (meta.version 13 -> 14, see
tracker/backup_tasks.py / views._write_backup_json). FamilyMemberPlaceState
(a derived transition-state cache) and FamilyPushToken (a live per-device
credential) are operational and excluded, same as StatsSnapshot/APIKey.key.
"""

from django.conf import settings
from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
        ('tracker', '0083_location_indexes'),
    ]

    operations = [
        migrations.CreateModel(
            name='FamilyCircle',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True,
                                           serialize=False, verbose_name='ID')),
                ('name', models.CharField(max_length=200)),
                ('invite_token', models.CharField(max_length=32, unique=True,
                                                  null=True, blank=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('creator', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE,
                                              related_name='family_circles_created',
                                              to=settings.AUTH_USER_MODEL)),
            ],
        ),
        migrations.CreateModel(
            name='FamilyMembership',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True,
                                           serialize=False, verbose_name='ID')),
                ('role', models.CharField(choices=[('creator', 'Creator'), ('member', 'Member')],
                                          default='member', max_length=10)),
                ('joined_at', models.DateTimeField(auto_now_add=True)),
                ('accepted_at', models.DateTimeField(blank=True, null=True)),
                ('share_location', models.BooleanField(default=False)),
                ('circle', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE,
                                             related_name='members', to='tracker.familycircle')),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE,
                                           related_name='family_memberships',
                                           to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'unique_together': {('circle', 'user')},
            },
        ),
        migrations.CreateModel(
            name='FamilyPlace',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True,
                                           serialize=False, verbose_name='ID')),
                ('name', models.CharField(max_length=200)),
                ('latitude', models.FloatField()),
                ('longitude', models.FloatField()),
                ('radius_m', models.FloatField(default=150)),
                ('color', models.CharField(blank=True, max_length=20)),
                ('notes', models.TextField(blank=True, default='')),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('circle', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE,
                                             related_name='places', to='tracker.familycircle')),
                ('creator', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE,
                                              related_name='family_places_created',
                                              to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'ordering': ['name'],
            },
        ),
        migrations.AddIndex(
            model_name='familyplace',
            index=models.Index(fields=['circle'], name='tracker_famplace_circle_idx'),
        ),
        migrations.CreateModel(
            name='FamilyPlaceAlert',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True,
                                           serialize=False, verbose_name='ID')),
                ('on_enter', models.BooleanField(default=True)),
                ('on_exit', models.BooleanField(default=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('place', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE,
                                            related_name='alerts', to='tracker.familyplace')),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE,
                                           related_name='family_place_alerts',
                                           to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'unique_together': {('place', 'user')},
            },
        ),
        migrations.CreateModel(
            name='FamilyMemberPlaceState',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True,
                                           serialize=False, verbose_name='ID')),
                ('is_inside', models.BooleanField(default=False)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                ('place', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE,
                                            related_name='member_states', to='tracker.familyplace')),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE,
                                           related_name='family_place_states',
                                           to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'unique_together': {('place', 'user')},
            },
        ),
        migrations.CreateModel(
            name='FamilyPushToken',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True,
                                           serialize=False, verbose_name='ID')),
                ('token', models.CharField(max_length=255, unique=True)),
                ('device_label', models.CharField(blank=True, default='', max_length=100)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('last_used_at', models.DateTimeField(blank=True, null=True)),
                ('last_error', models.CharField(blank=True, default='', max_length=200)),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE,
                                           related_name='family_push_tokens',
                                           to=settings.AUTH_USER_MODEL)),
            ],
        ),
    ]
