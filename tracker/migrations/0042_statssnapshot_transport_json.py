from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0041_dismissedsuggestion'),
    ]

    operations = [
        migrations.AddField(
            model_name='statssnapshot',
            name='transport_json',
            field=models.JSONField(blank=True, default=dict),
        ),
    ]
