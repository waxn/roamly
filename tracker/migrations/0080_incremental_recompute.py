from django.db import migrations, models


class Migration(migrations.Migration):
    """Incremental recalculation.

    `StatsSnapshot` gains a per-day partials cache (plus the coverage cursor that
    says whether it can be trusted as a whole-history record), and the three
    per-point jobs gain the two cursors an incremental re-run needs: when the last
    run finished, and how far back its results reach.

    No backfill: a NULL `covered_from` means "never run over the whole history",
    which is exactly the right thing to say about every pre-existing row — the
    first run after this migration is a full one, and every run after that is
    incremental.
    """

    dependencies = [
        ('tracker', '0079_siteconfig_carto_api_key'),
    ]

    operations = [
        migrations.AddField(
            model_name='statssnapshot',
            name='partials_json',
            field=models.JSONField(blank=True, default=dict),
        ),
        migrations.AddField(
            model_name='statssnapshot',
            name='covered_from',
            field=models.DateTimeField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='statssnapshot',
            name='last_mode',
            field=models.CharField(blank=True, default='', max_length=12),
        ),
        migrations.AddField(
            model_name='statssnapshot',
            name='last_window_start',
            field=models.DateTimeField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='geocodingjob',
            name='completed_at',
            field=models.DateTimeField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='poimatchjob',
            name='completed_at',
            field=models.DateTimeField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='poimatchjob',
            name='covered_from',
            field=models.DateTimeField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='transportjob',
            name='completed_at',
            field=models.DateTimeField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name='transportjob',
            name='covered_from',
            field=models.DateTimeField(blank=True, null=True),
        ),
    ]
