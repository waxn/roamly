"""Journals settings cog: AI title + mood, automatic weather."""
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0110_editbatch_relabel'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='journal_auto_title',
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='journal_auto_mood',
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name='userprofile',
            name='journal_auto_weather',
            field=models.BooleanField(default=True),
        ),
    ]
