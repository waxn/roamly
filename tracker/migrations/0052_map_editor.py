import django.contrib.postgres.fields
import django.db.models.deletion
from django.db import migrations, models


class Migration(migrations.Migration):
    """Replace automatic gap filling with the manual map editor.

    DESTRUCTIVE AND NOT REVERSIBLE: dropping InferredGap cascades away every
    InferredLocation with it, so all previously generated points are deleted.
    That is intended — they are the output the automatic filler produced and the
    reason it is being removed — but there is no way back short of a restore,
    and nothing in the backup carries them (all of these tables are excluded).

    InferredLocation is recreated rather than altered: its parent changes from
    InferredGap (two anchor ids + a uniqueness rule, built to make *automatic*
    re-detection idempotent) to EditBatch (one row per deliberate user action,
    built to be undone). Nothing in the old rows is worth migrating across.
    """

    dependencies = [
        ('tracker', '0051_userprofile_gap_fill_min_distance'),
    ]

    operations = [
        # Old inferred data and the background job's state go first; the
        # InferredLocation drop must precede InferredGap's (it holds the FK).
        migrations.RemoveField(model_name='inferredlocation', name='device'),
        migrations.RemoveField(model_name='inferredlocation', name='gap'),
        migrations.DeleteModel(name='InferredLocation'),
        migrations.RemoveField(model_name='inferredgap', name='device'),
        migrations.DeleteModel(name='InferredGap'),
        migrations.RemoveField(model_name='gapfilljob', name='user'),
        migrations.DeleteModel(name='GapFillJob'),

        # Automatic-fill configuration has nothing left to configure.
        migrations.RemoveField(model_name='userprofile', name='gap_fill_enabled'),
        migrations.RemoveField(model_name='userprofile', name='gap_fill_auto'),
        migrations.RemoveField(model_name='userprofile', name='gap_fill_min_minutes'),
        migrations.RemoveField(model_name='userprofile', name='gap_fill_min_distance_m'),
        migrations.RemoveField(model_name='userprofile', name='gap_fill_last_run'),

        migrations.CreateModel(
            name='EditBatch',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('kind', models.CharField(choices=[('route', 'Road route'), ('manual', 'Manual point'), ('run', 'Point run'), ('copy', 'Copied range'), ('delete', 'Deleted points')], max_length=10)),
                ('target', models.CharField(choices=[('inferred', 'Inferred'), ('location', 'Real data')], default='inferred', max_length=10)),
                ('note', models.CharField(blank=True, default='', max_length=200)),
                ('point_count', models.IntegerField(default=0)),
                ('location_ids', django.contrib.postgres.fields.ArrayField(base_field=models.BigIntegerField(), blank=True, default=list, size=None)),
                ('undone', models.BooleanField(default=False)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('device', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='edit_batches', to='tracker.device')),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='edit_batches', to='auth.user')),
            ],
            options={'ordering': ['-created_at']},
        ),
        migrations.CreateModel(
            name='InferredLocation',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('latitude', models.FloatField()),
                ('longitude', models.FloatField()),
                ('timestamp', models.DateTimeField()),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('batch', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='points', to='tracker.editbatch')),
                ('device', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='inferred_locations', to='tracker.device')),
            ],
            options={'ordering': ['timestamp']},
        ),
        migrations.CreateModel(
            name='TrashedLocation',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('original_id', models.BigIntegerField()),
                ('latitude', models.FloatField()),
                ('longitude', models.FloatField()),
                ('altitude', models.FloatField(blank=True, null=True)),
                ('accuracy', models.FloatField(blank=True, null=True)),
                ('speed', models.FloatField(blank=True, null=True)),
                ('battery', models.FloatField(blank=True, null=True)),
                ('timestamp', models.DateTimeField()),
                ('city', models.CharField(blank=True, max_length=200)),
                ('state', models.CharField(blank=True, max_length=200)),
                ('country', models.CharField(blank=True, max_length=100)),
                ('country_code', models.CharField(blank=True, max_length=3)),
                ('place_name', models.CharField(blank=True, max_length=300)),
                ('deleted_at', models.DateTimeField(auto_now_add=True, db_index=True)),
                ('batch', models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='trashed', to='tracker.editbatch')),
                ('device', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='trashed_locations', to='tracker.device')),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='trashed_locations', to='auth.user')),
            ],
            options={'ordering': ['-deleted_at']},
        ),
        migrations.AddIndex(
            model_name='editbatch',
            index=models.Index(fields=['user', '-created_at'], name='tracker_editbatch_user_idx'),
        ),
        migrations.AddIndex(
            model_name='inferredlocation',
            index=models.Index(fields=['device', 'timestamp'], name='tracker_inf_device__idx'),
        ),
        migrations.AddIndex(
            model_name='trashedlocation',
            index=models.Index(fields=['user', '-deleted_at'], name='tracker_trash_user_idx'),
        ),
    ]
