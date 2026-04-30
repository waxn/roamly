import time
import threading
import logging
import math
from datetime import timedelta
from collections import defaultdict

from django.utils import timezone
from django.db import transaction

logger = logging.getLogger(__name__)

_running_threads = {}

def _haversine_m(lat1, lon1, lat2, lon2):
    R = 6371000
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    a = math.sin(delta_phi / 2.0) ** 2 + \
        math.cos(phi1) * math.cos(phi2) * \
        math.sin(delta_lambda / 2.0) ** 2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return R * c

def _find_nearest_poi(lat, lon, radius_m=150):
    from .models import POI, HAS_POSTGIS
    
    if HAS_POSTGIS:
        from django.contrib.gis.geos import Point
        from django.contrib.gis.measure import D
        ref = Point(lon, lat, srid=4326)
        return POI.objects.filter(
            latitude__isnull=False,
            longitude__isnull=False
        ).annotate(
            distance=models.functions.Cast(
                models.F('latitude'), models.FloatField()
            )  # Not exact PostGIS distance, better to use raw SQL or simple bounding box then haversine
        ).filter(
            latitude__gte=lat - (radius_m / 111000.0),
            latitude__lte=lat + (radius_m / 111000.0),
            longitude__gte=lon - (radius_m / 111000.0),
            longitude__lte=lon + (radius_m / 111000.0)
        )
    else:
        # Simplistic box search
        delta = radius_m / 111000.0
        pois = POI.objects.filter(
            latitude__gte=lat - delta, latitude__lte=lat + delta,
            longitude__gte=lon - delta, longitude__lte=lon + delta,
        )
        best_poi = None
        best_dist = radius_m
        for p in pois:
            dist = _haversine_m(lat, lon, p.latitude, p.longitude)
            if dist <= best_dist:
                best_dist = dist
                best_poi = p
        return best_poi

def _visit_worker(user_id):
    from .models import Location, Visit, VisitJob, POI
    
    MIN_DURATION_S = 300  # 5 minutes
    RADIUS_M = 150
    BATCH_SIZE = 5000

    points_done = 0
    visits_added = 0

    try:
        try:
            job = VisitJob.objects.get(user_id=user_id)
        except VisitJob.DoesNotExist:
            return

        total_unprocessed = Location.objects.filter(device__user_id=user_id, processed_for_visits=False).count()
        job.total = total_unprocessed
        job.processed = 0
        job.save(update_fields=['total', 'processed', 'updated_at'])

        logger.info(f"Computing visits for user {user_id}: {total_unprocessed} unprocessed points")

        # Group by device
        devices = Location.objects.filter(device__user_id=user_id, processed_for_visits=False).values_list('device_id', flat=True).distinct()
        
        for device_id in devices:
            while True:
                # Check stop flag
                try:
                    job.refresh_from_db()
                    if job.status != 'running':
                        break
                except VisitJob.DoesNotExist:
                    break

                locations = list(Location.objects.filter(
                    device_id=device_id, 
                    processed_for_visits=False
                ).order_by('timestamp')[:BATCH_SIZE])

                if not locations:
                    break
                
                # Active visit state
                current_visit = None
                visits_to_create = []
                processed_ids = []

                for loc in locations:
                    processed_ids.append(loc.id)
                    
                    if current_visit is None:
                        current_visit = {
                            'device_id': device_id,
                            'start_time': loc.timestamp,
                            'end_time': loc.timestamp,
                            'lat_sum': loc.latitude,
                            'lon_sum': loc.longitude,
                            'point_count': 1,
                            'city': loc.city,
                            'state': loc.state,
                            'country': loc.country,
                            'country_code': loc.country_code,
                            'place_name': loc.place_name,
                        }
                    else:
                        # Check distance to centroid
                        centroid_lat = current_visit['lat_sum'] / current_visit['point_count']
                        centroid_lon = current_visit['lon_sum'] / current_visit['point_count']
                        dist = _haversine_m(centroid_lat, centroid_lon, loc.latitude, loc.longitude)
                        
                        # Check time gap (if gap > 1 hour, consider visit ended even if same location)
                        time_gap = (loc.timestamp - current_visit['end_time']).total_seconds()

                        if dist <= RADIUS_M and time_gap < 3600:
                            current_visit['end_time'] = loc.timestamp
                            current_visit['lat_sum'] += loc.latitude
                            current_visit['lon_sum'] += loc.longitude
                            current_visit['point_count'] += 1
                        else:
                            # Close visit
                            duration = (current_visit['end_time'] - current_visit['start_time']).total_seconds()
                            if duration >= MIN_DURATION_S:
                                visits_to_create.append(current_visit)
                            
                            # Start new visit
                            current_visit = {
                                'device_id': device_id,
                                'start_time': loc.timestamp,
                                'end_time': loc.timestamp,
                                'lat_sum': loc.latitude,
                                'lon_sum': loc.longitude,
                                'point_count': 1,
                                'city': loc.city,
                                'state': loc.state,
                                'country': loc.country,
                                'country_code': loc.country_code,
                                'place_name': loc.place_name,
                            }
                
                # Create visits
                new_visits = []
                for v in visits_to_create:
                    c_lat = v['lat_sum'] / v['point_count']
                    c_lon = v['lon_sum'] / v['point_count']
                    
                    # Simplistic nearest POI lookup
                    delta = RADIUS_M / 111000.0
                    pois = POI.objects.filter(
                        latitude__gte=c_lat - delta, latitude__lte=c_lat + delta,
                        longitude__gte=c_lon - delta, longitude__lte=c_lon + delta,
                    )
                    best_poi = None
                    best_dist = RADIUS_M
                    for p in pois:
                        dist = _haversine_m(c_lat, c_lon, p.latitude, p.longitude)
                        if dist <= best_dist:
                            best_dist = dist
                            best_poi = p
                    
                    new_visits.append(Visit(
                        device_id=v['device_id'],
                        start_time=v['start_time'],
                        end_time=v['end_time'],
                        latitude=c_lat,
                        longitude=c_lon,
                        poi=best_poi,
                        point_count=v['point_count'],
                        city=v['city'],
                        state=v['state'],
                        country=v['country'],
                        country_code=v['country_code'],
                        place_name=v['place_name']
                    ))
                
                if new_visits:
                    Visit.objects.bulk_create(new_visits)
                    visits_added += len(new_visits)

                # Mark locations as processed
                with transaction.atomic():
                    Location.objects.filter(id__in=processed_ids).update(processed_for_visits=True)
                
                points_done += len(processed_ids)
                job.processed = points_done
                job.visits_added = visits_added
                job.save(update_fields=['processed', 'visits_added', 'updated_at'])
                
                time.sleep(0.1)

            if job.status != 'running':
                break

    finally:
        try:
            job = VisitJob.objects.get(user_id=user_id)
            if job.status == 'running':
                job.status = 'completed'
            job.save(update_fields=['status', 'updated_at'])
        except VisitJob.DoesNotExist:
            pass

        _running_threads.pop(user_id, None)
        logger.info(
            f"Visit computation done for user {user_id}: {points_done} points processed, "
            f"{visits_added} visits added"
        )


def start_visit_computation(user_id):
    from .models import VisitJob

    VisitJob.objects.filter(
        user_id=user_id, status__in=['completed', 'stopped']
    ).delete()

    job, created = VisitJob.objects.get_or_create(
        user_id=user_id,
        defaults={'status': 'running', 'total': 0},
    )

    if not created:
        if job.status == 'running' and _is_thread_alive(user_id):
            return job
        job.status = 'running'
        job.total = 0
        job.processed = 0
        job.visits_added = 0
        job.save()

    _start_thread(user_id)
    return job

def _is_thread_alive(user_id):
    thread = _running_threads.get(user_id)
    return thread is not None and thread.is_alive()

def _start_thread(user_id):
    thread = threading.Thread(target=_visit_worker, args=(user_id,), daemon=True)
    _running_threads[user_id] = thread
    thread.start()

def get_visit_status(user_id):
    from .models import VisitJob, Location

    try:
        job = VisitJob.objects.get(user_id=user_id)
    except VisitJob.DoesNotExist:
        return {'status': 'idle'}

    if job.status == 'running' and not _is_thread_alive(user_id):
        stale = (timezone.now() - job.updated_at).total_seconds() > 30
        if stale:
            remaining = Location.objects.filter(
                device__user_id=user_id, processed_for_visits=False
            ).count()
            if remaining > 0:
                _start_thread(user_id)
            else:
                job.status = 'completed'
                job.save(update_fields=['status'])

    return {
        'status': job.status,
        'processed': job.processed,
        'total': job.total,
        'visits_added': job.visits_added,
    }

def stop_visit_computation(user_id):
    from .models import VisitJob

    try:
        job = VisitJob.objects.get(user_id=user_id, status='running')
        job.status = 'stopped'
        job.save(update_fields=['status'])
        return True
    except VisitJob.DoesNotExist:
        return False
