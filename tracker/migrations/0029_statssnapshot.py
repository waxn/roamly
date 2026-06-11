from django.conf import settings
from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0028_useradmin_siteconfig'),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.CreateModel(
            name='StatsSnapshot',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('stats_json', models.JSONField(blank=True, default=dict)),
                ('visits_json', models.JSONField(blank=True, default=dict)),
                ('yearly_json', models.JSONField(blank=True, default=dict)),
                ('places_json', models.JSONField(blank=True, default=dict)),
                ('status', models.CharField(default='idle', max_length=20)),
                ('error', models.TextField(blank=True)),
                ('started_at', models.DateTimeField(blank=True, null=True)),
                ('computed_at', models.DateTimeField(blank=True, null=True)),
                ('user', models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, related_name='stats_snapshot', to=settings.AUTH_USER_MODEL)),
            ],
        ),
    ]
