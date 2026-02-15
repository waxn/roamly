import csv
import io
import json
import logging
import math
import urllib.request
from collections import defaultdict
from datetime import datetime, timedelta, timezone as dt_timezone

from django.contrib import messages
from django.contrib.auth import authenticate, login, logout
from django.contrib.auth.decorators import login_required
from django.db.models import Count, Min, Max, Avg
from django.http import JsonResponse, HttpResponse
from django.shortcuts import render, redirect, get_object_or_404
from django.utils import timezone
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods

from django.core.cache import cache
from django.db import connection
from django.contrib.staticfiles import finders

from .forms import SignUpForm, APIKeyForm, TripForm
from .models import Device, Location, APIKey, Trip, TripPlace
from .geocoding_tasks import start_geocoding, get_status as get_geocoding_status, stop_geocoding

logger = logging.getLogger(__name__)

# Check for PostGIS
try:
    from django.contrib.gis.geos import Polygon
    HAS_POSTGIS = True
except Exception:
    HAS_POSTGIS = False
    Polygon = None


# ---------------------------------------------------------------------------
# PWA Service Worker
# ---------------------------------------------------------------------------

def service_worker(request):
    sw_path = finders.find('tracker/sw.js')
    with open(sw_path) as f:
        response = HttpResponse(f.read(), content_type='application/javascript')
    response['Service-Worker-Allowed'] = '/'
    response['Cache-Control'] = 'no-cache'
    return response


# ---------------------------------------------------------------------------
# Reverse Geocoding
# ---------------------------------------------------------------------------

def reverse_geocode(lat, lon):
    """Reverse geocode coordinates using OpenStreetMap Nominatim."""
    try:
        url = (
            f'https://nominatim.openstreetmap.org/reverse'
            f'?lat={lat}&lon={lon}&format=json&zoom=10&addressdetails=1'
        )
        headers = {
            'User-Agent': 'Roamly/0.5.0 (self-hosted location tracker)',
            'Accept-Language': 'en',
        }
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode())

        if 'error' in data:
            return None

        address = data.get('address', {})
        city = (
            address.get('city') or address.get('town') or
            address.get('village') or address.get('municipality') or
            address.get('county') or address.get('suburb') or ''
        )
        state = address.get('state') or address.get('province') or address.get('region') or ''
        country = address.get('country', '')
        country_code = address.get('country_code', '').upper()
        place_name = data.get('display_name', '')

        return {
            'city': city, 'state': state,
            'country': country, 'country_code': country_code,
            'place_name': place_name,
        }
    except Exception as e:
        logger.warning(f"Geocoding failed for {lat},{lon}: {e}")
        return None


# ---------------------------------------------------------------------------
# API Key Auth
# ---------------------------------------------------------------------------

def get_api_key_user(request):
    """Extract and validate API key from request."""
    auth_header = request.headers.get('Authorization', '')
    if auth_header.startswith('Bearer '):
        key = auth_header[7:]
    else:
        key = request.GET.get('api_key') or request.POST.get('api_key')
        if not key and request.body:
            try:
                data = json.loads(request.body.decode('utf-8'))
                key = data.get('api_key')
                request._cached_json_body = data
            except Exception:
                pass

    if not key:
        return None

    try:
        api_key = APIKey.objects.select_related('user').get(key=key, is_active=True)
        api_key.last_used = timezone.now()
        api_key.save(update_fields=['last_used'])
        return api_key.user
    except APIKey.DoesNotExist:
        return None


# ---------------------------------------------------------------------------
# Auth Views
# ---------------------------------------------------------------------------

def landing_view(request):
    """Landing page - show marketing page for non-authenticated users."""
    return render(request, 'tracker/landing.html')


def docs_view(request):
    """Documentation page - tutorials, setup guides, and API reference."""
    return render(request, 'tracker/docs.html')


def terms_view(request):
    return render(request, 'tracker/terms.html')


def privacy_view(request):
    return render(request, 'tracker/privacy.html')


def login_view(request):
    if request.user.is_authenticated:
        return redirect('tracker:map')
    if request.method == 'POST':
        username = request.POST.get('username')
        password = request.POST.get('password')
        user = authenticate(request, username=username, password=password)
        if user:
            login(request, user)
            return redirect(request.GET.get('next', 'tracker:map'))
        messages.error(request, 'Invalid username or password.')
    return render(request, 'tracker/login.html')


def signup_view(request):
    if request.user.is_authenticated:
        return redirect('tracker:map')
    if request.method == 'POST':
        form = SignUpForm(request.POST)
        if form.is_valid():
            user = form.save()
            login(request, user)
            return redirect('tracker:map')
    else:
        form = SignUpForm()
    return render(request, 'tracker/signup.html', {'form': form})


def logout_view(request):
    logout(request)
    return redirect('tracker:login')


# ---------------------------------------------------------------------------
# Page Views
# ---------------------------------------------------------------------------

@login_required
def map_view(request):
    devices = Device.objects.filter(user=request.user)
    return render(request, 'tracker/map.html', {
        'devices': devices,
        'has_postgis': HAS_POSTGIS,
    })


@login_required
def data_table(request):
    devices = Device.objects.filter(user=request.user)
    return render(request, 'tracker/data_table.html', {'devices': devices})


@login_required
def stats_view(request):
    return render(request, 'tracker/stats.html')


@login_required
def visits_view(request):
    return render(request, 'tracker/visits.html')


@login_required
def trips_view(request):
    devices = Device.objects.filter(user=request.user)
    trips = Trip.objects.filter(device__user=request.user)
    return render(request, 'tracker/trips.html', {'devices': devices, 'trips': trips})


@login_required
def settings_view(request):
    api_keys = APIKey.objects.filter(user=request.user)
    devices = Device.objects.filter(user=request.user)
    form = APIKeyForm()
    return render(request, 'tracker/settings.html', {
        'api_keys': api_keys,
        'devices': devices,
        'form': form,
        'has_postgis': HAS_POSTGIS,
    })


# ---------------------------------------------------------------------------
# Location API
# ---------------------------------------------------------------------------

@csrf_exempt
@require_http_methods(["POST", "GET"])
def push_location(request):
    """Receive location data from mobile devices (OwnTracks + standard format)."""
    user = get_api_key_user(request)
    if not user:
        return JsonResponse({"error": "Invalid or missing API key"}, status=401)

    if request.method == "GET":
        data = dict(request.GET)
        data = {k: v[0] if isinstance(v, list) and len(v) == 1 else v for k, v in data.items()}
    elif hasattr(request, '_cached_json_body'):
        data = request._cached_json_body
    else:
        try:
            data = json.loads(request.body.decode('utf-8'))
        except (json.JSONDecodeError, UnicodeDecodeError):
            # Fallback to form-encoded POST data
            if request.POST:
                data = dict(request.POST)
                data = {k: v[0] if isinstance(v, list) and len(v) == 1 else v for k, v in data.items()}
            else:
                return JsonResponse({"error": "Invalid JSON"}, status=400)

    # Detect format
    if data.get("_type") == "location":
        device_id = data.get("tid") or data.get("device_id", "unknown")
        latitude = data.get("lat")
        longitude = data.get("lon")
        timestamp = data.get("tst")
        if timestamp:
            timestamp = datetime.fromtimestamp(int(timestamp), tz=dt_timezone.utc)
        altitude = data.get("alt")
        accuracy = data.get("acc")
        speed = data.get("vel")
        battery = data.get("batt")
    else:
        device_id = data.get("device_id")
        latitude = data.get("latitude")
        longitude = data.get("longitude")
        timestamp = data.get("timestamp")
        if timestamp:
            if isinstance(timestamp, (int, float)):
                timestamp = datetime.fromtimestamp(int(timestamp), tz=dt_timezone.utc)
            elif isinstance(timestamp, str):
                try:
                    timestamp = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
                except ValueError:
                    timestamp = datetime.fromtimestamp(int(timestamp), tz=dt_timezone.utc)
        altitude = data.get("altitude")
        accuracy = data.get("accuracy")
        speed = data.get("speed")
        battery = data.get("battery")

    if not device_id:
        return JsonResponse({"error": "device_id required"}, status=400)
    if latitude is None or longitude is None:
        return JsonResponse({"error": "latitude and longitude required"}, status=400)

    try:
        latitude = float(latitude)
        longitude = float(longitude)
    except (TypeError, ValueError):
        return JsonResponse({"error": "Invalid latitude/longitude"}, status=400)

    if not timestamp:
        timestamp = timezone.now()

    device, _ = Device.objects.get_or_create(
        user=user, device_id=str(device_id),
        defaults={'name': str(device_id)}
    )

    location = Location.objects.create(
        device=device,
        latitude=latitude,
        longitude=longitude,
        altitude=float(altitude) if altitude else None,
        accuracy=float(accuracy) if accuracy else None,
        speed=float(speed) if speed else None,
        battery=float(battery) if battery else None,
        timestamp=timestamp,
    )

    # Non-blocking geocode
    try:
        result = reverse_geocode(latitude, longitude)
        if result:
            location.city = result['city']
            location.state = result['state']
            location.country = result['country']
            location.country_code = result['country_code']
            location.place_name = result['place_name']
            location.save()
    except Exception:
        pass

    return JsonResponse({"status": "ok", "location_id": location.id, "device": str(device_id)})


@login_required
def locations_api(request):
    """Get locations with spatial filtering."""
    device_id = request.GET.get("device_id")
    all_time = request.GET.get("all")
    limit = min(int(request.GET.get("limit", 5000)), 50000)
    offset = int(request.GET.get("offset", 0))
    start_date = request.GET.get("start_date")
    end_date = request.GET.get("end_date")
    min_lng = request.GET.get("min_lng")
    min_lat = request.GET.get("min_lat")
    max_lng = request.GET.get("max_lng")
    max_lat = request.GET.get("max_lat")

    locations = Location.objects.filter(device__user=request.user)

    if start_date and end_date:
        try:
            start = datetime.fromisoformat(start_date).replace(hour=0, minute=0, second=0)
            end = datetime.fromisoformat(end_date).replace(hour=23, minute=59, second=59)
            if timezone.is_naive(start):
                start = timezone.make_aware(start)
            if timezone.is_naive(end):
                end = timezone.make_aware(end)
            locations = locations.filter(timestamp__gte=start, timestamp__lte=end)
        except (ValueError, TypeError):
            pass
    elif not all_time:
        hours = int(request.GET.get("hours", 24))
        since = timezone.now() - timedelta(hours=hours)
        locations = locations.filter(timestamp__gte=since)

    if device_id:
        locations = locations.filter(device__device_id=device_id)

    if min_lng and min_lat and max_lng and max_lat:
        try:
            _min_lng, _min_lat = float(min_lng), float(min_lat)
            _max_lng, _max_lat = float(max_lng), float(max_lat)
            if HAS_POSTGIS and Polygon and hasattr(Location, 'location'):
                bbox = Polygon.from_bbox((_min_lng, _min_lat, _max_lng, _max_lat))
                locations = locations.filter(location__within=bbox)
            else:
                locations = locations.filter(
                    latitude__gte=_min_lat, latitude__lte=_max_lat,
                    longitude__gte=_min_lng, longitude__lte=_max_lng,
                )
        except (ValueError, TypeError):
            pass

    locations = locations.select_related('device').order_by('-timestamp')[offset:offset + limit]

    devices_data = {}
    for loc in locations:
        did = loc.device.device_id
        if did not in devices_data:
            devices_data[did] = {
                "device_id": did,
                "name": loc.device.name or did,
                "locations": [],
            }
        devices_data[did]["locations"].append({
            "id": loc.id,
            "lat": loc.latitude,
            "lng": loc.longitude,
            "timestamp": loc.timestamp.isoformat(),
            "altitude": loc.altitude,
            "accuracy": loc.accuracy,
            "speed": loc.speed,
            "battery": loc.battery,
            "city": loc.city,
            "state": loc.state,
            "country": loc.country,
            "country_code": loc.country_code,
        })

    return JsonResponse({"devices": list(devices_data.values())})


@login_required
def vector_tile(request, z, x, y):
    """Serve Mapbox Vector Tiles generated by PostGIS ST_AsMVT."""
    if not HAS_POSTGIS:
        return HttpResponse(status=404)

    # Parse filter params (same as locations_api)
    device_id = request.GET.get("device_id")
    all_time = request.GET.get("all")
    start_date = request.GET.get("start_date")
    end_date = request.GET.get("end_date")
    hours = request.GET.get("hours", "24")

    params = {"z": z, "x": x, "y": y, "user_id": request.user.id}
    extra_where = []

    if start_date and end_date:
        try:
            start = datetime.fromisoformat(start_date).replace(hour=0, minute=0, second=0)
            end_dt = datetime.fromisoformat(end_date).replace(hour=23, minute=59, second=59)
            if timezone.is_naive(start):
                start = timezone.make_aware(start)
            if timezone.is_naive(end_dt):
                end_dt = timezone.make_aware(end_dt)
            extra_where.append("AND l.timestamp >= %(ts_start)s AND l.timestamp <= %(ts_end)s")
            params["ts_start"] = start
            params["ts_end"] = end_dt
        except (ValueError, TypeError):
            pass
    elif not all_time:
        h = int(hours)
        since = timezone.now() - timedelta(hours=h)
        extra_where.append("AND l.timestamp >= %(since)s")
        params["since"] = since

    if device_id:
        extra_where.append("AND d.device_id = %(device_id)s")
        params["device_id"] = device_id

    filter_clause = "\n              ".join(extra_where)

    # Check cache
    cache_key = f"tile:{request.user.id}:{z}:{x}:{y}:{request.GET.urlencode()}"
    cached = cache.get(cache_key)
    if cached is not None:
        response = HttpResponse(cached, content_type="application/x-protobuf")
        response["Cache-Control"] = "public, max-age=30"
        return response

    points_sql = f"""
    WITH bounds AS (
        SELECT ST_TileEnvelope(%(z)s, %(x)s, %(y)s) AS geom
    ),
    mvtgeom AS (
        SELECT
            ST_AsMVTGeom(
                ST_Transform(l.location::geometry, 3857),
                bounds.geom, 4096, 256, true
            ) AS geom,
            l.id,
            l.speed,
            l.battery,
            l.city,
            l.state,
            l.country,
            EXTRACT(EPOCH FROM l.timestamp)::bigint AS ts,
            d.device_id,
            COALESCE(d.name, d.device_id) AS device_name
        FROM tracker_location l
        JOIN tracker_device d ON l.device_id = d.id
        CROSS JOIN bounds
        WHERE d.user_id = %(user_id)s
          AND l.location IS NOT NULL
          AND ST_Intersects(l.location::geometry, ST_Transform(bounds.geom, 4326))
              {filter_clause}
        LIMIT 100000
    )
    SELECT ST_AsMVT(mvtgeom.*, 'locations') FROM mvtgeom;
    """

    trails_sql = f"""
    WITH bounds AS (
        SELECT ST_TileEnvelope(%(z)s, %(x)s, %(y)s) AS geom
    ),
    ordered_pts AS (
        SELECT l.location, l.timestamp, d.device_id, d.id AS d_pk
        FROM tracker_location l
        JOIN tracker_device d ON l.device_id = d.id
        CROSS JOIN bounds
        WHERE d.user_id = %(user_id)s
          AND l.location IS NOT NULL
          AND ST_Intersects(l.location::geometry, ST_Transform(bounds.geom, 4326))
              {filter_clause}
        ORDER BY d.device_id, l.timestamp
    ),
    lines AS (
        SELECT device_id,
               ST_MakeLine(ST_Transform(location::geometry, 3857) ORDER BY timestamp) AS geom
        FROM ordered_pts
        GROUP BY device_id, d_pk
        HAVING COUNT(*) > 1
    ),
    mvtgeom AS (
        SELECT ST_AsMVTGeom(lines.geom, bounds.geom, 4096, 256, true) AS geom,
               lines.device_id
        FROM lines CROSS JOIN bounds
        WHERE ST_Intersects(lines.geom, bounds.geom)
    )
    SELECT ST_AsMVT(mvtgeom.*, 'trails') FROM mvtgeom;
    """

    with connection.cursor() as cursor:
        cursor.execute(points_sql, params)
        points_tile = cursor.fetchone()[0]

        cursor.execute(trails_sql, params)
        trails_tile = cursor.fetchone()[0]

    tile_data = bytes(points_tile) + bytes(trails_tile)

    if not tile_data:
        return HttpResponse(status=204)

    cache.set(cache_key, tile_data, timeout=30)

    response = HttpResponse(tile_data, content_type="application/x-protobuf")
    response["Cache-Control"] = "public, max-age=30"
    return response


@login_required
def locations_geojson_api(request):
    """Return locations as GeoJSON FeatureCollection."""
    device_id = request.GET.get("device_id")
    all_time = request.GET.get("all")
    limit = min(int(request.GET.get("limit", 5000)), 50000)

    locations = Location.objects.filter(device__user=request.user)

    if not all_time:
        hours = int(request.GET.get("hours", 24))
        since = timezone.now() - timedelta(hours=hours)
        locations = locations.filter(timestamp__gte=since)

    if device_id:
        locations = locations.filter(device__device_id=device_id)

    locations = locations.select_related('device').order_by('timestamp')[:limit]

    features = []
    for loc in locations:
        features.append({
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [loc.longitude, loc.latitude]},
            "properties": {
                "timestamp": loc.timestamp.isoformat(),
                "device": loc.device.name or loc.device.device_id,
                "city": loc.city,
                "country": loc.country,
            },
        })

    return JsonResponse({"type": "FeatureCollection", "features": features})


# ---------------------------------------------------------------------------
# Stats & Visits API
# ---------------------------------------------------------------------------

@login_required
def stats_api(request):
    """Overall statistics with time filtering."""
    device_id = request.GET.get("device_id")
    all_time = request.GET.get("all")
    start_date = request.GET.get("start_date")
    end_date = request.GET.get("end_date")

    locations = Location.objects.filter(device__user=request.user)

    if start_date and end_date:
        try:
            start = datetime.fromisoformat(start_date).replace(hour=0, minute=0, second=0)
            end = datetime.fromisoformat(end_date).replace(hour=23, minute=59, second=59)
            if timezone.is_naive(start):
                start = timezone.make_aware(start)
            if timezone.is_naive(end):
                end = timezone.make_aware(end)
            locations = locations.filter(timestamp__gte=start, timestamp__lte=end)
        except (ValueError, TypeError):
            pass
    elif not all_time:
        hours = int(request.GET.get("hours", 24))
        since = timezone.now() - timedelta(hours=hours)
        locations = locations.filter(timestamp__gte=since)

    if device_id:
        locations = locations.filter(device__device_id=device_id)

    total = locations.count()
    countries = locations.exclude(country='').values('country').distinct().count()
    cities = locations.exclude(city='').values('city', 'country').distinct().count()
    states = locations.exclude(state='').values('state', 'country').distinct().count()
    devices = Device.objects.filter(user=request.user).count()

    first = locations.order_by('timestamp').values_list('timestamp', flat=True).first()
    last = locations.order_by('-timestamp').values_list('timestamp', flat=True).first()

    return JsonResponse({
        "total_points": total,
        "countries": countries,
        "cities": cities,
        "states": states,
        "devices": devices,
        "first_location": first.isoformat() if first else None,
        "last_location": last.isoformat() if last else None,
    })


def _haversine_km(lat1, lon1, lat2, lon2):
    """Calculate distance between two points in km using haversine formula."""
    R = 6371.0
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


@login_required
def distance_api(request):
    """Daily distance travelled, computed from sequential location points."""
    device_id = request.GET.get("device_id")
    all_time = request.GET.get("all")
    start_date = request.GET.get("start_date")
    end_date = request.GET.get("end_date")

    locations = Location.objects.filter(device__user=request.user)

    if start_date and end_date:
        try:
            start = datetime.fromisoformat(start_date).replace(hour=0, minute=0, second=0)
            end = datetime.fromisoformat(end_date).replace(hour=23, minute=59, second=59)
            if timezone.is_naive(start):
                start = timezone.make_aware(start)
            if timezone.is_naive(end):
                end = timezone.make_aware(end)
            locations = locations.filter(timestamp__gte=start, timestamp__lte=end)
        except (ValueError, TypeError):
            pass
    elif not all_time:
        hours = int(request.GET.get("hours", 24))
        since = timezone.now() - timedelta(hours=hours)
        locations = locations.filter(timestamp__gte=since)

    if device_id:
        locations = locations.filter(device__device_id=device_id)

    locations = locations.order_by('device', 'timestamp').values_list(
        'device_id', 'latitude', 'longitude', 'timestamp'
    )[:50000]

    daily_km = defaultdict(float)
    total_km = 0.0
    prev = {}  # per-device previous point

    for dev_id, lat, lon, ts in locations:
        day = ts.strftime('%Y-%m-%d')
        if dev_id in prev:
            p_lat, p_lon, p_ts = prev[dev_id]
            # Skip if gap > 2 hours (likely separate trips, not continuous travel)
            if (ts - p_ts).total_seconds() <= 7200:
                d = _haversine_km(p_lat, p_lon, lat, lon)
                # Skip unreasonable jumps (> 500 km between consecutive points)
                if d <= 500:
                    daily_km[day] += d
                    total_km += d
        prev[dev_id] = (lat, lon, ts)

    days = sorted(daily_km.keys())
    return JsonResponse({
        "days": days,
        "distances": [round(daily_km[d], 2) for d in days],
        "total_km": round(total_km, 2),
    })


@login_required
def visits_api(request):
    """Aggregated city/state/country visit statistics."""
    device_id = request.GET.get("device_id")
    locations = Location.objects.filter(device__user=request.user).exclude(city='')
    if device_id:
        locations = locations.filter(device__device_id=device_id)

    city_stats = locations.values('city', 'state', 'country', 'country_code').annotate(
        count=Count('id'), first_seen=Min('timestamp'), last_seen=Max('timestamp')
    ).order_by('-count')

    cities = [{
        "city": s['city'], "state": s['state'],
        "country": s['country'], "country_code": s['country_code'],
        "visit_count": s['count'],
        "first_seen": s['first_seen'].isoformat() if s['first_seen'] else None,
        "last_seen": s['last_seen'].isoformat() if s['last_seen'] else None,
    } for s in city_stats]

    country_stats = locations.values('country', 'country_code').annotate(
        location_count=Count('id'),
        city_count=Count('city', distinct=True),
        state_count=Count('state', distinct=True),
    ).order_by('-location_count')

    countries = [{
        "country": s['country'], "country_code": s['country_code'],
        "location_count": s['location_count'],
        "city_count": s['city_count'], "state_count": s['state_count'],
    } for s in country_stats]

    state_stats = locations.values('state', 'country').annotate(
        count=Count('id'), city_count=Count('city', distinct=True)
    ).order_by('-count')

    states = [{
        "state": s['state'], "country": s['country'],
        "location_count": s['count'], "city_count": s['city_count'],
    } for s in state_stats if s['state']]

    # Time spent per city/state/country
    # Attribute gap between consecutive points to the first point's location
    MAX_GAP = 3600  # Cap at 1 hour to avoid idle/overnight skew
    time_city = defaultdict(float)
    time_state = defaultdict(float)
    time_country = defaultdict(float)

    points = locations.order_by('timestamp').values_list(
        'timestamp', 'city', 'state', 'country', 'country_code',
    )
    prev = None
    for ts, city, state_val, country_val, cc in points.iterator():
        if prev:
            gap = min((ts - prev[0]).total_seconds(), MAX_GAP)
            if gap > 0:
                if prev[1]:
                    time_city[(prev[1], prev[2], prev[3], prev[4])] += gap
                if prev[2]:
                    time_state[(prev[2], prev[3])] += gap
                if prev[3]:
                    time_country[prev[3]] += gap
        prev = (ts, city, state_val, country_val, cc)

    # Attach time_spent to existing result lists
    for c in cities:
        key = (c['city'], c['state'], c['country'], c['country_code'])
        c['time_spent'] = round(time_city.get(key, 0))

    for s in states:
        key = (s['state'], s['country'])
        s['time_spent'] = round(time_state.get(key, 0))

    for c in countries:
        c['time_spent'] = round(time_country.get(c['country'], 0))

    return JsonResponse({"cities": cities, "states": states, "countries": countries})


# ---------------------------------------------------------------------------
# Trips API
# ---------------------------------------------------------------------------

@login_required
def trips_api(request):
    trips = Trip.objects.filter(device__user=request.user).select_related('device')
    data = []
    for trip in trips:
        loc_count = trip.locations.count()
        data.append({
            "id": trip.id,
            "name": trip.name,
            "description": trip.description,
            "device": trip.device.name or trip.device.device_id,
            "start_time": trip.start_time.isoformat(),
            "end_time": trip.end_time.isoformat(),
            "location_count": loc_count,
        })
    return JsonResponse({"trips": data})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def create_trip(request):
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)

    device_id = data.get('device_id')
    if not device_id:
        return JsonResponse({"error": "device_id required"}, status=400)

    try:
        device = Device.objects.get(user=request.user, device_id=device_id)
    except Device.DoesNotExist:
        return JsonResponse({"error": "Device not found"}, status=404)

    try:
        start_raw = data.get('start_time', '').strip()
        end_raw = data.get('end_time', '').strip()
        if not start_raw or not end_raw:
            return JsonResponse({"error": "Start and end dates are required"}, status=400)
        start = datetime.fromisoformat(start_raw.replace('Z', '+00:00'))
        end = datetime.fromisoformat(end_raw.replace('Z', '+00:00'))
        # Date-only inputs produce midnight; set end to end of day
        if 'T' not in end_raw:
            end = end.replace(hour=23, minute=59, second=59)
        if timezone.is_naive(start):
            start = timezone.make_aware(start)
        if timezone.is_naive(end):
            end = timezone.make_aware(end)
    except (KeyError, ValueError) as e:
        return JsonResponse({"error": f"Invalid dates: {e}"}, status=400)

    trip = Trip.objects.create(
        device=device,
        name=data.get('name', 'Untitled Trip'),
        description=data.get('description', ''),
        start_time=start,
        end_time=end,
    )

    return JsonResponse({"status": "ok", "trip_id": trip.id})


def _calculate_time_spent(trip_locations, place_lat, place_lng, place_radius_m):
    """Calculate time spent near a place based on location points within radius."""
    nearby_timestamps = []
    for loc in trip_locations:
        dist_km = _haversine_km(loc.latitude, loc.longitude, place_lat, place_lng)
        if dist_km * 1000 <= place_radius_m:
            nearby_timestamps.append(loc.timestamp)

    if len(nearby_timestamps) < 2:
        return len(nearby_timestamps) * 60  # 1 min per single point

    nearby_timestamps.sort()
    total_seconds = 0
    for i in range(1, len(nearby_timestamps)):
        gap = (nearby_timestamps[i] - nearby_timestamps[i - 1]).total_seconds()
        if gap <= 7200:  # skip gaps > 2 hours
            total_seconds += gap
    return int(total_seconds)


def _format_duration(seconds):
    """Format seconds into human-readable duration."""
    if seconds < 60:
        return f"{seconds}s"
    minutes = seconds // 60
    if minutes < 60:
        return f"{minutes}m"
    hours = minutes // 60
    remaining_min = minutes % 60
    if hours < 24:
        return f"{hours}h {remaining_min}m" if remaining_min else f"{hours}h"
    days = hours // 24
    remaining_hours = hours % 24
    return f"{days}d {remaining_hours}h" if remaining_hours else f"{days}d"


@login_required
def trip_detail(request, trip_id):
    trip = get_object_or_404(Trip, id=trip_id, device__user=request.user)
    locations = list(trip.locations)
    locs = [{
        "lat": l.latitude, "lng": l.longitude,
        "timestamp": l.timestamp.isoformat(),
        "city": l.city, "country": l.country,
    } for l in locations]

    places = []
    for place in trip.places.all():
        time_spent_s = _calculate_time_spent(locations, place.latitude, place.longitude, place.radius)
        places.append({
            "id": place.id,
            "name": place.name,
            "latitude": place.latitude,
            "longitude": place.longitude,
            "radius": place.radius,
            "notes": place.notes,
            "time_spent": time_spent_s,
            "time_spent_display": _format_duration(time_spent_s),
        })

    return JsonResponse({
        "id": trip.id,
        "name": trip.name,
        "description": trip.description,
        "start_time": trip.start_time.isoformat(),
        "end_time": trip.end_time.isoformat(),
        "locations": locs,
        "places": places,
    })


@login_required
@csrf_exempt
@require_http_methods(["POST", "DELETE"])
def delete_trip(request, trip_id):
    trip = get_object_or_404(Trip, id=trip_id, device__user=request.user)
    trip.delete()
    return JsonResponse({"status": "ok"})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def update_trip(request, trip_id):
    trip = get_object_or_404(Trip, id=trip_id, device__user=request.user)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    if 'description' in data:
        trip.description = data['description']
    if 'name' in data:
        trip.name = data['name']
    trip.save()
    return JsonResponse({"status": "ok"})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def create_trip_place(request, trip_id):
    trip = get_object_or_404(Trip, id=trip_id, device__user=request.user)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)

    lat = data.get('latitude')
    lng = data.get('longitude')
    if lat is None or lng is None:
        return JsonResponse({"error": "latitude and longitude required"}, status=400)

    place = TripPlace.objects.create(
        trip=trip,
        name=data.get('name', 'Untitled Place'),
        latitude=float(lat),
        longitude=float(lng),
        radius=float(data.get('radius', 100)),
        notes=data.get('notes', ''),
    )

    locations = list(trip.locations)
    time_spent_s = _calculate_time_spent(locations, place.latitude, place.longitude, place.radius)

    return JsonResponse({
        "status": "ok",
        "place": {
            "id": place.id,
            "name": place.name,
            "latitude": place.latitude,
            "longitude": place.longitude,
            "radius": place.radius,
            "notes": place.notes,
            "time_spent": time_spent_s,
            "time_spent_display": _format_duration(time_spent_s),
        }
    })


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def update_trip_place(request, trip_id, place_id):
    trip = get_object_or_404(Trip, id=trip_id, device__user=request.user)
    place = get_object_or_404(TripPlace, id=place_id, trip=trip)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)

    if 'name' in data:
        place.name = data['name']
    if 'notes' in data:
        place.notes = data['notes']
    if 'radius' in data:
        place.radius = float(data['radius'])
    place.save()

    locations = list(trip.locations)
    time_spent_s = _calculate_time_spent(locations, place.latitude, place.longitude, place.radius)

    return JsonResponse({
        "status": "ok",
        "place": {
            "id": place.id,
            "name": place.name,
            "latitude": place.latitude,
            "longitude": place.longitude,
            "radius": place.radius,
            "notes": place.notes,
            "time_spent": time_spent_s,
            "time_spent_display": _format_duration(time_spent_s),
        }
    })


@login_required
@csrf_exempt
@require_http_methods(["POST", "DELETE"])
def delete_trip_place(request, trip_id, place_id):
    trip = get_object_or_404(Trip, id=trip_id, device__user=request.user)
    place = get_object_or_404(TripPlace, id=place_id, trip=trip)
    place.delete()
    return JsonResponse({"status": "ok"})


# ---------------------------------------------------------------------------
# Geocoding API
# ---------------------------------------------------------------------------

@login_required
@csrf_exempt
@require_http_methods(["POST"])
def geocode_api(request):
    """Start batch geocoding of all un-geocoded locations."""
    total = Location.objects.filter(device__user=request.user, city='').count()

    if not total:
        return JsonResponse({"status": "nothing_to_geocode", "total": 0})

    job = start_geocoding(request.user.id, total)
    return JsonResponse({
        "status": "started",
        "total": job.total,
        "processed": job.processed,
        "errors": job.errors,
    })


@login_required
def geocode_status(request):
    return JsonResponse(get_geocoding_status(request.user.id))


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def geocode_stop(request):
    """Stop a running geocoding task."""
    if stop_geocoding(request.user.id):
        return JsonResponse({"status": "stopped"})
    return JsonResponse({"status": "no_active_task"})


# ---------------------------------------------------------------------------
# Export / Import
# ---------------------------------------------------------------------------

@login_required
def export_csv(request):
    """Export locations as CSV."""
    locations = Location.objects.filter(device__user=request.user).select_related('device').order_by('timestamp')

    response = HttpResponse(content_type='text/csv')
    response['Content-Disposition'] = 'attachment; filename="roamly_export.csv"'

    writer = csv.writer(response)
    writer.writerow([
        'device', 'latitude', 'longitude', 'altitude', 'accuracy',
        'speed', 'battery', 'timestamp', 'city', 'state', 'country', 'country_code',
    ])
    for loc in locations:
        writer.writerow([
            loc.device.device_id, loc.latitude, loc.longitude,
            loc.altitude or '', loc.accuracy or '', loc.speed or '',
            loc.battery or '', loc.timestamp.isoformat(),
            loc.city, loc.state, loc.country, loc.country_code,
        ])
    return response


@login_required
def export_gpx(request):
    """Export locations as GPX."""
    locations = Location.objects.filter(device__user=request.user).order_by('timestamp')

    gpx_lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<gpx version="1.1" creator="Roamly">',
        '  <trk>',
        '    <name>Roamly Export</name>',
        '    <trkseg>',
    ]
    for loc in locations:
        ele = f'      <ele>{loc.altitude}</ele>' if loc.altitude else ''
        gpx_lines.append(f'      <trkpt lat="{loc.latitude}" lon="{loc.longitude}">')
        if ele:
            gpx_lines.append(ele)
        gpx_lines.append(f'        <time>{loc.timestamp.isoformat()}</time>')
        gpx_lines.append('      </trkpt>')
    gpx_lines += ['    </trkseg>', '  </trk>', '</gpx>']

    response = HttpResponse('\n'.join(gpx_lines), content_type='application/gpx+xml')
    response['Content-Disposition'] = 'attachment; filename="roamly_export.gpx"'
    return response


def _safe_float(value):
    """Convert a value to float, returning None for empty/invalid values."""
    if value is None:
        return None
    value = str(value).strip()
    if not value:
        return None
    try:
        return float(value)
    except (ValueError, TypeError):
        return None


def _get_csv_field(row, *candidates):
    """Get a field from a CSV row, trying multiple possible column names."""
    for name in candidates:
        val = row.get(name)
        if val is not None and str(val).strip():
            return str(val).strip()
        # Try case-insensitive match
        for key in row:
            if key and key.strip().lower() == name.lower():
                val = row[key]
                if val is not None and str(val).strip():
                    return str(val).strip()
    return None


def _parse_timestamp(value):
    """Parse a timestamp string in various formats."""
    if not value:
        return None
    value = str(value).strip()
    if not value:
        return None

    # Try ISO format first
    for fmt_value in [value, value.replace('Z', '+00:00')]:
        try:
            ts = datetime.fromisoformat(fmt_value)
            if timezone.is_naive(ts):
                ts = timezone.make_aware(ts)
            return ts
        except (ValueError, TypeError):
            pass

    # Try common formats
    for fmt in [
        '%Y-%m-%d %H:%M:%S',
        '%Y-%m-%dT%H:%M:%S',
        '%Y-%m-%d %H:%M:%S%z',
        '%Y-%m-%dT%H:%M:%S%z',
        '%Y/%m/%d %H:%M:%S',
        '%m/%d/%Y %H:%M:%S',
        '%d/%m/%Y %H:%M:%S',
        '%Y-%m-%d',
    ]:
        try:
            ts = datetime.strptime(value, fmt)
            if timezone.is_naive(ts):
                ts = timezone.make_aware(ts)
            return ts
        except (ValueError, TypeError):
            pass

    # Try unix timestamp
    try:
        ts_float = float(value)
        if ts_float > 1e12:  # milliseconds
            ts_float /= 1000
        return datetime.fromtimestamp(ts_float, tz=dt_timezone.utc)
    except (ValueError, TypeError, OverflowError, OSError):
        pass

    return None


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def import_csv(request):
    """Import locations from CSV."""
    f = request.FILES.get('file')
    if not f:
        return JsonResponse({"error": "No file uploaded"}, status=400)

    try:
        decoded = f.read().decode('utf-8-sig')  # utf-8-sig handles BOM
    except UnicodeDecodeError:
        f.seek(0)
        decoded = f.read().decode('latin-1')

    reader = csv.DictReader(io.StringIO(decoded))
    count = 0
    errors = 0
    first_error = None
    override_device_id = request.POST.get('device_id', '').strip()

    for row in reader:
        try:
            device_id = override_device_id or (
                _get_csv_field(row, 'device', 'device_id', 'deviceId', 'Device') or 'import'
            )
            device, _ = Device.objects.get_or_create(
                user=request.user, device_id=device_id,
                defaults={'name': device_id}
            )

            lat = _safe_float(
                _get_csv_field(row, 'latitude', 'lat', 'Latitude', 'Lat')
            )
            lon = _safe_float(
                _get_csv_field(row, 'longitude', 'lng', 'lon', 'long',
                               'Longitude', 'Lng', 'Lon', 'Long')
            )

            if lat is None or lon is None:
                raise ValueError(
                    f"Missing lat/lon. Columns found: {list(row.keys())}"
                )

            ts_raw = _get_csv_field(
                row, 'timestamp', 'time', 'datetime', 'date', 'created_at',
                'Timestamp', 'Time', 'DateTime', 'Date'
            )
            ts = _parse_timestamp(ts_raw)
            if ts is None:
                ts = timezone.now()

            Location.objects.get_or_create(
                device=device,
                latitude=lat,
                longitude=lon,
                timestamp=ts,
                defaults={
                    'altitude': _safe_float(
                        _get_csv_field(row, 'altitude', 'alt', 'elevation', 'ele')
                    ),
                    'accuracy': _safe_float(
                        _get_csv_field(row, 'accuracy', 'acc', 'hdop')
                    ),
                    'speed': _safe_float(
                        _get_csv_field(row, 'speed', 'vel', 'velocity')
                    ),
                    'battery': _safe_float(
                        _get_csv_field(row, 'battery', 'batt', 'battery_level')
                    ),
                    'city': _get_csv_field(row, 'city', 'City') or '',
                    'state': _get_csv_field(row, 'state', 'State', 'province', 'region') or '',
                    'country': _get_csv_field(row, 'country', 'Country') or '',
                    'country_code': _get_csv_field(row, 'country_code', 'countryCode', 'cc') or '',
                }
            )
            count += 1
        except Exception as e:
            errors += 1
            if first_error is None:
                first_error = str(e)
            logger.warning(f"CSV import error on row {count + errors}: {e}")

    result = {"status": "ok", "imported": count, "errors": errors}
    if first_error and errors > 0:
        result["first_error"] = first_error
    return JsonResponse(result)


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def import_gpx(request):
    """Import locations from GPX file."""
    f = request.FILES.get('file')
    if not f:
        return JsonResponse({"error": "No file uploaded"}, status=400)

    import xml.etree.ElementTree as ET

    try:
        content = f.read().decode('utf-8-sig')
    except UnicodeDecodeError:
        f.seek(0)
        content = f.read().decode('latin-1')

    try:
        root = ET.fromstring(content)
    except ET.ParseError as e:
        return JsonResponse({"error": f"Invalid GPX XML: {e}"}, status=400)

    # Detect namespace from root element (supports GPX 1.0 and 1.1)
    root_tag = root.tag
    gpx_ns = ''
    if root_tag.startswith('{'):
        gpx_ns = root_tag.split('}')[0] + '}'

    device_id = request.POST.get('device_id', 'gpx-import')
    device, _ = Device.objects.get_or_create(
        user=request.user, device_id=device_id,
        defaults={'name': device_id}
    )

    count = 0
    errors = 0
    first_error = None

    def _find_points(tag):
        """Find elements by tag, trying detected namespace then bare tag."""
        if gpx_ns:
            pts = root.findall(f'.//{gpx_ns}{tag}')
            if pts:
                return pts
        return root.findall(f'.//{tag}')

    def _find_child(parent, tag):
        """Find child element, trying detected namespace then bare tag."""
        if gpx_ns:
            el = parent.find(f'{gpx_ns}{tag}')
            if el is not None:
                return el
        return parent.find(tag)

    # Collect track points, route points, and waypoints
    trkpts = list(_find_points('trkpt'))
    trkpts += list(_find_points('rtept'))
    trkpts += list(_find_points('wpt'))

    if not trkpts:
        return JsonResponse({
            "error": "No track points, route points, or waypoints found in GPX file",
            "imported": 0, "errors": 0,
        }, status=400)

    for pt in trkpts:
        try:
            lat = float(pt.get('lat'))
            lon = float(pt.get('lon'))

            time_el = _find_child(pt, 'time')
            if time_el is not None and time_el.text:
                ts = _parse_timestamp(time_el.text)
            else:
                ts = timezone.now()

            ele_el = _find_child(pt, 'ele')
            alt = float(ele_el.text) if ele_el is not None and ele_el.text else None

            Location.objects.get_or_create(
                device=device, latitude=lat, longitude=lon, timestamp=ts,
                defaults={'altitude': alt}
            )
            count += 1
        except Exception as e:
            errors += 1
            if first_error is None:
                first_error = str(e)
            logger.warning(f"GPX import error: {e}")

    result = {"status": "ok", "imported": count, "errors": errors}
    if first_error and errors > 0:
        result["first_error"] = first_error
    return JsonResponse(result)


# ---------------------------------------------------------------------------
# API Keys Management
# ---------------------------------------------------------------------------

@login_required
@csrf_exempt
@require_http_methods(["POST"])
def create_api_key(request):
    name = request.POST.get('name', 'My Device')
    api_key = APIKey(user=request.user, name=name)
    api_key.save()
    return JsonResponse({"status": "ok", "key": api_key.key, "name": api_key.name, "id": api_key.id})


@login_required
@csrf_exempt
@require_http_methods(["POST", "DELETE"])
def delete_api_key(request, key_id):
    api_key = get_object_or_404(APIKey, id=key_id, user=request.user)
    api_key.delete()
    return JsonResponse({"status": "ok"})


# ---------------------------------------------------------------------------
# Devices Management
# ---------------------------------------------------------------------------

@login_required
def devices_api(request):
    """Get list of user's devices."""
    devices = Device.objects.filter(user=request.user).order_by('-created_at')
    return JsonResponse({
        "devices": [
            {
                "id": d.id,
                "device_id": d.device_id,
                "name": d.name or d.device_id,
            }
            for d in devices
        ]
    })


# ---------------------------------------------------------------------------
# Account / Danger Zone
# ---------------------------------------------------------------------------

@login_required
@require_http_methods(["POST"])
def delete_location_data(request):
    """Delete location data for the authenticated user within a time range."""
    try:
        body = json.loads(request.body)
    except (json.JSONDecodeError, ValueError):
        return JsonResponse({"error": "Invalid JSON"}, status=400)

    range_val = body.get("range", "all")
    locations = Location.objects.filter(device__user=request.user)

    if range_val != "all":
        try:
            days = int(range_val)
        except (ValueError, TypeError):
            return JsonResponse({"error": "Invalid range"}, status=400)
        cutoff = timezone.now() - timedelta(days=days)
        locations = locations.filter(timestamp__gte=cutoff)

    count, _ = locations.delete()
    return JsonResponse({"status": "ok", "deleted": count})


@login_required
@csrf_exempt
@require_http_methods(["DELETE"])
def delete_location(request, location_id):
    """Delete a single location point."""
    deleted, _ = Location.objects.filter(
        id=location_id, device__user=request.user
    ).delete()
    if not deleted:
        return JsonResponse({"error": "Not found"}, status=404)
    return JsonResponse({"status": "ok"})


@login_required
@require_http_methods(["POST"])
def delete_account(request):
    """Delete the user's account and all associated data."""
    user = request.user
    logout(request)
    user.delete()
    return JsonResponse({"status": "ok"})
