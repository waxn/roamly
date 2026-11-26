import django.db.models.deletion
import django.utils.timezone
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0119_summaryemaillog'),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.CreateModel(
            name='YearAgoRecap',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('day', models.DateField()),
                ('headline', models.CharField(max_length=80)),
                ('summary', models.TextField(blank=True)),
                ('distance', models.CharField(blank=True, max_length=40)),
                ('places', models.CharField(blank=True, max_length=500)),
                ('journal_used', models.BooleanField(default=False)),
                ('journal_consent', models.BooleanField(default=False)),
                ('generated_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('viewed_at', models.DateTimeField(blank=True, null=True)),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='year_ago_recaps', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'indexes': [models.Index(fields=['user', '-generated_at', '-id'], name='tracker_yearago_user_idx'),
                            models.Index(fields=['viewed_at'], name='tracker_yearago_viewed_idx')],
                'constraints': [models.UniqueConstraint(fields=('user', 'day'), name='tracker_yearago_user_day_uniq')],
            },
        ),
    ]
