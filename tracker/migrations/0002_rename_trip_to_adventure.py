from django.db import migrations

class Migration(migrations.Migration):
    dependencies = [
        ('tracker', '0009_trip_creator_trip_public_slug_tripblurb_and_more'),
    ]
    operations = [
        migrations.RenameModel('Trip', 'Adventure'),
        migrations.RenameModel('TripPlace', 'AdventurePlace'),
        migrations.RenameModel('TripMember', 'AdventureMember'),
        migrations.RenameModel('TripBlurb', 'AdventureBlurb'),
        migrations.RenameModel('TripBlurbPhoto', 'AdventureBlurbPhoto'),
        migrations.RenameModel('TripMilestone', 'AdventureMilestone'),
        migrations.RenameModel('TripComment', 'AdventureComment'),
    ]
