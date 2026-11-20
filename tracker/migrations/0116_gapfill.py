from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0115_userprofile_appearance'),
    ]

    operations = [
        migrations.AddField(
            model_name='userprofile',
            name='gapfill_scan_last_id',
            field=models.BigIntegerField(blank=True, null=True),
        ),
        migrations.AlterField(
            model_name='editbatch',
            name='kind',
            field=models.CharField(choices=[
                ('route', 'Road route'), ('manual', 'Manual point'),
                ('run', 'Point run'), ('copy', 'Copied range'),
                ('delete', 'Deleted points'), ('subway', 'Subway route'),
                ('relabel', 'Relabelled'), ('auto', 'Auto-filled')],
                max_length=10),
        ),
    ]
