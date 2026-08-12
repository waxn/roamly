from django.db import migrations, models


class Migration(migrations.Migration):
    """First-run welcome tour flag (see RoamlyIntro in base.html). Defaults
    True so the default backfills every existing profile as already-seen;
    signup_view explicitly overrides it to False on profile creation so only
    genuinely new signups get the auto-triggered tour. See
    UserProfile.intro_seen and profile_intro_seen_api in views.py."""

    dependencies = [
        ('tracker', '0064_subway'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='intro_seen',
            field=models.BooleanField(default=True),
        ),
    ]
