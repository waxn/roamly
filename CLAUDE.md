# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commits

**Make a commit after every meaningful change.** Each commit should be signed:

```
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
```

## Running the app

Everything runs inside Docker. Never run `python manage.py` directly on the host.

```bash
# Start (builds if needed)
sudo docker compose up -d --build

# Restart without rebuild (picks up template/static changes immediately)
sudo docker compose restart web

# View logs
sudo docker compose logs web --tail=50

# Run migrations after adding/changing models
sudo docker compose exec web python manage.py makemigrations
sudo docker compose exec web python manage.py migrate

# Django shell
sudo docker compose exec web python manage.py shell
```

Templates and `tracker/migrations/` are volume-mounted, so template edits are live without a rebuild. Python file changes (views.py, models.py, etc.) require a restart. Model changes require a new migration.

## Architecture

Single Django app (`tracker/`) inside the `roamly` project. No separate services or task queues — background work (geocoding, backups, POI download) runs in Python threads launched from views.

**Database:** SQLite by default (dev); PostgreSQL + PostGIS when `DATABASE_URL` is set. PostGIS enables vector tile generation and spatial queries. Code checks `HAS_POSTGIS` at runtime and falls back gracefully. The `Location` model has dual backends for this reason.

**Caching:** Redis when `REDIS_URL` is set, in-memory otherwise. Per-user cache generation keys (`cache_gen:{user_id}`) are incremented by `_bust_user_cache()` whenever location data changes, invalidating all cached API responses for that user.

**Auth:** Session-based for web. `ApiKeyAuthMiddleware` also accepts `Authorization: Bearer <key>` headers, allowing mobile apps (GPSLogger, OwnTracks) to push locations without a session.

## Key files

| File | Purpose |
|------|---------|
| `tracker/models.py` | All models — see below |
| `tracker/views.py` | Every view and API endpoint (~3900 lines) |
| `tracker/urls.py` | All URL patterns |
| `tracker/forms.py` | `SignUpForm`, `APIKeyForm`, `AdventureForm` |
| `tracker/middleware.py` | API key Bearer auth |
| `tracker/backup_tasks.py` | S3 backup logic (runs in threads) |
| `tracker/geocoding_tasks.py` | Nominatim reverse geocoding (runs in threads) |
| `tracker/poi_tasks.py` | OSM POI download (runs in threads) |
| `tracker/image_utils.py` | `resize_image`, `resize_photo` helpers |

## Models

**Core tracking:**
- `Device` — a tracked phone/device, belongs to a User
- `Location` — raw GPS point (lat, lon, altitude, accuracy, speed, battery, timestamp) + reverse-geocoded city/state/country
- `APIKey` — 64-char hex token for mobile push auth

**Adventures** (named journeys, formerly "Trips"):
- `Adventure` — time-bounded journey (device, creator, start_time, end_time, public_slug). `.locations` property filters Location by device + time range.
- `AdventurePlace` — named map pin within an adventure
- `AdventureMember` — shared access (roles: creator, member)
- `AdventureBlurb` — timeline post with optional photos and map location
- `AdventureBlurbPhoto` — photo attached to a blurb (max 5)
- `AdventureMilestone` — titled event with emoji and date
- `AdventureComment` — comment on a blurb

**Pals** — multi-user group trips where each member contributes their own location track. Same social structure as Adventures (PalMember, PalBlurb, PalBlurbPhoto, PalMilestone, PalComment).

**Background jobs:**
- `GeocodingJob` — tracks geocoding progress per user (one per user, replaced on restart)
- `POIDownloadJob` — tracks OSM POI download progress
- `BackupConfig` — S3-compatible backup configuration + status

**User:**
- `UserProfile` — profile picture; created via `get_or_create` in `settings_view`
- `POI` — locally cached OpenStreetMap points of interest

## URL / API conventions

Adventure API URLs still use `/api/trips/` paths (kept for backward compat with existing clients). The view functions are also still named `trips_api`, `trip_detail`, etc. internally — only the UI and model classes use "adventure" naming.

Public adventure pages use `/adventure/<slug>/`; public API uses `/api/trip/<slug>/`.

## Map rendering

The main map (`/map/`) has two rendering modes selected by the `roamly_map_renderer` localStorage key:

- **Vector tile mode** (PostGIS only): `/api/tiles/<z>/<x>/<y>.pbf` — fast for large datasets
- **Classic mode** (SQLite/fallback): fetches GeoJSON from `/api/track/`, decimated to a configurable point limit

Detail points (individual GPS dots) load progressively via `/api/locations/` as the user pans/zooms, bounded by viewport bbox. Loaded points accumulate in a client-side `accMap` (deduped by `"deviceId:timestamp"` key).

The adventure map loads all points at once from `/api/trips/<id>/` (capped at 30k). Adventures under 20k points show all dots immediately; larger ones show heatmap-first with dots appearing on zoom.

## User-facing preferences

All map display preferences are stored in `localStorage` with `roamly_` prefix:

| Key | Values | Default |
|-----|--------|---------|
| `roamly_theme` | dark / light | dark |
| `roamly_speed_unit` | kmh / mph | kmh |
| `roamly_map_renderer` | vector / classic | vector |
| `roamly_speed_gradient` | on / off | on |
| `roamly_connect_lines` | on / off | off |
| `roamly_show_heatmap` | on / off | on |
| `roamly_heatmap_intensity` | low / medium / high | medium |
| `roamly_dot_size` | small / medium / large | medium |
| `roamly_line_gap_minutes` | 5 / 10 / 20 / 60 | 20 |
| `roamly_detail_zoom` | 6 / 8 / 10 / 12 | 8 |
| `roamly_point_limit` | 2000–25000 | 5000 |
| `roamly_cluster_radius` | 30 / 50 / 80 | 50 |
| `roamly_default_time_range` | hours or 'all' | 24 |

## Import formats

`/api/import/csv/` — broad column alias matching (Garmin, Strava, GPSLogger, generic)
`/api/import/gpx/` — GPX 1.0 and 1.1, handles namespace detection
`/api/import/json/` — Google Takeout Location History (`{locations:[...]}`) and OwnTracks array format

Helper functions in `views.py`: `_get_csv_field`, `_parse_timestamp`, `_safe_float`.

## Migration notes

The `Adventure` model was renamed from `Trip` in migration `0002_rename_trip_to_adventure`. FK fields within related models (`adventure` replacing `trip`) were renamed in `0010_rename_trip_fk_to_adventure`. Both depend on `0009_trip_creator_trip_public_slug_tripblurb_and_more` which created the original social models.

When adding new models or fields, create and commit the migration file — it lives in `tracker/migrations/` which is volume-mounted into the container.
