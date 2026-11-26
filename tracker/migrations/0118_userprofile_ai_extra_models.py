from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0117_journalentry_quick_note'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='ai_extra_models',
            field=models.TextField(blank=True, default=''),
        ),
    ]
