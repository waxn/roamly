from django.db import migrations, models


class Migration(migrations.Migration):
    """New profiles start with no chosen distance unit (blank), so the app can fall
    back to the phone's region. Existing rows keep what they hold."""

    dependencies = [('tracker', '0120_yearagorecap')]

    operations = [
        migrations.AlterField(
            model_name='userprofile',
            name='distance_unit',
            field=models.CharField(blank=True, default='', max_length=10),
        ),
    ]
