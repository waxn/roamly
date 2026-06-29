from django.db import migrations


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0035_userprofile_signup_ip'),
    ]

    operations = [
        migrations.RemoveField(
            model_name='userprofile',
            name='signup_ip',
        ),
        migrations.RemoveField(
            model_name='userprofile',
            name='signup_user_agent',
        ),
        migrations.DeleteModel(name='AccessLog'),
        migrations.DeleteModel(name='ActionLog'),
        migrations.DeleteModel(name='AdminPanelConfig'),
    ]
