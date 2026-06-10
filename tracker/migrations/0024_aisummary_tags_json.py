from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0023_aiconfig_aisummary'),
    ]

    operations = [
        migrations.AddField(
            model_name='aisummary',
            name='tags_json',
            field=models.JSONField(default=list),
        ),
    ]
