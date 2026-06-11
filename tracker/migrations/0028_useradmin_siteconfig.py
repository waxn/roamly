from django.db import migrations, models


def make_existing_users_admin(apps, schema_editor):
    """Every account that exists before this feature becomes an instance admin."""
    User = apps.get_model('auth', 'User')
    UserProfile = apps.get_model('tracker', 'UserProfile')
    for user in User.objects.all():
        profile, _ = UserProfile.objects.get_or_create(user=user)
        if not profile.is_admin:
            profile.is_admin = True
            profile.save(update_fields=['is_admin'])


def noop_reverse(apps, schema_editor):
    pass


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0027_customplace'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='is_admin',
            field=models.BooleanField(default=False),
        ),
        migrations.CreateModel(
            name='SiteConfig',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('custom_js', models.TextField(blank=True, default='')),
                ('updated_at', models.DateTimeField(auto_now=True)),
            ],
        ),
        migrations.RunPython(make_existing_users_admin, noop_reverse),
    ]
