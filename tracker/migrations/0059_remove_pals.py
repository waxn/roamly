from django.db import migrations


class Migration(migrations.Migration):
    """Remove the Pals feature (superseded by Adventures).

    Pals was a parallel, simpler multi-member group-trip feature. Adventures now
    covers that use case (members already contribute their own tracks), so the
    whole Pal* model cluster is dropped. Every FK is Pal-internal, so deleting the
    leaf models before Pal is enough; nothing outside the cluster referenced it.

    Destructive: any existing Pal rows are permanently removed.
    """

    dependencies = [
        ('tracker', '0058_userprofile_distance_unit'),
    ]

    operations = [
        migrations.DeleteModel(name='PalComment'),
        migrations.DeleteModel(name='PalBlurbPhoto'),
        migrations.DeleteModel(name='PalMilestone'),
        migrations.DeleteModel(name='PalMember'),
        migrations.DeleteModel(name='PalBlurb'),
        migrations.DeleteModel(name='Pal'),
    ]
