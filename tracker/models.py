import secrets
import uuid
from django.db import models
from django.conf import settings
from django.contrib.auth.models import User
from django.utils import timezone

# Check if PostGIS is available
HAS_POSTGIS = 'postgis' in settings.DATABASES.get('default', {}).get('ENGINE', '')

if HAS_POSTGIS:
    from django.contrib.gis.db import models as gis_models
    from django.contrib.gis.geos import Point
else:
    gis_models = None
    Point = None


class APIKey(models.Model):
    """API key for authenticating location pushes from mobile devices."""
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='api_keys')
    key = models.CharField(max_length=64, unique=True, db_index=True)
    name = models.CharField(max_length=100, help_text="Device name, e.g., 'iPhone'")
    created_at = models.DateTimeField(auto_now_add=True)
    last_used = models.DateTimeField(null=True, blank=True)
    is_active = models.BooleanField(default=True)

    def save(self, *args, **kwargs):
        if not self.key:
            self.key = secrets.token_hex(32)
        super().save(*args, **kwargs)

    def __str__(self):
        return f"{self.name} ({self.user.username})"


class Device(models.Model):
    """Represents a tracked device (phone, tablet, etc.)."""
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='devices')
    device_id = models.CharField(max_length=100)
    name = models.CharField(max_length=100, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        unique_together = ['user', 'device_id']

    def __str__(self):
        return self.name or self.device_id


if HAS_POSTGIS and gis_models:
    class Location(gis_models.Model):
        """Location data point with PostGIS spatial indexing."""
        device = gis_models.ForeignKey(Device, on_delete=models.CASCADE, related_name='locations')
        latitude = gis_models.FloatField()
        longitude = gis_models.FloatField()
        location = gis_models.PointField(geography=True, null=True, blank=True, srid=4326)
        altitude = gis_models.FloatField(null=True, blank=True)
        accuracy = gis_models.FloatField(null=True, blank=True)
        speed = gis_models.FloatField(null=True, blank=True)
        battery = gis_models.FloatField(null=True, blank=True)
        timestamp = gis_models.DateTimeField()
        created_at = gis_models.DateTimeField(auto_now_add=True)
        city = gis_models.CharField(max_length=200, blank=True)
        state = gis_models.CharField(max_length=200, blank=True)
        country = gis_models.CharField(max_length=100, blank=True)
        country_code = gis_models.CharField(max_length=3, blank=True)
        place_name = gis_models.CharField(max_length=300, blank=True)

        class Meta:
            ordering = ['-timestamp']
            indexes = [
                models.Index(fields=['device', '-timestamp'], name='tracker_loc_device__idx'),
                models.Index(fields=['city'], name='tracker_loc_city_idx'),
                models.Index(fields=['country'], name='tracker_loc_country_idx'),
                models.Index(fields=['timestamp'], name='tracker_loc_timesta_idx'),
            ]
            unique_together = ['device', 'latitude', 'longitude', 'timestamp']

        def save(self, *args, **kwargs):
            if self.latitude is not None and self.longitude is not None:
                self.location = Point(self.longitude, self.latitude, srid=4326)
            super().save(*args, **kwargs)

        def __str__(self):
            return f"{self.device} @ {self.timestamp}"
else:
    class Location(models.Model):
        """Location data point (SQLite fallback)."""
        device = models.ForeignKey(Device, on_delete=models.CASCADE, related_name='locations')
        latitude = models.FloatField()
        longitude = models.FloatField()
        altitude = models.FloatField(null=True, blank=True)
        accuracy = models.FloatField(null=True, blank=True)
        speed = models.FloatField(null=True, blank=True)
        battery = models.FloatField(null=True, blank=True)
        timestamp = models.DateTimeField()
        created_at = models.DateTimeField(auto_now_add=True)
        city = models.CharField(max_length=200, blank=True)
        state = models.CharField(max_length=200, blank=True)
        country = models.CharField(max_length=100, blank=True)
        country_code = models.CharField(max_length=3, blank=True)
        place_name = models.CharField(max_length=300, blank=True)

        class Meta:
            ordering = ['-timestamp']
            unique_together = ['device', 'latitude', 'longitude', 'timestamp']

        def __str__(self):
            return f"{self.device} @ {self.timestamp}"


class Trip(models.Model):
    """Named trip or journey with date range."""
    device = models.ForeignKey(Device, on_delete=models.CASCADE, related_name='trips')
    creator = models.ForeignKey(User, on_delete=models.SET_NULL, null=True, blank=True, related_name='created_trips')
    name = models.CharField(max_length=200)
    description = models.TextField(blank=True)
    start_time = models.DateTimeField()
    end_time = models.DateTimeField()
    public_slug = models.SlugField(max_length=64, unique=True, null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['-start_time']

    @property
    def locations(self):
        return Location.objects.filter(
            device=self.device,
            timestamp__gte=self.start_time,
            timestamp__lte=self.end_time
        ).order_by('timestamp')

    def __str__(self):
        return self.name


class GeocodingJob(models.Model):
    """Persistent state for background geocoding tasks."""
    STATUS_CHOICES = [
        ('running', 'Running'),
        ('completed', 'Completed'),
        ('stopped', 'Stopped'),
    ]
    user = models.OneToOneField(User, on_delete=models.CASCADE, related_name='geocoding_job')
    status = models.CharField(max_length=20, choices=STATUS_CHOICES, default='running')
    processed = models.IntegerField(default=0)
    errors = models.IntegerField(default=0)
    total = models.IntegerField(default=0)
    started_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return f"Geocoding {self.user.username}: {self.processed}/{self.total}"


class POI(models.Model):
    """Locally cached point of interest from OpenStreetMap."""
    name = models.CharField(max_length=300, db_index=True)
    latitude = models.FloatField()
    longitude = models.FloatField()
    category = models.CharField(max_length=100, blank=True)  # shop, amenity, aeroway, etc.
    address = models.CharField(max_length=500, blank=True)

    class Meta:
        indexes = [
            models.Index(fields=['latitude', 'longitude'], name='tracker_poi_latitud_idx'),
        ]
        # Prevent exact duplicates
        unique_together = ['name', 'latitude', 'longitude']

    def __str__(self):
        return self.name


class POIDownloadJob(models.Model):
    """Persistent state for background POI download tasks."""
    STATUS_CHOICES = [
        ('running', 'Running'),
        ('completed', 'Completed'),
        ('stopped', 'Stopped'),
    ]
    user = models.OneToOneField(User, on_delete=models.CASCADE, related_name='poi_job')
    status = models.CharField(max_length=20, choices=STATUS_CHOICES, default='running')
    processed = models.IntegerField(default=0)
    total = models.IntegerField(default=0)
    pois_added = models.IntegerField(default=0)
    started_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return f"POI Download {self.user.username}: {self.processed}/{self.total}"


class BackupConfig(models.Model):
    """S3-compatible automatic backup configuration."""
    INTERVAL_CHOICES = [
        ('disabled', 'Disabled'),
        ('daily', 'Daily'),
        ('weekly', 'Weekly'),
        ('monthly', 'Monthly'),
    ]
    STATUS_CHOICES = [
        ('never', 'Never'),
        ('success', 'Success'),
        ('failed', 'Failed'),
        ('running', 'Running'),
    ]
    user = models.OneToOneField(User, on_delete=models.CASCADE, related_name='backup_config')
    endpoint_url = models.URLField(max_length=500)
    bucket_name = models.CharField(max_length=200)
    access_key = models.CharField(max_length=200)
    secret_key = models.CharField(max_length=200)
    prefix = models.CharField(max_length=200, default='roamly-backups/', blank=True)
    region = models.CharField(max_length=100, default='auto', blank=True)
    interval = models.CharField(max_length=20, choices=INTERVAL_CHOICES, default='disabled')
    last_backup_started_at = models.DateTimeField(null=True, blank=True)
    last_backup_at = models.DateTimeField(null=True, blank=True)
    last_backup_status = models.CharField(max_length=20, choices=STATUS_CHOICES, default='never')
    last_backup_error = models.TextField(blank=True)
    last_backup_size = models.BigIntegerField(null=True, blank=True)
    last_backup_bytes_uploaded = models.BigIntegerField(null=True, blank=True)
    max_backups = models.IntegerField(default=0, help_text="Max backups to keep (0 = unlimited)")

    # Image backup
    image_backup_enabled = models.BooleanField(default=False)
    image_use_same_creds = models.BooleanField(default=True)
    image_endpoint_url = models.URLField(max_length=500, blank=True, default='')
    image_bucket_name = models.CharField(max_length=200, blank=True, default='')
    image_access_key = models.CharField(max_length=200, blank=True, default='')
    image_secret_key = models.CharField(max_length=200, blank=True, default='')
    image_prefix = models.CharField(max_length=200, blank=True, default='roamly-media/')
    image_region = models.CharField(max_length=100, blank=True, default='auto')
    last_image_backup_at = models.DateTimeField(null=True, blank=True)
    last_image_backup_status = models.CharField(max_length=20, blank=True, default='')
    last_image_backup_error = models.TextField(blank=True, default='')
    last_image_backup_size = models.BigIntegerField(null=True, blank=True)

    def __str__(self):
        return f"Backup config for {self.user.username}"


class TripPlace(models.Model):
    """Marked waypoint within a trip."""
    trip = models.ForeignKey(Trip, on_delete=models.CASCADE, related_name='places')
    name = models.CharField(max_length=200)
    latitude = models.FloatField()
    longitude = models.FloatField()
    radius = models.FloatField(default=100, help_text="Radius in meters")
    notes = models.TextField(blank=True)
    visited_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    def __str__(self):
        return self.name


class TripMember(models.Model):
    """Membership in a shared trip."""
    ROLE_CHOICES = [('creator', 'Creator'), ('member', 'Member')]
    trip = models.ForeignKey(Trip, on_delete=models.CASCADE, related_name='members')
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='trip_memberships')
    role = models.CharField(max_length=10, choices=ROLE_CHOICES, default='member')
    joined_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        unique_together = ['trip', 'user']

    def __str__(self):
        return f"{self.user.username} in {self.trip.name}"


class TripBlurb(models.Model):
    """User-posted text + location entry on a trip timeline."""
    trip = models.ForeignKey(Trip, on_delete=models.CASCADE, related_name='blurbs')
    author = models.ForeignKey(User, on_delete=models.CASCADE, related_name='trip_blurbs')
    text = models.TextField()
    latitude = models.FloatField(null=True, blank=True)
    longitude = models.FloatField(null=True, blank=True)
    location_name = models.CharField(max_length=300, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['created_at']

    def __str__(self):
        return f"Blurb by {self.author.username} on {self.trip.name}"


class TripBlurbPhoto(models.Model):
    """Photo attached to a trip blurb. Max 5 per blurb."""
    blurb = models.ForeignKey(TripBlurb, on_delete=models.CASCADE, related_name='photos')
    image = models.ImageField(upload_to='trips/blurbs/')
    thumbnail = models.ImageField(upload_to='trips/blurbs/thumbs/', null=True, blank=True)
    order = models.PositiveIntegerField(default=0)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['order']

    def __str__(self):
        return f"Photo {self.order} on blurb {self.blurb_id}"


class TripMilestone(models.Model):
    """Milestone event on a trip timeline."""
    trip = models.ForeignKey(Trip, on_delete=models.CASCADE, related_name='milestones')
    author = models.ForeignKey(User, on_delete=models.CASCADE, related_name='trip_milestones')
    title = models.CharField(max_length=200)
    description = models.TextField(blank=True)
    emoji = models.CharField(max_length=10, blank=True, default='\U0001f3c1')
    date = models.DateTimeField()
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['date']

    def __str__(self):
        return f"Milestone: {self.title}"


class TripComment(models.Model):
    """Comment on a trip blurb."""
    blurb = models.ForeignKey(TripBlurb, on_delete=models.CASCADE, related_name='comments')
    author = models.ForeignKey(User, on_delete=models.CASCADE, related_name='trip_comments', null=True, blank=True)
    guest_name = models.CharField(max_length=100, blank=True)
    text = models.TextField()
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['created_at']

    def __str__(self):
        return f"Comment by {self.author.username if self.author else self.guest_name}"


class UserProfile(models.Model):
    """Extended user profile for profile pictures."""
    user = models.OneToOneField(User, on_delete=models.CASCADE, related_name='profile')
    profile_picture = models.ImageField(upload_to='profiles/', null=True, blank=True)
    profile_picture_thumbnail = models.ImageField(upload_to='profiles/thumbs/', null=True, blank=True)

    def __str__(self):
        return f"Profile: {self.user.username}"


class Pal(models.Model):
    """A shared group trip (PAL) with a defined date range."""
    name = models.CharField(max_length=200)
    description = models.TextField(blank=True)
    start_date = models.DateField()
    end_date = models.DateField()
    creator = models.ForeignKey(User, on_delete=models.CASCADE, related_name='created_pals')
    public_slug = models.SlugField(max_length=64, unique=True, null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['-created_at']

    def __str__(self):
        return self.name


class PalMember(models.Model):
    """Membership in a PAL."""
    ROLE_CHOICES = [
        ('creator', 'Creator'),
        ('member', 'Member'),
    ]
    pal = models.ForeignKey(Pal, on_delete=models.CASCADE, related_name='members')
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='pal_memberships')
    role = models.CharField(max_length=10, choices=ROLE_CHOICES, default='member')
    joined_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        unique_together = ['pal', 'user']

    def __str__(self):
        return f"{self.user.username} in {self.pal.name}"


class PalBlurb(models.Model):
    """User-posted text + location entry in a PAL timeline."""
    pal = models.ForeignKey(Pal, on_delete=models.CASCADE, related_name='blurbs')
    author = models.ForeignKey(User, on_delete=models.CASCADE, related_name='pal_blurbs')
    text = models.TextField()
    latitude = models.FloatField()
    longitude = models.FloatField()
    location_name = models.CharField(max_length=300, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['created_at']

    def __str__(self):
        return f"Blurb by {self.author.username} in {self.pal.name}"


class PalBlurbPhoto(models.Model):
    """Photo attached to a PAL blurb. Max 5 per blurb."""
    blurb = models.ForeignKey(PalBlurb, on_delete=models.CASCADE, related_name='photos')
    image = models.ImageField(upload_to='pals/blurbs/')
    thumbnail = models.ImageField(upload_to='pals/blurbs/thumbs/', null=True, blank=True)
    order = models.PositiveIntegerField(default=0)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['order']

    def __str__(self):
        return f"Photo {self.order} on blurb {self.blurb_id}"


class PalMilestone(models.Model):
    """Milestone event in a PAL timeline."""
    pal = models.ForeignKey(Pal, on_delete=models.CASCADE, related_name='milestones')
    author = models.ForeignKey(User, on_delete=models.CASCADE, related_name='pal_milestones')
    title = models.CharField(max_length=200)
    description = models.TextField(blank=True)
    emoji = models.CharField(max_length=10, blank=True, default='\U0001f3c1')
    date = models.DateTimeField()
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['date']

    def __str__(self):
        return f"Milestone: {self.title}"


class PalComment(models.Model):
    """Comment on a PAL blurb."""
    blurb = models.ForeignKey(PalBlurb, on_delete=models.CASCADE, related_name='comments')
    author = models.ForeignKey(User, on_delete=models.CASCADE, related_name='pal_comments', null=True, blank=True)
    guest_name = models.CharField(max_length=100, blank=True)
    text = models.TextField()
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['created_at']

    def __str__(self):
        return f"Comment by {self.author.username if self.author else self.guest_name}"
