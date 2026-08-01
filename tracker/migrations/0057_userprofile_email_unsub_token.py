from django.db import migrations, models


class Migration(migrations.Migration):
    """Unauthenticated email-unsubscribe link credential. See
    UserProfile.email_unsub_token and email_unsubscribe_view in views.py."""

    dependencies = [
        ('tracker', '0056_totp'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='email_unsub_token',
            field=models.CharField(blank=True, max_length=32, null=True, unique=True),
        ),
    ]
