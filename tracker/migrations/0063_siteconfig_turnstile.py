from django.db import migrations, models


class Migration(migrations.Migration):
    """Optional Cloudflare Turnstile CAPTCHA on login/signup, admin-configurable
    from the Admin Panel (mirrors the custom_js/contact_email SiteConfig fields)."""

    dependencies = [
        ('tracker', '0062_adventure_media_video'),
    ]

    operations = [
        migrations.AddField(
            model_name='siteconfig',
            name='turnstile_enabled',
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name='siteconfig',
            name='turnstile_site_key',
            field=models.CharField(blank=True, default='', max_length=255),
        ),
        migrations.AddField(
            model_name='siteconfig',
            name='turnstile_secret_key',
            field=models.CharField(blank=True, default='', max_length=255),
        ),
    ]
