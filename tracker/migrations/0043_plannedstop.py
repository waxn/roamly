import django.db.models.deletion
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0042_statssnapshot_transport_json'),
    ]

    operations = [
        migrations.CreateModel(
            name='PlannedStop',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('name', models.CharField(max_length=200)),
                ('latitude', models.FloatField(blank=True, null=True)),
                ('longitude', models.FloatField(blank=True, null=True)),
                ('location_name', models.CharField(blank=True, max_length=300)),
                ('arrival_date', models.DateField(blank=True, null=True)),
                ('nights', models.PositiveIntegerField(default=0)),
                ('transport', models.CharField(blank=True, max_length=30)),
                ('notes', models.TextField(blank=True)),
                ('accommodation', models.CharField(blank=True, max_length=300)),
                ('order', models.PositiveIntegerField(default=0)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('adventure', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='planned_stops', to='tracker.adventure')),
            ],
            options={
                'ordering': ['order', 'arrival_date', 'id'],
            },
        ),
    ]
