from django.db import migrations, models


class Migration(migrations.Migration):
    """Arrival/departure notifications on a custom place.

    last_inside is nullable on purpose: NULL means "never evaluated", and the
    sweep sends nothing on the first pass. Switching a notification on while
    already standing at the place must not immediately claim you just arrived.
    """

    dependencies = [
        ('tracker', '0084_locationshare'),
    ]

    operations = [
        migrations.AddField(model_name='customplace', name='notify_arrive',
                            field=models.BooleanField(default=False)),
        migrations.AddField(model_name='customplace', name='notify_leave',
                            field=models.BooleanField(default=False)),
        migrations.AddField(model_name='customplace', name='last_inside',
                            field=models.BooleanField(blank=True, null=True)),
        migrations.AddField(model_name='customplace', name='last_notified_at',
                            field=models.DateTimeField(blank=True, null=True)),
    ]
