from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0114_ask_proposals'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='appearance',
            field=models.JSONField(blank=True, default=dict),
        ),
    ]
