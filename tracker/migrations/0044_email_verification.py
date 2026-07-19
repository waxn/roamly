import django.db.models.deletion
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
        ('tracker', '0043_plannedstop'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='email_verified',
            field=models.BooleanField(default=True),
        ),
        migrations.CreateModel(
            name='KnownDevice',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('token_hash', models.CharField(db_index=True, max_length=64)),
                ('label', models.CharField(blank=True, max_length=200)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('last_used', models.DateTimeField(auto_now=True)),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='known_devices', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'unique_together': {('user', 'token_hash')},
            },
        ),
    ]
