from django.db import migrations, models


class Migration(migrations.Migration):
    """Allow video uploads on day-log entries and story/blurb photo grids.

    Each *Photo row becomes image-or-video: add `media_type` + a `video`
    FileField, and let `image` be blank (video rows have no image)."""

    dependencies = [
        ('tracker', '0061_daynote_multi_place'),
    ]

    operations = [
        migrations.AddField(
            model_name='adventureblurbphoto',
            name='media_type',
            field=models.CharField(default='image', max_length=10),
        ),
        migrations.AddField(
            model_name='adventureblurbphoto',
            name='video',
            field=models.FileField(blank=True, null=True, upload_to='adventures/blurbs/videos/'),
        ),
        migrations.AlterField(
            model_name='adventureblurbphoto',
            name='image',
            field=models.ImageField(blank=True, upload_to='adventures/blurbs/'),
        ),
        migrations.AddField(
            model_name='adventuredayphoto',
            name='media_type',
            field=models.CharField(default='image', max_length=10),
        ),
        migrations.AddField(
            model_name='adventuredayphoto',
            name='video',
            field=models.FileField(blank=True, null=True, upload_to='adventures/days/videos/'),
        ),
        migrations.AlterField(
            model_name='adventuredayphoto',
            name='image',
            field=models.ImageField(blank=True, upload_to='adventures/days/'),
        ),
    ]
