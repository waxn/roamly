"""DeviceOverlap.kind: 'same' (duplicate track) or 'apart' (only one was carried)."""
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0100_basemap_config_to_siteconfig'),
    ]

    operations = [
        migrations.AddField(
            model_name='deviceoverlap',
            name='kind',
            field=models.CharField(choices=[('same', 'Same track'), ('apart', 'Apart')], default='same', max_length=6),
        ),
    ]
