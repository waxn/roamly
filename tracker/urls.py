from django.urls import path
from . import views

app_name = 'tracker'

urlpatterns = [
    # PWA
    path('sw.js', views.service_worker, name='service_worker'),

    # Pages
    path('', views.landing_view, name='landing'),
    path('docs/', views.docs_view, name='docs'),
    path('terms/', views.terms_view, name='terms'),
    path('privacy/', views.privacy_view, name='privacy'),
    path('map/', views.map_view, name='map'),
    path('data/', views.data_table, name='data'),
    path('stats/', views.stats_view, name='stats'),
    path('visits/', views.visits_view, name='visits'),
    path('trips/', views.trips_view, name='trips'),
    path('settings/', views.settings_view, name='settings'),

    # Auth
    path('login/', views.login_view, name='login'),
    path('signup/', views.signup_view, name='signup'),
    path('logout/', views.logout_view, name='logout'),

    # Location API
    path('api/push/', views.push_location, name='push_location'),
    path('api/locations/', views.locations_api, name='locations_api'),
    path('api/locations/geojson/', views.locations_geojson_api, name='locations_geojson'),

    # Stats API
    path('api/stats/', views.stats_api, name='stats_api'),
    path('api/visits/', views.visits_api, name='visits_api'),
    path('api/distance/', views.distance_api, name='distance_api'),

    # Trips API
    path('api/trips/', views.trips_api, name='trips_api'),
    path('api/trips/create/', views.create_trip, name='create_trip'),
    path('api/trips/<int:trip_id>/', views.trip_detail, name='trip_detail'),
    path('api/trips/<int:trip_id>/delete/', views.delete_trip, name='delete_trip'),
    path('api/trips/<int:trip_id>/update/', views.update_trip, name='update_trip'),
    path('api/trips/<int:trip_id>/places/create/', views.create_trip_place, name='create_trip_place'),
    path('api/trips/<int:trip_id>/places/<int:place_id>/update/', views.update_trip_place, name='update_trip_place'),
    path('api/trips/<int:trip_id>/places/<int:place_id>/delete/', views.delete_trip_place, name='delete_trip_place'),

    # Geocoding
    path('api/geocode/', views.geocode_api, name='geocode_api'),
    path('api/geocode/status/', views.geocode_status, name='geocode_status'),
    path('api/geocode/stop/', views.geocode_stop, name='geocode_stop'),

    # Export/Import
    path('api/export/csv/', views.export_csv, name='export_csv'),
    path('api/export/gpx/', views.export_gpx, name='export_gpx'),
    path('api/import/csv/', views.import_csv, name='import_csv'),
    path('api/import/gpx/', views.import_gpx, name='import_gpx'),

    # API Keys
    path('api/keys/create/', views.create_api_key, name='create_api_key'),
    path('api/keys/<int:key_id>/delete/', views.delete_api_key, name='delete_api_key'),
    
    # Devices
    path('api/devices/', views.devices_api, name='devices_api'),

    # Account
    path('api/locations/<int:location_id>/delete/', views.delete_location, name='delete_location'),
    path('api/account/delete-data/', views.delete_location_data, name='delete_location_data'),
    path('api/account/delete/', views.delete_account, name='delete_account'),
]
