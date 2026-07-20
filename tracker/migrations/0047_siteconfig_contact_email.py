from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0046_userprofile_summary_email'),
    ]

    operations = [
        migrations.AddField(
            model_name='siteconfig',
            name='contact_email',
            field=models.EmailField(blank=True, default='', max_length=254),
        ),
    ]
