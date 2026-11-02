"""Saved AI Ask conversations."""
import django.db.models.deletion
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
        ('tracker', '0111_journal_assist'),
    ]

    operations = [
        migrations.CreateModel(
            name='AskChat',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('title', models.CharField(blank=True, max_length=120)),
                ('titled', models.BooleanField(default=False)),
                ('pinned', models.BooleanField(default=False)),
                ('mode', models.CharField(blank=True, max_length=16)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='ask_chats', to=settings.AUTH_USER_MODEL)),
            ],
        ),
        migrations.CreateModel(
            name='AskMessage',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('role', models.CharField(choices=[('user', 'user'), ('assistant', 'assistant')], max_length=10)),
                ('content', models.TextField(blank=True)),
                ('cards', models.JSONField(blank=True, default=list)),
                ('meta', models.JSONField(blank=True, default=dict)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('chat', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='messages', to='tracker.askchat')),
            ],
            options={'ordering': ['id']},
        ),
        migrations.AddIndex(
            model_name='askchat',
            index=models.Index(fields=['user', '-updated_at'], name='tracker_askchat_user_idx'),
        ),
    ]
