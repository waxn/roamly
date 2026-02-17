import secrets
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
                models.Index(fields=['device', '-timestamp']),
                models.Index(fields=['city']),
                models.Index(fields=['country']),
                models.Index(fields=['timestamp']),
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
    name = models.CharField(max_length=200)
    description = models.TextField(blank=True)
    start_time = models.DateTimeField()
    end_time = models.DateTimeField()
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
            models.Index(fields=['latitude', 'longitude']),
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
