from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0045_adventure_invite_token'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='summary_daily',
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='summary_weekly',
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='summary_monthly',
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='summary_yearly',
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='summary_last_daily',
            field=models.DateTimeField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='summary_last_weekly',
            field=models.DateTimeField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='summary_last_monthly',
            field=models.DateTimeField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='summary_last_yearly',
            field=models.DateTimeField(blank=True, null=True),
        ),
    ]
