from django.conf import settings
from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0024_aisummary_tags_json'),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.AddField(
            model_name='location',
            name='poi',
            field=models.ForeignKey(
                blank=True, null=True,
                on_delete=django.db.models.deletion.SET_NULL,
                related_name='located_points', to='tracker.poi',
            ),
        ),
        migrations.CreateModel(
            name='POIMatchJob',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('status', models.CharField(choices=[('running', 'Running'), ('completed', 'Completed'), ('stopped', 'Stopped')], default='running', max_length=20)),
                ('processed', models.IntegerField(default=0)),
                ('total', models.IntegerField(default=0)),
                ('matched', models.IntegerField(default=0)),
                ('started_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                ('user', models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, related_name='poi_match_job', to=settings.AUTH_USER_MODEL)),
            ],
        ),
    ]
