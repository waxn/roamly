from django.db import migrations, models
from django.db.models import Q


class Migration(migrations.Migration):
    """Two indexes the query patterns always needed.

    tracker_loc_dev_ts_idx is the ASC companion to tracker_loc_device__idx: a
    btree on ('device', '-timestamp') reads as (device ASC, ts DESC) or
    (device DESC, ts ASC), and six hot paths order by (device ASC, ts ASC), so
    Postgres full-sorted the filtered set first.

    tracker_loc_unproc_idx is partial over the visit worker's backlog.
    processed_for_visits had no index at all, so each 5000-row chunk re-walked
    the history from the oldest end — O(n^2) across a full run. Being partial it
    covers only the unprocessed tail and shrinks to nothing as the worker
    catches up.

    Both are large tables in production. Run this during a quiet window, or
    convert to AddIndexConcurrently (with atomic = False) if the instance cannot
    take the write lock.
    """

    dependencies = [
        ('tracker', '0082_adventuremember_consent'),
    ]

    operations = [
        migrations.AddIndex(
            model_name='location',
            index=models.Index(fields=['device', 'timestamp'], name='tracker_loc_dev_ts_idx'),
        ),
        migrations.AddIndex(
            model_name='location',
            index=models.Index(fields=['device', 'timestamp'],
                               condition=Q(processed_for_visits=False),
                               name='tracker_loc_unproc_idx'),
        ),
    ]
