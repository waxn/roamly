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
    from django.contrib.postgres.fields import ArrayField
    from django.contrib.postgres.indexes import GistIndex
else:
    gis_models = None
    GistIndex = None
    ArrayField = None
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
    """Persistent state for the background POI download task.

    Singleton (pk=1), like SiteConfig — POI is instance-wide reference data
    (no user FK), so the job that populates it is now an admin-only,
    instance-wide operation rather than one row per user. Was a per-user
    OneToOneField(User) row before this; the migration that changed it also
    drops any pre-existing per-user rows, since job *status* has no value
    worth preserving across the shape change.
    """
    STATUS_CHOICES = [
        ('running', 'Running'),
        ('completed', 'Completed'),
        ('stopped', 'Stopped'),
    ]
    status = models.CharField(max_length=20, choices=STATUS_CHOICES, default='running')
    processed = models.IntegerField(default=0)
    total = models.IntegerField(default=0)
    pois_added = models.IntegerField(default=0)
    # Cities Overpass would not return after retries — same "surfaced rather
    # than silently empty" reasoning as RoadDownloadJob.failed.
    failed = models.IntegerField(default=0)
    worker_token = models.CharField(max_length=32, blank=True, default='')
    started_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    @classmethod
    def load(cls):
        obj, _ = cls.objects.get_or_create(pk=1)
        return obj

    def __str__(self):
        return f"POI Download: {self.processed}/{self.total}"


class DownloadedRegion(models.Model):
    """A chunk of the world already queried from Overpass for a given download
    kind, so a re-run — the manual admin-panel button or the auto-download
    sweep (see auto_download_tasks.py) — only asks about growth since the
    last run instead of re-downloading full history every time.

    `key` is a "gy,gx" grid-cell coordinate (road_download_tasks.CELL_DEG)
    for kind='road'/'subway' — both share the same cell grid
    (road_download_tasks._visited_cells) but are tracked independently,
    since a cell can have roads downloaded without subway or vice versa —
    or "city|state" for kind='poi', which selects by city centroid rather
    than grid cell (see poi_tasks.py).

    Instance-wide reference data, like the tables it tracks coverage for
    (no user FK) ⇒ excluded from backups.
    """
    KIND_CHOICES = [('road', 'Road'), ('subway', 'Subway'), ('poi', 'POI')]
    kind = models.CharField(max_length=10, choices=KIND_CHOICES)
    key = models.CharField(max_length=200)

    class Meta:
        unique_together = [('kind', 'key')]
        indexes = [models.Index(fields=['kind'])]

    def __str__(self):
        return f"{self.kind}:{self.key}"


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


# ---------------------------------------------------------------------------
# Road geometry — the prerequisite for snap-to-road and gap routing.
#
# Nothing else in the app stores line geometry (POI rows are bare centroids,
# Boundary is polygons), so this is the only routable road data available to
# the "local" provider in tracker/roads.py. PostGIS-only: snapping is a KNN
# `<->` probe against a GiST index and routing needs real line geometry, so the
# SQLite branch omits the table's geometry exactly like Location omits its
# PointField, and the local provider reports itself unavailable there.
#
# This is the heaviest table in the app, so the importer keeps it as small as
# it can: only drivable highway classes (no service roads, footways or paths),
# geometry simplified to ~2m on import (invisible against a 30m snap
# tolerance), node ids in a bigint array rather than JSON, and download areas
# derived from where the user actually has fixes rather than a blanket disc
# around each city.
# ---------------------------------------------------------------------------
if HAS_POSTGIS and gis_models:
    class RoadSegment(gis_models.Model):
        # OSM way id. Unique so repeated/overlapping downloads dedupe via
        # bulk_create(ignore_conflicts=True) instead of multiplying storage.
        way_id = gis_models.BigIntegerField(unique=True)
        name = gis_models.CharField(max_length=200, blank=True)
        highway = gis_models.CharField(max_length=32, db_index=True)
        oneway = gis_models.BooleanField(default=False)
        # Ordered OSM node ids. This is what makes the table routable: a node id
        # appearing in two ways is an intersection, so the routing graph can be
        # rebuilt from the rows alone without a second Overpass round-trip.
        # A bigint[] costs ~8 bytes/element against JSONB's ~10 plus heavier
        # per-row overhead — worth it on a table this size.
        node_ids = ArrayField(gis_models.BigIntegerField(), default=list, blank=True)
        geom = gis_models.LineStringField(srid=4326)

        class Meta:
            indexes = [
                # Load-bearing for both features: snapping is a KNN `geom <-> pt`
                # probe per point and routing pulls every way in a bbox. Without
                # the spatial index both degrade to sequential scans over what is
                # the largest table in the database.
                GistIndex(fields=['geom'], name='tracker_road_geom_gist'),
            ]

        def __str__(self):
            return f"{self.name or self.highway} ({self.way_id})"
else:
    class RoadSegment(models.Model):
        """SQLite fallback — no geometry, so the local road provider is unavailable."""
        way_id = models.BigIntegerField(unique=True)
        name = models.CharField(max_length=200, blank=True)
        highway = models.CharField(max_length=32, db_index=True)
        oneway = models.BooleanField(default=False)

        def __str__(self):
            return f"{self.name or self.highway} ({self.way_id})"


# ---------------------------------------------------------------------------
# Subway/metro geometry — a second, much smaller reference table modelled on
# RoadSegment. Downloaded by rail_download_tasks and routed over by rails.py to
# reconstruct the underground stretches GPS never recorded. Subway only
# (railway=subway); RailStation carries the interchange nodes the router stitches
# lines together at, so a ride crossing a transfer still routes end to end.
# ---------------------------------------------------------------------------
if HAS_POSTGIS and gis_models:
    class RailSegment(gis_models.Model):
        # OSM way id, unique so overlapping downloads dedupe via
        # bulk_create(ignore_conflicts=True), exactly like RoadSegment.
        way_id = gis_models.BigIntegerField(unique=True)
        name = gis_models.CharField(max_length=200, blank=True)
        # railway=subway today; kept as a column so the family could widen later
        # without a migration.
        railway = gis_models.CharField(max_length=32, db_index=True)
        geom = gis_models.LineStringField(srid=4326)

        class Meta:
            indexes = [
                GistIndex(fields=['geom'], name='tracker_rail_geom_gist'),
            ]

        def __str__(self):
            return f"{self.name or self.railway} ({self.way_id})"

    class RailStation(gis_models.Model):
        # OSM node id (a station/stop node). Unique so downloads dedupe.
        node_id = gis_models.BigIntegerField(unique=True)
        name = gis_models.CharField(max_length=200, blank=True)
        geom = gis_models.PointField(srid=4326)

        class Meta:
            indexes = [
                GistIndex(fields=['geom'], name='tracker_railstn_geom_gist'),
            ]

        def __str__(self):
            return f"{self.name or 'station'} ({self.node_id})"
else:
    class RailSegment(models.Model):
        """SQLite fallback — no geometry, so subway routing is unavailable."""
        way_id = models.BigIntegerField(unique=True)
        name = models.CharField(max_length=200, blank=True)
        railway = models.CharField(max_length=32, db_index=True)

        def __str__(self):
            return f"{self.name or self.railway} ({self.way_id})"

    class RailStation(models.Model):
        """SQLite fallback — no geometry."""
        node_id = models.BigIntegerField(unique=True)
        name = models.CharField(max_length=200, blank=True)

        def __str__(self):
            return f"{self.name or 'station'} ({self.node_id})"


SUBWAY_AVAILABLE_CACHE_KEY = 'subway_available'


def _subway_data_available():
    """Whether subway routing can be used at all: PostGIS + at least one way.

    Cached like _local_roads_available — the diagnostics gap endpoint and the
    settings page both consult it, and an EXISTS per request is avoidable. The
    rail importer and the delete endpoint bust this key.
    """
    if not HAS_POSTGIS:
        return False
    from django.core.cache import cache
    available = cache.get(SUBWAY_AVAILABLE_CACHE_KEY)
    if available is None:
        try:
            available = RailSegment.objects.exists()
        except Exception:
            available = False
        cache.set(SUBWAY_AVAILABLE_CACHE_KEY, available, 300)
    return bool(available)


ROADS_AVAILABLE_CACHE_KEY = 'roads_available'


def _local_roads_available():
    """Whether the local road provider can be used at all.

    Needs a spatial backend *and* at least one downloaded way. Cached because
    `road_provider_resolved` is read per-request by the context processor, and
    an `EXISTS` against the largest table in the database is not something to do
    on every page render. The road importer busts this key when it finishes.
    """
    if not HAS_POSTGIS:
        return False
    from django.core.cache import cache
    available = cache.get(ROADS_AVAILABLE_CACHE_KEY)
    if available is None:
        try:
            available = RoadSegment.objects.exists()
        except Exception:
            available = False
        cache.set(ROADS_AVAILABLE_CACHE_KEY, available, 300)
    return bool(available)


class RoadDownloadJob(models.Model):
    """Persistent state for the background OSM road download task.

    Singleton (pk=1), like SiteConfig — RoadSegment is instance-wide
    reference data (no user FK), and the download area now covers every
    user's combined travel history, so the job tracking it is an admin-only,
    instance-wide operation rather than one row per user. Was a per-user
    OneToOneField(User) row before this; the migration that changed it also
    drops any pre-existing per-user rows, since job *status* has no value
    worth preserving across the shape change.
    """
    STATUS_CHOICES = [
        ('running', 'Running'),
        ('completed', 'Completed'),
        ('stopped', 'Stopped'),
    ]
    status = models.CharField(max_length=20, choices=STATUS_CHOICES, default='running')
    processed = models.IntegerField(default=0)   # areas downloaded
    total = models.IntegerField(default=0)       # areas queued
    ways = models.IntegerField(default=0)        # road segments stored this run
    # Areas Overpass would not return after retries. Surfaced in the UI because a
    # silent failure leaves a corridor with no roads, which looks identical to
    # snapping simply not working.
    failed = models.IntegerField(default=0)
    # Which worker owns this run. _running_threads is per-process, so with more
    # than one gunicorn worker the liveness check cannot see a thread running
    # elsewhere and would resurrect a second one — both then writing their own
    # progress counter over each other, which made the reported percentage climb
    # and then jump backwards. The token is the cross-process answer: a worker
    # exits as soon as the row stops naming it.
    worker_token = models.CharField(max_length=32, blank=True, default='')
    started_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    @classmethod
    def load(cls):
        obj, _ = cls.objects.get_or_create(pk=1)
        return obj

    def __str__(self):
        return f"Road Download: {self.processed}/{self.total}"


class RailDownloadJob(models.Model):
    """Persistent state for the background OSM subway download task.

    A clone of RoadDownloadJob — same singleton (pk=1) shape, same
    worker-token ownership, same progress columns — with one extra counter
    for stations (RailStation rows added this run), since the subway
    download stores both track ways and station nodes.
    """
    STATUS_CHOICES = [
        ('running', 'Running'),
        ('completed', 'Completed'),
        ('stopped', 'Stopped'),
    ]
    status = models.CharField(max_length=20, choices=STATUS_CHOICES, default='running')
    processed = models.IntegerField(default=0)   # areas downloaded
    total = models.IntegerField(default=0)       # areas queued
    ways = models.IntegerField(default=0)        # rail segments stored this run
    stations = models.IntegerField(default=0)    # rail stations stored this run
    failed = models.IntegerField(default=0)
    worker_token = models.CharField(max_length=32, blank=True, default='')
    started_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    @classmethod
    def load(cls):
        obj, _ = cls.objects.get_or_create(pk=1)
        return obj

    def __str__(self):
        return f"Subway Download: {self.processed}/{self.total}"


class EditBatch(models.Model):
    """One action taken in the map editor, so it can be undone as a unit.

    Replaces the old InferredGap, whose whole design — two anchor ids, a unique
    constraint on them, a 'dismissed' status — existed to make *automatic*
    re-detection idempotent. With a person choosing what to change, that concern
    disappears; what matters instead is being able to reverse exactly what one
    action did.

    `location_ids` is how a paste-as-real-data is undone. Recording the created
    ids here rather than marking rows on Location keeps that model (and the
    backup format that mirrors it) completely untouched.
    """
    KIND_CHOICES = [
        ('route', 'Road route'),       # connected two points along real roads
        ('manual', 'Manual point'),    # a single hand-placed point
        ('run', 'Point run'),          # a sequence laid down from an anchor
        ('copy', 'Copied range'),      # a stretch pasted from another time
        ('delete', 'Deleted points'),  # real points moved to the trash
        ('subway', 'Subway route'),    # a GPS gap filled along subway track
    ]
    TARGET_CHOICES = [
        ('inferred', 'Inferred'),      # InferredLocation — invisible to aggregates
        ('location', 'Real data'),     # real Location rows — counted everywhere
    ]
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='edit_batches')
    device = models.ForeignKey(Device, on_delete=models.CASCADE, related_name='edit_batches')
    kind = models.CharField(max_length=10, choices=KIND_CHOICES)
    target = models.CharField(max_length=10, choices=TARGET_CHOICES, default='inferred')
    note = models.CharField(max_length=200, blank=True, default='')
    point_count = models.IntegerField(default=0)
    location_ids = ArrayField(models.BigIntegerField(), default=list, blank=True)
    undone = models.BooleanField(default=False)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['-created_at']
        indexes = [
            models.Index(fields=['user', '-created_at'], name='tracker_editbatch_user_idx'),
        ]

    def __str__(self):
        return f"{self.get_kind_display()} ({self.point_count} pts) {self.created_at}"


class InferredLocation(models.Model):
    """A point the map editor generated — never a recorded GPS fix.

    Deliberately a separate table rather than a flag on Location: Location is
    queried from ~75 places across the app (stats snapshots, distance, visits,
    fog, geocoding, transport, backups, exports), and a synthetic row in there
    would silently inflate every one of those unless all of them were audited.
    Keeping generated points out of Location makes that impossible by
    construction — the aggregates stay honest, and only the map and the editor
    ever read this table.

    No PostGIS field: the map filters these by lat/lon bbox and nothing does a
    spatial join against them, so the table stays backend-agnostic.
    """
    batch = models.ForeignKey(EditBatch, on_delete=models.CASCADE, related_name='points')
    device = models.ForeignKey(Device, on_delete=models.CASCADE, related_name='inferred_locations')
    latitude = models.FloatField()
    longitude = models.FloatField()
    timestamp = models.DateTimeField()
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['timestamp']
        indexes = [
            models.Index(fields=['device', 'timestamp'], name='tracker_inf_device__idx'),
        ]

    def __str__(self):
        return f"Inferred {self.latitude:.5f},{self.longitude:.5f} @ {self.timestamp}"


class TrashedLocation(models.Model):
    """A deleted real GPS point, kept so the deletion can be undone.

    A full snapshot rather than a tombstone: unlike DismissedSuggestion or the
    old InferredGap 'dismissed' status — where "restore" just clears a marker
    and lets a job regenerate the data — a Location is the primary record and
    cannot be recomputed from anything. If the row isn't stored here it is gone.

    Purged after TRASH_RETENTION_DAYS by the hourly log-cleanup sweep. Restoring
    mints a *new* Location id, so `original_id` is informational only.
    """
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='trashed_locations')
    device = models.ForeignKey(Device, on_delete=models.CASCADE, related_name='trashed_locations')
    batch = models.ForeignKey(EditBatch, on_delete=models.SET_NULL, null=True, blank=True,
                              related_name='trashed')
    original_id = models.BigIntegerField()
    latitude = models.FloatField()
    longitude = models.FloatField()
    altitude = models.FloatField(null=True, blank=True)
    accuracy = models.FloatField(null=True, blank=True)
    speed = models.FloatField(null=True, blank=True)
    battery = models.FloatField(null=True, blank=True)
    timestamp = models.DateTimeField()
    city = models.CharField(max_length=200, blank=True)
    state = models.CharField(max_length=200, blank=True)
    country = models.CharField(max_length=100, blank=True)
    country_code = models.CharField(max_length=3, blank=True)
    place_name = models.CharField(max_length=300, blank=True)
    deleted_at = models.DateTimeField(auto_now_add=True, db_index=True)

    class Meta:
        ordering = ['-deleted_at']
        indexes = [
            models.Index(fields=['user', '-deleted_at'], name='tracker_trash_user_idx'),
        ]

    def __str__(self):
        return f"Trashed {self.latitude:.5f},{self.longitude:.5f} @ {self.timestamp}"



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


# Place categories for located AdventureBlurbs — drive a matching map marker
# icon and let the UI filter places by kind. Blank = uncategorised.
ADVENTURE_PLACE_CATEGORIES = [
    ('restaurant', 'Restaurant'),
    ('cafe', 'Café'),
    ('bar', 'Bar / Nightlife'),
    ('hotel', 'Hotel / Lodging'),
    ('store', 'Store / Shopping'),
    ('attraction', 'Attraction / Sight'),
    ('nature', 'Nature / Park'),
    ('transport', 'Transport'),
    ('town', 'Town / City'),
    ('other', 'Other'),
]


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
    # A located blurb is a "place" you can rate + categorise (restaurant, hotel,
    # store, …). rating is 1–5, null = unrated; category ∈ ADVENTURE_PLACE_CATEGORIES.
    rating = models.PositiveSmallIntegerField(null=True, blank=True)
    category = models.CharField(max_length=20, blank=True, choices=ADVENTURE_PLACE_CATEGORIES)
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)

    class Meta:
        ordering = ['created_at']

    def __str__(self):
        return f"Blurb by {self.author.username} on {self.adventure.name}"


class AdventureBlurbPhoto(models.Model):
    """Photo or video attached to an adventure blurb. Max 5 per blurb.

    Named *Photo for history; a row is an image (image + thumbnail) or a video
    (video file, no thumbnail) per `media_type`."""
    blurb = models.ForeignKey(AdventureBlurb, on_delete=models.CASCADE, related_name='photos')
    media_type = models.CharField(max_length=10, default='image')  # 'image' | 'video'
    image = models.ImageField(upload_to='adventures/blurbs/', blank=True)
    thumbnail = models.ImageField(upload_to='adventures/blurbs/thumbs/', null=True, blank=True)
    video = models.FileField(upload_to='adventures/blurbs/videos/', null=True, blank=True)
    order = models.PositiveIntegerField(default=0)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['order']

    def __str__(self):
        return f"{self.media_type} {self.order} on blurb {self.blurb_id}"


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


class AdventureDayNote(models.Model):
    """One log entry on a single calendar day of an adventure.

    A member may write **several** entries per day (e.g. "morning hike",
    "dinner in town") — the "Day Log" tab groups every member's entries under
    each date, each editable only by its author (unlike the shared freeform
    story body). An entry may link a `place` (one of the adventure's located
    blurbs / rated POIs). The day's map track is derived on the fly from that
    member's Location points for the date, so no track data is stored here.
    Distinct from the personal JournalEntry model — that is private and
    single-author; a day note is adventure-scoped and visible to co-members
    and the public page.
    """
    adventure = models.ForeignKey(Adventure, on_delete=models.CASCADE, related_name='day_notes')
    author = models.ForeignKey(User, on_delete=models.CASCADE, related_name='adventure_day_notes')
    date = models.DateField()
    title = models.CharField(max_length=200, blank=True, default='')
    body = models.TextField(blank=True)
    # Optional link to one of the adventure's places (a located AdventureBlurb).
    place = models.ForeignKey(AdventureBlurb, on_delete=models.SET_NULL, null=True, blank=True, related_name='day_notes')
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ['date', 'created_at', 'id']

    def __str__(self):
        return f"Day note {self.date} by {self.author.username} on {self.adventure.name}"


class AdventureDayPhoto(models.Model):
    """Photo or video on an adventure day note. Max 10 per note.

    Named *Photo for history; a row is an image (image + thumbnail) or a video
    (video file, no thumbnail) per `media_type`."""
    day_note = models.ForeignKey(AdventureDayNote, on_delete=models.CASCADE, related_name='photos')
    media_type = models.CharField(max_length=10, default='image')  # 'image' | 'video'
    image = models.ImageField(upload_to='adventures/days/', blank=True)
    thumbnail = models.ImageField(upload_to='adventures/days/thumbs/', null=True, blank=True)
    video = models.FileField(upload_to='adventures/days/videos/', null=True, blank=True)
    order = models.PositiveIntegerField(default=0)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['order']

    def __str__(self):
        return f"{self.media_type} {self.order} on day note {self.day_note_id}"


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

    # Distance/speed unit for display ('kmh' or 'mph' — same values as the
    # roamly_speed_unit localStorage key it mirrors). localStorage stays the
    # source of truth for the map/stats pages (no read-path change there);
    # this server copy exists only so request-less background jobs — the
    # summary-email scheduler — can honor the user's unit too.
    distance_unit = models.CharField(max_length=10, blank=True, default='kmh')

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
    # Lazily-generated credential for the unauthenticated email-unsubscribe
    # link (see email_unsubscribe_view). null=True (not '') so multiple
    # profiles without a token yet don't collide on the unique constraint,
    # same trick as Adventure.invite_token.
    email_unsub_token = models.CharField(max_length=32, unique=True, null=True, blank=True)

    # "No data" tracking alert (see tracker/alert_tasks.py). Opt-in, SMTP-gated
    # and — unlike the summary emails above — independent of the AI provider,
    # since this is a plain operational notice with nothing to narrate. Fires
    # when *no* device on the account has reported a fix for
    # alert_no_data_hours, which is the failure users actually care about: a
    # phone that silently stopped tracking. Deliberately account-wide rather
    # than per-device — a retired device that will never report again would
    # otherwise alert forever.
    alert_no_data_enabled = models.BooleanField(default=False)
    alert_no_data_hours = models.PositiveIntegerField(default=3)
    # Dedupe cursor: the timestamp of the newest fix at the moment the alert
    # went out. One email per outage — the sweep skips a user whose newest fix
    # is still the one already alerted about, and re-arms by itself as soon as
    # a newer point lands (tracking resumed, so the next silence is a new
    # outage). No separate "recovered" flag to keep in sync.
    alert_no_data_last_point = models.DateTimeField(null=True, blank=True)
    alert_no_data_sent_at = models.DateTimeField(null=True, blank=True)

    # Roads: snap-to-road rendering + tracking-gap filling (see tracker/roads.py).
    # Provider is server-side rather than a localStorage map pref because the
    # provider config has to live here anyway and the nightly sweep needs a
    # server-side opt-in — splitting one feature's switches across two stores
    # would be worse. '' means "auto": resolve local → mapbox → osrm.
    road_provider = models.CharField(max_length=10, blank=True, default='')  # ''|local|mapbox|osrm
    osrm_url = models.CharField(max_length=300, blank=True, default='')
    # Display-only: snapped coordinates are cached, never written to Location.
    # Defaults ON (migration 0071 also flips every pre-existing profile) because
    # this is the master switch for the whole snap pipeline — point-mode snapping
    # *and* Trip Lines' road-following curvature — and defaulting it off meant the
    # feature was silently inert for every account until someone found a checkbox
    # buried under a provider dropdown. It is harmless when there is nothing
    # behind it: road_provider_resolved gates every consumer, so an account with
    # no downloaded roads and no external provider behaves exactly as before and
    # starts snapping by itself the moment a provider becomes available.
    snap_to_roads = models.BooleanField(default=True)

    # TOTP two-factor auth — an alternate 2FA method to the emailed new-device
    # code above, and independent of it: works with no SMTP configured, and
    # once enabled it's checked on *every* login rather than only new devices.
    # totp_secret is set (unconfirmed) by setup and only takes effect once
    # totp_enabled flips true after the user proves possession of it.
    totp_secret = models.CharField(max_length=32, blank=True, default='')
    totp_enabled = models.BooleanField(default=False)

    # First-run welcome tour (see RoamlyIntro in base.html). Defaults True so
    # existing accounts are never interrupted by a tour added after they
    # signed up — signup_view explicitly sets this False for a brand new
    # profile so the tour auto-triggers exactly once, on that user's first
    # visit after signup. A manual "Replay intro" (Settings) re-runs the
    # client-side tour without touching this flag.
    intro_seen = models.BooleanField(default=True)

    # Health Connect: which writing app's records to trust for a given day when
    # several of them recorded the same walk. Blank means "pick the source with
    # the most data that day", resolved per (day, metric) at read time. Config,
    # so excluded from backups like the AI/summary/alert prefs above.
    health_preferred_source = models.CharField(max_length=128, blank=True, default='')

    # Zepp (Amazfit) cloud sync. Zepp's *official* REST API is corporate-only —
    # their docs say data cooperation "only supports corporate users, not
    # individual users" — and Zepp's Health Connect integration is too patchy to
    # rely on, so this talks to the same endpoint the Zepp app itself uses.
    #
    # That means the credential is an `apptoken` lifted from the app (proxy
    # capture or a rooted device), not something Roamly can mint. It is stored
    # like ai_api_key: masked on read, only overwritten when the submitted value
    # differs from the mask. Being unofficial, it can stop working whenever Zepp
    # changes something — zepp_last_error exists so that surfaces plainly
    # instead of looking like an empty account.
    zepp_enabled = models.BooleanField(default=False)
    zepp_token = models.CharField(max_length=255, blank=True, default='')
    zepp_user_id = models.CharField(max_length=64, blank=True, default='')
    # Regional API host; Zepp shards accounts by region and the wrong host just
    # returns nothing, so it is configurable rather than hardcoded.
    zepp_host = models.CharField(max_length=128, blank=True, default='api-mifit.huami.com')
    zepp_last_sync = models.DateTimeField(null=True, blank=True)
    zepp_last_error = models.TextField(blank=True, default='')

    @property
    def zepp_configured(self):
        return bool(self.zepp_enabled and self.zepp_token and self.zepp_user_id)

    @property
    def ai_configured(self):
        return bool(self.ai_ask_enabled and self.ai_base_url and self.ai_api_key and self.ai_model)

    @property
    def road_provider_resolved(self):
        """Which road provider this profile will actually use ('' = none available).

        The "auto" default the Settings card offers: prefer locally stored OSM
        roads (no external calls at all), fall back to Mapbox if the user already
        has a token, then a configured OSRM instance.

        Returning '' when nothing is usable is load-bearing — it is the gate every
        snap consumer checks, and it is what lets snap_to_roads default on without
        firing requests at a provider that isn't there. A previous Valhalla option
        defaulted its URL to the compose service's address, so this fell through to
        'valhalla' for *every* account with no local roads and no token and never
        returned '' at all; that is one of the reasons it was removed.
        """
        if self.road_provider:
            # An explicit choice still has to be usable.
            if self.road_provider == 'local':
                return 'local' if _local_roads_available() else ''
            if self.road_provider == 'mapbox':
                return 'mapbox' if self.mapbox_token else ''
            if self.road_provider == 'osrm':
                return 'osrm' if self.osrm_url else ''
            return ''
        if _local_roads_available():
            return 'local'
        if self.mapbox_token:
            return 'mapbox'
        if self.osrm_url:
            return 'osrm'
        return ''

    @property
    def roads_configured(self):
        return bool(self.road_provider_resolved)

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


class TOTPBackupCode(models.Model):
    """One single-use recovery code for a user's TOTP enrollment, minted in a
    batch by _generate_totp_backup_codes whenever TOTP is enabled or the set
    is regenerated. Only the hash is stored — the plaintext code is shown to
    the user once, at generation time, and never again."""
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='totp_backup_codes')
    code_hash = models.CharField(max_length=64, db_index=True)
    used_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    def __str__(self):
        return f"Backup code for {self.user.username}"


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
    # Optional Cloudflare Turnstile CAPTCHA on login/signup. Site key is safe to
    # expose to templates; the secret key is server-only (used to call
    # Cloudflare's siteverify endpoint) and must never reach a template context.
    turnstile_enabled = models.BooleanField(default=False)
    turnstile_site_key = models.CharField(max_length=255, blank=True, default='')
    turnstile_secret_key = models.CharField(max_length=255, blank=True, default='')
    # Auto-download new regions for road/subway/POI data as anyone on the
    # instance travels somewhere not yet covered, instead of requiring an
    # admin to notice and click the Downloads-tab button. Each defaults on;
    # see tracker/auto_download_tasks.py.
    auto_download_roads = models.BooleanField(default=True)
    auto_download_subway = models.BooleanField(default=True)
    auto_download_pois = models.BooleanField(default=True)
    # Overpass endpoints for those downloads, one per line, tried in order.
    # Blank falls back to settings.OVERPASS_URLS (the built-in pool, with the
    # legacy OVERPASS_URL env var first if it is set). Editable here rather
    # than only via env because docker-compose.yml is gitignored and enumerates
    # its env vars explicitly, so adding one to .env alone never reaches the
    # container — which makes "just point it at a working mirror" a much bigger
    # job than it sounds. See tracker/overpass.py.
    overpass_urls = models.TextField(blank=True, default='')
    updated_at = models.DateTimeField(auto_now=True)

    @classmethod
    def load(cls):
        obj, _ = cls.objects.get_or_create(pk=1)
        return obj

    def __str__(self):
        return "Site configuration"


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


class DismissedSubwayGap(models.Model):
    """A detected subway gap the user rejected, so it stops resurfacing.

    Gaps are recomputed on the fly from the track (subway_gap_tasks), never
    stored — filling one just creates real Location rows that close the gap, so
    it drops out of detection on its own. The only state worth persisting is a
    rejection, keyed on the two anchor coordinates (rounded when matched), the
    same trust model as DismissedSuggestion. User intent ⇒ excluded from backups.
    """
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='dismissed_subway_gaps')
    start_latitude = models.FloatField()
    start_longitude = models.FloatField()
    end_latitude = models.FloatField()
    end_longitude = models.FloatField()
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        indexes = [models.Index(fields=['user'], name='tracker_dismsub_user_idx')]

    def __str__(self):
        return (f"Dismissed subway gap "
                f"({self.start_latitude:.4f},{self.start_longitude:.4f})→"
                f"({self.end_latitude:.4f},{self.end_longitude:.4f}) ({self.user.username})")


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
        ('totp_enable', 'TOTP enabled'),
        ('totp_disable', 'TOTP disabled'),
        ('totp_regen_backup', 'TOTP backup codes regenerated'),
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


# ── Health Connect ────────────────────────────────────────────────────────────
# Steps / distance / calories read from Android Health Connect, plus manually
# imported exercise sessions. Deliberately isolated: nothing in the tracking,
# stats, visits or distance pipeline reads these tables, and they read nothing
# from it. Health works on an account that never enabled GPS tracking at all,
# which is why neither model has a Device FK.
#
# Both are non-spatial, so unlike Location/Visit/Boundary they are defined once
# rather than behind the HAS_POSTGIS branch — that split exists only to keep a
# geometry column and its GiST index off SQLite.

HEALTH_KINDS = [
    ('steps', 'Steps'),
    ('distance', 'Distance'),
    ('calories_active', 'Active calories'),
    ('calories_total', 'Total calories'),
]


class HealthSample(models.Model):
    """One raw Health Connect record.

    Stored raw rather than pre-aggregated so the day view can show *when* the
    user was moving, not just a daily total.

    ``external_id`` is the source's own stable id for the record, which makes
    ingest idempotent under ``bulk_create(ignore_conflicts=True)`` against the
    unique constraint — the same dedupe strategy push_location_batch uses on
    Location. For Health Connect that is its record UUID; Zepp has no such id, so
    it gets a deterministic key synthesised from the day and segment
    (``zepp:steps:2026-08-27:540``), which means a re-sync of the same day
    updates rather than duplicates. The column was called ``hc_id`` when Health
    Connect was the only source; the wire format still accepts that name, since
    app builds predating the rename are already in the wild.

    ``source`` matters more than it looks: several apps (the phone's own step
    counter, Fitbit, Samsung Health, Strava) all write overlapping records for
    the same walk. Health Connect's aggregate() API dedupes them against a user
    priority list; raw records carry no such dedupe, so summing across sources
    inflates a day's steps several times over. Within one package records are
    non-overlapping, so the read path sums per-source and then picks one source
    per (day, metric) — see _health_daily_totals in views.py.

    ``device_id`` is a plain CharField, NOT a Device FK: health must work for an
    account that never enabled tracking. It exists from the first migration
    because two phones on one account produce distinct record ids for the same steps
    from the same source package, and that cannot be backfilled later.
    """
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='health_samples')
    kind = models.CharField(max_length=16, choices=HEALTH_KINDS)
    value = models.FloatField()  # count / metres / kilocalories, per `kind`
    start_time = models.DateTimeField()
    end_time = models.DateTimeField()
    # HC startZoneOffset.totalSeconds — recorded so a future "use the timezone I
    # was actually in" option doesn't require re-syncing the whole history.
    zone_offset_seconds = models.IntegerField(null=True, blank=True)
    source = models.CharField(max_length=128, blank=True, default='')
    device_id = models.CharField(max_length=100, blank=True, default='')
    external_id = models.CharField(max_length=120)
    last_modified = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        # Deliberately NO `ordering`. Django appends Meta.ordering columns to the
        # GROUP BY of a .values().annotate() aggregation, which would shatter the
        # daily rollup into one group per row. Order explicitly at each call site.
        unique_together = ['user', 'external_id']
        indexes = [
            models.Index(fields=['user', 'kind', 'start_time'], name='tracker_hs_user_kind_idx'),
            models.Index(fields=['user', '-start_time'], name='tracker_hs_user_start_idx'),
        ]

    def __str__(self):
        return f"{self.kind}={self.value} @ {self.start_time}"


class HealthWorkout(models.Model):
    """An exercise session the user explicitly chose to import.

    Deliberately manual — the app lists Health Connect sessions and the user taps
    Import on the ones worth keeping, rather than a heuristic deciding which
    sessions "look real" on their behalf.

    Totals are denormalised, computed on the phone at import time via Health
    Connect's aggregate() over the session window. They are NOT recomputed from
    HealthSample: steps aren't recorded for a bike ride, the user may not have
    granted every metric permission, and a session can predate the sample sync
    window — so a server-side recomputation would quietly report zeros.
    """
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='health_workouts')
    external_id = models.CharField(max_length=120)
    source = models.CharField(max_length=128, blank=True, default='')
    device_id = models.CharField(max_length=100, blank=True, default='')
    start_time = models.DateTimeField()
    end_time = models.DateTimeField()
    zone_offset_seconds = models.IntegerField(null=True, blank=True)
    # Both the raw HC constant (fidelity) and a resolved slug (display), so
    # neither the server nor the web page has to carry Google's enum table.
    exercise_type = models.IntegerField(default=0)
    exercise_slug = models.CharField(max_length=40, blank=True, default='')
    title = models.CharField(max_length=200, blank=True, default='')
    notes = models.TextField(blank=True, default='')
    duration_s = models.IntegerField(default=0)
    steps = models.IntegerField(null=True, blank=True)
    distance_m = models.FloatField(null=True, blank=True)
    calories_kcal = models.FloatField(null=True, blank=True)
    avg_heart_rate = models.FloatField(null=True, blank=True)
    imported_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        unique_together = ['user', 'external_id']
        indexes = [
            models.Index(fields=['user', '-start_time'], name='tracker_hw_user_start_idx'),
        ]

    def __str__(self):
        return f"{self.exercise_slug or 'workout'} @ {self.start_time}"
