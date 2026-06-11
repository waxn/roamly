from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0029_statssnapshot'),
    ]

    operations = [
        migrations.AddField(
            model_name='customplace',
            name='notes',
            field=models.TextField(blank=True, default=''),
        ),
    ]
