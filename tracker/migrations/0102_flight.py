"""Flight: the personal flight log behind the Flights page."""
from django.conf import settings
from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0101_deviceoverlap_kind'),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.CreateModel(
            name='Flight',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('flight_number', models.CharField(blank=True, default='', max_length=10)),
                ('date', models.DateField()),
                ('airline_name', models.CharField(blank=True, default='', max_length=120)),
                ('airline_iata', models.CharField(blank=True, default='', max_length=3)),
                ('airline_icao', models.CharField(blank=True, default='', max_length=4)),
                ('dep_iata', models.CharField(blank=True, default='', max_length=3)),
                ('dep_icao', models.CharField(blank=True, default='', max_length=4)),
                ('dep_name', models.CharField(blank=True, default='', max_length=200)),
                ('dep_city', models.CharField(blank=True, default='', max_length=120)),
                ('dep_country', models.CharField(blank=True, default='', max_length=2)),
                ('dep_lat', models.FloatField(blank=True, null=True)),
                ('dep_lon', models.FloatField(blank=True, null=True)),
                ('dep_tz', models.CharField(blank=True, default='', max_length=64)),
                ('arr_iata', models.CharField(blank=True, default='', max_length=3)),
                ('arr_icao', models.CharField(blank=True, default='', max_length=4)),
                ('arr_name', models.CharField(blank=True, default='', max_length=200)),
                ('arr_city', models.CharField(blank=True, default='', max_length=120)),
                ('arr_country', models.CharField(blank=True, default='', max_length=2)),
                ('arr_lat', models.FloatField(blank=True, null=True)),
                ('arr_lon', models.FloatField(blank=True, null=True)),
                ('arr_tz', models.CharField(blank=True, default='', max_length=64)),
                ('sched_dep', models.DateTimeField(blank=True, null=True)),
                ('sched_arr', models.DateTimeField(blank=True, null=True)),
                ('actual_dep', models.DateTimeField(blank=True, null=True)),
                ('actual_arr', models.DateTimeField(blank=True, null=True)),
                ('aircraft_model', models.CharField(blank=True, default='', max_length=120)),
                ('registration', models.CharField(blank=True, default='', max_length=16)),
                ('seat', models.CharField(blank=True, default='', max_length=8)),
                ('seat_class', models.CharField(blank=True, choices=[('economy', 'Economy'), ('premium', 'Premium economy'), ('business', 'Business'), ('first', 'First'), ('private', 'Private')], default='', max_length=10)),
                ('seat_type', models.CharField(blank=True, choices=[('window', 'Window'), ('middle', 'Middle'), ('aisle', 'Aisle')], default='', max_length=8)),
                ('reason', models.CharField(blank=True, choices=[('leisure', 'Leisure'), ('business', 'Business'), ('crew', 'Crew'), ('other', 'Other')], default='', max_length=10)),
                ('notes', models.TextField(blank=True, default='')),
                ('distance_km', models.FloatField(blank=True, null=True)),
                ('source', models.CharField(choices=[('manual', 'Manual'), ('aerodatabox', 'AeroDataBox')], default='manual', max_length=12)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='flights', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'ordering': ['-date', '-sched_dep'],
                'indexes': [models.Index(fields=['user', 'date'], name='tracker_flight_user_date_idx')],
            },
        ),
    ]
