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
    path('search/', views.search_view, name='search'),
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
    path('api/tiles/<int:z>/<int:x>/<int:y>.pbf', views.vector_tile, name='vector_tile'),

    # Stats API
    path('api/stats/', views.stats_api, name='stats_api'),
    path('api/visits/', views.visits_api, name='visits_api'),
    path('api/distance/', views.distance_api, name='distance_api'),
    path('api/search/', views.search_api, name='search_api'),

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

    # POI Download
    path('api/poi/download/', views.poi_download_api, name='poi_download'),
    path('api/poi/status/', views.poi_status_api, name='poi_status'),
    path('api/poi/stop/', views.poi_stop_api, name='poi_stop'),

    # Automatic Backups
    path('api/backup/config/', views.backup_config_api, name='backup_config'),
    path('api/backup/test/', views.backup_test_api, name='backup_test'),
    path('api/backup/now/', views.backup_now_api, name='backup_now'),
    path('api/backup/status/', views.backup_status_api, name='backup_status'),
    path('api/backup/stop/', views.backup_stop_api, name='backup_stop'),
    path('api/backup/image/now/', views.image_backup_now_api, name='image_backup_now'),
    path('api/backup/image/status/', views.image_backup_status_api, name='image_backup_status'),

    # Export/Import
    path('api/export/csv/', views.export_csv, name='export_csv'),
    path('api/export/gpx/', views.export_gpx, name='export_gpx'),
    path('api/export/backup/', views.export_backup, name='export_backup'),
    path('api/import/csv/', views.import_csv, name='import_csv'),
    path('api/import/gpx/', views.import_gpx, name='import_gpx'),
    path('api/import/backup/', views.restore_backup, name='restore_backup'),

    # API Keys
    path('api/keys/create/', views.create_api_key, name='create_api_key'),
    path('api/keys/<int:key_id>/delete/', views.delete_api_key, name='delete_api_key'),
    
    # Devices
    path('api/devices/', views.devices_api, name='devices_api'),

    # Account
    path('api/locations/<int:location_id>/delete/', views.delete_location, name='delete_location'),
    path('api/account/delete-data/', views.delete_location_data, name='delete_location_data'),
    path('api/account/delete/', views.delete_account, name='delete_account'),

    # Profile
    path('api/profile/picture/', views.upload_profile_picture, name='upload_profile_picture'),
    path('api/profile/picture/delete/', views.delete_profile_picture, name='delete_profile_picture'),

    # PAL Pages
    path('pals/', views.pals_view, name='pals'),
    path('pals/<int:pal_id>/', views.pal_detail_view, name='pal_detail'),
    path('pal/<slug:slug>/', views.pal_public_view, name='pal_public'),

    # PAL API
    path('api/pals/', views.pals_api, name='pals_api'),
    path('api/pals/<int:pal_id>/', views.pal_detail_api, name='pal_detail_api'),
    path('api/pals/<int:pal_id>/update/', views.pal_update, name='pal_update'),
    path('api/pals/<int:pal_id>/delete/', views.pal_delete, name='pal_delete'),
    path('api/pals/<int:pal_id>/toggle-public/', views.pal_toggle_public, name='pal_toggle_public'),
    path('api/pals/<int:pal_id>/members/add/', views.pal_add_member, name='pal_add_member'),
    path('api/pals/<int:pal_id>/members/<int:user_id>/remove/', views.pal_remove_member, name='pal_remove_member'),
    path('api/pals/<int:pal_id>/locations/', views.pal_locations_api, name='pal_locations_api'),
    path('api/pals/<int:pal_id>/timeline/', views.pal_timeline_api, name='pal_timeline_api'),
    path('api/pals/<int:pal_id>/blurbs/create/', views.pal_create_blurb, name='pal_create_blurb'),
    path('api/pals/<int:pal_id>/blurbs/<int:blurb_id>/update/', views.pal_update_blurb, name='pal_update_blurb'),
    path('api/pals/<int:pal_id>/blurbs/<int:blurb_id>/delete/', views.pal_delete_blurb, name='pal_delete_blurb'),
    path('api/pals/<int:pal_id>/blurbs/<int:blurb_id>/comments/', views.pal_blurb_comments, name='pal_blurb_comments'),
    path('api/pals/<int:pal_id>/blurbs/<int:blurb_id>/comments/create/', views.pal_create_comment, name='pal_create_comment'),
    path('api/pals/<int:pal_id>/comments/<int:comment_id>/delete/', views.pal_delete_comment, name='pal_delete_comment'),
    path('api/pals/<int:pal_id>/milestones/create/', views.pal_create_milestone, name='pal_create_milestone'),
    path('api/pals/<int:pal_id>/milestones/<int:milestone_id>/delete/', views.pal_delete_milestone, name='pal_delete_milestone'),

    # Public PAL API
    path('api/pal/<slug:slug>/detail/', views.pal_public_detail_api, name='pal_public_detail_api'),
    path('api/pal/<slug:slug>/timeline/', views.pal_public_timeline_api, name='pal_public_timeline_api'),
    path('api/pal/<slug:slug>/locations/', views.pal_public_locations_api, name='pal_public_locations_api'),
    path('api/pal/<slug:slug>/blurbs/<int:blurb_id>/comments/', views.pal_public_comments_api, name='pal_public_comments_api'),
    path('api/pal/<slug:slug>/blurbs/<int:blurb_id>/comments/create/', views.pal_public_create_comment, name='pal_public_create_comment'),
]
