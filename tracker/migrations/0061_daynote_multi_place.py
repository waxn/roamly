import django.db.models.deletion
from django.db import migrations, models


class Migration(migrations.Migration):
    """Day Log: allow multiple entries per member per day + link a place.

    Drops the (adventure, author, date) uniqueness so a member can write
    several entries on one day, and adds an optional FK to an AdventureBlurb
    (a located/rated place) per entry.
    """

    dependencies = [
        ('tracker', '0060_adventure_daynotes_place_ratings'),
    ]

    operations = [
        migrations.AlterUniqueTogether(
            name='adventuredaynote',
            unique_together=set(),
        ),
        migrations.AddField(
            model_name='adventuredaynote',
            name='place',
            field=models.ForeignKey(
                blank=True, null=True,
                on_delete=django.db.models.deletion.SET_NULL,
                related_name='day_notes', to='tracker.adventureblurb',
            ),
        ),
        migrations.AlterModelOptions(
            name='adventuredaynote',
            options={'ordering': ['date', 'created_at', 'id']},
        ),
    ]
