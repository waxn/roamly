# Key files

_Read when: Locating which file owns a feature._

## Key files

| File | Purpose |
|------|---------|
| `tracker/models.py` | All models |
| `tracker/views.py` | Every view and API endpoint (~19,000 lines) |
| `tracker/appearance.py` | Settings → Appearance registry: palettes, components, shapes, type, backgrounds, extras, presets + `sanitize` |
| `tracker/static/tracker/styles/` | The appearance layers: `palettes/`, `components/`, `backgrounds/`, `facets.css`, `extras.css/.js` |
| `tracker/urls.py` | All URL patterns |
| `tracker/forms.py` | `SignUpForm`, `APIKeyForm`, `AdventureForm` |
| `tracker/middleware.py` | API key Bearer auth |
| `tracker/backup_tasks.py` | S3 backup logic (runs in threads) |
| `tracker/geocoding_tasks.py` | Background geocode worker + `ensure_auto_geocode` |
| `tracker/offline_geocode.py` | `local_reverse_geocode` (TIGER point-in-polygon) + `offline_reverse_geocode` (intl nearest-city + timezone border check) |
| `tracker/data/places.json.gz` | Border-check dataset (GeoNames cities500 + zone→country), built by `scripts/build_cities.py` |
| `tracker/regeocode_tasks.py` | Relabel stored locations with the current geocoder (changed rows only): Settings job + `manage.py regeocode` |
| `tracker/geoip_utils.py` | Offline IP → country resolution (`geoip2fast`) for the admin Monitoring country breakdown |
| `tracker/management/commands/import_boundaries.py` | Load US Census TIGER boundaries; `--regeocode` relabels all points |
| `tracker/poi_tasks.py` | OSM POI download (runs in threads) |
| `tracker/transport_tasks.py` | Transport-mode detection (journey segmentation + classify) |
| `tracker/roads.py` | Road snapping + routing; local/Mapbox/OSRM providers |
| `tracker/overpass.py` | The only place that talks to Overpass — endpoint pool, failover, failure classification |
| `tracker/road_download_tasks.py` | Overpass road download into `RoadSegment` |
| `tracker/rail_download_tasks.py` | Overpass subway download into `RailSegment`/`RailStation` |
| `tracker/auto_download_tasks.py` | 6-hourly sweep: auto-starts road/subway/POI downloads |
| `tracker/rails.py` | Transfer-aware subway routing (reuses `roads.py` A\*) |
| `tracker/subway_gap_tasks.py` | Detect subway-explained GPS gaps |
| `tracker/editor_tasks.py` | Map editor operations: route, add, copy, trash, undo |
| `tracker/ai_tasks.py` | AI "Ask" — tool-call loop (`run_ask`, `TOOL_DISPATCH`) |
| `tracker/summary_email_tasks.py` | AI summary emails — hourly scheduler + per-period send |
| `tracker/ask_views.py` | Ask chat page endpoints: saved chats, the streamed send, map-card geometry |
| `tracker/ask_tools.py` | Read-only Ask tools (flights, adventures, stays, achievements, unvisited, search near) + flights/pins map cards |
| `tracker/ask_map.py` | Ask history map cards: validation, the user's own points for a card, full-map link |
| `tracker/ask_edits.py` | Ask proposals: the `propose_*` tools (draft only) and applying one on the user's Approve |
| `tracker/year_ago.py` | The header's "a year ago today" chip: one cached AI line about the same date last year |
| `tracker/journal_recap.py` | On-demand AI recap for the Journals side panel; nothing stored |
| `tracker/alert_tasks.py` | "No data received" tracking alerts — 15-min scheduler + per-user send |
| `tracker/log_writer.py` | Batched background writer for AccessLog/ActionLog |
| `tracker/log_cleanup_tasks.py` | Hourly log rollup + retention-pruning scheduler |
| `tracker/error_log_handler.py` | Logging handler that records 500s into ActionLog |
| `tracker/image_utils.py` | `resize_image`, `resize_photo` helpers |
| `tracker/dwell_utils.py` | `bridges_gap` — shared rule for crediting a tracking gap as time in place |
| `tracker/scheduler_utils.py` | `wait_for_app_registry` — makes an `apps.ready()`-started daemon wait out `apps.populate()` before its first query |
| `tracker/tz_utils.py` | Per-user local timezone from the latest fix; `aware_local` (DST-safe day bounds), `user_timezone` |
| `tracker/zepp_tasks.py` | Zepp (Amazfit) cloud sync — unofficial band_data API + 6-hourly scheduler |
| `tracker/overlap_tasks.py` | Hourly device-overlap detection (two devices recording the same track) + hide/unhide |
| `tracker/gapfill_tasks.py` | Hourly auto-fill of single missed fixes with a road-snapped inferred midpoint |
| `tracker/family_tasks.py` | Family Circle geofence enter/exit detection — event-driven + 15-min reconciliation sweep |
| `tracker/push_tasks.py` | Firebase Cloud Messaging send path for Family Circle place alerts |
| `tracker/models.py` → `HealthSample`/`HealthWorkout` | Health Connect data (see Health Connect above) |
| `tracker/activity_track.py` | Recorded-activity pipeline: clean, Kalman + RTS smooth, stats, Douglas-Peucker, history mirror rows |
| `tracker/basemaps.py` | Basemap catalog + instance config (keys, on/off, Mapbox Studio style, custom XYZ) for the map picker and Admin Panel → Basemaps |
| `tracker/cell_utils.py` | Carrier identification (name-first, MCC/MNC fallback) + tower-position estimation with a confidence verdict |
| `mobile/.../tracking/CellScanner.kt` | The only file in the app that touches telephony — reads cells per SIM and gates what is stored |
| `tracker/flight_lookup.py` | AeroDataBox flight lookup by number + date (Flights page auto-fill) |
| `tracker/opensky.py` | OpenSky Network client (OAuth2 client credentials, per-family credit tracking) + 3-hourly flight-track fetch sweep |
| `tracker/flight_detect.py` | Finds airport-to-airport hops in GPS history for the bell's "Did you fly…?" suggestions |
| `tracker/achievement_tasks.py` | Achievements scan: history pass, boundary/road coverage, places, badges + nightly scheduler |
| `tracker/achievement_defs.py` | Badge registry, tiers, XP and level table (pure Python) |
| `tracker/boundary_download_tasks.py` | geoBoundaries ADM1-3 download for countries with 7+ days |
| `tracker/trip_blog.py` | Trip blog logic: share-link gate, header/status (home city, fuzzed spots), feed payloads, photo EXIF |
| `tracker/trip_blog_views.py` | Trip blog member + viewer views (posts, chunked video, links, comments, subscribe) |
| `tracker/video_tasks.py` | Trip-blog video transcode (ffmpeg → 1080p H.264) + 10-min recovery sweep |
| `tracker/trip_digest_tasks.py` | Trip blog daily digest email scheduler |
| `tracker/airports.py` | Lazy lookup over the bundled OurAirports subset (`static/tracker/data/airports.json`, built by `scripts/build_airports.py`) |
| `trackerhw/` | Hardware tracker (ESP32-S3 + L76K GNSS) firmware and docs — see Hardware tracker below |
