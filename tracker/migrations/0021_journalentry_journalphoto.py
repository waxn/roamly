# Generated for the Journals feature.

import django.db.models.deletion
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0020_restore_adventure_body_cover_subtitle'),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.CreateModel(
            name='JournalEntry',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('date', models.DateField(db_index=True)),
                ('title', models.CharField(blank=True, max_length=300)),
                ('body', models.TextField(blank=True)),
                ('mood', models.CharField(blank=True, help_text='Emoji or short mood label', max_length=20)),
                ('weather', models.CharField(blank=True, max_length=60)),
                ('is_favorite', models.BooleanField(default=False)),
                ('pin_latitude', models.FloatField(blank=True, null=True)),
                ('pin_longitude', models.FloatField(blank=True, null=True)),
                ('location_name', models.CharField(blank=True, max_length=300)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='journal_entries', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'ordering': ['-date'],
            },
        ),
        migrations.CreateModel(
            name='JournalPhoto',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('image', models.ImageField(upload_to='journals/')),
                ('thumbnail', models.ImageField(blank=True, null=True, upload_to='journals/thumbs/')),
                ('caption', models.CharField(blank=True, max_length=300)),
                ('order', models.PositiveIntegerField(default=0)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('entry', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='photos', to='tracker.journalentry')),
            ],
            options={
                'ordering': ['order', 'id'],
            },
        ),
        migrations.AddIndex(
            model_name='journalentry',
            index=models.Index(fields=['user', '-date'], name='tracker_journal_user_date'),
        ),
        migrations.AlterUniqueTogether(
            name='journalentry',
            unique_together={('user', 'date')},
        ),
    ]
