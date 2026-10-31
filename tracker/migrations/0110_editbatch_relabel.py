from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0109_trip_blog'),
    ]

    operations = [
        migrations.AddField(
            model_name='editbatch',
            name='label_undo',
            field=models.JSONField(blank=True, default=dict),
        ),
        migrations.AlterField(
            model_name='editbatch',
            name='kind',
            field=models.CharField(choices=[('route', 'Road route'), ('manual', 'Manual point'), ('run', 'Point run'), ('copy', 'Copied range'), ('delete', 'Deleted points'), ('subway', 'Subway route'), ('relabel', 'Relabelled')], max_length=10),
        ),
    ]
