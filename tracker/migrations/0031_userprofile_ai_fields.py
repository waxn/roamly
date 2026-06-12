from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0030_customplace_notes'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='ai_ask_enabled',
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='ai_base_url',
            field=models.CharField(blank=True, default='', max_length=500),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='ai_api_key',
            field=models.CharField(blank=True, default='', max_length=500),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='ai_model',
            field=models.CharField(blank=True, default='', max_length=200),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='ai_system_prompt',
            field=models.TextField(blank=True, default=''),
        ),
    ]
