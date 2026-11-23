from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0116_gapfill'),
    ]

    operations = [
        migrations.AddField(
            model_name='journalentry',
            name='quick_note',
            field=models.TextField(blank=True),
        ),
    ]
