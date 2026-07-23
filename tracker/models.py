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
    from django.contrib.postgres.indexes import GistIndex
else:
    gis_models = None
    GistIndex = None
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
        processed_for_visits = gis_models.BooleanField(default=False)
        # Nearest named POI to this point, populated by the "Match POIs" job.
        poi = gis_models.ForeignKey('POI', on_delete=gis_models.SET_NULL, null=True, blank=True, related_name='located_points')
        # Quality review: '' = normal, 'suspect' = auto-flagged, 'ok' = user-accepted.
        flag = gis_models.CharField(max_length=10, blank=True, default='', db_index=True)
        flag_reason = gis_models.CharField(max_length=100, blank=True, default='')
        # How the user was moving, populated by the "Detect transport modes" job.
        # '' = unclassified. See tracker/transport_tasks.py for the codes.
        transport_mode = gis_models.CharField(max_length=10, blank=True, default='', db_index=True)

        class Meta:
            ordering = ['-timestamp']
            indexes = [
                models.Index(fields=['device', '-timestamp'], name='tracker_loc_device__idx'),
                models.Index(fields=['city'], name='tracker_loc_city_idx'),
                models.Index(fields=['country'], name='tracker_loc_country_idx'),
                models.Index(fields=['state'], name='tracker_loc_state_idx'),
                models.Index(fields=['country_code'], name='tracker_loc_ccode_idx'),
                models.Index(fields=['speed'], name='tracker_loc_speed_idx'),
                models.Index(fields=['battery'], name='tracker_loc_battery_idx'),
                models.Index(fields=['timestamp'], name='tracker_loc_timesta_idx'),
                GistIndex(fields=['location'], name='tracker_loc_location_gist'),
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
        processed_for_visits = models.BooleanField(default=False)
        # Nearest named POI to this point, populated by the "Match POIs" job.
        poi = models.ForeignKey('POI', on_delete=models.SET_NULL, null=True, blank=True, related_name='located_points')
        # Quality review: '' = normal, 'suspect' = auto-flagged, 'ok' = user-accepted.
        flag = models.CharField(max_length=10, blank=True, default='', db_index=True)
        flag_reason = models.CharField(max_length=100, blank=True, default='')
        # How the user was moving, populated by the "Detect transport modes" job.
        # '' = unclassified. See tracker/transport_tasks.py for the codes.
        transport_mode = models.CharField(max_length=10, blank=True, default='', db_index=True)

        class Meta:
            ordering = ['-timestamp']
            indexes = [
                models.Index(fields=['device', '-timestamp'], name='tracker_loc_device__idx'),
                models.Index(fields=['city'], name='tracker_loc_city_idx'),
                models.Index(fields=['country'], name='tracker_loc_country_idx'),
                models.Index(fields=['state'], name='tracker_loc_state_idx'),
                models.Index(fields=['country_code'], name='tracker_loc_ccode_idx'),
                models.Index(fields=['speed'], name='tracker_loc_speed_idx'),
                models.Index(fields=['battery'], name='tracker_loc_battery_idx'),
                models.Index(fields=['timestamp'], name='tracker_loc_timesta_idx'),
            ]
            unique_together = ['device', 'latitude', 'longitude', 'timestamp']

        def __str__(self):
            return f"{self.device} @ {self.timestamp}"


# Administrative boundary polygons for offline point-in-polygon reverse geocoding
# (US Census TIGER places + county subdivisions). Loaded by the import_boundaries
# management command. Lets a tracked point resolve to the town that actually
# CONTAINS it — e.g. Waldo, not the nearest bigger town a centroid lookup returns.
# PostGIS-only (PIP needs a spatial backend); the SQLite branch omits the geometry
# the same way the SQLite Location omits its PointField.
if HAS_POSTGIS and gis_models:
    class Boundary(gis_models.Model):
        name = gis_models.CharField(max_length=200, db_index=True)
        state = gis_models.CharField(max_length=100, blank=True)
        country_code = gis_models.CharField(max_length=3, default='US')
        kind = gis_models.CharField(max_length=20)  # 'place' | 'cousub'
        geom = gis_models.MultiPolygonField(srid=4326)

        class Meta:
            indexes = [
                models.Index(fields=['kind'], name='tracker_boundary_kind_idx'),
                # Spatial index is essential: every reverse-geocode does a
                # `geom__contains=point` lookup. Without a GiST index that's a
                # sequential scan running ST_Contains against every polygon in
                # the table — and because tracking pushes a point continuously,
                # the background geocoder runs that full-table scan around the
                # clock, pinning DB CPU. With the index it's an index probe.
                GistIndex(fields=['geom'], name='tracker_boundary_geom_gist'),
            ]

        def __str__(self):
            return f"{self.name}, {self.state} ({self.kind})"
else:
    class Boundary(models.Model):
        name = models.CharField(max_length=200, db_index=True)
        state = models.CharField(max_length=100, blank=True)
        country_code = models.CharField(max_length=3, default='US')
        kind = models.CharField(max_length=20)

        class Meta:
            indexes = [models.Index(fields=['kind'], name='tracker_boundary_kind_idx')]

        def __str__(self):
            return f"{self.name}, {self.state} ({self.kind})"


if HAS_POSTGIS and gis_models:
    class Visit(gis_models.Model):
        """Precomputed stay at a specific location."""
        device = gis_models.ForeignKey(Device, on_delete=gis_models.CASCADE, related_name='visits')
        start_time = gis_models.DateTimeField()
        end_time = gis_models.DateTimeField()
        latitude = gis_models.FloatField()
        longitude = gis_models.FloatField()
        location = gis_models.PointField(geography=True, null=True, blank=True, srid=4326)
        poi = gis_models.ForeignKey('POI', on_delete=gis_models.SET_NULL, null=True, blank=True, related_name='visits')
        point_count = gis_models.IntegerField(default=0)
        
        city = gis_models.CharField(max_length=200, blank=True)
        state = gis_models.CharField(max_length=200, blank=True)
        country = gis_models.CharField(max_length=100, blank=True)
        country_code = gis_models.CharField(max_length=3, blank=True)
        place_name = gis_models.CharField(max_length=300, blank=True)

        class Meta:
            ordering = ['-start_time']
            indexes = [
                gis_models.Index(fields=['device', '-start_time'], name='tracker_vis_device__idx'),
                gis_models.Index(fields=['city'], name='tracker_vis_city_idx'),
                gis_models.Index(fields=['poi'], name='tracker_vis_poi_idx'),
                GistIndex(fields=['location'], name='tracker_vis_location_gist'),
            ]

        def save(self, *args, **kwargs):
            if self.latitude is not None and self.longitude is not None:
                self.location = Point(self.longitude, self.latitude, srid=4326)
            super().save(*args, **kwargs)

        def __str__(self):
            return f"{self.device} Visit at {self.start_time}"

else:
    class Visit(models.Model):
        """Precomputed stay at a specific location (SQLite fallback)."""
        device = models.ForeignKey(Device, on_delete=models.CASCADE, related_name='visits')
        start_time = models.DateTimeField()
        end_time = models.DateTimeField()
        latitude = models.FloatField()
        longitude = models.FloatField()
        poi = models.ForeignKey('POI', on_delete=models.SET_NULL, null=True, blank=True, related_name='visits')
        point_count = models.IntegerField(default=0)

        city = models.CharField(max_length=200, blank=True)
        state = models.CharField(max_length=200, blank=True)
        country = models.CharField(max_length=100, blank=True)
        country_code = models.CharField(max_length=3, blank=True)
        place_name = models.CharField(max_length=300, blank=True)

        class Meta:
            ordering = ['-start_time']
            indexes = [
                models.Index(fields=['device', '-start_time'], name='tracker_vis_device__idx'),
                models.Index(fields=['city'], name='tracker_vis_city_idx'),
                models.Index(fields=['poi'], name='tracker_vis_poi_idx'),
            ]

        def __str__(self):
            return f"{self.device} Visit at {self.start_time}"


class VisitJob(models.Model):
    """Persistent state for background visit computation tasks."""
    STATUS_CHOICES = [
        ('running', 'Running'),
        ('completed', 'Completed'),
        ('stopped', 'Stopped'),
    ]
    user = models.OneToOneField(User, on_delete=models.CASCADE, related_name='visit_job')
    status = models.CharField(max_length=20, choices=STATUS_CHOICES, default='running')
    processed = models.IntegerField(default=0)
    total = models.IntegerField(default=0)
    visits_added = models.IntegerField(default=0)
    started_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return f"Visit Computation {self.user.username}: {self.processed}/{self.total}"




class Adventure(models.Model):
    """Named adventure or journey with date range."""
    device = models.ForeignKey(Device, on_delete=models.CASCADE, related_name='adventures')
    creator = models.ForeignKey(User, on_delete=models.SET_NULL, null=True, blank=True, related_name='created_adventures')
    name = models.CharField(max_length=200)
    description = models.TextField(blank=True)
    subtitle = models.CharField(max_length=400, blank=True)
    start_time = models.DateTimeField()
    end_time = models.DateTimeField()
    public_slug = models.SlugField(max_length=64, unique=True, null=True, blank=True)
    access_pin = models.CharField(max_length=20, blank=True)
    # Open-join invite token — anyone with the link can log in / sign up and be
    # added as a member. Distinct from public_slug (read-only page) + access_pin.
    invite_token = models.CharField(max_length=32, unique=True, null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    cover_image = models.ImageField(upload_to='adventures/covers/', null=True, blank=True)
    cover_image_thumbnail = models.ImageField(upload_to='adventures/covers/thumbs/', null=True, blank=True)
    body = models.JSONField(default=list, blank=True)

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


class POIMatchJob(models.Model):
    """Persistent state for the background 'match nearest POI to each point' task."""
    STATUS_CHOICES = [
        ('running', 'Running'),
        ('completed', 'Completed'),
        ('stopped', 'Stopped'),
    ]
    user = models.OneToOneField(User, on_delete=models.CASCADE, related_name='poi_match_job')
    status = models.CharField(max_length=20, choices=STATUS_CHOICES, default='running')
    processed = models.IntegerField(default=0)
    total = models.IntegerField(default=0)
    matched = models.IntegerField(default=0)
    started_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return f"POI Match {self.user.username}: {self.processed}/{self.total}"


class TransportJob(models.Model):
    """Persistent state for the background transport-mode detection task."""
    STATUS_CHOICES = [
        ('running', 'Running'),
        ('completed', 'Completed'),
        ('stopped', 'Stopped'),
    ]
    user = models.OneToOneField(User, on_delete=models.CASCADE, related_name='transport_job')
    status = models.CharField(max_length=20, choices=STATUS_CHOICES, default='running')
    processed = models.IntegerField(default=0)
    total = models.IntegerField(default=0)
    classified = models.IntegerField(default=0)
    started_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return f"Transport Detection {self.user.username}: {self.processed}/{self.total}"


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


class AdventureMember(models.Model):
    """Membership in a shared adventure."""
    ROLE_CHOICES = [('creator', 'Creator'), ('member', 'Member')]
    adventure = models.ForeignKey(Adventure, on_delete=models.CASCADE, related_name='members')
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='adventure_memberships')
    role = models.CharField(max_length=10, choices=ROLE_CHOICES, default='member')
    joined_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        unique_together = ['adventure', 'user']

    def __str__(self):
        return f"{self.user.username} in {self.adventure.name}"


class AdventureBlurb(models.Model):
    """A point of interest (POI) on an adventure: title + notes + optional map
    location, with photos and comments. Unifies the former AdventurePlace and
    note concepts (migration 0037)."""
    adventure = models.ForeignKey(Adventure, on_delete=models.CASCADE, related_name='blurbs')
    author = models.ForeignKey(User, on_delete=models.CASCADE, related_name='adventure_blurbs')
    title = models.CharField(max_length=200, blank=True, default='')
    text = models.TextField(blank=True)
    latitude = models.FloatField(null=True, blank=True)
    longitude = models.FloatField(null=True, blank=True)
    location_name = models.CharField(max_length=300, blank=True)
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)

    class Meta:
        ordering = ['created_at']

    def __str__(self):
        return f"Blurb by {self.author.username} on {self.adventure.name}"


class AdventureBlurbPhoto(models.Model):
    """Photo attached to an adventure blurb. Max 5 per blurb."""
    blurb = models.ForeignKey(AdventureBlurb, on_delete=models.CASCADE, related_name='photos')
    image = models.ImageField(upload_to='adventures/blurbs/')
    thumbnail = models.ImageField(upload_to='adventures/blurbs/thumbs/', null=True, blank=True)
    order = models.PositiveIntegerField(default=0)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['order']

    def __str__(self):
        return f"Photo {self.order} on blurb {self.blurb_id}"


class AdventureMilestone(models.Model):
    """Milestone event on an adventure timeline."""
    adventure = models.ForeignKey(Adventure, on_delete=models.CASCADE, related_name='milestones')
    author = models.ForeignKey(User, on_delete=models.CASCADE, related_name='adventure_milestones')
    title = models.CharField(max_length=200)
    description = models.TextField(blank=True)
    emoji = models.CharField(max_length=10, blank=True, default='\U0001f3c1')
    date = models.DateTimeField(db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['date']

    def __str__(self):
        return f"Milestone: {self.title}"


class AdventureComment(models.Model):
    """Comment on an adventure blurb."""
    blurb = models.ForeignKey(AdventureBlurb, on_delete=models.CASCADE, related_name='comments')
    author = models.ForeignKey(User, on_delete=models.CASCADE, related_name='adventure_comments', null=True, blank=True)
    guest_name = models.CharField(max_length=100, blank=True)
    text = models.TextField()
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['created_at']

    def __str__(self):
        return f"Comment by {self.author.username if self.author else self.guest_name}"


class PlannedStop(models.Model):
    """A forward-looking itinerary stop on an Adventure.

    Distinct from AdventureBlurb (a past located note/POI): a PlannedStop is
    ordered future *intent* — where you plan to go, when, how you'll get there,
    and where you'll stay. One Adventure holds both the plan (these rows) and
    the reality (the recorded track via Adventure.locations), so plan-vs-actual
    lives side by side.
    """
    adventure = models.ForeignKey(Adventure, on_delete=models.CASCADE, related_name='planned_stops')
    name = models.CharField(max_length=200)
    latitude = models.FloatField(null=True, blank=True)
    longitude = models.FloatField(null=True, blank=True)
    location_name = models.CharField(max_length=300, blank=True)
    arrival_date = models.DateField(null=True, blank=True)   # planned arrival, not actual
    nights = models.PositiveIntegerField(default=0)
    transport = models.CharField(max_length=30, blank=True)  # leg to here: plane/car/train/cycle/walk/boat/…
    notes = models.TextField(blank=True)
    accommodation = models.CharField(max_length=300, blank=True)
    order = models.PositiveIntegerField(default=0)           # manual itinerary order
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['order', 'arrival_date', 'id']

    def __str__(self):
        return f"Planned: {self.name}"


class UserProfile(models.Model):
    """Extended user profile for profile pictures."""
    user = models.OneToOneField(User, on_delete=models.CASCADE, related_name='profile')
    profile_picture = models.ImageField(upload_to='profiles/', null=True, blank=True)
    profile_picture_thumbnail = models.ImageField(upload_to='profiles/thumbs/', null=True, blank=True)
    # Instance admin: can edit instance-wide settings (e.g. the custom JS snippet).
    is_admin = models.BooleanField(default=False)

    # AI "Ask" — per-user, OpenAI-compatible provider config (see ai_tasks.py).
    # The Ask tab only appears once ai_ask_enabled + base_url + api_key + model are set.
    # ai_api_key is plaintext (like BackupConfig.secret_key) and masked on GET.
    ai_ask_enabled = models.BooleanField(default=False)
    ai_base_url = models.CharField(max_length=500, blank=True, default='')   # e.g. https://api.openai.com/v1
    ai_api_key = models.CharField(max_length=500, blank=True, default='')
    ai_model = models.CharField(max_length=200, blank=True, default='')      # e.g. gpt-4o-mini
    ai_system_prompt = models.TextField(blank=True, default='')              # optional override
    # Opt-in: let the Ask assistant read journal entries (they can be sensitive,
    # so this is off by default and gated separately from ai_ask_enabled).
    ai_allow_journals = models.BooleanField(default=False)

    # Optional Mapbox public access token (pk.…). Stored server-side so the
    # user's Mapbox basemap choice follows them across every device — web and
    # mobile both read it. It's a public/client-side token by design (safe to
    # embed in the browser and app), so it is not masked.
    mapbox_token = models.CharField(max_length=500, blank=True, default='')

    # Email verification (only enforced when SMTP is configured). Defaults True
    # so existing accounts and no-SMTP instances are never blocked; signup with
    # email enabled explicitly sets it False until the emailed code is entered.
    email_verified = models.BooleanField(default=True)

    # Opt-in AI-generated summary emails (see summary_email_tasks.py). Only sent
    # when SMTP is configured (EMAIL_ENABLED) *and* the user's AI Ask provider is
    # configured (ai_configured). Each flag is an independent cadence; the
    # summary_last_* cursor records when that cadence was last delivered so the
    # scheduler is restart-safe (no external cron, no duplicate sends).
    summary_daily = models.BooleanField(default=False)
    summary_weekly = models.BooleanField(default=False)
    summary_monthly = models.BooleanField(default=False)
    summary_yearly = models.BooleanField(default=False)
    summary_last_daily = models.DateTimeField(null=True, blank=True)
    summary_last_weekly = models.DateTimeField(null=True, blank=True)
    summary_last_monthly = models.DateTimeField(null=True, blank=True)
    summary_last_yearly = models.DateTimeField(null=True, blank=True)

    @property
    def ai_configured(self):
        return bool(self.ai_ask_enabled and self.ai_base_url and self.ai_api_key and self.ai_model)

    @property
    def summary_any(self):
        return bool(self.summary_daily or self.summary_weekly
                    or self.summary_monthly or self.summary_yearly)

    def __str__(self):
        return f"Profile: {self.user.username}"


class KnownDevice(models.Model):
    """A browser/app the user has verified, so it skips new-device email codes.

    The raw token lives in a long-lived cookie (web) or app storage (mobile);
    only its SHA-256 hash is stored here. New-device login verification is only
    triggered when SMTP is configured (see settings.EMAIL_ENABLED).
    """
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='known_devices')
    token_hash = models.CharField(max_length=64, db_index=True)
    label = models.CharField(max_length=200, blank=True)  # user-agent summary
    created_at = models.DateTimeField(auto_now_add=True)
    last_used = models.DateTimeField(auto_now=True)

    class Meta:
        unique_together = ['user', 'token_hash']

    def __str__(self):
        return f"Device for {self.user.username}"


class SiteConfig(models.Model):
    """Singleton row (pk=1) of instance-wide settings editable by an admin."""
    # Raw HTML injected verbatim before </body> on every page — paste analytics
    # snippets as-is, *including* their <script> tags (so e.g. GoatCounter's
    # <script src> tag works). Replaces the old CUSTOM_JS_SNIPPET env var.
    custom_js = models.TextField(blank=True, default='')
    # Instance contact address shown in page footers + used as the destination
    # for the public contact form. Blank hides the contact link entirely.
    contact_email = models.EmailField(blank=True, default='')
    # Admin logging retention (days). The high-volume per-request AccessLog is
    # pruned aggressively; the low-volume ActionLog (logins/signups/errors/admin
    # actions) is kept long-term. 0 = keep forever. DailyLogRollup is never pruned.
    access_log_retention_days = models.PositiveIntegerField(default=30)
    event_log_retention_days = models.PositiveIntegerField(default=0)
    updated_at = models.DateTimeField(auto_now=True)

    @classmethod
    def load(cls):
        obj, _ = cls.objects.get_or_create(pk=1)
        return obj

    def __str__(self):
        return "Site configuration"


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


class SiteStat(models.Model):
    """Singleton row (pk=1) of cached site-wide stats for the landing page.
    Refreshed at most once per 24h by a background thread from landing_view."""
    total_points = models.BigIntegerField(default=0)
    total_cities = models.IntegerField(default=0)
    total_meters = models.BigIntegerField(default=0)
    updated_at = models.DateTimeField(auto_now=True)


class JournalEntry(models.Model):
    """A DayOne-style daily journal entry. One per user per calendar day.

    The map of "where you went that day" is derived on the fly from the user's
    Location points for that date, so no track data is stored on the entry."""
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='journal_entries')
    date = models.DateField(db_index=True)
    title = models.CharField(max_length=300, blank=True)
    body = models.TextField(blank=True)
    mood = models.CharField(max_length=20, blank=True, help_text="Emoji or short mood label")
    weather = models.CharField(max_length=60, blank=True)
    is_favorite = models.BooleanField(default=False)
    # Optional manual pin chosen on the day's map (falls back to track centroid)
    pin_latitude = models.FloatField(null=True, blank=True)
    pin_longitude = models.FloatField(null=True, blank=True)
    location_name = models.CharField(max_length=300, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ['-date']
        unique_together = ['user', 'date']
        indexes = [
            models.Index(fields=['user', '-date'], name='tracker_journal_user_date'),
        ]

    def __str__(self):
        return f"Journal {self.user.username} {self.date}"


class JournalPhoto(models.Model):
    """Photo attached to a journal entry."""
    entry = models.ForeignKey(JournalEntry, on_delete=models.CASCADE, related_name='photos')
    image = models.ImageField(upload_to='journals/')
    thumbnail = models.ImageField(upload_to='journals/thumbs/', null=True, blank=True)
    caption = models.CharField(max_length=300, blank=True)
    order = models.PositiveIntegerField(default=0)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['order', 'id']

    def __str__(self):
        return f"Photo {self.order} on journal {self.entry_id}"


class CustomPlace(models.Model):
    """A user-defined named geofence (center + radius) — e.g. "Home", "Work".

    Membership is computed on the fly via the same radius query used elsewhere
    (`_find_nearby_locations`), so there is no per-point FK and no background job;
    a user has only a handful of these, so checks are cheap. The geometry lives on
    Location, so this model needs no spatial column of its own."""
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='custom_places')
    name = models.CharField(max_length=200)
    latitude = models.FloatField()
    longitude = models.FloatField()
    radius_m = models.FloatField(default=150)  # geofence radius in metres
    color = models.CharField(max_length=20, blank=True)  # auto-assigned from clay palette
    notes = models.TextField(blank=True, default='')  # free-text details, editable on the detail panel
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['name']
        indexes = [models.Index(fields=['user'], name='tracker_custplace_user_idx')]

    def __str__(self):
        return f"{self.name} ({self.user.username})"


class DismissedSuggestion(models.Model):
    """A place suggestion the user rejected, so it stops resurfacing.

    Suggestions themselves are *not* stored — they're clustered from Visit rows
    on the fly, and confirming one just creates a CustomPlace, which then covers
    the cluster and drops it from the queue on its own. So the only state worth
    persisting is the rejections, which nothing else can infer.

    Rejecting says "don't offer this as a place", not "this stay never happened":
    the underlying Visit rows are left alone, so Stats/Search/AI are unaffected.
    """
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='dismissed_suggestions')
    latitude = models.FloatField()
    longitude = models.FloatField()
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        indexes = [models.Index(fields=['user'], name='tracker_dismissed_user_idx')]

    def __str__(self):
        return f"Dismissed suggestion at {self.latitude:.4f},{self.longitude:.4f} ({self.user.username})"


class StatsSnapshot(models.Model):
    """Per-user nightly precomputation of the heavy Stats/Visits/Places payloads.

    These pages aggregate the whole location history (dwell-time per city, gated
    distance, per-place scans) which is too slow to do live — especially since a
    tracking phone pushes points constantly and busts the per-user cache. This
    snapshot is deliberately DECOUPLED from cache_gen: it is refreshed once a
    night (stats_tasks scheduler) or on demand via the recalculate button, not
    on every push. The pages serve the stored JSON instantly."""
    user = models.OneToOneField(User, on_delete=models.CASCADE, related_name='stats_snapshot')
    stats_json = models.JSONField(default=dict, blank=True)    # overview counts + total distance
    visits_json = models.JSONField(default=dict, blank=True)   # full visits payload
    yearly_json = models.JSONField(default=dict, blank=True)   # yearly overview payload
    places_json = models.JSONField(default=dict, blank=True)   # custom-places list payload
    transport_json = models.JSONField(default=dict, blank=True)  # transport-mode breakdown payload
    status = models.CharField(max_length=20, default='idle')   # idle|running|done|error
    error = models.TextField(blank=True)
    started_at = models.DateTimeField(null=True, blank=True)
    computed_at = models.DateTimeField(null=True, blank=True)

    def __str__(self):
        return f"StatsSnapshot {self.user.username} ({self.status})"


class AccessLog(models.Model):
    """One row per HTTP request — the admin panel's traffic/IP-access log.

    High volume: written by the batched background writer (log_writer.py) from
    RequestLoggingMiddleware, and pruned by log_cleanup_tasks after
    SiteConfig.access_log_retention_days. Operational data — excluded from backups.
    """
    ip_address = models.GenericIPAddressField(null=True, blank=True)
    user = models.ForeignKey(User, on_delete=models.SET_NULL, null=True, blank=True, related_name='access_logs')
    path = models.CharField(max_length=500)
    method = models.CharField(max_length=10)
    user_agent = models.TextField(blank=True)
    status_code = models.PositiveSmallIntegerField(null=True, blank=True)
    response_ms = models.PositiveIntegerField(null=True, blank=True)
    timestamp = models.DateTimeField(db_index=True)

    class Meta:
        ordering = ['-timestamp']
        indexes = [
            models.Index(fields=['ip_address', '-timestamp'], name='tracker_alog_ip_idx'),
            models.Index(fields=['user', '-timestamp'], name='tracker_alog_user_idx'),
        ]

    def __str__(self):
        return f"{self.method} {self.path} {self.status_code} @ {self.timestamp}"


class ActionLog(models.Model):
    """Significant events — logins/signups, server errors, admin ops, deletions.

    Low volume, high value: kept long-term (SiteConfig.event_log_retention_days,
    default 0 = forever). Error rows carry the traceback in `description`.
    Operational data — excluded from backups.
    """
    ACTION_CHOICES = [
        ('login', 'Login'),
        ('logout', 'Logout'),
        ('signup', 'Signup'),
        ('login_fail', 'Login failed'),
        ('error', 'Server error'),
        ('import', 'Import data'),
        ('delete_data', 'Delete location data'),
        ('delete_account', 'Delete account'),
        ('admin_toggle', 'Admin toggle'),
        ('admin_delete_user', 'Admin deleted user'),
        ('custom_js_save', 'Custom JS saved'),
        ('api_key_create', 'API key created'),
        ('api_key_delete', 'API key deleted'),
        ('other', 'Other'),
    ]
    user = models.ForeignKey(User, on_delete=models.SET_NULL, null=True, blank=True, related_name='action_logs')
    action = models.CharField(max_length=30, choices=ACTION_CHOICES, db_index=True)
    description = models.TextField(blank=True)
    ip_address = models.GenericIPAddressField(null=True, blank=True)
    timestamp = models.DateTimeField(db_index=True)

    class Meta:
        ordering = ['-timestamp']

    def __str__(self):
        return f"{self.action} by {self.user_id} @ {self.timestamp}"


class DailyLogRollup(models.Model):
    """Per-day aggregate of log activity — kept forever, powers long-range graphs.

    Computed by log_cleanup_tasks *before* raw rows are pruned, so the analytics
    time series survives even after AccessLog/ActionLog rows are deleted. Tiny
    (one row per day). Operational data — excluded from backups.
    """
    date = models.DateField(unique=True, db_index=True)
    requests = models.PositiveIntegerField(default=0)
    unique_ips = models.PositiveIntegerField(default=0)
    unique_users = models.PositiveIntegerField(default=0)
    logins = models.PositiveIntegerField(default=0)
    signups = models.PositiveIntegerField(default=0)
    errors = models.PositiveIntegerField(default=0)
    computed_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ['-date']

    def __str__(self):
        return f"Rollup {self.date}: {self.requests} req"
