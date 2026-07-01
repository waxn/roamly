from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0037_unify_places_into_blurbs'),
    ]

    operations = [
        migrations.AddField(
            model_name='location',
            name='flag',
            field=models.CharField(blank=True, db_index=True, default='', max_length=10),
        ),
        migrations.AddField(
            model_name='location',
            name='flag_reason',
            field=models.CharField(blank=True, default='', max_length=100),
        ),
    ]
