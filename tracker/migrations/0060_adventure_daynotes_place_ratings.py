import django.db.models.deletion
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):
    """Adventures rebuild: rateable/categorised places + per-member day notes.

    - AdventureBlurb gains rating (1–5) + category, so a located blurb becomes a
      rateable "place" (restaurant/hotel/store/…).
    - AdventureDayNote / AdventureDayPhoto add the Day Log: each member writes
      their own note per calendar day, with photos.
    """

    dependencies = [
        ('tracker', '0059_remove_pals'),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.AddField(
            model_name='adventureblurb',
            name='rating',
            field=models.PositiveSmallIntegerField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='adventureblurb',
            name='category',
            field=models.CharField(
                blank=True, max_length=20,
                choices=[
                    ('restaurant', 'Restaurant'), ('cafe', 'Café'),
                    ('bar', 'Bar / Nightlife'), ('hotel', 'Hotel / Lodging'),
                    ('store', 'Store / Shopping'), ('attraction', 'Attraction / Sight'),
                    ('nature', 'Nature / Park'), ('transport', 'Transport'),
                    ('town', 'Town / City'), ('other', 'Other'),
                ],
            ),
        ),
        migrations.CreateModel(
            name='AdventureDayNote',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('date', models.DateField()),
                ('title', models.CharField(blank=True, default='', max_length=200)),
                ('body', models.TextField(blank=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                ('adventure', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='day_notes', to='tracker.adventure')),
                ('author', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='adventure_day_notes', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'ordering': ['date'],
                'unique_together': {('adventure', 'author', 'date')},
            },
        ),
        migrations.CreateModel(
            name='AdventureDayPhoto',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('image', models.ImageField(upload_to='adventures/days/')),
                ('thumbnail', models.ImageField(blank=True, null=True, upload_to='adventures/days/thumbs/')),
                ('order', models.PositiveIntegerField(default=0)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('day_note', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='photos', to='tracker.adventuredaynote')),
            ],
            options={
                'ordering': ['order'],
            },
        ),
    ]
