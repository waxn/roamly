from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0034_accesslog_actionlog_adminpanelconfig'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='signup_ip',
            field=models.GenericIPAddressField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='signup_user_agent',
            field=models.TextField(blank=True, default=''),
        ),
    ]
