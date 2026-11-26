import django.db.models.deletion
import django.utils.timezone
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0118_userprofile_ai_extra_models'),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.CreateModel(
            name='SummaryEmailLog',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('period', models.CharField(choices=[('daily', 'Daily'), ('weekly', 'Weekly'), ('monthly', 'Monthly'), ('yearly', 'Yearly')], max_length=10)),
                ('period_start', models.DateField()),
                ('period_end', models.DateField()),
                ('subject', models.CharField(max_length=200)),
                ('body_text', models.TextField(blank=True)),
                ('body_html', models.TextField(blank=True)),
                ('status', models.CharField(choices=[('sent', 'Sent'), ('failed', 'Failed')], default='sent', max_length=10)),
                ('is_test', models.BooleanField(default=False)),
                ('sent_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='summary_email_logs', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'indexes': [models.Index(fields=['user', '-sent_at', '-id'], name='tracker_sumlog_user_idx')],
            },
        ),
    ]
