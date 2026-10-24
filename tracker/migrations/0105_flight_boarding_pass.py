"""Flight: boarding-pass fields (booking reference, raw barcode, photo)."""
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0104_dismissedflightsuggestion'),
    ]

    operations = [
        migrations.AlterField(
            model_name='flight',
            name='source',
            field=models.CharField(choices=[('manual', 'Manual'), ('aerodatabox', 'AeroDataBox'), ('boarding_pass', 'Boarding pass')], default='manual', max_length=16),
        ),
        migrations.AddField(model_name='flight', name='booking_ref',
                            field=models.CharField(blank=True, default='', max_length=8)),
        migrations.AddField(model_name='flight', name='passenger_name',
                            field=models.CharField(blank=True, default='', max_length=64)),
        migrations.AddField(model_name='flight', name='checkin_seq',
                            field=models.CharField(blank=True, default='', max_length=5)),
        migrations.AddField(model_name='flight', name='ticket_number',
                            field=models.CharField(blank=True, default='', max_length=16)),
        migrations.AddField(model_name='flight', name='frequent_flyer',
                            field=models.CharField(blank=True, default='', max_length=24)),
        migrations.AddField(model_name='flight', name='bcbp_raw',
                            field=models.TextField(blank=True, default='')),
        migrations.AddField(model_name='flight', name='boarding_pass_image',
                            field=models.ImageField(blank=True, null=True, upload_to='boarding_passes/')),
    ]
