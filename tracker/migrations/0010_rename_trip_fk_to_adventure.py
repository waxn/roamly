from django.db import migrations


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0002_rename_trip_to_adventure'),
    ]

    operations = [
        migrations.RenameField('AdventurePlace', 'trip', 'adventure'),
        migrations.RenameField('AdventureMember', 'trip', 'adventure'),
        migrations.RenameField('AdventureBlurb', 'trip', 'adventure'),
        migrations.RenameField('AdventureMilestone', 'trip', 'adventure'),
    ]
