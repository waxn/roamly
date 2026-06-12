from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0031_userprofile_ai_fields'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='ai_allow_journals',
            field=models.BooleanField(default=False),
        ),
    ]
