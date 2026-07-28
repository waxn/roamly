from django.db import migrations, models


class Migration(migrations.Migration):
    """Re-add RoadDownloadJob.worker_token.

    Churn, and worth recording why rather than squashing it away. 0053 added the
    column, but the edit that was supposed to declare the field on the model
    silently did nothing — it anchored on a comment whose wording had changed, so
    the replace matched nothing and `ast.parse` still succeeded. A later
    `makemigrations` therefore saw a column in the migration state with no
    matching model field and generated 0054 to drop it, which is exactly what
    the autodetector is supposed to do.

    With the field now genuinely declared, this puts the column back. 0053 and
    0054 are left in place: both are already applied, and rewriting applied
    history would be far worse than an honest add/remove/add.
    """

    dependencies = [
        ('tracker', '0054_remove_roaddownloadjob_worker_token'),
    ]

    operations = [
        migrations.AddField(
            model_name='roaddownloadjob',
            name='worker_token',
            field=models.CharField(blank=True, default='', max_length=32),
        ),
    ]
