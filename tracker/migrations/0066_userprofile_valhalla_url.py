from django.db import migrations, models


class Migration(migrations.Migration):
    """Valhalla as a fourth road-snapping/routing provider (see roads.py). A
    self-hosted (or third-party) Valhalla instance URL, alongside the
    existing OSRM URL — Meili, Valhalla's map-matching engine, is a proper
    HMM-based matcher, generally more robust than the local provider's
    nearest-edge+Viterbi-lite snapper in dense grids with parallel roads."""

    dependencies = [
        ('tracker', '0065_userprofile_intro_seen'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='valhalla_url',
            field=models.CharField(max_length=300, blank=True, default=''),
        ),
    ]
