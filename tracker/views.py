import csv
import io
import json
import logging
import math
import os
import tempfile
import threading
import time
import uuid
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import datetime, date, time as dt_time, timedelta, timezone as dt_timezone

from django.contrib import messages
from django.contrib.auth import authenticate, login, logout
from django.contrib.auth.decorators import login_required
from django.db.models import Count, Min, Max, Avg, Q, Case, When, Value, IntegerField, FloatField
from django.db.models.functions import Coalesce
from django.http import FileResponse, JsonResponse, HttpResponse, StreamingHttpResponse
from django.shortcuts import render, redirect, get_object_or_404
from django.utils import timezone
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods, require_POST

from django.core.cache import cache
from django.core.serializers.json import DjangoJSONEncoder
from django.db import connection, transaction
from django.contrib.staticfiles import finders

from .forms import SignUpForm, APIKeyForm, AdventureForm
from .models import (
    Device, Location, APIKey, Adventure, AdventurePlace, POI, BackupConfig,
    UserProfile, Pal, PalMember, PalBlurb, PalBlurbPhoto, PalMilestone, PalComment,
    AdventureMember, AdventureBlurb, AdventureBlurbPhoto, AdventureMilestone, AdventureComment,
)
from .image_utils import resize_image, resize_photo
from .geocoding_tasks import start_geocoding, get_status as get_geocoding_status, stop_geocoding
from .poi_tasks import start_poi_download, get_poi_status, stop_poi_download
from .backup_tasks import (
    test_s3_connection, run_backup_now, get_backup_status, stop_backup_now,
    run_image_backup_now, get_image_backup_status, _build_pals_data,
)

logger = logging.getLogger(__name__)


def _bust_user_cache(user_id):
    """Increment the per-user cache generation so all cached API responses are invalidated."""
    key = f"cache_gen:{user_id}"
    val = (cache.get(key) or 0) + 1
    cache.set(key, val, timeout=86400 * 30)


def _jf(value):
    """Return None if value is NaN/Infinity — JSON can't represent those floats."""
    if isinstance(value, float) and (math.isnan(value) or math.isinf(value)):
        return None
    return value


# Check for PostGIS
try:
    from django.contrib.gis.geos import Polygon, Point
    HAS_POSTGIS = True
except Exception:
    HAS_POSTGIS = False
    Polygon = None
    Point = None


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
            'User-Agent': 'Roamly/0.8 (self-hosted location tracker)',
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


def robots_txt(request):
    site_url = request.build_absolute_uri('/').rstrip('/')
    lines = [
        'User-agent: *',
        'Disallow: /admin/',
        'Disallow: /api/',
        'Disallow: /map/',
        'Disallow: /data/',
        'Disallow: /stats/',
        'Disallow: /visits/',
        'Disallow: /search/',
        'Disallow: /trips/',
        'Disallow: /pals/',
        'Disallow: /settings/',
        'Disallow: /login/',
        'Disallow: /signup/',
        '',
        f'Sitemap: {site_url}/sitemap.xml',
    ]
    return HttpResponse('\n'.join(lines), content_type='text/plain')


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
def adventures_view(request):
    devices = Device.objects.filter(user=request.user)
    adventures = Adventure.objects.filter(device__user=request.user)
    return render(request, 'tracker/adventures.html', {'devices': devices, 'adventures': adventures})


@login_required
def settings_view(request):
    api_keys = APIKey.objects.filter(user=request.user)
    devices = Device.objects.filter(user=request.user)
    form = APIKeyForm()
    backup_config = BackupConfig.objects.filter(user=request.user).first()
    UserProfile.objects.get_or_create(user=request.user)
    return render(request, 'tracker/settings.html', {
        'api_keys': api_keys,
        'devices': devices,
        'form': form,
        'has_postgis': HAS_POSTGIS,
        'backup_config': backup_config,
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

    loc_defaults = {
        'altitude': _safe_float(altitude),
        'accuracy': _safe_float(accuracy),
        'speed': _safe_float(speed),
        'battery': _safe_float(battery),
    }

    from django.db import IntegrityError
    try:
        location = Location.objects.create(
            device=device,
            latitude=latitude,
            longitude=longitude,
            timestamp=timestamp,
            **loc_defaults,
        )
        created = True
    except IntegrityError:
        # Duplicate location (same device+lat+lng+timestamp) — return existing
        try:
            location = Location.objects.get(
                device=device, latitude=latitude,
                longitude=longitude, timestamp=timestamp,
            )
        except Location.DoesNotExist:
            location = None
        created = False

    if created and location:
        # Attempt inline geocode (up to 10s timeout)
        try:
            result = reverse_geocode(latitude, longitude)
            if result:
                location.city = result['city']
                location.state = result['state']
                location.country = result['country']
                location.country_code = result['country_code']
                location.place_name = result['place_name']
                location.save(update_fields=['city', 'state', 'country', 'country_code', 'place_name'])
        except Exception:
            pass

    _bust_user_cache(user.id)
    loc_id = location.id if location else None
    return JsonResponse({"status": "ok", "location_id": loc_id, "device": str(device_id)})


@login_required
def locations_bounds_api(request):
    """Return the bounding box of all user locations matching current filters. Fast — no point data returned."""
    device_id = request.GET.get('device_id')
    all_time = request.GET.get('all')
    start_date = request.GET.get('start_date')
    end_date = request.GET.get('end_date')
    hours = request.GET.get('hours', '24')

    qs = Location.objects.filter(device__user=request.user)

    if start_date and end_date:
        try:
            start = datetime.fromisoformat(start_date).replace(hour=0, minute=0, second=0)
            end_dt = datetime.fromisoformat(end_date).replace(hour=23, minute=59, second=59)
            if timezone.is_naive(start):
                start = timezone.make_aware(start)
            if timezone.is_naive(end_dt):
                end_dt = timezone.make_aware(end_dt)
            qs = qs.filter(timestamp__gte=start, timestamp__lte=end_dt)
        except (ValueError, TypeError):
            pass
    elif not all_time:
        qs = qs.filter(timestamp__gte=timezone.now() - timedelta(hours=int(hours)))

    if device_id:
        qs = qs.filter(device__device_id=device_id)

    agg = qs.aggregate(
        min_lat=Min('latitude'), max_lat=Max('latitude'),
        min_lng=Min('longitude'), max_lng=Max('longitude'),
    )

    if agg['min_lat'] is None:
        return JsonResponse({'bounds': None})

    return JsonResponse({'bounds': [
        [agg['min_lng'], agg['min_lat']],
        [agg['max_lng'], agg['max_lat']],
    ]})


@login_required
def track_api(request):
    """Return GPS track as decimated path per device — single request, no tiles.

    Returns at most MAX_POINTS_PER_DEVICE coordinates per device, evenly sampled,
    so the response is fast regardless of time range. Cached in Redis.
    """
    MAX_POINTS = 4000
    STATIONARY_MIN_INTERVAL_S = 600
    MOVEMENT_DISTANCE_M = 60
    MOVEMENT_SPEED_MPS = 1.1
    STATIONARY_GAP_FORCE_KEEP_S = 1800

    device_id = request.GET.get('device_id')
    all_time = request.GET.get('all')
    hours_param = request.GET.get('hours', '24')
    start_date = request.GET.get('start_date')
    end_date = request.GET.get('end_date')

    cache_key = f"track:{request.user.id}:{request.GET.urlencode()}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

    qs = Location.objects.filter(
        device__user=request.user,
        latitude__isnull=False,
        longitude__isnull=False,
    ).select_related('device').order_by('device_id', 'timestamp')

    cache_ttl = 60

    if start_date and end_date:
        try:
            start = datetime.fromisoformat(start_date).replace(hour=0, minute=0, second=0)
            end_dt = datetime.fromisoformat(end_date).replace(hour=23, minute=59, second=59)
            if timezone.is_naive(start):
                start = timezone.make_aware(start)
            if timezone.is_naive(end_dt):
                end_dt = timezone.make_aware(end_dt)
            qs = qs.filter(timestamp__gte=start, timestamp__lte=end_dt)
            if end_dt < timezone.now() - timedelta(hours=1):
                cache_ttl = 3600  # historical range, cache 1 hour
        except (ValueError, TypeError):
            pass
    elif not all_time:
        h = int(hours_param)
        qs = qs.filter(timestamp__gte=timezone.now() - timedelta(hours=h))
        if h >= 720:
            cache_ttl = 600
        elif h >= 168:
            cache_ttl = 300
        elif h >= 24:
            cache_ttl = 120

    if device_id:
        qs = qs.filter(device__device_id=device_id)

    # Group by device, then decimate each device's track independently
    devices_map = {}
    for loc in qs.values('latitude', 'longitude', 'timestamp', 'speed', 'city', 'state', 'country',
                          'device__device_id', 'device__name'):
        did = loc['device__device_id']
        if did not in devices_map:
            devices_map[did] = {
                'id': did,
                'name': loc['device__name'] or did,
                'points': [],
            }
        devices_map[did]['points'].append({
            'c': [_jf(loc['longitude']), _jf(loc['latitude'])],
            'ts': int(loc['timestamp'].timestamp()),
            'speed': _jf(loc['speed']),
            'city': loc['city'],
            'state': loc['state'],
            'country': loc['country'],
        })

    result_devices = []
    for dev in devices_map.values():
        pts = dev['points']
        total = len(pts)

        if total <= 2:
            sampled = pts
        else:
            sampled = [pts[0]]
            last_kept_stationary_ts = pts[0]['ts']

            for i in range(1, total):
                prev = pts[i - 1]
                cur = pts[i]

                dt = max(0, cur['ts'] - prev['ts'])
                dist_m = _haversine_km(prev['c'][1], prev['c'][0], cur['c'][1], cur['c'][0]) * 1000
                speed_mps = cur.get('speed')
                if speed_mps is None and dt > 0:
                    speed_mps = dist_m / dt

                is_moving = (
                    dist_m >= MOVEMENT_DISTANCE_M
                    or (speed_mps is not None and speed_mps >= MOVEMENT_SPEED_MPS)
                )

                if is_moving:
                    sampled.append(cur)
                    continue

                if (
                    cur['ts'] - last_kept_stationary_ts >= STATIONARY_MIN_INTERVAL_S
                    or dt >= STATIONARY_GAP_FORCE_KEEP_S
                ):
                    sampled.append(cur)
                    last_kept_stationary_ts = cur['ts']

            # Always include the final point so the line ends at the latest fix
            if sampled[-1] is not pts[-1]:
                sampled.append(pts[-1])

        if len(sampled) > MAX_POINTS:
            stride = max(1, len(sampled) // MAX_POINTS)
            sampled = sampled[::stride]
            if sampled[-1] is not pts[-1]:
                sampled.append(pts[-1])

        sampled = [
            {
                'c': p['c'],
                'ts': p['ts'],
                'speed': p['speed'],
                'city': p['city'],
                'state': p['state'],
                'country': p['country'],
            }
            for p in sampled
        ]

        result_devices.append({
            'id': dev['id'],
            'name': dev['name'],
            'total': total,
            'hidden': max(0, total - len(sampled)),
            'points': sampled,
        })

    payload = {'devices': result_devices}
    cache.set(cache_key, payload, timeout=cache_ttl)
    return JsonResponse(payload)


@login_required
def locations_api(request):
    """Get locations with spatial filtering."""
    try:
        return _locations_api_inner(request)
    except Exception as exc:
        import traceback
        logger.error("locations_api error: %s\n%s", exc, traceback.format_exc())
        return JsonResponse({"error": str(exc), "type": type(exc).__name__}, status=500)


def _locations_api_inner(request):
    device_id = request.GET.get("device_id")
    all_time = request.GET.get("all")
    limit = min(int(request.GET.get("limit", 5000)), 50000)
    offset = int(request.GET.get("offset", 0))
    sort_key = request.GET.get('sort_key', 'timestamp')
    sort_dir = request.GET.get('sort_dir', 'desc').lower()
    before_value = request.GET.get('before_value')
    before_id = request.GET.get('before_id')
    start_date = request.GET.get("start_date")
    end_date = request.GET.get("end_date")
    min_lng = request.GET.get("min_lng")
    min_lat = request.GET.get("min_lat")
    max_lng = request.GET.get("max_lng")
    max_lat = request.GET.get("max_lat")

    if sort_dir not in ("asc", "desc"):
        sort_dir = "desc"

    sort_config = {
        'timestamp': {'field': 'timestamp', 'kind': 'datetime'},
        'speed': {'field': 'sort_value', 'kind': 'number', 'source': 'speed'},
        'battery': {'field': 'sort_value', 'kind': 'number', 'source': 'battery'},
        'lat': {'field': 'sort_value', 'kind': 'number', 'source': 'latitude'},
        'lng': {'field': 'sort_value', 'kind': 'number', 'source': 'longitude'},
        'city': {'field': 'sort_value', 'kind': 'text', 'source': 'city'},
        'state': {'field': 'sort_value', 'kind': 'text', 'source': 'state'},
        'country_code': {'field': 'sort_value', 'kind': 'text', 'source': 'country_code'},
        'country': {'field': 'sort_value', 'kind': 'text', 'source': 'country'},
        'device': {'field': 'sort_value', 'kind': 'text', 'source': 'device_sort'},
    }
    if sort_key not in sort_config:
        sort_key = 'timestamp'
    config = sort_config[sort_key]
    sort_field = config['field']
    sort_source = config.get('source', sort_field)

    def _cursor_value_for_loc(loc):
        if sort_key == 'timestamp':
            return loc.timestamp.isoformat()
        value = getattr(loc, sort_source, None)
        if sort_key == 'device':
            value = loc.device.name or loc.device.device_id
        if config['kind'] == 'number':
            if value is None:
                return -1e308
            return _jf(value)
        return value or ''

    def _parse_cursor_value(raw):
        if raw is None:
            return None
        try:
            if config['kind'] == 'number':
                return float(raw)
            if sort_key == 'timestamp':
                dt = datetime.fromisoformat(raw)
                if timezone.is_naive(dt):
                    dt = timezone.make_aware(dt)
                return dt
            return str(raw)
        except (TypeError, ValueError):
            return None

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

    # Additional filter parameters
    q = request.GET.get('q')
    min_speed = request.GET.get('min_speed')
    max_speed = request.GET.get('max_speed')
    min_battery = request.GET.get('min_battery')
    max_battery = request.GET.get('max_battery')
    city = request.GET.get('city')
    state = request.GET.get('state')
    country_code = request.GET.get('country_code')

    if q:
        q = q.strip()
        # search across city/state/country
        locations = locations.filter(Q(city__icontains=q) | Q(state__icontains=q) | Q(country__icontains=q))

    try:
        if min_speed is not None and min_speed != '':
            locations = locations.filter(speed__gte=float(min_speed))
    except (ValueError, TypeError):
        pass
    try:
        if max_speed is not None and max_speed != '':
            locations = locations.filter(speed__lte=float(max_speed))
    except (ValueError, TypeError):
        pass
    try:
        if min_battery is not None and min_battery != '':
            locations = locations.filter(battery__gte=int(min_battery))
    except (ValueError, TypeError):
        pass
    try:
        if max_battery is not None and max_battery != '':
            locations = locations.filter(battery__lte=int(max_battery))
    except (ValueError, TypeError):
        pass

    if city:
        locations = locations.filter(city__iexact=city)
    if state:
        locations = locations.filter(state__iexact=state)
    if country_code:
        locations = locations.filter(country_code__iexact=country_code)

    if sort_key == 'device':
        locations = locations.annotate(sort_value=Coalesce('device__name', 'device__device_id', Value('')))
    elif config['kind'] == 'text':
        locations = locations.annotate(sort_value=Coalesce(sort_source, Value('')))
    elif config['kind'] == 'number':
        locations = locations.annotate(sort_value=Coalesce(sort_source, Value(-1e308), output_field=FloatField()))

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

    try:
        before_id_int = int(before_id) if before_id else None
    except (ValueError, TypeError):
        before_id_int = None

    sort_lookup = 'timestamp' if sort_key == 'timestamp' else 'sort_value'
    if sort_dir == 'asc':
        order_prefix = ''
        cursor_cmp = 'gt'
        tie_cmp = 'gt'
    else:
        order_prefix = '-'
        cursor_cmp = 'lt'
        tie_cmp = 'lt'

    cursor_value = _parse_cursor_value(before_value)
    if cursor_value is not None:
        cursor_filter = Q(**{f'{sort_lookup}__{cursor_cmp}': cursor_value})
        if before_id_int is not None:
            cursor_filter |= Q(**{sort_lookup: cursor_value}) & Q(**{f'id__{tie_cmp}': before_id_int})
        locations = locations.filter(cursor_filter)

    order_fields = [f'{order_prefix}{sort_lookup}', f'{order_prefix}id']

    locations = locations.select_related('device').order_by(*order_fields)
    page = list(locations[: limit + 1])
    has_more = len(page) > limit
    locations = page[:limit]

    devices_data = {}
    locations_data = []
    last_cursor_ts = None
    last_cursor_id = None
    last_cursor_value = None
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
            "lat": _jf(loc.latitude),
            "lng": _jf(loc.longitude),
            "timestamp": loc.timestamp.isoformat(),
            "altitude": _jf(loc.altitude),
            "accuracy": _jf(loc.accuracy),
            "speed": _jf(loc.speed),
            "battery": _jf(loc.battery),
            "city": loc.city,
            "state": loc.state,
            "country": loc.country,
            "country_code": loc.country_code,
        })
        locations_data.append({
            "id": loc.id,
            "device": loc.device.name or did,
            "lat": _jf(loc.latitude),
            "lng": _jf(loc.longitude),
            "timestamp": loc.timestamp.isoformat(),
            "altitude": _jf(loc.altitude),
            "accuracy": _jf(loc.accuracy),
            "speed": _jf(loc.speed),
            "battery": _jf(loc.battery),
            "city": loc.city,
            "state": loc.state,
            "country": loc.country,
            "country_code": loc.country_code,
        })
        # track last cursor (the last item in this page — remember results are desc)
        last_cursor_ts = loc.timestamp
        last_cursor_id = loc.id
        last_cursor_value = _cursor_value_for_loc(loc)

    resp = {"devices": list(devices_data.values()), "locations": locations_data, "sort_key": sort_key, "sort_dir": sort_dir, "has_more": has_more}
    if last_cursor_ts is not None and has_more:
        resp['next_before_value'] = last_cursor_value
        resp['next_before_id'] = last_cursor_id
    return JsonResponse(resp)


def _tile_coords(lat, lng, z):
    """Convert lat/lng to tile x/y at zoom z."""
    import math
    n = 2 ** z
    x = int((lng + 180) / 360 * n)
    lat_r = math.radians(lat)
    y = int((1 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2 * n)
    return x, max(0, min(n - 1, y))


@login_required
@require_POST
def seed_tiles(request):
    """Pre-generate and cache all tiles covering the user's data in the background."""
    if not HAS_POSTGIS:
        return JsonResponse({'error': 'PostGIS required'}, status=400)

    # Skip seeding for large time ranges — too many tiles, would hammer the DB
    # and starve real tile requests. Let them warm up on-demand instead.
    hours_param = request.GET.get('hours')
    if hours_param and int(hours_param) >= 168:
        return JsonResponse({'status': 'skipped'})

    qs_params = request.GET.urlencode()
    user_id = request.user.id

    # Get bounding box of user data
    from django.db.models import Min, Max
    agg = Location.objects.filter(device__user=request.user).aggregate(
        min_lat=Min('latitude'), max_lat=Max('latitude'),
        min_lng=Min('longitude'), max_lng=Max('longitude'),
    )
    if agg['min_lat'] is None:
        return JsonResponse({'tiles': 0})

    min_lat, max_lat = agg['min_lat'], agg['max_lat']
    min_lng, max_lng = agg['min_lng'], agg['max_lng']

    def do_seed():
        # Seed zoom levels 2-8 only — z9/z10 tile counts grow too large even for
        # moderate bounding boxes and compete with real requests on limited workers
        tiles_to_seed = []
        for z in range(2, 9):
            x0, y1 = _tile_coords(max_lat, min_lng, z)  # top-left
            x1, y0 = _tile_coords(min_lat, max_lng, z)  # bottom-right
            for tx in range(max(0, x0 - 1), x1 + 2):
                for ty in range(max(0, y0 - 1), y1 + 2):
                    tiles_to_seed.append((z, tx, ty))

        # Hard cap to avoid runaway seeding
        tiles_to_seed = tiles_to_seed[:500]

        from django.test import RequestFactory
        from django.contrib.auth.models import User
        factory = RequestFactory()
        user = User.objects.get(id=user_id)

        for z, tx, ty in tiles_to_seed:
            cache_key = f"tile:{user_id}:{z}:{tx}:{ty}:{qs_params}"
            if cache.get(cache_key) is not None:
                continue  # already cached
            # Build a fake request and call the view directly
            fake_req = factory.get(f'/api/tiles/{z}/{tx}/{ty}.pbf?' + qs_params)
            fake_req.user = user
            try:
                vector_tile(fake_req, z, tx, ty)
            except Exception:
                pass

    threading.Thread(target=do_seed, daemon=True).start()
    return JsonResponse({'status': 'seeding'})


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
    # Historical tiles (not recent) can be cached much longer
    cache_ttl = 30

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
            # Historical date ranges don't change — cache for 1 hour
            if end_dt < timezone.now() - timedelta(hours=1):
                cache_ttl = 3600
        except (ValueError, TypeError):
            pass
    elif all_time:
        # All-time tiles are expensive but stable — cache for 5 minutes
        cache_ttl = 300
    else:
        h = int(hours)
        since = timezone.now() - timedelta(hours=h)
        extra_where.append("AND l.timestamp >= %(since)s")
        params["since"] = since
        # Scale cache TTL with time range — large ranges change slowly
        if h >= 720:
            cache_ttl = 600   # 10 min for 30-day view
        elif h >= 168:
            cache_ttl = 300   # 5 min for 7-day view
        elif h >= 24:
            cache_ttl = 120   # 2 min for multi-day views

    if device_id:
        extra_where.append("AND d.device_id = %(device_id)s")
        params["device_id"] = device_id

    filter_clause = "\n              ".join(extra_where)

    # Check cache
    cache_key = f"tile:{request.user.id}:{z}:{x}:{y}:{request.GET.urlencode()}"
    cached = cache.get(cache_key)
    if cached is not None:
        response = HttpResponse(cached, content_type="application/x-protobuf")
        response["Cache-Control"] = f"public, max-age={cache_ttl}"
        return response

    # Spatial thinning via ST_SnapToGrid — keeps one point per grid cell so
    # coverage is always uniform with no gaps. Grid shrinks as zoom increases.
    if z <= 4:
        grid_deg = 0.5
    elif z <= 6:
        grid_deg = 0.1
    elif z <= 8:
        grid_deg = 0.02
    else:
        grid_deg = 0.0

    params["grid_deg"] = grid_deg

    if grid_deg > 0:
        thinning = f"""
        DISTINCT ON (ST_SnapToGrid(l.location::geometry, %(grid_deg)s))"""
        order_clause = f"ORDER BY ST_SnapToGrid(l.location::geometry, %(grid_deg)s), l.id"
    else:
        thinning = ""
        order_clause = ""

    sql = f"""
    WITH bounds AS (
        SELECT ST_TileEnvelope(%(z)s, %(x)s, %(y)s) AS geom,
               ST_Transform(ST_TileEnvelope(%(z)s, %(x)s, %(y)s), 4326) AS geom_4326
    ),
    thinned AS (
        SELECT {thinning}
            l.location, l.id, l.speed, l.battery, l.city, l.state, l.country,
            l.timestamp, d.device_id, COALESCE(d.name, d.device_id) AS device_name
        FROM tracker_location l
        JOIN tracker_device d ON l.device_id = d.id
        CROSS JOIN bounds
        WHERE d.user_id = %(user_id)s
          AND l.location IS NOT NULL
          AND l.location::geometry && bounds.geom_4326
          AND ST_Intersects(l.location::geometry, bounds.geom_4326)
              {filter_clause}
        {order_clause}
        LIMIT 50000
    ),
    pts AS (
        SELECT
            ST_AsMVTGeom(ST_Transform(t.location::geometry, 3857), bounds.geom, 4096, 256, true) AS geom,
            t.id, t.speed, t.battery, t.city, t.state, t.country,
            EXTRACT(EPOCH FROM t.timestamp)::bigint AS ts,
            t.device_id, t.device_name
        FROM thinned t CROSS JOIN bounds
    )
    SELECT ST_AsMVT(pts.*, 'locations') FROM pts;
    """

    with connection.cursor() as cursor:
        cursor.execute(sql, params)
        points_tile = cursor.fetchone()[0]

    tile_data = bytes(points_tile)

    if not tile_data:
        return HttpResponse(status=204)

    cache.set(cache_key, tile_data, timeout=cache_ttl)

    response = HttpResponse(tile_data, content_type="application/x-protobuf")
    response["Cache-Control"] = f"public, max-age={cache_ttl}"
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
            "geometry": {"type": "Point", "coordinates": [_jf(loc.longitude), _jf(loc.latitude)]},
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
    gen = cache.get(f"cache_gen:{request.user.id}", 0)
    cache_key = f"stats:{request.user.id}:{gen}:{request.GET.urlencode()}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

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

    result = {
        "total_points": total,
        "countries": countries,
        "cities": cities,
        "states": states,
        "devices": devices,
        "first_location": first.isoformat() if first else None,
        "last_location": last.isoformat() if last else None,
    }
    cache.set(cache_key, result, timeout=600)
    return JsonResponse(result)


@login_required
def yearly_overview_api(request):
    """Yearly overview: week/month/year stats with comparisons and top places."""
    gen = cache.get(f"cache_gen:{request.user.id}", 0)
    cache_key = f"yearly:{request.user.id}:{gen}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

    now = timezone.now()
    qs = Location.objects.filter(device__user=request.user)

    def _period_stats(start, end):
        period = qs.filter(timestamp__gte=start, timestamp__lt=end)
        pts = period.count()
        countries = period.exclude(country='').values('country').distinct().count()
        cities = period.exclude(city='').values('city', 'country').distinct().count()
        return {"points": pts, "countries": countries, "cities": cities}

    week_start = (now - timedelta(days=now.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
    last_week_start = week_start - timedelta(days=7)
    month_start = now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    last_month_start = (month_start - timedelta(days=1)).replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    year_start = now.replace(month=1, day=1, hour=0, minute=0, second=0, microsecond=0)
    last_year_start = year_start.replace(year=year_start.year - 1)

    this_week = _period_stats(week_start, now)
    last_week = _period_stats(last_week_start, week_start)
    this_month = _period_stats(month_start, now)
    last_month = _period_stats(last_month_start, month_start)
    this_year = _period_stats(year_start, now)
    last_year = _period_stats(last_year_start, year_start)

    # Monthly breakdown for this year
    monthly = []
    for m in range(1, now.month + 1):
        m_start = year_start.replace(month=m)
        m_end = year_start.replace(month=m + 1) if m < 12 else now.replace(year=now.year + 1, month=1, day=1)
        period = qs.filter(timestamp__gte=m_start, timestamp__lt=m_end)
        monthly.append({"month": m, "points": period.count()})

    # Top cities (all time)
    top_cities = list(
        qs.exclude(city='').values('city', 'country').annotate(count=Count('id')).order_by('-count')[:10]
    )
    top_countries = list(
        qs.exclude(country='').values('country').annotate(count=Count('id')).order_by('-count')[:10]
    )

    result = {
        "this_week": this_week, "last_week": last_week,
        "this_month": this_month, "last_month": last_month,
        "this_year": this_year, "last_year": last_year,
        "monthly_breakdown": monthly,
        "top_cities": top_cities,
        "top_countries": top_countries,
        "year": now.year,
    }
    cache.set(cache_key, result, timeout=1800)
    return JsonResponse(result)


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
    gen = cache.get(f"cache_gen:{request.user.id}", 0)
    cache_key = f"distance:{request.user.id}:{gen}:{request.GET.urlencode()}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

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
    ).iterator(chunk_size=10000)

    granularity = request.GET.get("granularity", "daily")
    bucket_km = defaultdict(float)
    total_km = 0.0
    prev = {}  # per-device previous point

    for dev_id, lat, lon, ts in locations:
        if granularity == "hourly":
            key = ts.strftime('%Y-%m-%d %H')
        else:
            key = ts.strftime('%Y-%m-%d')
        if dev_id in prev:
            p_lat, p_lon, p_ts = prev[dev_id]
            # Skip if gap > 2 hours (likely separate trips, not continuous travel)
            if (ts - p_ts).total_seconds() <= 7200:
                d = _haversine_km(p_lat, p_lon, lat, lon)
                # Skip unreasonable jumps (> 500 km between consecutive points)
                if 0.03 <= d <= 500:
                    bucket_km[key] += d
                    total_km += d
        prev[dev_id] = (lat, lon, ts)

    keys = sorted(bucket_km.keys())
    result = {
        "days": keys,
        "distances": [round(bucket_km[k], 2) for k in keys],
        "total_km": round(total_km, 2),
    }
    cache.set(cache_key, result, timeout=600)
    return JsonResponse(result)


@login_required
def visits_api(request):
    """Aggregated city/state/country visit statistics."""
    gen = cache.get(f"cache_gen:{request.user.id}", 0)
    cache_key = f"visits:{request.user.id}:{gen}:{request.GET.urlencode()}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

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

    result = {"cities": cities, "states": states, "countries": countries}
    cache.set(cache_key, result, timeout=600)
    return JsonResponse(result)


# ---------------------------------------------------------------------------
# Trips API
# ---------------------------------------------------------------------------

def _is_trip_member(trip, user):
    if trip.device.user == user:
        return True
    return AdventureMember.objects.filter(adventure=trip, user=user).exists()


def _get_trip_for_user(trip_id, user):
    trip = get_object_or_404(Adventure, id=trip_id)
    if not _is_trip_member(trip, user):
        from django.http import Http404
        raise Http404
    return trip


@login_required
def trips_api(request):
    owned_ids = Adventure.objects.filter(device__user=request.user).values_list('id', flat=True)
    member_ids = AdventureMember.objects.filter(user=request.user).values_list('adventure_id', flat=True)
    all_ids = set(list(owned_ids) + list(member_ids))
    trips = Adventure.objects.filter(id__in=all_ids).select_related('device').order_by('-start_time')
    data = []
    for trip in trips:
        loc_count = trip.locations.count()
        member_count = trip.members.count()
        is_creator = trip.device.user == request.user or (trip.creator == request.user)
        data.append({
            "id": trip.id,
            "name": trip.name,
            "description": trip.description,
            "device": trip.device.name or trip.device.device_id,
            "start_time": trip.start_time.isoformat(),
            "end_time": trip.end_time.isoformat(),
            "location_count": loc_count,
            "member_count": member_count,
            "is_public": bool(trip.public_slug),
            "is_creator": is_creator,
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
        if not start_raw:
            return JsonResponse({"error": "Start date is required"}, status=400)
        start = datetime.fromisoformat(start_raw.replace('Z', '+00:00'))
        if timezone.is_naive(start):
            start = timezone.make_aware(start)

        end_raw = data.get('end_time', '').strip()
        if end_raw:
            end = datetime.fromisoformat(end_raw.replace('Z', '+00:00'))
            # Date-only: set to end of day
            if len(end_raw) <= 10:
                end = end.replace(hour=23, minute=59, second=59)
            if timezone.is_naive(end):
                end = timezone.make_aware(end)
        else:
            # No end date — open-ended adventure, far future so all points are captured
            end = timezone.make_aware(datetime(2099, 12, 31, 23, 59, 59))
    except (KeyError, ValueError) as e:
        return JsonResponse({"error": f"Invalid dates: {e}"}, status=400)

    trip = Adventure.objects.create(
        device=device,
        creator=request.user,
        name=data.get('name', 'Untitled Adventure'),
        description=data.get('description', ''),
        start_time=start,
        end_time=end,
    )
    AdventureMember.objects.create(adventure=trip, user=request.user, role='creator')

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
    try:
        return _trip_detail_inner(request, trip_id)
    except Exception as exc:
        import traceback
        logger.error("trip_detail error: %s\n%s", exc, traceback.format_exc())
        return JsonResponse({"error": str(exc), "type": type(exc).__name__}, status=500)


def _trip_detail_inner(request, trip_id):
    trip = _get_trip_for_user(trip_id, request.user)
    LOCATION_LIMIT = 30000
    location_qs = trip.locations
    total_count = location_qs.count()
    locations = list(location_qs[:LOCATION_LIMIT])
    locs = [{
        "lat": _jf(l.latitude), "lng": _jf(l.longitude),
        "timestamp": l.timestamp.isoformat(),
        "city": l.city, "country": l.country,
        "speed": _jf(l.speed),
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

    is_creator = trip.device.user == request.user or trip.creator == request.user
    owner_user = trip.device.user
    members = []
    member_locations = {}
    for m in trip.members.select_related('user'):
        members.append({
            "user_id": m.user.id,
            "username": m.user.username,
            "role": m.role,
            "avatar": _get_user_avatar(m.user),
        })
        if m.user != owner_user:
            member_locs = list(
                Location.objects.filter(
                    device__user=m.user,
                    timestamp__gte=trip.start_time,
                    timestamp__lte=trip.end_time,
                ).order_by('timestamp')[:LOCATION_LIMIT]
            )
            if member_locs:
                member_locations[m.user.username] = [{
                    "lat": _jf(l.latitude), "lng": _jf(l.longitude),
                    "timestamp": l.timestamp.isoformat(),
                    "speed": _jf(l.speed),
                } for l in member_locs]

    return JsonResponse({
        "id": trip.id,
        "name": trip.name,
        "description": trip.description,
        "device_name": trip.device.name or trip.device.device_id,
        "start_time": trip.start_time.isoformat(),
        "end_time": trip.end_time.isoformat(),
        "locations": locs,
        "total_location_count": total_count,
        "places": places,
        "is_creator": is_creator,
        "is_public": bool(trip.public_slug),
        "public_slug": trip.public_slug,
        "members": members,
        "member_locations": member_locations,
    })


@login_required
@csrf_exempt
@require_http_methods(["POST", "DELETE"])
def delete_trip(request, trip_id):
    trip = get_object_or_404(Adventure, id=trip_id, device__user=request.user)
    trip.delete()
    return JsonResponse({"status": "ok"})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def update_trip(request, trip_id):
    trip = _get_trip_for_user(trip_id, request.user)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    if 'description' in data:
        trip.description = data['description']
    if 'name' in data:
        trip.name = data['name']
    if 'start_time' in data and data['start_time']:
        try:
            start = datetime.fromisoformat(data['start_time'].replace('Z', '+00:00'))
            trip.start_time = timezone.make_aware(start) if timezone.is_naive(start) else start
        except (ValueError, TypeError):
            pass
    if 'end_time' in data:
        raw = (data['end_time'] or '').strip()
        if raw:
            try:
                end = datetime.fromisoformat(raw.replace('Z', '+00:00'))
                trip.end_time = timezone.make_aware(end) if timezone.is_naive(end) else end
            except (ValueError, TypeError):
                pass
        else:
            trip.end_time = timezone.make_aware(datetime(2099, 12, 31, 23, 59, 59))
    if 'device_id' in data and data['device_id']:
        try:
            device = Device.objects.get(user=request.user, device_id=data['device_id'])
            trip.device = device
        except Device.DoesNotExist:
            pass
    trip.save()
    return JsonResponse({"status": "ok"})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def create_trip_place(request, trip_id):
    trip = _get_trip_for_user(trip_id, request.user)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)

    lat = data.get('latitude')
    lng = data.get('longitude')
    if lat is None or lng is None:
        return JsonResponse({"error": "latitude and longitude required"}, status=400)

    place = AdventurePlace.objects.create(
        adventure=trip,
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
    trip = _get_trip_for_user(trip_id, request.user)
    place = get_object_or_404(AdventurePlace, id=place_id, adventure=trip)
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
    trip = _get_trip_for_user(trip_id, request.user)
    place = get_object_or_404(AdventurePlace, id=place_id, adventure=trip)
    place.delete()
    return JsonResponse({"status": "ok"})


# ---------------------------------------------------------------------------
# Trip Social API (members, timeline, blurbs, milestones, comments)
# ---------------------------------------------------------------------------

@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_add_member(request, trip_id):
    trip = get_object_or_404(Adventure, id=trip_id, device__user=request.user)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    username = data.get('username', '').strip()
    if not username:
        return JsonResponse({"error": "username required"}, status=400)
    from django.contrib.auth.models import User as AuthUser
    try:
        user = AuthUser.objects.get(username=username)
    except AuthUser.DoesNotExist:
        return JsonResponse({"error": "User not found"}, status=404)
    if user == request.user:
        return JsonResponse({"error": "You are already the creator"}, status=400)
    member, created = AdventureMember.objects.get_or_create(adventure=trip, user=user, defaults={'role': 'member'})
    if not created:
        return JsonResponse({"error": "Already a member"}, status=400)
    return JsonResponse({"status": "ok", "user_id": user.id, "username": user.username})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_remove_member(request, trip_id, user_id):
    trip = get_object_or_404(Adventure, id=trip_id, device__user=request.user)
    from django.contrib.auth.models import User as AuthUser
    user = get_object_or_404(AuthUser, id=user_id)
    AdventureMember.objects.filter(adventure=trip, user=user).exclude(role='creator').delete()
    return JsonResponse({"status": "ok"})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_toggle_public(request, trip_id):
    trip = get_object_or_404(Adventure, id=trip_id, device__user=request.user)
    if trip.public_slug:
        trip.public_slug = None
    else:
        trip.public_slug = str(uuid.uuid4()).replace('-', '')[:32]
    trip.save()
    return JsonResponse({"status": "ok", "is_public": bool(trip.public_slug), "public_slug": trip.public_slug})


@login_required
def trip_timeline_api(request, trip_id):
    trip = _get_trip_for_user(trip_id, request.user)
    page = int(request.GET.get('page', 1))
    per_page = 50
    events = []
    for b in trip.blurbs.select_related('author').prefetch_related('photos', 'comments'):
        events.append({
            'type': 'blurb',
            'id': b.id,
            'author': b.author.username,
            'author_id': b.author.id,
            'avatar': _get_user_avatar(b.author),
            'text': b.text,
            'latitude': b.latitude,
            'longitude': b.longitude,
            'location_name': b.location_name,
            'photos': [{'id': p.id, 'url': p.image.url, 'thumb': p.thumbnail.url if p.thumbnail else p.image.url} for p in b.photos.all()],
            'comment_count': b.comments.count(),
            'created_at': b.created_at.isoformat(),
            'sort_key': b.created_at.isoformat(),
            'can_delete': b.author == request.user or trip.device.user == request.user,
        })
    for m in trip.milestones.select_related('author'):
        events.append({
            'type': 'milestone',
            'id': m.id,
            'author': m.author.username,
            'author_id': m.author.id,
            'title': m.title,
            'description': m.description,
            'emoji': m.emoji,
            'date': m.date.isoformat(),
            'created_at': m.created_at.isoformat(),
            'sort_key': m.date.isoformat(),
            'can_delete': m.author == request.user or trip.device.user == request.user,
        })
    events.sort(key=lambda e: e['sort_key'])
    total = len(events)
    start = (page - 1) * per_page
    events = events[start:start + per_page]
    return JsonResponse({'events': events, 'page': page, 'has_more': start + per_page < total})


@login_required
@require_http_methods(["POST"])
def trip_create_blurb(request, trip_id):
    trip = _get_trip_for_user(trip_id, request.user)
    text = request.POST.get('text', '').strip()
    if not text:
        return JsonResponse({"error": "Text is required"}, status=400)
    lat = request.POST.get('latitude')
    lng = request.POST.get('longitude')
    blurb = AdventureBlurb.objects.create(
        adventure=trip, author=request.user, text=text,
        latitude=float(lat) if lat else None,
        longitude=float(lng) if lng else None,
        location_name=request.POST.get('location_name', ''),
    )
    photos = request.FILES.getlist('photos')
    for i, photo_file in enumerate(photos[:5]):
        if photo_file.size > 10 * 1024 * 1024:
            continue
        full_file, thumb_file = resize_photo(photo_file)
        AdventureBlurbPhoto.objects.create(blurb=blurb, image=full_file, thumbnail=thumb_file, order=i)
    return JsonResponse({"status": "ok", "blurb_id": blurb.id})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_delete_blurb(request, trip_id, blurb_id):
    trip = _get_trip_for_user(trip_id, request.user)
    blurb = get_object_or_404(AdventureBlurb, id=blurb_id, adventure=trip)
    if blurb.author != request.user and trip.device.user != request.user:
        return JsonResponse({"error": "Permission denied"}, status=403)
    blurb.delete()
    return JsonResponse({"status": "ok"})


@login_required
def trip_blurb_comments(request, trip_id, blurb_id):
    trip = _get_trip_for_user(trip_id, request.user)
    blurb = get_object_or_404(AdventureBlurb, id=blurb_id, adventure=trip)
    comments = []
    for c in blurb.comments.select_related('author'):
        comments.append({
            'id': c.id,
            'author': c.author.username if c.author else c.guest_name,
            'author_id': c.author.id if c.author else None,
            'avatar': _get_user_avatar(c.author) if c.author else None,
            'text': c.text,
            'created_at': c.created_at.isoformat(),
            'can_delete': c.author == request.user or trip.device.user == request.user,
        })
    return JsonResponse({'comments': comments})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_create_comment(request, trip_id, blurb_id):
    trip = _get_trip_for_user(trip_id, request.user)
    blurb = get_object_or_404(AdventureBlurb, id=blurb_id, adventure=trip)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    text = data.get('text', '').strip()
    if not text:
        return JsonResponse({"error": "Text required"}, status=400)
    comment = AdventureComment.objects.create(blurb=blurb, author=request.user, text=text)
    return JsonResponse({"status": "ok", "comment_id": comment.id})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_delete_comment(request, trip_id, comment_id):
    trip = _get_trip_for_user(trip_id, request.user)
    comment = get_object_or_404(AdventureComment, id=comment_id, blurb__adventure=trip)
    if comment.author != request.user and trip.device.user != request.user:
        return JsonResponse({"error": "Permission denied"}, status=403)
    comment.delete()
    return JsonResponse({"status": "ok"})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_create_milestone(request, trip_id):
    trip = _get_trip_for_user(trip_id, request.user)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    title = data.get('title', '').strip()
    if not title:
        return JsonResponse({"error": "Title required"}, status=400)
    date_raw = data.get('date', '').strip()
    try:
        date_val = datetime.fromisoformat(date_raw.replace('Z', '+00:00'))
        if timezone.is_naive(date_val):
            date_val = timezone.make_aware(date_val)
    except (ValueError, AttributeError):
        date_val = timezone.now()
    milestone = AdventureMilestone.objects.create(
        adventure=trip, author=request.user,
        title=title,
        description=data.get('description', ''),
        emoji=data.get('emoji', '🏁'),
        date=date_val,
    )
    return JsonResponse({"status": "ok", "milestone_id": milestone.id})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_delete_milestone(request, trip_id, milestone_id):
    trip = _get_trip_for_user(trip_id, request.user)
    milestone = get_object_or_404(AdventureMilestone, id=milestone_id, adventure=trip)
    if milestone.author != request.user and trip.device.user != request.user:
        return JsonResponse({"error": "Permission denied"}, status=403)
    milestone.delete()
    return JsonResponse({"status": "ok"})


@login_required
def trip_visits_api(request, trip_id):
    """Visit stats (cities/states/countries) scoped to this trip's device and time range."""
    trip = _get_trip_for_user(trip_id, request.user)
    locations = Location.objects.filter(
        device=trip.device,
        timestamp__gte=trip.start_time,
        timestamp__lte=trip.end_time,
    ).exclude(city='')

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

    MAX_GAP = 3600
    time_city = defaultdict(float)
    time_country = defaultdict(float)
    points = locations.order_by('timestamp').values_list('timestamp', 'city', 'state', 'country', 'country_code')
    prev = None
    for ts, city, state_val, country_val, cc in points.iterator():
        if prev:
            gap = min((ts - prev[0]).total_seconds(), MAX_GAP)
            if gap > 0:
                if prev[1]:
                    time_city[(prev[1], prev[2], prev[3], prev[4])] += gap
                if prev[3]:
                    time_country[prev[3]] += gap
        prev = (ts, city, state_val, country_val, cc)

    for c in cities:
        key = (c['city'], c['state'], c['country'], c['country_code'])
        c['time_spent'] = round(time_city.get(key, 0))
    for c in countries:
        c['time_spent'] = round(time_country.get(c['country'], 0))

    return JsonResponse({"cities": cities, "states": states, "countries": countries})


# ---------------------------------------------------------------------------
# Public Trip API (no auth required)
# ---------------------------------------------------------------------------

def trip_public_detail_api(request, slug):
    trip = get_object_or_404(Adventure, public_slug=slug)
    locations = list(trip.locations)
    locs = [{
        "lat": _jf(l.latitude), "lng": _jf(l.longitude),
        "timestamp": l.timestamp.isoformat(),
        "city": l.city, "country": l.country,
    } for l in locations]
    members = [{"username": m.user.username, "role": m.role} for m in trip.members.select_related('user')]
    return JsonResponse({
        "id": trip.id,
        "name": trip.name,
        "description": trip.description,
        "start_time": trip.start_time.isoformat(),
        "end_time": trip.end_time.isoformat(),
        "locations": locs,
        "members": members,
    })


def trip_public_timeline_api(request, slug):
    trip = get_object_or_404(Adventure, public_slug=slug)
    events = []
    for b in trip.blurbs.select_related('author').prefetch_related('photos', 'comments'):
        events.append({
            'type': 'blurb',
            'id': b.id,
            'author': b.author.username,
            'text': b.text,
            'latitude': b.latitude,
            'longitude': b.longitude,
            'location_name': b.location_name,
            'photos': [{'id': p.id, 'url': p.image.url, 'thumb': p.thumbnail.url if p.thumbnail else p.image.url} for p in b.photos.all()],
            'comment_count': b.comments.count(),
            'created_at': b.created_at.isoformat(),
            'sort_key': b.created_at.isoformat(),
        })
    for m in trip.milestones.select_related('author'):
        events.append({
            'type': 'milestone',
            'id': m.id,
            'author': m.author.username,
            'title': m.title,
            'description': m.description,
            'emoji': m.emoji,
            'date': m.date.isoformat(),
            'sort_key': m.date.isoformat(),
        })
    events.sort(key=lambda e: e['sort_key'])
    return JsonResponse({'events': events})


def trip_public_blurb_comments(request, slug, blurb_id):
    trip = get_object_or_404(Adventure, public_slug=slug)
    blurb = get_object_or_404(AdventureBlurb, id=blurb_id, adventure=trip)
    comments = []
    for c in blurb.comments.select_related('author').order_by('created_at'):
        author = c.author.username if c.author else (c.guest_name or 'guest')
        comments.append({
            'id': c.id,
            'author': author,
            'is_guest': c.author is None,
            'text': c.text,
            'created_at': c.created_at.isoformat(),
        })
    return JsonResponse({'comments': comments})


@csrf_exempt
@require_http_methods(["POST"])
def trip_public_create_comment(request, slug, blurb_id):
    trip = get_object_or_404(Adventure, public_slug=slug)
    blurb = get_object_or_404(AdventureBlurb, id=blurb_id, adventure=trip)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    text = data.get('text', '').strip()
    name = data.get('guest_name', '').strip()
    if not text:
        return JsonResponse({"error": "Text required"}, status=400)
    if not name:
        return JsonResponse({"error": "Name required"}, status=400)
    if len(name) > 100:
        return JsonResponse({"error": "Name too long"}, status=400)
    comment = AdventureComment.objects.create(blurb=blurb, author=None, guest_name=name, text=text)
    return JsonResponse({"status": "ok", "comment": {
        'id': comment.id,
        'author': name,
        'is_guest': True,
        'text': text,
        'created_at': comment.created_at.isoformat(),
    }})


def trip_public_view(request, slug):
    trip = get_object_or_404(Adventure, public_slug=slug)
    description = trip.description or f'An adventure from {trip.start_time.strftime("%b %d")} to {trip.end_time.strftime("%b %d, %Y")} on Roamly.'
    return render(request, 'tracker/trip_public.html', {
        'trip': trip,
        'slug': slug,
        'seo_description': description,
        'seo_canonical': request.build_absolute_uri(),
    })

adventure_public_view = trip_public_view


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


def _write_backup_json(user, f):
    """Write backup JSON to a file-like object row-by-row to avoid OOM on large datasets."""
    encoder = DjangoJSONEncoder()

    meta = {'version': 2, 'exported_at': timezone.now().isoformat(), 'username': user.username}
    devices = [{'device_id': d.device_id, 'name': d.name}
               for d in Device.objects.filter(user=user)]
    trips = [
        {'device_id': t.device.device_id, 'name': t.name, 'description': t.description,
         'start_time': t.start_time, 'end_time': t.end_time}
        for t in Adventure.objects.filter(device__user=user).select_related('device')
    ]
    trip_places = [
        {'trip_name': tp.adventure.name, 'trip_device_id': tp.adventure.device.device_id,
         'trip_start_time': tp.adventure.start_time, 'name': tp.name,
         'latitude': tp.latitude, 'longitude': tp.longitude, 'radius': tp.radius,
         'notes': tp.notes, 'visited_at': tp.visited_at}
        for tp in AdventurePlace.objects.filter(adventure__device__user=user).select_related('adventure', 'adventure__device')
    ]
    api_keys = [
        {'name': k.name, 'key': k.key, 'is_active': k.is_active, 'created_at': k.created_at}
        for k in APIKey.objects.filter(user=user)
    ]

    pals = _build_pals_data(user)

    f.write(b'{"meta":' + encoder.encode(meta).encode() + b',')
    f.write(b'"devices":' + encoder.encode(devices).encode() + b',')
    f.write(b'"trips":' + encoder.encode(trips).encode() + b',')
    f.write(b'"trip_places":' + encoder.encode(trip_places).encode() + b',')
    f.write(b'"api_keys":' + encoder.encode(api_keys).encode() + b',')
    f.write(b'"pals":' + encoder.encode(pals).encode() + b',')

    f.write(b'"locations":[')
    first = True
    qs = (Location.objects.filter(device__user=user)
          .select_related('device').order_by('timestamp').iterator(chunk_size=2000))
    for loc in qs:
        if not first:
            f.write(b',')
        first = False
        f.write(encoder.encode({
            'device_id': loc.device.device_id,
            'latitude': _jf(loc.latitude), 'longitude': _jf(loc.longitude),
            'altitude': _jf(loc.altitude), 'accuracy': _jf(loc.accuracy),
            'speed': _jf(loc.speed), 'battery': _jf(loc.battery),
            'timestamp': loc.timestamp,
            'city': loc.city, 'state': loc.state,
            'country': loc.country, 'country_code': loc.country_code,
            'place_name': loc.place_name,
        }).encode())
    f.write(b']}')


@login_required
@require_POST
def export_backup_start(request):
    """Start async backup generation, return a job ID to poll."""
    job_id = str(uuid.uuid4())
    tmp_path = os.path.join(tempfile.gettempdir(), f'roamly_backup_{job_id}.json')
    cache.set(f'backup_job:{request.user.id}:{job_id}', 'running', timeout=600)

    user = request.user

    def generate():
        try:
            with open(tmp_path, 'wb') as f:
                _write_backup_json(user, f)
            cache.set(f'backup_job:{user.id}:{job_id}', 'ready', timeout=600)
        except Exception as e:
            logger.error(f'Backup generation failed: {e}')
            cache.set(f'backup_job:{user.id}:{job_id}', f'error:{e}', timeout=600)

    threading.Thread(target=generate, daemon=True).start()
    return JsonResponse({'job_id': job_id})


@login_required
def export_backup_status(request, job_id):
    """Poll backup generation status."""
    status = cache.get(f'backup_job:{request.user.id}:{job_id}')
    if status is None:
        return JsonResponse({'status': 'not_found'}, status=404)
    if status.startswith('error:'):
        return JsonResponse({'status': 'error', 'message': status[6:]})
    return JsonResponse({'status': status})


@login_required
def export_backup_download(request, job_id):
    """Download a completed backup file."""
    status = cache.get(f'backup_job:{request.user.id}:{job_id}')
    if status != 'ready':
        return HttpResponse('Backup not ready', status=404)
    tmp_path = os.path.join(tempfile.gettempdir(), f'roamly_backup_{job_id}.json')
    if not os.path.exists(tmp_path):
        return HttpResponse('Backup file not found', status=404)
    filename = f'roamly_backup_{timezone.now().strftime("%Y-%m-%d")}.json'
    cache.delete(f'backup_job:{request.user.id}:{job_id}')
    f = open(tmp_path, 'rb')
    response = FileResponse(f, content_type='application/json', as_attachment=True, filename=filename)
    def _cleanup():
        time.sleep(60)
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
    threading.Thread(target=_cleanup, daemon=True).start()
    return response


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def restore_backup(request):
    """Restore user data from a JSON backup file."""
    f = request.FILES.get('file')
    if not f:
        return JsonResponse({'error': 'No file uploaded'}, status=400)

    try:
        data = json.loads(f.read().decode('utf-8-sig'))
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        return JsonResponse({'error': f'Invalid JSON file: {e}'}, status=400)

    meta = data.get('meta', {})
    if 'version' not in meta:
        return JsonResponse({'error': 'Not a valid Roamly backup file (missing meta.version)'}, status=400)

    user = request.user
    counts = {'devices': 0, 'locations': 0, 'trips': 0, 'trip_places': 0, 'api_keys': 0, 'pals': 0}
    errors = 0

    try:
        with transaction.atomic():
            # Restore devices (small count, get_or_create is fine)
            device_map = {}
            all_device_ids = set()
            for loc in data.get('locations', []):
                all_device_ids.add(loc.get('device_id', ''))
            for t in data.get('trips', []):
                all_device_ids.add(t.get('device_id', ''))
            for d in data.get('devices', []):
                all_device_ids.add(d.get('device_id', ''))
            all_device_ids.discard('')

            device_names = {d['device_id']: d.get('name', d['device_id']) for d in data.get('devices', [])}
            for device_id in all_device_ids:
                device, created = Device.objects.get_or_create(
                    user=user, device_id=device_id,
                    defaults={'name': device_names.get(device_id, device_id)}
                )
                device_map[device_id] = device
                if created:
                    counts['devices'] += 1

            # Restore locations using bulk_create with ignore_conflicts
            BATCH_SIZE = 1000
            loc_batch = []
            loc_total = 0
            for loc in data.get('locations', []):
                try:
                    device = device_map.get(loc.get('device_id'))
                    if not device:
                        errors += 1
                        continue
                    ts = _parse_timestamp(loc['timestamp'])
                    loc_obj = Location(
                        device=device,
                        latitude=loc['latitude'],
                        longitude=loc['longitude'],
                        timestamp=ts,
                        altitude=loc.get('altitude'),
                        accuracy=loc.get('accuracy'),
                        speed=loc.get('speed'),
                        battery=loc.get('battery'),
                        city=loc.get('city', ''),
                        state=loc.get('state', ''),
                        country=loc.get('country', ''),
                        country_code=loc.get('country_code', ''),
                        place_name=loc.get('place_name', ''),
                    )
                    if HAS_POSTGIS and Point:
                        loc_obj.location = Point(float(loc['longitude']), float(loc['latitude']), srid=4326)
                    loc_batch.append(loc_obj)
                    if len(loc_batch) >= BATCH_SIZE:
                        created = Location.objects.bulk_create(loc_batch, ignore_conflicts=True)
                        loc_total += len(created)
                        loc_batch = []
                except Exception as e:
                    errors += 1
                    logger.warning(f"Backup restore location error: {e}")
            if loc_batch:
                created = Location.objects.bulk_create(loc_batch, ignore_conflicts=True)
                loc_total += len(created)
            counts['locations'] = loc_total

            # Restore trips (small count, get_or_create is fine)
            trip_map = {}
            for t in data.get('trips', []):
                try:
                    device = device_map.get(t.get('device_id'))
                    if not device:
                        errors += 1
                        continue
                    start = _parse_timestamp(t['start_time'])
                    end = _parse_timestamp(t['end_time'])
                    trip, created = Adventure.objects.get_or_create(
                        device=device,
                        name=t['name'],
                        start_time=start,
                        defaults={
                            'description': t.get('description', ''),
                            'end_time': end,
                        }
                    )
                    trip_key = (t['device_id'], t['name'], str(start))
                    trip_map[trip_key] = trip
                    if created:
                        counts['trips'] += 1
                except Exception as e:
                    errors += 1
                    logger.warning(f"Backup restore trip error: {e}")

            # Restore trip places
            for tp in data.get('trip_places', []):
                try:
                    trip_key = (tp['trip_device_id'], tp['trip_name'], str(_parse_timestamp(tp['trip_start_time'])))
                    trip = trip_map.get(trip_key)
                    if not trip:
                        errors += 1
                        continue
                    _, created = AdventurePlace.objects.get_or_create(
                        adventure=trip,
                        name=tp['name'],
                        latitude=tp['latitude'],
                        longitude=tp['longitude'],
                        defaults={
                            'radius': tp.get('radius', 100),
                            'notes': tp.get('notes', ''),
                            'visited_at': _parse_timestamp(tp['visited_at']) if tp.get('visited_at') else None,
                        }
                    )
                    if created:
                        counts['trip_places'] += 1
                except Exception as e:
                    errors += 1
                    logger.warning(f"Backup restore trip place error: {e}")

            # Restore API keys
            for k in data.get('api_keys', []):
                try:
                    _, created = APIKey.objects.get_or_create(
                        user=user, key=k['key'],
                        defaults={
                            'name': k.get('name', 'Restored Key'),
                            'is_active': k.get('is_active', True),
                        }
                    )
                    if created:
                        counts['api_keys'] += 1
                except Exception as e:
                    errors += 1
                    logger.warning(f"Backup restore API key error: {e}")

            # Restore pals
            from django.contrib.auth import get_user_model
            AuthUser = get_user_model()
            import datetime

            for pal_data in data.get('pals', []):
                try:
                    start_date = datetime.date.fromisoformat(str(pal_data['start_date'])[:10])
                    end_date = datetime.date.fromisoformat(str(pal_data['end_date'])[:10])
                    pal, pal_created = Pal.objects.get_or_create(
                        creator=user,
                        name=pal_data['name'],
                        start_date=start_date,
                        defaults={
                            'description': pal_data.get('description', ''),
                            'end_date': end_date,
                            'public_slug': pal_data.get('public_slug'),
                        }
                    )
                    if pal_created:
                        counts['pals'] += 1

                    # Members
                    for m in pal_data.get('members', []):
                        try:
                            member_user = AuthUser.objects.get(username=m['username'])
                            PalMember.objects.get_or_create(
                                pal=pal, user=member_user,
                                defaults={'role': m.get('role', 'member')}
                            )
                        except AuthUser.DoesNotExist:
                            pass

                    # Blurbs (only on new pals to avoid duplicates)
                    if pal_created:
                        for b in pal_data.get('blurbs', []):
                            try:
                                try:
                                    blurb_author = AuthUser.objects.get(username=b['author_username'])
                                except AuthUser.DoesNotExist:
                                    blurb_author = user
                                blurb = PalBlurb.objects.create(
                                    pal=pal,
                                    author=blurb_author,
                                    text=b.get('text', ''),
                                    latitude=b['latitude'],
                                    longitude=b['longitude'],
                                    location_name=b.get('location_name', ''),
                                )
                                for c in b.get('comments', []):
                                    try:
                                        comment_author = None
                                        if c.get('author_username'):
                                            try:
                                                comment_author = AuthUser.objects.get(username=c['author_username'])
                                            except AuthUser.DoesNotExist:
                                                pass
                                        PalComment.objects.create(
                                            blurb=blurb,
                                            author=comment_author,
                                            guest_name=c.get('guest_name', ''),
                                            text=c['text'],
                                        )
                                    except Exception:
                                        pass
                            except Exception as e:
                                errors += 1
                                logger.warning(f"Backup restore blurb error: {e}")

                        for m in pal_data.get('milestones', []):
                            try:
                                try:
                                    milestone_author = AuthUser.objects.get(username=m['author_username'])
                                except AuthUser.DoesNotExist:
                                    milestone_author = user
                                PalMilestone.objects.create(
                                    pal=pal,
                                    author=milestone_author,
                                    title=m['title'],
                                    description=m.get('description', ''),
                                    emoji=m.get('emoji', '🏁'),
                                    date=_parse_timestamp(m['date']),
                                )
                            except Exception as e:
                                errors += 1
                                logger.warning(f"Backup restore milestone error: {e}")

                except Exception as e:
                    errors += 1
                    logger.warning(f"Backup restore pal error: {e}")

    except Exception as e:
        logger.error(f"Backup restore failed: {e}")
        return JsonResponse({'error': f'Restore failed: {e}'}, status=500)

    return JsonResponse({'status': 'ok', 'restored': counts, 'errors': errors})


def _safe_float(value):
    """Convert a value to float, returning None for empty/invalid/NaN values."""
    if value is None:
        return None
    value = str(value).strip()
    if not value:
        return None
    try:
        result = float(value)
        if math.isnan(result) or math.isinf(result):
            return None
        return result
    except (ValueError, TypeError):
        return None


def _get_csv_field(row, *candidates):
    """Get a field from a CSV row, trying multiple possible column names."""
    for name in candidates:
        val = row.get(name)
        if val is not None and str(val).strip():
            return str(val).strip()
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
        '%Y-%m-%d %H:%M',
        '%d-%m-%Y %H:%M:%S',
        '%d.%m.%Y %H:%M:%S',
        '%Y.%m.%d %H:%M:%S',
        '%Y-%m-%dT%H:%M:%S.%f',
        '%Y-%m-%dT%H:%M:%S.%f%z',
        '%a %b %d %H:%M:%S %Z %Y',
        '%Y-%m-%d',
        '%m/%d/%Y',
        '%d/%m/%Y',
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
                _get_csv_field(row, 'latitude', 'lat', 'Latitude', 'Lat',
                               'position_lat', 'y', 'Y', 'LATITUDE', 'LAT')
            )
            lon = _safe_float(
                _get_csv_field(row, 'longitude', 'lng', 'lon', 'long',
                               'Longitude', 'Lng', 'Lon', 'Long',
                               'position_long', 'x', 'X', 'LONGITUDE', 'LON')
            )

            if lat is None or lon is None:
                raise ValueError(
                    f"Missing lat/lon. Columns found: {list(row.keys())}"
                )

            if not (-90 <= lat <= 90) or not (-180 <= lon <= 180):
                raise ValueError(f"Coordinates out of range: lat={lat} lon={lon}")

            ts_raw = _get_csv_field(
                row, 'timestamp', 'time', 'datetime', 'date', 'created_at',
                'Timestamp', 'Time', 'DateTime', 'Date', 'timestamp_iso',
                'local_timestamp', 'utc_timestamp', 'time_local', 'date_time',
                'recorded_at', 'starttime', 'date/time',
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
                        _get_csv_field(row, 'altitude', 'alt', 'elevation', 'ele',
                                       'altitude_m', 'enhanced_altitude', 'gps_altitude')
                    ),
                    'accuracy': _safe_float(
                        _get_csv_field(row, 'accuracy', 'acc', 'hdop', 'horizontal_accuracy',
                                       'position_accuracy', 'gps_accuracy')
                    ),
                    'speed': _safe_float(
                        _get_csv_field(row, 'speed', 'vel', 'velocity', 'speed_m_s',
                                       'enhanced_speed', 'ground_speed', 'speed_ms')
                    ),
                    'battery': _safe_float(
                        _get_csv_field(row, 'battery', 'batt', 'battery_level', 'battery_pct')
                    ),
                    'city': _get_csv_field(row, 'city', 'City', 'locality') or '',
                    'state': _get_csv_field(row, 'state', 'State', 'province', 'region', 'administrative_area') or '',
                    'country': _get_csv_field(row, 'country', 'Country', 'country_name') or '',
                    'country_code': _get_csv_field(row, 'country_code', 'countryCode', 'cc', 'iso_country') or '',
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


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def import_json(request):
    """Import locations from JSON (Google Takeout Location History or OwnTracks export)."""
    f = request.FILES.get('file')
    if not f:
        return JsonResponse({"error": "No file uploaded"}, status=400)

    try:
        content = f.read().decode('utf-8-sig')
        data = json.loads(content)
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        return JsonResponse({"error": f"Invalid JSON: {e}"}, status=400)

    override_device_id = request.POST.get('device_id', '').strip()
    count = 0
    errors = 0
    first_error = None

    def _import_one(lat, lon, ts, device_id_val, **kwargs):
        nonlocal count, errors, first_error
        try:
            if lat is None or lon is None:
                raise ValueError("Missing lat/lon")
            if not (-90 <= lat <= 90) or not (-180 <= lon <= 180):
                raise ValueError(f"Coordinates out of range: {lat}, {lon}")
            dev_id = override_device_id or device_id_val or 'json-import'
            device, _ = Device.objects.get_or_create(
                user=request.user, device_id=dev_id, defaults={'name': dev_id}
            )
            Location.objects.get_or_create(
                device=device, latitude=lat, longitude=lon,
                timestamp=ts or timezone.now(),
                defaults=kwargs,
            )
            count += 1
        except Exception as e:
            errors += 1
            if first_error is None:
                first_error = str(e)

    # Google Takeout Records format (new): {"locations": [...], "timelineObjects": [...]}
    # or {"semanticSegments": [...]}
    if isinstance(data, dict) and 'locations' in data:
        for loc in data['locations']:
            lat_raw = loc.get('latitudeE7') or loc.get('latitude')
            lon_raw = loc.get('longitudeE7') or loc.get('longitude')
            lat = float(lat_raw) / 1e7 if lat_raw and abs(float(lat_raw)) > 90 else _safe_float(lat_raw)
            lon = float(lon_raw) / 1e7 if lon_raw and abs(float(lon_raw)) > 180 else _safe_float(lon_raw)
            ts_raw = loc.get('timestamp') or loc.get('timestampMs')
            ts = _parse_timestamp(ts_raw)
            _import_one(lat, lon, ts, 'google-takeout',
                        altitude=_safe_float(loc.get('altitude')),
                        accuracy=_safe_float(loc.get('accuracy') or loc.get('horizontalAccuracy')))

    # OwnTracks JSON array: [{"_type": "location", "lat": ..., "lon": ..., "tst": ...}, ...]
    elif isinstance(data, list) and data and isinstance(data[0], dict):
        for loc in data:
            if loc.get('_type') == 'location' or ('lat' in loc and 'lon' in loc):
                lat = _safe_float(loc.get('lat') or loc.get('latitude'))
                lon = _safe_float(loc.get('lon') or loc.get('longitude'))
                ts_raw = loc.get('tst') or loc.get('timestamp') or loc.get('time')
                ts = _parse_timestamp(str(ts_raw)) if ts_raw else None
                device_id_val = str(loc.get('tid') or loc.get('device_id') or 'owntracks')
                _import_one(lat, lon, ts, device_id_val,
                            altitude=_safe_float(loc.get('alt') or loc.get('altitude')),
                            accuracy=_safe_float(loc.get('acc') or loc.get('accuracy')),
                            speed=_safe_float(loc.get('vel') or loc.get('speed')),
                            battery=_safe_float(loc.get('batt') or loc.get('battery')))
            elif 'latitude' in loc or 'lat' in loc:
                lat = _safe_float(loc.get('latitude') or loc.get('lat'))
                lon = _safe_float(loc.get('longitude') or loc.get('lon') or loc.get('lng'))
                ts = _parse_timestamp(str(loc.get('timestamp') or loc.get('time') or ''))
                _import_one(lat, lon, ts, 'json-import',
                            altitude=_safe_float(loc.get('altitude') or loc.get('alt')),
                            accuracy=_safe_float(loc.get('accuracy')),
                            speed=_safe_float(loc.get('speed')))
    else:
        return JsonResponse({"error": "Unrecognized JSON format. Expected Google Takeout {'locations':[...]} or OwnTracks array [{...}]."}, status=400)

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


# ---------------------------------------------------------------------------
# Search
# ---------------------------------------------------------------------------

_CLUSTER_MAX_DEG = 1.0  # ~111 km — POIs within this spread share one bbox query


def _cluster_pois(pois):
    """Group POIs into geographic clusters so each cluster fits within _CLUSTER_MAX_DEG degrees.

    Reduces the number of Location DB queries from one-per-POI down to one-per-cluster.
    """
    clusters = []
    for poi in sorted(pois, key=lambda p: (p.latitude, p.longitude)):
        for cluster in clusters:
            lats = [p.latitude for p in cluster]
            lngs = [p.longitude for p in cluster]
            if (max(max(lats), poi.latitude) - min(min(lats), poi.latitude) <= _CLUSTER_MAX_DEG
                    and max(max(lngs), poi.longitude) - min(min(lngs), poi.longitude) <= _CLUSTER_MAX_DEG):
                cluster.append(poi)
                break
        else:
            clusters.append([poi])
    return clusters


def _search_local_pois(query, user_locations_qs, radius_m=150):
    """Search the local POI table and match against user's location history."""
    query = query.strip()
    if not query:
        return []

    matching_pois = list(
        POI.objects.filter(name__icontains=query)
        .annotate(
            match_rank=Case(
                When(name__iexact=query, then=Value(0)),
                When(name__istartswith=query, then=Value(1)),
                default=Value(2),
                output_field=IntegerField(),
            )
        )
        .order_by('match_rank', 'name')[:120]
    )

    if not matching_pois:
        return []

    delta = radius_m / 111000.0
    results = []

    for cluster in _cluster_pois(matching_pois):
        min_lat = min(p.latitude for p in cluster) - delta
        max_lat = max(p.latitude for p in cluster) + delta
        min_lng = min(p.longitude for p in cluster) - delta
        max_lng = max(p.longitude for p in cluster) + delta

        # One query per cluster instead of one per POI.
        bbox_locs = list(
            user_locations_qs.filter(
                latitude__gte=min_lat, latitude__lte=max_lat,
                longitude__gte=min_lng, longitude__lte=max_lng,
            ).order_by('timestamp').values(
                'timestamp', 'latitude', 'longitude', 'city', 'state', 'device__name'
            )
        )

        if not bbox_locs:
            continue

        for poi in cluster:
            nearby = [
                loc for loc in bbox_locs
                if abs(loc['latitude'] - poi.latitude) <= delta
                and abs(loc['longitude'] - poi.longitude) <= delta
            ]
            if not nearby:
                continue

            day_data = _group_by_day_dicts(nearby)
            day_data = [d for d in day_data if d['time_spent'] >= 300]
            if not day_data:
                continue

            total_points = sum(d['count'] for d in day_data)
            total_time_spent = sum(d['time_spent'] for d in day_data)
            display = f"{poi.name}, {poi.address}" if poi.address else poi.name
            results.append({
                'place_name': display,
                'lat': poi.latitude,
                'lng': poi.longitude,
                'days': day_data,
                'total_points': total_points,
                'total_time_spent': total_time_spent,
                'match_rank': poi.match_rank,
            })

    results.sort(
        key=lambda r: (r['match_rank'], -r['total_time_spent'], -r['total_points'], r['place_name'])
    )
    for r in results:
        r.pop('total_time_spent', None)
        r.pop('match_rank', None)
    return results


def _find_nearby_locations(base_qs, lat, lng, radius_m):
    """Filter a Location queryset to points within radius_m of (lat, lng)."""
    if HAS_POSTGIS and Point:
        from django.contrib.gis.measure import D
        ref = Point(lng, lat, srid=4326)
        return base_qs.filter(location__distance_lte=(ref, D(m=radius_m)))
    else:
        delta = radius_m / 111000.0
        return base_qs.filter(
            latitude__gte=lat - delta, latitude__lte=lat + delta,
            longitude__gte=lng - delta, longitude__lte=lng + delta,
        )


def _calc_dwell_time(qs, max_gap=600):
    """Calculate dwell time from a queryset, only summing gaps under max_gap seconds.

    If consecutive points are more than max_gap apart, that gap is excluded
    (the user likely left and came back). Default max_gap is 10 minutes.
    """
    timestamps = list(qs.order_by('timestamp').values_list('timestamp', flat=True))
    if len(timestamps) < 2:
        return 0
    total = 0
    for i in range(1, len(timestamps)):
        gap = (timestamps[i] - timestamps[i - 1]).total_seconds()
        if gap <= max_gap:
            total += gap
    return int(total)


def _group_by_day(qs):
    """Group a Location queryset by date, returning summary dicts."""
    by_day = {}

    rows = qs.order_by('timestamp').values_list(
        'timestamp', 'city', 'state', 'device__name'
    )

    for ts, city, state, device_name in rows.iterator(chunk_size=5000):
        day_str = ts.date().isoformat()
        day = by_day.get(day_str)
        if day is None:
            day = {
                'date': day_str,
                'count': 0,
                'first_ts': ts,
                'last_ts': ts,
                'time_spent': 0,
                'cities_set': set(),
                'cities': [],
                'devices_set': set(),
                'devices': [],
                'prev_ts': None,
            }
            by_day[day_str] = day

        day['count'] += 1
        day['last_ts'] = ts

        if day['prev_ts'] is not None:
            gap = (ts - day['prev_ts']).total_seconds()
            if gap <= 600:
                day['time_spent'] += int(gap)
        day['prev_ts'] = ts

        city_key = (city or '', state or '')
        if city and city_key not in day['cities_set'] and len(day['cities']) < 5:
            day['cities_set'].add(city_key)
            day['cities'].append({'city': city, 'state': state or ''})

        if device_name and device_name not in day['devices_set'] and len(day['devices']) < 5:
            day['devices_set'].add(device_name)
            day['devices'].append(device_name)

    result = []
    for day_str in sorted(by_day.keys(), reverse=True)[:100]:
        day = by_day[day_str]
        result.append({
            'date': day['date'],
            'count': day['count'],
            'first_ts': day['first_ts'].isoformat() if day['first_ts'] else None,
            'last_ts': day['last_ts'].isoformat() if day['last_ts'] else None,
            'time_spent': day['time_spent'],
            'cities': day['cities'],
            'devices': day['devices'],
        })
    return result


def _group_by_day_dicts(locs):
    """Like _group_by_day but takes a pre-fetched list of dicts (sorted by timestamp).

    Used by _search_local_pois so no extra DB query is needed per POI.
    """
    by_day = {}
    for loc in locs:
        ts = loc['timestamp']
        day_str = ts.date().isoformat()
        day = by_day.get(day_str)
        if day is None:
            day = {
                'date': day_str,
                'count': 0,
                'first_ts': ts,
                'last_ts': ts,
                'time_spent': 0,
                'cities_set': set(),
                'cities': [],
                'devices_set': set(),
                'devices': [],
                'prev_ts': None,
            }
            by_day[day_str] = day

        day['count'] += 1
        day['last_ts'] = ts

        if day['prev_ts'] is not None:
            gap = (ts - day['prev_ts']).total_seconds()
            if gap <= 600:
                day['time_spent'] += int(gap)
        day['prev_ts'] = ts

        city = loc.get('city') or ''
        state = loc.get('state') or ''
        device_name = loc.get('device__name') or ''

        city_key = (city, state)
        if city and city_key not in day['cities_set'] and len(day['cities']) < 5:
            day['cities_set'].add(city_key)
            day['cities'].append({'city': city, 'state': state})

        if device_name and device_name not in day['devices_set'] and len(day['devices']) < 5:
            day['devices_set'].add(device_name)
            day['devices'].append(device_name)

    result = []
    for day_str in sorted(by_day.keys(), reverse=True)[:100]:
        day = by_day[day_str]
        result.append({
            'date': day['date'],
            'count': day['count'],
            'first_ts': day['first_ts'].isoformat() if day['first_ts'] else None,
            'last_ts': day['last_ts'].isoformat() if day['last_ts'] else None,
            'time_spent': day['time_spent'],
            'cities': day['cities'],
            'devices': day['devices'],
        })
    return result


def _parse_date_search_query(query):
    """Parse common date query formats into [start, end) datetimes."""
    text = (query or '').strip()
    if not text:
        return None

    lower = text.lower()
    now = timezone.now()

    if lower == 'today':
        start = now.replace(hour=0, minute=0, second=0, microsecond=0)
        end = start + timedelta(days=1)
        return {'start': start, 'end': end, 'kind': 'day'}

    if lower == 'yesterday':
        end = now.replace(hour=0, minute=0, second=0, microsecond=0)
        start = end - timedelta(days=1)
        return {'start': start, 'end': end, 'kind': 'day'}

    day_formats = [
        '%Y-%m-%d',
        '%Y/%m/%d',
        '%b %d %Y',
        '%B %d %Y',
    ]
    month_formats = [
        '%Y-%m',
        '%Y/%m',
        '%b %Y',
        '%B %Y',
    ]

    for fmt in day_formats:
        try:
            d = datetime.strptime(text, fmt)
            start = timezone.make_aware(datetime(d.year, d.month, d.day, 0, 0, 0))
            end = start + timedelta(days=1)
            return {'start': start, 'end': end, 'kind': 'day'}
        except ValueError:
            continue

    for fmt in month_formats:
        try:
            d = datetime.strptime(text, fmt)
            start = timezone.make_aware(datetime(d.year, d.month, 1, 0, 0, 0))
            if d.month == 12:
                end = timezone.make_aware(datetime(d.year + 1, 1, 1, 0, 0, 0))
            else:
                end = timezone.make_aware(datetime(d.year, d.month + 1, 1, 0, 0, 0))
            return {'start': start, 'end': end, 'kind': 'month'}
        except ValueError:
            continue

    if text.isdigit() and len(text) == 4:
        year = int(text)
        if 1900 <= year <= 2100:
            start = timezone.make_aware(datetime(year, 1, 1, 0, 0, 0))
            end = timezone.make_aware(datetime(year + 1, 1, 1, 0, 0, 0))
            return {'start': start, 'end': end, 'kind': 'year'}

    return None


@login_required
def search_view(request):
    return render(request, 'tracker/search.html')


@login_required
def search_api(request):
    """Search location history by text, current location, or place name."""
    mode = request.GET.get('mode', 'text')
    q = request.GET.get('q', '').strip()
    locations = Location.objects.filter(device__user=request.user)
    gen = cache.get(f"cache_gen:{request.user.id}", 0)

    if mode == 'here':
        try:
            lat = float(request.GET['lat'])
            lng = float(request.GET['lng'])
        except (KeyError, ValueError):
            return JsonResponse({'error': 'lat and lng required'}, status=400)
        radius_m = float(request.GET.get('radius', 100))

        cache_key = (
            f"search:{request.user.id}:{gen}:here:"
            f"{round(lat, 5)}:{round(lng, 5)}:{int(radius_m)}"
        )
        cached = cache.get(cache_key)
        if cached is not None:
            return JsonResponse(cached)

        nearby = _find_nearby_locations(locations, lat, lng, radius_m)
        days = _group_by_day(nearby)
        payload = {
            'mode': 'here',
            'results': days,
            'total_days': len(days),
            'total_points': sum(d['count'] for d in days),
        }
        cache.set(cache_key, payload, timeout=120)
        return JsonResponse(payload)

    if not q:
        return JsonResponse({'error': 'q parameter required'}, status=400)

    cache_key = f"search:{request.user.id}:{gen}:text:{q.lower()}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

    parsed_date = _parse_date_search_query(q)

    # Location name results (city/state/place_name matches)
    if parsed_date:
        filtered = locations.filter(
            timestamp__gte=parsed_date['start'],
            timestamp__lt=parsed_date['end'],
        )
    else:
        filtered = locations.filter(
            Q(city__icontains=q) | Q(state__icontains=q) | Q(place_name__icontains=q)
        )
    days = _group_by_day(filtered)

    # For text-based town/state/place searches, show the searched term in the location column
    # so each result row reflects the query rather than listing all nearby cities for that day.
    if not parsed_date:
        for d in days:
            d['matched_query'] = q
            d['cities'] = [{'city': q, 'state': ''}]

    # Place results (from local POI database)
    place_results = []
    needs_download = False
    places_checked = 0
    if not parsed_date and POI.objects.count() > 0:
        place_results = _search_local_pois(q, locations)
        places_checked = len(place_results)
    elif not parsed_date:
        needs_download = True

    payload = {
        'query': q,
        'query_type': 'date' if parsed_date else 'text',
        'location_results': days,
        'total_days': len(days),
        'total_points': sum(d['count'] for d in days),
        'place_results': place_results,
        'places_checked': places_checked,
        'needs_download': needs_download,
    }
    cache.set(cache_key, payload, timeout=120)
    return JsonResponse(payload)


# ---------------------------------------------------------------------------
# POI Download
# ---------------------------------------------------------------------------

@login_required
@require_http_methods(["POST"])
def poi_download_api(request):
    """Start downloading POIs for the user's travel area."""
    job = start_poi_download(request.user.id)
    return JsonResponse({
        'status': job.status,
        'total': job.total,
    })


@login_required
def poi_status_api(request):
    """Check POI download status."""
    return JsonResponse(get_poi_status(request.user.id))


@login_required
@require_http_methods(["POST"])
def poi_stop_api(request):
    """Stop a running POI download."""
    stopped = stop_poi_download(request.user.id)
    return JsonResponse({'stopped': stopped})


# ---------------------------------------------------------------------------
# Automatic Backups (S3-compatible)
# ---------------------------------------------------------------------------

@login_required
@require_http_methods(["GET", "POST"])
def backup_config_api(request):
    """Get or save S3 backup configuration."""
    if request.method == 'GET':
        try:
            config = BackupConfig.objects.get(user=request.user)
            return JsonResponse({
                'configured': True,
                'endpoint_url': config.endpoint_url,
                'bucket_name': config.bucket_name,
                'access_key': config.access_key,
                'secret_key': '••••••••' if config.secret_key else '',
                'prefix': config.prefix,
                'region': config.region,
                'interval': config.interval,
                'max_backups': config.max_backups,
                'image_backup_enabled': config.image_backup_enabled,
                'image_use_same_creds': config.image_use_same_creds,
                'image_endpoint_url': config.image_endpoint_url,
                'image_bucket_name': config.image_bucket_name,
                'image_access_key': config.image_access_key,
                'image_secret_key': '••••••••' if config.image_secret_key else '',
                'image_prefix': config.image_prefix,
                'image_region': config.image_region,
            })
        except BackupConfig.DoesNotExist:
            return JsonResponse({'configured': False})

    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({'error': 'Invalid JSON'}, status=400)

    endpoint_url = data.get('endpoint_url', '').strip()
    bucket_name = data.get('bucket_name', '').strip()
    access_key = data.get('access_key', '').strip()
    secret_key = data.get('secret_key', '').strip()
    prefix = data.get('prefix', 'roamly-backups/').strip()
    region = data.get('region', 'auto').strip()
    interval = data.get('interval', 'disabled')
    max_backups = max(0, int(data.get('max_backups', 0)))

    if not endpoint_url or not bucket_name or not access_key:
        return JsonResponse({'error': 'Endpoint URL, bucket name, and access key are required'}, status=400)

    if interval not in ('disabled', 'daily', 'weekly', 'monthly'):
        return JsonResponse({'error': 'Invalid interval'}, status=400)

    config, created = BackupConfig.objects.get_or_create(
        user=request.user,
        defaults={
            'endpoint_url': endpoint_url,
            'bucket_name': bucket_name,
            'access_key': access_key,
            'secret_key': secret_key,
            'prefix': prefix,
            'region': region,
            'interval': interval,
            'max_backups': max_backups,
        }
    )

    # Image backup fields
    image_backup_enabled = data.get('image_backup_enabled', False)
    image_use_same_creds = data.get('image_use_same_creds', True)
    image_endpoint_url = data.get('image_endpoint_url', '').strip()
    image_bucket_name = data.get('image_bucket_name', '').strip()
    image_access_key = data.get('image_access_key', '').strip()
    image_secret_key = data.get('image_secret_key', '').strip()
    image_prefix = data.get('image_prefix', 'roamly-media/').strip()
    image_region = data.get('image_region', 'auto').strip()

    if not created:
        config.endpoint_url = endpoint_url
        config.bucket_name = bucket_name
        config.access_key = access_key
        # Only update secret if it's not the masked placeholder
        if secret_key and secret_key != '••••••••':
            config.secret_key = secret_key
        config.prefix = prefix
        config.region = region
        config.interval = interval
        config.max_backups = max_backups
        config.image_backup_enabled = image_backup_enabled
        config.image_use_same_creds = image_use_same_creds
        config.image_endpoint_url = image_endpoint_url
        config.image_bucket_name = image_bucket_name
        config.image_access_key = image_access_key
        if image_secret_key and image_secret_key != '••••••••':
            config.image_secret_key = image_secret_key
        config.image_prefix = image_prefix
        config.image_region = image_region
        config.save()
    else:
        config.image_backup_enabled = image_backup_enabled
        config.image_use_same_creds = image_use_same_creds
        config.image_endpoint_url = image_endpoint_url
        config.image_bucket_name = image_bucket_name
        config.image_access_key = image_access_key
        config.image_secret_key = image_secret_key
        config.image_prefix = image_prefix
        config.image_region = image_region
        config.save()

    return JsonResponse({'status': 'ok'})


@login_required
@require_http_methods(["POST"])
def backup_test_api(request):
    """Test S3 connection with the saved or provided credentials."""
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({'error': 'Invalid JSON'}, status=400)

    # Build a temporary config-like object for testing
    config = BackupConfig(user=request.user)
    config.endpoint_url = data.get('endpoint_url', '').strip()
    config.bucket_name = data.get('bucket_name', '').strip()
    config.access_key = data.get('access_key', '').strip()
    config.prefix = data.get('prefix', 'roamly-backups/').strip()
    config.region = data.get('region', 'auto').strip()

    secret_key = data.get('secret_key', '').strip()
    if secret_key == '••••••••':
        # Use the saved secret key
        try:
            saved = BackupConfig.objects.get(user=request.user)
            config.secret_key = saved.secret_key
        except BackupConfig.DoesNotExist:
            return JsonResponse({'error': 'No saved secret key — please enter one'}, status=400)
    else:
        config.secret_key = secret_key

    if not config.endpoint_url or not config.bucket_name or not config.access_key or not config.secret_key:
        return JsonResponse({'error': 'All connection fields are required'}, status=400)

    success, error = test_s3_connection(config)
    if success:
        return JsonResponse({'status': 'ok'})
    return JsonResponse({'error': error}, status=400)


@login_required
@require_http_methods(["POST"])
def backup_now_api(request):
    """Trigger an immediate backup."""
    try:
        BackupConfig.objects.get(user=request.user)
    except BackupConfig.DoesNotExist:
        return JsonResponse({'error': 'No backup configuration found. Save your S3 settings first.'}, status=400)

    result = run_backup_now(request.user.id)
    return JsonResponse({'status': result})


@login_required
def backup_status_api(request):
    """Get backup status."""
    return JsonResponse(get_backup_status(request.user.id))


@login_required
@require_POST
def backup_stop_api(request):
    """Force-stop a running backup."""
    stopped = stop_backup_now(request.user.id)
    return JsonResponse({'stopped': stopped})


@login_required
@require_POST
def image_backup_now_api(request):
    """Trigger an immediate image backup."""
    try:
        config = BackupConfig.objects.get(user=request.user)
    except BackupConfig.DoesNotExist:
        return JsonResponse({'error': 'No backup configuration found. Save your S3 settings first.'}, status=400)
    result = run_image_backup_now(request.user.id)
    return JsonResponse({'status': result})


@login_required
def image_backup_status_api(request):
    """Get image backup status."""
    return JsonResponse(get_image_backup_status(request.user.id))


# ---------------------------------------------------------------------------
# Profile Picture
# ---------------------------------------------------------------------------

@login_required
@require_http_methods(["POST"])
def upload_profile_picture(request):
    if 'picture' not in request.FILES:
        return JsonResponse({"error": "No file uploaded"}, status=400)
    pic = request.FILES['picture']
    if pic.size > 5 * 1024 * 1024:
        return JsonResponse({"error": "File too large (max 5MB)"}, status=400)
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    if profile.profile_picture:
        profile.profile_picture.delete(save=False)
    if profile.profile_picture_thumbnail:
        profile.profile_picture_thumbnail.delete(save=False)
    full = resize_image(pic, max_size=300)
    pic.seek(0)
    thumb = resize_image(pic, max_size=80)
    profile.profile_picture = full
    profile.profile_picture_thumbnail = thumb
    profile.save()
    return JsonResponse({
        "status": "ok",
        "picture_url": profile.profile_picture.url,
        "thumbnail_url": profile.profile_picture_thumbnail.url,
    })


@login_required
@require_http_methods(["POST"])
def delete_profile_picture(request):
    try:
        profile = request.user.profile
        if profile.profile_picture:
            profile.profile_picture.delete(save=False)
        if profile.profile_picture_thumbnail:
            profile.profile_picture_thumbnail.delete(save=False)
        profile.profile_picture = None
        profile.profile_picture_thumbnail = None
        profile.save()
    except UserProfile.DoesNotExist:
        pass
    return JsonResponse({"status": "ok"})


# ---------------------------------------------------------------------------
# PAL (Shared Group Trips)
# ---------------------------------------------------------------------------

def _get_user_avatar(user):
    """Return avatar data for a user."""
    try:
        profile = user.profile
        if profile.profile_picture_thumbnail:
            return {
                'type': 'image',
                'url': profile.profile_picture_thumbnail.url,
                'full_url': profile.profile_picture.url if profile.profile_picture else None,
            }
    except UserProfile.DoesNotExist:
        pass
    colors = ['#3b82f6', '#ef4444', '#22c55e', '#f59e0b', '#8b5cf6', '#ec4899', '#06b6d4', '#f97316']
    color = colors[hash(user.username) % len(colors)]
    initials = user.username[:2].upper()
    return {'type': 'initials', 'initials': initials, 'color': color}


@login_required
def pals_view(request):
    return render(request, 'tracker/pals.html')


@login_required
def pal_detail_view(request, pal_id):
    pal = get_object_or_404(Pal, id=pal_id)
    if not PalMember.objects.filter(pal=pal, user=request.user).exists():
        return redirect('tracker:pals')
    return render(request, 'tracker/pal_detail.html', {
        'pal_id': pal_id,
        'is_public': False,
        'public_slug': '',
    })


def pal_public_view(request, slug):
    pal = get_object_or_404(Pal, public_slug=slug)
    description = pal.description or f'A shared trip on Roamly.'
    return render(request, 'tracker/pal_detail.html', {
        'pal_id': pal.id,
        'pal': pal,
        'is_public': True,
        'public_slug': slug,
        'seo_description': description,
        'seo_canonical': request.build_absolute_uri(),
    })


@login_required
@require_http_methods(["GET", "POST"])
def pals_api(request):
    if request.method == 'GET':
        memberships = PalMember.objects.filter(user=request.user).select_related('pal', 'pal__creator')
        pals = []
        for m in memberships:
            p = m.pal
            pals.append({
                'id': p.id,
                'name': p.name,
                'description': p.description,
                'start_date': p.start_date.isoformat(),
                'end_date': p.end_date.isoformat(),
                'creator': p.creator.username,
                'is_public': bool(p.public_slug),
                'member_count': p.members.count(),
                'role': m.role,
            })
        return JsonResponse({'pals': pals})

    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    name = data.get('name', '').strip()
    if not name:
        return JsonResponse({"error": "Name is required"}, status=400)
    start_date = data.get('start_date')
    end_date = data.get('end_date')
    if not start_date or not end_date:
        return JsonResponse({"error": "Start and end dates required"}, status=400)
    try:
        sd = date.fromisoformat(start_date)
        ed = date.fromisoformat(end_date)
    except ValueError:
        return JsonResponse({"error": "Invalid date format"}, status=400)
    pal = Pal.objects.create(
        name=name,
        description=data.get('description', ''),
        start_date=sd,
        end_date=ed,
        creator=request.user,
    )
    PalMember.objects.create(pal=pal, user=request.user, role='creator')
    return JsonResponse({"status": "ok", "pal_id": pal.id})


@login_required
def pal_detail_api(request, pal_id):
    pal = get_object_or_404(Pal, id=pal_id)
    membership = PalMember.objects.filter(pal=pal, user=request.user).first()
    if not membership:
        return JsonResponse({"error": "Not a member"}, status=403)
    members = []
    for m in pal.members.select_related('user'):
        members.append({
            'user_id': m.user.id,
            'username': m.user.username,
            'role': m.role,
            'joined_at': m.joined_at.isoformat(),
            'avatar': _get_user_avatar(m.user),
        })
    return JsonResponse({
        'id': pal.id,
        'name': pal.name,
        'description': pal.description,
        'start_date': pal.start_date.isoformat(),
        'end_date': pal.end_date.isoformat(),
        'creator': pal.creator.username,
        'is_public': bool(pal.public_slug),
        'public_url': f'/pal/{pal.public_slug}/' if pal.public_slug else None,
        'members': members,
        'role': membership.role,
    })


@login_required
@require_http_methods(["POST"])
def pal_update(request, pal_id):
    pal = get_object_or_404(Pal, id=pal_id, creator=request.user)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    if 'name' in data:
        pal.name = data['name'].strip()
    if 'description' in data:
        pal.description = data['description']
    if 'start_date' in data:
        pal.start_date = date.fromisoformat(data['start_date'])
    if 'end_date' in data:
        pal.end_date = date.fromisoformat(data['end_date'])
    pal.save()
    return JsonResponse({"status": "ok"})


@login_required
@require_http_methods(["POST"])
def pal_delete(request, pal_id):
    pal = get_object_or_404(Pal, id=pal_id, creator=request.user)
    pal.delete()
    return JsonResponse({"status": "ok"})


@login_required
@require_http_methods(["POST"])
def pal_toggle_public(request, pal_id):
    pal = get_object_or_404(Pal, id=pal_id, creator=request.user)
    if pal.public_slug:
        pal.public_slug = None
    else:
        pal.public_slug = uuid.uuid4().hex[:12]
    pal.save()
    return JsonResponse({
        "status": "ok",
        "is_public": bool(pal.public_slug),
        "public_url": f'/pal/{pal.public_slug}/' if pal.public_slug else None,
    })


@login_required
@require_http_methods(["POST"])
def pal_add_member(request, pal_id):
    pal = get_object_or_404(Pal, id=pal_id, creator=request.user)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    username = data.get('username', '').strip()
    if not username:
        return JsonResponse({"error": "Username required"}, status=400)
    from django.contrib.auth.models import User as AuthUser
    try:
        target = AuthUser.objects.get(username=username)
    except AuthUser.DoesNotExist:
        return JsonResponse({"error": "User not found"}, status=404)
    if PalMember.objects.filter(pal=pal, user=target).exists():
        return JsonResponse({"error": "Already a member"}, status=400)
    PalMember.objects.create(pal=pal, user=target, role='member')
    return JsonResponse({
        "status": "ok",
        "member": {
            "user_id": target.id,
            "username": target.username,
            "role": "member",
            "avatar": _get_user_avatar(target),
        }
    })


@login_required
@require_http_methods(["POST"])
def pal_remove_member(request, pal_id, user_id):
    pal = get_object_or_404(Pal, id=pal_id, creator=request.user)
    if user_id == request.user.id:
        return JsonResponse({"error": "Cannot remove yourself"}, status=400)
    membership = get_object_or_404(PalMember, pal=pal, user_id=user_id)
    membership.delete()
    return JsonResponse({"status": "ok"})


@login_required
def pal_locations_api(request, pal_id):
    """Get all member location tracks within PAL date range."""
    pal = get_object_or_404(Pal, id=pal_id)
    if not PalMember.objects.filter(pal=pal, user=request.user).exists():
        return JsonResponse({"error": "Not a member"}, status=403)
    start_dt = timezone.make_aware(datetime.combine(pal.start_date, dt_time.min))
    end_dt = timezone.make_aware(datetime.combine(pal.end_date, dt_time(23, 59, 59)))
    result = {}
    for m in pal.members.select_related('user'):
        devices = Device.objects.filter(user=m.user)
        locations = list(Location.objects.filter(
            device__in=devices,
            timestamp__gte=start_dt,
            timestamp__lte=end_dt,
        ).order_by('timestamp').values_list('latitude', 'longitude', 'timestamp', named=True))
        if len(locations) > 500:
            step = len(locations) // 500
            locations = locations[::step]
        result[m.user.username] = {
            'avatar': _get_user_avatar(m.user),
            'locations': [
                {'lat': _jf(loc.latitude), 'lng': _jf(loc.longitude), 'ts': loc.timestamp.isoformat()}
                for loc in locations
            ]
        }
    return JsonResponse({'members': result})


@login_required
def pal_timeline_api(request, pal_id):
    """Combined blurbs + milestones in chronological order."""
    pal = get_object_or_404(Pal, id=pal_id)
    if not PalMember.objects.filter(pal=pal, user=request.user).exists():
        return JsonResponse({"error": "Not a member"}, status=403)
    page = int(request.GET.get('page', 1))
    per_page = 50
    events = []
    for b in pal.blurbs.select_related('author').prefetch_related('photos', 'comments'):
        events.append({
            'type': 'blurb',
            'id': b.id,
            'author': b.author.username,
            'author_id': b.author.id,
            'avatar': _get_user_avatar(b.author),
            'text': b.text,
            'latitude': b.latitude,
            'longitude': b.longitude,
            'location_name': b.location_name,
            'photos': [{'id': p.id, 'url': p.image.url, 'thumb': p.thumbnail.url if p.thumbnail else p.image.url} for p in b.photos.all()],
            'comment_count': b.comments.count(),
            'created_at': b.created_at.isoformat(),
            'sort_key': b.created_at.isoformat(),
        })
    for m in pal.milestones.select_related('author'):
        events.append({
            'type': 'milestone',
            'id': m.id,
            'author': m.author.username,
            'author_id': m.author.id,
            'title': m.title,
            'description': m.description,
            'emoji': m.emoji,
            'date': m.date.isoformat(),
            'created_at': m.created_at.isoformat(),
            'sort_key': m.date.isoformat(),
        })
    events.sort(key=lambda e: e['sort_key'])
    total = len(events)
    start = (page - 1) * per_page
    events = events[start:start + per_page]
    return JsonResponse({'events': events, 'page': page, 'has_more': start + per_page < total})


@login_required
@require_http_methods(["POST"])
def pal_create_blurb(request, pal_id):
    pal = get_object_or_404(Pal, id=pal_id)
    if not PalMember.objects.filter(pal=pal, user=request.user).exists():
        return JsonResponse({"error": "Not a member"}, status=403)
    text = request.POST.get('text', '').strip()
    if not text:
        return JsonResponse({"error": "Text is required"}, status=400)
    lat = request.POST.get('latitude')
    lng = request.POST.get('longitude')
    if not lat or not lng:
        return JsonResponse({"error": "Location is required"}, status=400)
    blurb = PalBlurb.objects.create(
        pal=pal, author=request.user, text=text,
        latitude=float(lat), longitude=float(lng),
        location_name=request.POST.get('location_name', ''),
    )
    photos = request.FILES.getlist('photos')
    for i, photo_file in enumerate(photos[:5]):
        if photo_file.size > 10 * 1024 * 1024:
            continue
        full_file, thumb_file = resize_photo(photo_file)
        PalBlurbPhoto.objects.create(blurb=blurb, image=full_file, thumbnail=thumb_file, order=i)
    return JsonResponse({"status": "ok", "blurb_id": blurb.id})


@login_required
@require_http_methods(["POST"])
def pal_update_blurb(request, pal_id, blurb_id):
    pal = get_object_or_404(Pal, id=pal_id)
    blurb = get_object_or_404(PalBlurb, id=blurb_id, pal=pal)
    if blurb.author != request.user and pal.creator != request.user:
        return JsonResponse({"error": "Permission denied"}, status=403)
    text = request.POST.get('text', '').strip()
    if text:
        blurb.text = text
    lat = request.POST.get('latitude')
    lng = request.POST.get('longitude')
    if lat and lng:
        blurb.latitude = float(lat)
        blurb.longitude = float(lng)
    location_name = request.POST.get('location_name')
    if location_name is not None:
        blurb.location_name = location_name
    blurb.save()
    return JsonResponse({"status": "ok"})


@login_required
@require_http_methods(["POST"])
def pal_delete_blurb(request, pal_id, blurb_id):
    pal = get_object_or_404(Pal, id=pal_id)
    blurb = get_object_or_404(PalBlurb, id=blurb_id, pal=pal)
    if blurb.author != request.user and pal.creator != request.user:
        return JsonResponse({"error": "Permission denied"}, status=403)
    blurb.delete()
    return JsonResponse({"status": "ok"})


@login_required
@require_http_methods(["POST"])
def pal_create_milestone(request, pal_id):
    pal = get_object_or_404(Pal, id=pal_id)
    if not PalMember.objects.filter(pal=pal, user=request.user).exists():
        return JsonResponse({"error": "Not a member"}, status=403)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    title = data.get('title', '').strip()
    if not title:
        return JsonResponse({"error": "Title required"}, status=400)
    date_str = data.get('date')
    if not date_str:
        return JsonResponse({"error": "Date required"}, status=400)
    milestone_dt = datetime.fromisoformat(date_str.replace('Z', '+00:00'))
    if timezone.is_naive(milestone_dt):
        milestone_dt = timezone.make_aware(milestone_dt)
    milestone = PalMilestone.objects.create(
        pal=pal, author=request.user, title=title,
        description=data.get('description', ''),
        emoji=data.get('emoji', '\U0001f3c1'),
        date=milestone_dt,
    )
    return JsonResponse({"status": "ok", "milestone_id": milestone.id})


@login_required
@require_http_methods(["POST"])
def pal_delete_milestone(request, pal_id, milestone_id):
    pal = get_object_or_404(Pal, id=pal_id)
    milestone = get_object_or_404(PalMilestone, id=milestone_id, pal=pal)
    if milestone.author != request.user and pal.creator != request.user:
        return JsonResponse({"error": "Permission denied"}, status=403)
    milestone.delete()
    return JsonResponse({"status": "ok"})


def _serialize_comment(c):
    """Serialize a PalComment for JSON response."""
    if c.author:
        return {
            'id': c.id,
            'author': c.author.username,
            'author_id': c.author.id,
            'avatar': _get_user_avatar(c.author),
            'is_guest': False,
            'text': c.text,
            'created_at': c.created_at.isoformat(),
        }
    name = c.guest_name or 'guest'
    initial = name[0].upper() if name else 'G'
    return {
        'id': c.id,
        'author': name,
        'author_id': None,
        'avatar': {'type': 'initials', 'initials': initial, 'color': '#6b7280'},
        'is_guest': True,
        'text': c.text,
        'created_at': c.created_at.isoformat(),
    }


@login_required
def pal_blurb_comments(request, pal_id, blurb_id):
    pal = get_object_or_404(Pal, id=pal_id)
    if not PalMember.objects.filter(pal=pal, user=request.user).exists():
        return JsonResponse({"error": "Not a member"}, status=403)
    blurb = get_object_or_404(PalBlurb, id=blurb_id, pal=pal)
    comments = [_serialize_comment(c) for c in blurb.comments.select_related('author')]
    return JsonResponse({'comments': comments})


@login_required
@require_http_methods(["POST"])
def pal_create_comment(request, pal_id, blurb_id):
    pal = get_object_or_404(Pal, id=pal_id)
    if not PalMember.objects.filter(pal=pal, user=request.user).exists():
        return JsonResponse({"error": "Not a member"}, status=403)
    blurb = get_object_or_404(PalBlurb, id=blurb_id, pal=pal)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    text = data.get('text', '').strip()
    if not text:
        return JsonResponse({"error": "Text required"}, status=400)
    comment = PalComment.objects.create(blurb=blurb, author=request.user, text=text)
    return JsonResponse({"status": "ok", "comment": _serialize_comment(comment)})


@login_required
@require_http_methods(["POST"])
def pal_delete_comment(request, pal_id, comment_id):
    pal = get_object_or_404(Pal, id=pal_id)
    comment = get_object_or_404(PalComment, id=comment_id, blurb__pal=pal)
    if comment.author != request.user and pal.creator != request.user:
        return JsonResponse({"error": "Permission denied"}, status=403)
    comment.delete()
    return JsonResponse({"status": "ok"})


# ---------------------------------------------------------------------------
# Public PAL API (no auth required for public PALs)
# ---------------------------------------------------------------------------

def pal_public_detail_api(request, slug):
    pal = get_object_or_404(Pal, public_slug=slug)
    members = []
    for m in pal.members.select_related('user'):
        members.append({
            'user_id': m.user.id,
            'username': m.user.username,
            'role': m.role,
            'avatar': _get_user_avatar(m.user),
        })
    return JsonResponse({
        'id': pal.id,
        'name': pal.name,
        'description': pal.description,
        'start_date': pal.start_date.isoformat(),
        'end_date': pal.end_date.isoformat(),
        'creator': pal.creator.username,
        'is_public': True,
        'members': members,
        'role': 'viewer',
    })


def pal_public_timeline_api(request, slug):
    pal = get_object_or_404(Pal, public_slug=slug)
    page = int(request.GET.get('page', 1))
    per_page = 50
    events = []
    for b in pal.blurbs.select_related('author').prefetch_related('photos', 'comments'):
        events.append({
            'type': 'blurb',
            'id': b.id,
            'author': b.author.username,
            'author_id': b.author.id,
            'avatar': _get_user_avatar(b.author),
            'text': b.text,
            'latitude': b.latitude,
            'longitude': b.longitude,
            'location_name': b.location_name,
            'photos': [{'id': p.id, 'url': p.image.url, 'thumb': p.thumbnail.url if p.thumbnail else p.image.url} for p in b.photos.all()],
            'comment_count': b.comments.count(),
            'created_at': b.created_at.isoformat(),
            'sort_key': b.created_at.isoformat(),
        })
    for m in pal.milestones.select_related('author'):
        events.append({
            'type': 'milestone',
            'id': m.id,
            'author': m.author.username,
            'author_id': m.author.id,
            'title': m.title,
            'description': m.description,
            'emoji': m.emoji,
            'date': m.date.isoformat(),
            'created_at': m.created_at.isoformat(),
            'sort_key': m.date.isoformat(),
        })
    events.sort(key=lambda e: e['sort_key'])
    total = len(events)
    start = (page - 1) * per_page
    events = events[start:start + per_page]
    return JsonResponse({'events': events, 'page': page, 'has_more': start + per_page < total})


def pal_public_locations_api(request, slug):
    pal = get_object_or_404(Pal, public_slug=slug)
    start_dt = timezone.make_aware(datetime.combine(pal.start_date, dt_time.min))
    end_dt = timezone.make_aware(datetime.combine(pal.end_date, dt_time(23, 59, 59)))
    result = {}
    for m in pal.members.select_related('user'):
        devices = Device.objects.filter(user=m.user)
        locations = list(Location.objects.filter(
            device__in=devices,
            timestamp__gte=start_dt,
            timestamp__lte=end_dt,
        ).order_by('timestamp').values_list('latitude', 'longitude', 'timestamp', named=True))
        if len(locations) > 500:
            step = len(locations) // 500
            locations = locations[::step]
        result[m.user.username] = {
            'avatar': _get_user_avatar(m.user),
            'locations': [
                {'lat': _jf(loc.latitude), 'lng': _jf(loc.longitude), 'ts': loc.timestamp.isoformat()}
                for loc in locations
            ]
        }
    return JsonResponse({'members': result})


def pal_public_comments_api(request, slug, blurb_id):
    pal = get_object_or_404(Pal, public_slug=slug)
    blurb = get_object_or_404(PalBlurb, id=blurb_id, pal=pal)
    comments = [_serialize_comment(c) for c in blurb.comments.select_related('author')]
    return JsonResponse({'comments': comments})


@csrf_exempt
@require_http_methods(["POST"])
def pal_public_create_comment(request, slug, blurb_id):
    pal = get_object_or_404(Pal, public_slug=slug)
    blurb = get_object_or_404(PalBlurb, id=blurb_id, pal=pal)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    text = data.get('text', '').strip()
    name = data.get('name', '').strip()
    if not text:
        return JsonResponse({"error": "Text required"}, status=400)
    if not name:
        return JsonResponse({"error": "Name required"}, status=400)
    if len(name) > 100:
        return JsonResponse({"error": "Name too long"}, status=400)
    comment = PalComment.objects.create(blurb=blurb, author=None, guest_name=name, text=text)
    return JsonResponse({"status": "ok", "comment": _serialize_comment(comment)})
