from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0016_sitestat_total_meters'),
    ]

    operations = [
        migrations.AddField(
            model_name='adventure',
            name='subtitle',
            field=models.CharField(blank=True, max_length=400),
        ),
        migrations.AddField(
            model_name='adventure',
            name='cover_image',
            field=models.ImageField(blank=True, null=True, upload_to='adventures/covers/'),
        ),
        migrations.AddField(
            model_name='adventure',
            name='cover_image_thumbnail',
            field=models.ImageField(blank=True, null=True, upload_to='adventures/covers/thumbs/'),
        ),
        migrations.AddField(
            model_name='adventure',
            name='body',
            field=models.JSONField(blank=True, default=list),
        ),
        migrations.AlterField(
            model_name='adventureblurb',
            name='created_at',
            field=models.DateTimeField(auto_now_add=True, db_index=True),
        ),
        migrations.AlterField(
            model_name='adventuremilestone',
            name='date',
            field=models.DateTimeField(db_index=True),
        ),
    ]
