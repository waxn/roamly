"""Cell coverage: which towers the phone camps on, per SIM.

Roamly records where you were but nothing about how you were connected. The
phone logs its serving and neighbour cells alongside each GPS fix — signal
strength, band, RAT, and which SIM — and the web map gains an optional layer
for it. Off by default on the phone and off by default on the map.

ONE TABLE, AND DELIBERATELY NO DERIVED-TOWER TABLE. A tower is a GROUP BY over
these rows — signal-weighted centroid, first/last seen, sample count, best and
worst dBm (views.cell_towers_api) — so it can never drift from the samples that
produced it and needs no rebuild job. A second table would be a cache with no
invalidation story, and the aggregation is already cached for an hour behind
its own cell_gen counter.

Non-spatial, so defined once rather than behind the HAS_POSTGIS branch, like
the health models in 0074 and Activity in 0081. That split exists only to keep
a geometry column and its GiST index off SQLite, and nothing in this feature
queries spatially: the tower layer is all-time and sparse, the sample layer is
date-ranged like track_api rather than viewport-driven. If a viewport-driven
cell layer is ever wanted, that is the moment a geography column earns its cost.

NO Meta.ordering, for the reason HealthSample's own Meta records — and it bites
harder here, because the tower view IS a .values().annotate(). Django appends
ordering columns to the GROUP BY, which would shatter the aggregation into one
group per row.

client_id is Activity's phone-minted idempotency key, for the same reason: the
uploader re-sends routinely (the 4xx single-point fallback, the replace=true
wedged-backlog recovery), and retrofitting uniqueness onto a table that already
has duplicates is far worse than paying for the unique index now.

THE TABLE IS THIS SIZE BY DESIGN. An ungated scan — serving plus neighbours,
dual SIM, at the 30s capture interval — is roughly 8-12 CellInfo entries per
reading, ~29,000 rows/day, ~10M/year: ten times Location itself, for a feature
that is off by default. The phone gates hard before writing anything (see
tracking/CellScanner.kt): a 60s scan floor decoupled from the capture interval,
then per-cell "this identity is new, OR you moved 150m since its last sample,
OR its heartbeat elapsed", with neighbours written only in a scan where that
SIM's serving row also qualified and capped per scan. That lands at
~1,500-2,300 rows/day — under what Location already produces, a ~12x reduction.
A future reader finding this table large should check the gate, not assume it.

sim_slot defaults to -1, which is what a phone reports when the optional
READ_PHONE_STATE permission was declined: multi-SIM enumeration needs it, plain
cell scanning does not, so declining degrades the feature to the default
subscription rather than breaking it. Subscription id is not stored (unstable
across SIM re-insertion and eSIM reprovisioning, so it would fragment a tower's
history); IMSI/ICCID/IMEI are never read at all.

Cell history is user content the phone cannot re-derive — the modem keeps no
log — so it joins the backup format: meta.version 15 -> 16. It streams
row-by-row like locations and health samples rather than going through a
list-builder, being the second-largest section in the document.
"""

from django.conf import settings
from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
        ('tracker', '0088_actionlog_share_choices'),
    ]

    operations = [
        migrations.CreateModel(
            name='CellSample',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('device_id', models.CharField(blank=True, default='', max_length=100)),
                ('client_id', models.CharField(max_length=32)),
                ('timestamp', models.DateTimeField()),
                ('latitude', models.FloatField()),
                ('longitude', models.FloatField()),
                ('accuracy', models.FloatField(blank=True, null=True)),
                ('rat', models.CharField(choices=[('lte', 'LTE'), ('nr', '5G NR'), ('wcdma', 'WCDMA'), ('gsm', 'GSM')], max_length=8)),
                ('role', models.CharField(choices=[('serving', 'Serving'), ('secondary', 'Secondary serving'), ('neighbour', 'Neighbour')], max_length=10)),
                ('sim_slot', models.SmallIntegerField(default=-1)),
                ('carrier', models.CharField(blank=True, default='', max_length=64)),
                ('mcc', models.CharField(blank=True, default='', max_length=3)),
                ('mnc', models.CharField(blank=True, default='', max_length=3)),
                ('tac', models.IntegerField(blank=True, null=True)),
                ('cid', models.BigIntegerField(blank=True, null=True)),
                ('pci', models.IntegerField(blank=True, null=True)),
                ('earfcn', models.IntegerField(blank=True, null=True)),
                ('band', models.SmallIntegerField(blank=True, null=True)),
                ('dbm', models.SmallIntegerField(blank=True, null=True)),
                ('asu', models.SmallIntegerField(blank=True, null=True)),
                ('level', models.SmallIntegerField(blank=True, null=True)),
                ('rsrq', models.SmallIntegerField(blank=True, null=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='cell_samples', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'unique_together': {('user', 'client_id')},
            },
        ),
        migrations.AddIndex(
            model_name='cellsample',
            index=models.Index(fields=['user', 'timestamp'], name='tracker_cs_user_ts_idx'),
        ),
        migrations.AddIndex(
            model_name='cellsample',
            index=models.Index(fields=['user', 'mcc', 'mnc', 'tac', 'cid'], name='tracker_cs_user_cell_idx'),
        ),
    ]
