from django.conf import settings
from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
        ('tracker', '0113_ask_fix_mojibake'),
    ]

    operations = [
        migrations.CreateModel(
            name='AskProposal',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('kind', models.CharField(choices=[('flight_add', 'Add flight'), ('flight_update', 'Edit flight'), ('journal', 'Journal entry'), ('place_add', 'Add place')], max_length=16)),
                ('target_id', models.IntegerField(blank=True, null=True)),
                ('target_key', models.CharField(blank=True, max_length=20)),
                ('summary', models.CharField(max_length=200)),
                ('payload', models.JSONField(default=dict)),
                ('before', models.JSONField(blank=True, default=dict)),
                ('rows', models.JSONField(blank=True, default=list)),
                ('status', models.CharField(choices=[('pending', 'Pending'), ('approved', 'Approved'), ('denied', 'Denied'), ('failed', 'Failed')], default='pending', max_length=10)),
                ('error', models.CharField(blank=True, max_length=300)),
                ('result_id', models.IntegerField(blank=True, null=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('decided_at', models.DateTimeField(blank=True, null=True)),
                ('chat', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='proposals', to='tracker.askchat')),
                ('message', models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.CASCADE, related_name='proposals', to='tracker.askmessage')),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='ask_proposals', to=settings.AUTH_USER_MODEL)),
            ],
            options={'ordering': ['id']},
        ),
    ]
