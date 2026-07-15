import django.db.models.deletion
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
        ('tracker', '0039_userprofile_mapbox_token'),
    ]

    operations = [
        migrations.AddField(
            model_name='location',
            name='transport_mode',
            field=models.CharField(blank=True, db_index=True, default='', max_length=10),
        ),
        migrations.CreateModel(
            name='TransportJob',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('status', models.CharField(choices=[('running', 'Running'), ('completed', 'Completed'), ('stopped', 'Stopped')], default='running', max_length=20)),
                ('processed', models.IntegerField(default=0)),
                ('total', models.IntegerField(default=0)),
                ('classified', models.IntegerField(default=0)),
                ('started_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                ('user', models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, related_name='transport_job', to=settings.AUTH_USER_MODEL)),
            ],
        ),
    ]
