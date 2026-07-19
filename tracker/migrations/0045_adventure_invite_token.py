from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0044_email_verification'),
    ]

    operations = [
        migrations.AddField(
            model_name='adventure',
            name='invite_token',
            field=models.CharField(blank=True, max_length=32, null=True, unique=True),
        ),
    ]
