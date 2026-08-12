from django.urls import path
from . import views

app_name = 'tracker'

urlpatterns = [
    # PWA
    path('sw.js', views.service_worker, name='service_worker'),

    # SEO
    path('robots.txt', views.robots_txt, name='robots_txt'),
    path('sitemap.xml', views.sitemap_xml, name='sitemap_xml'),

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
    path('adventures/', views.adventures_view, name='adventures'),
    path('journals/', views.journals_view, name='journals'),
    path('places/', views.places_view, name='places'),
    path('ask/', views.ask_view, name='ask'),
    path('settings/', views.settings_view, name='settings'),
    path('diagnostics/', views.diagnostics_view, name='diagnostics'),
    path('editor/', views.editor_view, name='editor'),

    # Auth
    path('login/', views.login_view, name='login'),
    path('signup/', views.signup_view, name='signup'),
    path('logout/', views.logout_view, name='logout'),
    path('verify/', views.verify_view, name='verify'),
    path('verify/resend/', views.verify_resend, name='verify_resend'),
    path('verify/totp/', views.verify_use_totp, name='verify_use_totp'),
    path('totp/', views.totp_verify_view, name='totp_verify'),
    path('totp/email/', views.totp_email_fallback, name='totp_email_fallback'),
    path('reset/', views.password_reset_request, name='password_reset'),
    path('reset/<uidb64>/<token>/', views.password_reset_confirm, name='password_reset_confirm'),
    path('email/unsubscribe/<str:token>/', views.email_unsubscribe_view, name='email_unsubscribe'),

    # Location API
    path('api/push/', views.push_location, name='push_location'),
    path('api/push/batch/', views.push_location_batch, name='push_location_batch'),
    path('api/mobile/version/', views.mobile_version_check, name='mobile_version_check'),
    path('api/mobile/apk/', views.mobile_download_apk, name='mobile_download_apk'),
    path('api/locations/', views.locations_api, name='locations_api'),
    path('api/track/', views.track_api, name='track_api'),
    path('api/locations/bounds/', views.locations_bounds_api, name='locations_bounds_api'),
    path('api/locations/geojson/', views.locations_geojson_api, name='locations_geojson'),
    path('api/tiles/<int:z>/<int:x>/<int:y>.pbf', views.vector_tile, name='vector_tile'),
    path('api/tiles/seed/', views.seed_tiles, name='seed_tiles'),

    # Stats API
    path('api/stats/', views.stats_api, name='stats_api'),
    path('api/stats/yearly/', views.yearly_overview_api, name='yearly_overview_api'),
    path('api/stats/recompute/', views.stats_recompute_api, name='stats_recompute'),
    path('api/stats/recompute/status/', views.stats_recompute_status_api, name='stats_recompute_status'),
    path('api/visits/', views.visits_api, name='visits_api'),
    path('api/countries/', views.countries_api, name='countries_api'),
    path('api/fog/', views.fog_api, name='fog_api'),
    path('api/distance/', views.distance_api, name='distance_api'),
    path('api/diagnostics/location/', views.location_diagnostics_api, name='location_diagnostics_api'),
    path('api/diagnostics/detail/', views.location_diagnostics_detail_api, name='location_diagnostics_detail_api'),
    path('api/diagnostics/flagged/', views.flagged_locations_api, name='flagged_locations_api'),
    path('api/diagnostics/flag-scan/', views.flag_scan_api, name='flag_scan_api'),
    path('api/locations/<int:location_id>/accept-flag/', views.accept_flag_api, name='accept_flag_api'),
    path('api/search/', views.search_api, name='search_api'),

    # AI Ask API
    path('api/ask/', views.ask_api, name='ask_api'),
    path('api/ask/test/', views.ask_test_api, name='ask_test_api'),
    path('api/profile/ai-config/', views.profile_ai_config_api, name='profile_ai_config'),
    path('api/profile/mapbox-token/', views.profile_mapbox_token_api, name='profile_mapbox_token'),
    path('api/profile/distance-unit/', views.profile_distance_unit_api, name='profile_distance_unit'),
    path('api/profile/summary-email/', views.profile_summary_email_api, name='profile_summary_email'),
    path('api/profile/intro-seen/', views.profile_intro_seen_api, name='profile_intro_seen'),

    # TOTP two-factor auth API
    path('api/profile/totp/', views.totp_status_api, name='totp_status'),
    path('api/profile/totp/setup/', views.totp_setup_api, name='totp_setup'),
    path('api/profile/totp/confirm/', views.totp_confirm_api, name='totp_confirm'),
    path('api/profile/totp/disable/', views.totp_disable_api, name='totp_disable'),
    path('api/profile/totp/backup-codes/regenerate/', views.totp_backup_codes_regenerate_api, name='totp_backup_regenerate'),

    # Custom Places API
    path('api/places/', views.places_api, name='places_api'),
    # Registered before <int:place_id> — a literal path would otherwise be fine,
    # but keeping suggestion routes together above the id routes avoids surprises.
    path('api/places/suggestions/', views.place_suggestions_api, name='place_suggestions'),
    path('api/places/suggestions/dismiss/', views.place_suggestion_dismiss_api, name='place_suggestion_dismiss'),
    path('api/places/<int:place_id>/', views.place_detail_api, name='place_detail_api'),
    path('api/places/<int:place_id>/points/', views.place_points_api, name='place_points_api'),
    path('api/places/<int:place_id>/update/', views.place_update, name='place_update'),
    path('api/places/<int:place_id>/delete/', views.place_delete, name='place_delete'),

    # Trips pages
    path('adventure/join/<str:token>/', views.trip_join_view, name='trip_join'),
    path('adventure/<slug:slug>/', views.adventure_public_view, name='adventure_public'),
    path('adventures/<int:trip_id>/edit/', views.adventure_edit_view, name='adventure_edit'),
    path('adventures/<int:trip_id>/preview/', views.adventure_preview_view, name='adventure_preview'),
    path('adventures/<int:trip_id>/plan/', views.adventure_plan_view, name='adventure_plan'),

    # Adventures API
    path('api/trips/', views.trips_api, name='trips_api'),
    path('api/trips/create/', views.create_trip, name='create_trip'),
    path('api/trips/<int:trip_id>/', views.trip_detail, name='trip_detail'),
    path('api/trips/<int:trip_id>/delete/', views.delete_trip, name='delete_trip'),
    path('api/trips/<int:trip_id>/update/', views.update_trip, name='update_trip'),
    path('api/trips/<int:trip_id>/places/create/', views.create_trip_place, name='create_trip_place'),
    path('api/trips/<int:trip_id>/places/<int:place_id>/update/', views.update_trip_place, name='update_trip_place'),
    path('api/trips/<int:trip_id>/places/<int:place_id>/delete/', views.delete_trip_place, name='delete_trip_place'),
    path('api/trips/<int:trip_id>/days/', views.trip_day_notes_list, name='trip_day_notes_list'),
    path('api/trips/<int:trip_id>/days/photos/<int:photo_id>/delete/', views.trip_day_note_photo_delete, name='trip_day_note_photo_delete'),
    path('api/trips/<int:trip_id>/days/<str:date_str>/create/', views.trip_day_note_create, name='trip_day_note_create'),
    path('api/trips/<int:trip_id>/days/note/<int:note_id>/', views.trip_day_note_save, name='trip_day_note_save'),
    path('api/trips/<int:trip_id>/days/note/<int:note_id>/photos/', views.trip_day_note_photos, name='trip_day_note_photos'),
    path('api/trips/<int:trip_id>/days/note/<int:note_id>/delete/', views.trip_day_note_delete, name='trip_day_note_delete'),
    path('api/trips/<int:trip_id>/plan/', views.trip_plan_list, name='trip_plan_list'),
    path('api/trips/<int:trip_id>/plan/create/', views.create_trip_plan_stop, name='create_trip_plan_stop'),
    path('api/trips/<int:trip_id>/plan/<int:stop_id>/update/', views.update_trip_plan_stop, name='update_trip_plan_stop'),
    path('api/trips/<int:trip_id>/plan/<int:stop_id>/delete/', views.delete_trip_plan_stop, name='delete_trip_plan_stop'),
    path('api/trips/<int:trip_id>/invite/', views.trip_invite_api, name='trip_invite'),
    path('api/trips/<int:trip_id>/invite/email/', views.trip_invite_email_api, name='trip_invite_email'),
    path('api/trips/<int:trip_id>/members/add/', views.trip_add_member, name='trip_add_member'),
    path('api/trips/<int:trip_id>/members/<int:user_id>/remove/', views.trip_remove_member, name='trip_remove_member'),
    path('api/trips/<int:trip_id>/toggle-public/', views.trip_toggle_public, name='trip_toggle_public'),
    path('api/trips/<int:trip_id>/timeline/', views.trip_timeline_api, name='trip_timeline_api'),
    path('api/trips/<int:trip_id>/body/', views.trip_update_body, name='trip_update_body'),
    path('api/trips/<int:trip_id>/cover/', views.trip_upload_cover, name='trip_upload_cover'),
    path('api/trips/<int:trip_id>/cover/delete/', views.trip_delete_cover, name='trip_delete_cover'),
    path('api/trips/<int:trip_id>/blurbs/create/', views.trip_create_blurb, name='trip_create_blurb'),
    path('api/trips/<int:trip_id>/blurbs/<int:blurb_id>/update/', views.trip_update_blurb, name='trip_update_blurb'),
    path('api/trips/<int:trip_id>/blurbs/<int:blurb_id>/delete/', views.trip_delete_blurb, name='trip_delete_blurb'),
    path('api/trips/<int:trip_id>/blurbs/<int:blurb_id>/comments/', views.trip_blurb_comments, name='trip_blurb_comments'),
    path('api/trips/<int:trip_id>/blurbs/<int:blurb_id>/comments/create/', views.trip_create_comment, name='trip_create_comment'),
    path('api/trips/<int:trip_id>/comments/<int:comment_id>/delete/', views.trip_delete_comment, name='trip_delete_comment'),
    path('api/trips/<int:trip_id>/milestones/create/', views.trip_create_milestone, name='trip_create_milestone'),
    path('api/trips/<int:trip_id>/milestones/<int:milestone_id>/delete/', views.trip_delete_milestone, name='trip_delete_milestone'),
    path('api/trips/<int:trip_id>/visits/', views.trip_visits_api, name='trip_visits_api'),
    path('api/trips/<int:trip_id>/preview-detail/', views.trip_public_preview_api, name='trip_public_preview_api'),
    # Public trip API
    path('api/trip/<slug:slug>/verify-pin/', views.trip_verify_pin, name='trip_verify_pin'),
    path('api/trip/<slug:slug>/detail/', views.trip_public_detail_api, name='trip_public_detail_api'),
    path('api/trip/<slug:slug>/timeline/', views.trip_public_timeline_api, name='trip_public_timeline_api'),
    path('api/trip/<slug:slug>/blurbs/<int:blurb_id>/comments/', views.trip_public_blurb_comments, name='trip_public_blurb_comments'),
    path('api/trip/<slug:slug>/blurbs/<int:blurb_id>/comments/create/', views.trip_public_create_comment, name='trip_public_create_comment'),

    # Journals API
    path('api/journals/', views.journals_list_api, name='journals_list_api'),
    path('api/journals/stats/', views.journal_stats_api, name='journal_stats_api'),
    path('api/journals/search/', views.journal_search_api, name='journal_search_api'),
    path('api/journals/photos/<int:photo_id>/delete/', views.journal_photo_delete_api, name='journal_photo_delete_api'),
    path('api/journals/<str:date_str>/', views.journal_detail_api, name='journal_detail_api'),
    path('api/journals/<str:date_str>/save/', views.journal_save_api, name='journal_save_api'),
    path('api/journals/<str:date_str>/delete/', views.journal_delete_api, name='journal_delete_api'),
    path('api/journals/<str:date_str>/photos/', views.journal_photos_api, name='journal_photos_api'),

    # Geocoding
    path('api/geocode/', views.geocode_api, name='geocode_api'),
    path('api/geocode/status/', views.geocode_status, name='geocode_status'),
    path('api/geocode/stop/', views.geocode_stop, name='geocode_stop'),

    # POI Download
    path('api/poi/download/', views.poi_download_api, name='poi_download'),
    path('api/poi/status/', views.poi_status_api, name='poi_status'),
    path('api/poi/stop/', views.poi_stop_api, name='poi_stop'),
    path('api/site/custom-js/', views.site_custom_js_api, name='site_custom_js'),
    path('api/site/contact-email/', views.site_contact_email_api, name='site_contact_email'),
    path('api/site/turnstile/', views.site_turnstile_api, name='site_turnstile'),
    path('api/contact/', views.contact_api, name='contact_api'),
    path('api/poi/match/', views.poi_match_api, name='poi_match'),
    path('api/poi/match/status/', views.poi_match_status_api, name='poi_match_status'),
    path('api/poi/match/stop/', views.poi_match_stop_api, name='poi_match_stop'),
    path('api/transport/detect/', views.transport_detect_api, name='transport_detect'),
    path('api/transport/detect/status/', views.transport_status_api, name='transport_status'),
    path('api/transport/detect/stop/', views.transport_stop_api, name='transport_stop'),
    path('api/transport/breakdown/', views.transport_breakdown_api, name='transport_breakdown'),

    # Roads & Editor API
    path('api/profile/roads-config/', views.profile_roads_config_api, name='profile_roads_config'),
    path('api/roads/snap/', views.roads_snap_api, name='roads_snap'),
    path('api/roads/download/', views.road_download_api, name='road_download'),
    path('api/roads/download/status/', views.road_download_status_api, name='road_download_status'),
    path('api/roads/download/stop/', views.road_download_stop_api, name='road_download_stop'),
    path('api/roads/delete/', views.road_data_delete_api, name='road_data_delete'),
    path('api/inferred/', views.inferred_locations_api, name='inferred_locations'),

    # Subway data + gap filling API
    path('api/subway/download/', views.subway_download_api, name='subway_download'),
    path('api/subway/download/status/', views.subway_download_status_api, name='subway_download_status'),
    path('api/subway/download/stop/', views.subway_download_stop_api, name='subway_download_stop'),
    path('api/subway/delete/', views.subway_data_delete_api, name='subway_data_delete'),
    path('api/subway/gaps/', views.subway_gaps_api, name='subway_gaps'),
    path('api/subway/gaps/fill/', views.subway_gap_fill_api, name='subway_gap_fill'),
    path('api/subway/gaps/dismiss/', views.subway_gap_dismiss_api, name='subway_gap_dismiss'),
    path('api/subway/fills/', views.subway_fills_api, name='subway_fills'),
    path('api/subway/fills/<int:batch_id>/delete/', views.subway_fill_delete_api, name='subway_fill_delete'),

    # Map Editor API
    path('api/editor/delete/', views.editor_delete_api, name='editor_delete'),
    path('api/editor/route/', views.editor_route_api, name='editor_route'),
    path('api/editor/route/commit/', views.editor_route_commit_api, name='editor_route_commit'),
    path('api/editor/points/add/', views.editor_add_api, name='editor_add'),
    path('api/editor/points/<int:location_id>/move/', views.editor_move_api, name='editor_move'),
    path('api/editor/copy/preview/', views.editor_copy_preview_api, name='editor_copy_preview'),
    path('api/editor/copy/', views.editor_copy_api, name='editor_copy'),
    path('api/editor/batches/', views.editor_batches_api, name='editor_batches'),
    path('api/editor/batches/<int:batch_id>/undo/', views.editor_batch_undo_api, name='editor_batch_undo'),
    path('api/trash/', views.trash_api, name='trash_api'),
    path('api/trash/restore/', views.trash_restore_api, name='trash_restore'),
    path('api/trash/empty/', views.trash_empty_api, name='trash_empty'),

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
    path('api/export/backup/start/', views.export_backup_start, name='export_backup_start'),
    path('api/export/backup/status/<str:job_id>/', views.export_backup_status, name='export_backup_status'),
    path('api/export/backup/download/<str:job_id>/', views.export_backup_download, name='export_backup_download'),
    path('api/import/csv/', views.import_csv, name='import_csv'),
    path('api/import/gpx/', views.import_gpx, name='import_gpx'),
    path('api/import/json/', views.import_json, name='import_json'),
    path('api/import/kml/', views.import_kml, name='import_kml'),
    path('api/import/backup/', views.restore_backup, name='restore_backup'),

    # API Keys
    path('api/keys/app/', views.app_api_key, name='app_api_key'),
    path('api/keys/create/', views.create_api_key, name='create_api_key'),
    path('api/keys/<int:key_id>/delete/', views.delete_api_key, name='delete_api_key'),
    path('api/keys/<int:key_id>/rename/', views.rename_api_key, name='rename_api_key'),
    
    # Devices
    path('api/devices/', views.devices_api, name='devices_api'),

    # Account
    path('api/locations/<int:location_id>/delete/', views.delete_location, name='delete_location'),
    path('api/account/delete-data/', views.delete_location_data, name='delete_location_data'),
    path('api/account/delete/', views.delete_account, name='delete_account'),

    # Profile
    path('api/profile/picture/', views.upload_profile_picture, name='upload_profile_picture'),
    path('api/profile/picture/delete/', views.delete_profile_picture, name='delete_profile_picture'),

    # Admin panel
    path('admin-panel/', views.admin_panel_view, name='admin_panel'),
    path('api/admin/users/', views.admin_users_api, name='admin_users_api'),
    path('api/admin/users/<int:user_id>/toggle-admin/', views.admin_toggle_admin_api, name='admin_toggle_admin'),
    path('api/admin/users/<int:user_id>/delete/', views.admin_delete_user_api, name='admin_delete_user'),
    # Logging & monitoring
    path('api/admin/overview/', views.admin_overview_api, name='admin_overview'),
    path('api/admin/access-logs/', views.admin_access_logs_api, name='admin_access_logs'),
    path('api/admin/action-logs/', views.admin_action_logs_api, name='admin_action_logs'),
    path('api/admin/log-config/', views.admin_log_config_api, name='admin_log_config'),
]
