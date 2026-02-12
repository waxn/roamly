import csv
import io
import json
import logging
import urllib.request
from datetime import datetime, timedelta

from django.contrib import messages
from django.contrib.auth import authenticate, login, logout
from django.contrib.auth.decorators import login_required
from django.db.models import Count, Min, Max, Avg
from django.http import JsonResponse, HttpResponse
from django.shortcuts import render, redirect, get_object_or_404
from django.utils import timezone
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods

from .forms import SignUpForm, APIKeyForm, TripForm
from .models import Device, Location, APIKey, Trip, TripPlace
from .geocoding_tasks import GeocodingTask, get_active_task, cleanup_old_tasks

logger = logging.getLogger(__name__)

# Check for PostGIS
try:
    from django.contrib.gis.geos import Polygon
    HAS_POSTGIS = True
except Exception:
    HAS_POSTGIS = False
    Polygon = None


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
            'User-Agent': 'Roamly/1.0 (self-hosted location tracker)',
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
    if request.user.is_authenticated:
        return redirect('tracker:map')
    return redirect('tracker:login')


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
    return render(request, 'tracker/map.html', {'devices': devices})


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
        except json.JSONDecodeError:
            return JsonResponse({"error": "Invalid JSON"}, status=400)

    # Detect format
    if data.get("_type") == "location":
        device_id = data.get("tid") or data.get("device_id", "unknown")
        latitude = data.get("lat")
        longitude = data.get("lon")
        timestamp = data.get("tst")
        if timestamp:
            timestamp = datetime.fromtimestamp(int(timestamp), tz=timezone.utc)
        altitude = data.get("alt")
        accuracy = data.get("acc")
        speed = data.get("vel")
        battery = data.get("batt")
    else:
        device_id = data.get("device_id")
        latitude = data.get("latitude")
        longitude = data.get("longitude")
        timestamp = data.get("timestamp")
        if timestamp and isinstance(timestamp, str):
            timestamp = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
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

    locations = locations.select_related('device').order_by('timestamp')[:limit]

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
    """Overall statistics."""
    device_id = request.GET.get("device_id")
    locations = Location.objects.filter(device__user=request.user)
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
        start = datetime.fromisoformat(data['start_time'].replace('Z', '+00:00'))
        end = datetime.fromisoformat(data['end_time'].replace('Z', '+00:00'))
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


@login_required
def trip_detail(request, trip_id):
    trip = get_object_or_404(Trip, id=trip_id, device__user=request.user)
    locations = trip.locations
    locs = [{
        "lat": l.latitude, "lng": l.longitude,
        "timestamp": l.timestamp.isoformat(),
        "city": l.city, "country": l.country,
    } for l in locations]

    return JsonResponse({
        "id": trip.id,
        "name": trip.name,
        "description": trip.description,
        "start_time": trip.start_time.isoformat(),
        "end_time": trip.end_time.isoformat(),
        "locations": locs,
    })


@login_required
@csrf_exempt
@require_http_methods(["POST", "DELETE"])
def delete_trip(request, trip_id):
    trip = get_object_or_404(Trip, id=trip_id, device__user=request.user)
    trip.delete()
    return JsonResponse({"status": "ok"})


# ---------------------------------------------------------------------------
# Geocoding API
# ---------------------------------------------------------------------------

@login_required
@csrf_exempt
@require_http_methods(["POST"])
def geocode_api(request):
    """Start batch geocoding of un-geocoded locations."""
    cleanup_old_tasks(request.user.id)

    existing = get_active_task(request.user.id)
    if existing:
        _, info = existing
        if info['status'] == 'running':
            return JsonResponse({"status": "already_running", **info})

    locations = list(
        Location.objects.filter(device__user=request.user, city='')
        .order_by('-timestamp')[:500]
    )

    if not locations:
        return JsonResponse({"status": "nothing_to_geocode", "total": 0})

    task = GeocodingTask(request.user.id)
    task_id = task.run(locations)
    return JsonResponse({"status": "started", "task_id": task_id, "total": len(locations)})


@login_required
def geocode_status(request):
    result = get_active_task(request.user.id)
    if result:
        _, info = result
        return JsonResponse(info)
    return JsonResponse({"status": "idle"})


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


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def import_csv(request):
    """Import locations from CSV."""
    f = request.FILES.get('file')
    if not f:
        return JsonResponse({"error": "No file uploaded"}, status=400)

    decoded = f.read().decode('utf-8')
    reader = csv.DictReader(io.StringIO(decoded))
    count = 0
    errors = 0

    for row in reader:
        try:
            device_id = row.get('device', 'import')
            device, _ = Device.objects.get_or_create(
                user=request.user, device_id=device_id,
                defaults={'name': device_id}
            )
            ts = row.get('timestamp')
            if ts:
                ts = datetime.fromisoformat(ts.replace('Z', '+00:00'))
                if timezone.is_naive(ts):
                    ts = timezone.make_aware(ts)
            else:
                ts = timezone.now()

            Location.objects.get_or_create(
                device=device,
                latitude=float(row['latitude']),
                longitude=float(row['longitude']),
                timestamp=ts,
                defaults={
                    'altitude': float(row['altitude']) if row.get('altitude') else None,
                    'accuracy': float(row['accuracy']) if row.get('accuracy') else None,
                    'speed': float(row['speed']) if row.get('speed') else None,
                    'battery': float(row['battery']) if row.get('battery') else None,
                    'city': row.get('city', ''),
                    'state': row.get('state', ''),
                    'country': row.get('country', ''),
                    'country_code': row.get('country_code', ''),
                }
            )
            count += 1
        except Exception as e:
            errors += 1
            logger.warning(f"CSV import error: {e}")

    return JsonResponse({"status": "ok", "imported": count, "errors": errors})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def import_gpx(request):
    """Import locations from GPX file."""
    f = request.FILES.get('file')
    if not f:
        return JsonResponse({"error": "No file uploaded"}, status=400)

    import xml.etree.ElementTree as ET
    content = f.read().decode('utf-8')

    try:
        root = ET.fromstring(content)
    except ET.ParseError:
        return JsonResponse({"error": "Invalid GPX XML"}, status=400)

    ns = {'gpx': 'http://www.topografix.com/GPX/1/1'}
    device_id = request.POST.get('device_id', 'gpx-import')
    device, _ = Device.objects.get_or_create(
        user=request.user, device_id=device_id,
        defaults={'name': device_id}
    )

    count = 0
    errors = 0

    # Try both namespaced and non-namespaced
    trkpts = root.findall('.//gpx:trkpt', ns)
    if not trkpts:
        trkpts = root.findall('.//{http://www.topografix.com/GPX/1/1}trkpt')
    if not trkpts:
        trkpts = root.findall('.//trkpt')

    for pt in trkpts:
        try:
            lat = float(pt.get('lat'))
            lon = float(pt.get('lon'))

            # Try to find time element
            time_el = (
                pt.find('gpx:time', ns) or
                pt.find('{http://www.topografix.com/GPX/1/1}time') or
                pt.find('time')
            )
            if time_el is not None and time_el.text:
                ts = datetime.fromisoformat(time_el.text.replace('Z', '+00:00'))
                if timezone.is_naive(ts):
                    ts = timezone.make_aware(ts)
            else:
                ts = timezone.now()

            ele_el = (
                pt.find('gpx:ele', ns) or
                pt.find('{http://www.topografix.com/GPX/1/1}ele') or
                pt.find('ele')
            )
            alt = float(ele_el.text) if ele_el is not None and ele_el.text else None

            Location.objects.get_or_create(
                device=device, latitude=lat, longitude=lon, timestamp=ts,
                defaults={'altitude': alt}
            )
            count += 1
        except Exception as e:
            errors += 1
            logger.warning(f"GPX import error: {e}")

    return JsonResponse({"status": "ok", "imported": count, "errors": errors})


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
