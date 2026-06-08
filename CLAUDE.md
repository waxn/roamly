# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Versioning

This project uses [Semantic Versioning](https://semver.org/) via git tags (`v1.2.3`). The tag is what triggers the Docker Hub publish in CI.

| Change type | Version bump | Example |
|---|---|---|
| Bug fix, security patch, copy/style tweak | patch (`1.2.x`) | `v1.2.4` |
| New feature, new model/field, new endpoint | minor (`1.x.0`) | `v1.3.0`) |
| Breaking change to backup format, API contract, or migration that can't roll back | major (`x.0.0`) | `v2.0.0` |

After finishing a set of changes, create and push the tag:

```bash
git tag v1.2.3
git push origin v1.2.3
```

The backup JSON format version (in `backup_tasks.py`) must also be bumped whenever the backup schema changes.

## Commits

**Every individual fix or change gets its own commit — no batching.** This includes bug fixes, single-file edits, and CSS tweaks. Each commit must be signed:

```
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
```

Also update this CLAUDE.md whenever something architectural or behavioural is added or changed.

## Running the app

Everything runs inside Docker. Never run `python manage.py` directly on the host. Do not attempt to run `docker compose` or `sudo docker compose` commands — these require a password and an interactive terminal. If a restart or migration is needed, tell the user to run it themselves.

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

**Geocoding is offline, and never on the push path.** `push_location` (`/api/push/`) creates points with `city=''` and returns immediately; labelling happens in a background thread. Two reasons it's off the request path: (1) a blocking reverse-geocode per point stalled uploads past the mobile client timeout and wedged WorkManager backoff, causing multi-minute-to-hour delivery gaps even screen-on; (2) the public OSM **Nominatim blocks the IP of a continuous tracker** (HTTP 429 on every call) — a self-hosted tracker pushing nonstop is exactly what its usage policy forbids, so a 40k+ backlog could never clear. Reverse geocoding is therefore done **offline**: `tracker/offline_geocode.py` (`offline_reverse_geocode`) matches each point to the nearest city in the bundled-GeoNames `reverse_geocode` package — no network, no rate limit, no 429 — at the same city/state/country granularity Roamly stores (Nominatim was only ever queried at zoom=10). The `geocoding_tasks._geocode_worker` drains the whole `city=''` backlog in instant chunks (no per-cluster sleep/clustering), groups ids by resulting label (one UPDATE per place), and busts the user cache after each chunk. It's kicked fire-and-forget from `push_location` via `ensure_auto_geocode(user_id)` (debounced per user, no-op while a geocode thread — manual or auto — already runs) and still runs on demand from Settings. The old Nominatim `reverse_geocode()` in `views.py` is retained, unused, as an optional fallback. **Adding the offline package is a new dependency → needs `docker compose up -d --build`, not just a restart.**

**Database:** SQLite by default (dev); PostgreSQL + PostGIS when `DATABASE_URL` is set. PostGIS enables vector tile generation and spatial queries. Code checks `HAS_POSTGIS` at runtime and falls back gracefully. The `Location` model has dual backends for this reason.

**Caching:** Redis when `REDIS_URL` is set, in-memory otherwise. Per-user cache generation keys (`cache_gen:{user_id}`) are incremented by `_bust_user_cache()` whenever location data changes, invalidating all cached API responses for that user.

**Response compression:** `django.middleware.gzip.GZipMiddleware` (first after SecurityMiddleware) gzips all dynamic HTML/JSON responses. WhiteNoise serves its own pre-compressed static assets and sets `Content-Encoding`, so GZipMiddleware skips those (no double-compression). The ~60KB landing HTML drops to ~8KB on the wire.

**Landing page performance:** `landing_view` is tuned for the fastest possible cold load (it's the public entry point). The three hero stats are read from the `site_stats` cache key (1h TTL, also primed by `_refresh_site_stats`) so the hot path never hits the DB; only a cache miss does a `SiteStat` lookup. Anonymous responses get `Cache-Control: public, max-age=300, stale-while-revalidate=86400` + `Vary: Cookie, Accept-Encoding` so browsers/CDNs serve repeat visits instantly without leaking the logged-in nav variant.

The template (`landing.html`) is optimised against PageSpeed/Lighthouse:
- **Fonts are self-hosted** — `tracker/static/tracker/fonts/{nunito,syne}-latin-var.woff2` (variable, latin subset; one file per family covers the whole weight range). `@font-face` rules live in the inline `<style>`; the two files are `<link rel="preload" as="font" crossorigin>`-ed. This removes the third-party `fonts.googleapis.com`/`fonts.gstatic.com` request chain entirely (no render-blocking CSS, no extra DNS/TLS, LCP font discoverable immediately), and WhiteNoise serves them with a 1-year immutable cache. **Because of `CompressedManifestStaticFilesStorage`, new font files need `collectstatic` before `{% static %}` can resolve them — otherwise the page 500s on a missing manifest entry.**
- **All CSS is inline and minified** — zero render-blocking stylesheet requests. The inline `<style>` block is minified (~39% smaller); edit it knowing it's a single line. (If you need to hand-edit heavily, re-minify after.)
- **No non-composited animations** — the hero route is statically drawn (the old `stroke-dashoffset` draw couldn't be GPU-composited); only `transform`/`opacity` animations remain. The background `<canvas>` is gated behind `prefers-reduced-motion` and started via `requestIdleCallback`. The hero-tilt handler caches `getBoundingClientRect` (reads on enter / invalidates on scroll+resize) so `mousemove` only writes `transform` — no forced reflow.
- **Accessibility** — `--text-dim` is `#7c88a6` (~5.2:1 on `--bg`, was failing AA); heading order is strictly h1→h2→h3 (setup-step titles are `h3`, not `h4`).

**Auth:** Session-based for web. `ApiKeyAuthMiddleware` also accepts `Authorization: Bearer <key>` headers, allowing mobile apps (GPSLogger, OwnTracks) to push locations without a session.

**Mobile branding:** The launcher icon is an adaptive icon (`res/mipmap-anydpi-v26/`) — the white Roamly mark (`drawable/roamly_favicon.png`) on a **black** background (`drawable/ic_launcher_background.xml`); the mark is white line-art so it needs the dark backdrop to be visible. The app theme's `android:windowBackground` is black to kill the white cold-start flash. On launch, `ui/SplashScreen.kt` shows an animated opening screen (the mark springs in over a pulsing coral halo + a rotating gradient ring, then the wordmark rises); `RoamlyNavHost` plays it until both a minimum intro time has elapsed and `isLoggedIn` has resolved, then reveals Map/Login with no spinner.

**Mobile tracking:** The Android app (`mobile/`, Kotlin + Jetpack Compose) uses a claymorphism design system (`ui/theme/Theme.kt` + `ui/theme/Clay.kt`: soft dual-shadow surfaces, gradient accents, puffy press-animated buttons). Login is **session-based** — logging in stores the session cookie and does **not** create or require an API key. A device id is auto-assigned at login. The API key is needed **only for tracking** (it authenticates location uploads) and is set up lazily: when the user starts tracking without one, Settings prompts them to create it in-app (idempotent `POST /api/keys/app/`, which returns the account's single existing active key or mints one named "Roamly Android") or to paste an existing key. There is **just one** key and it works forever (keys don't expire), so re-logging-in never spawns duplicates. `ApiKeyAuthMiddleware` authenticates the Bearer key on every endpoint, so once a key exists the app also stays usable via the key even if the session cookie lapses. `isLoggedIn` (in `AuthViewModel`) keys off server URL + session id.

The location tracker (`tracking/LocationTrackingService.kt`) is a foreground service that survives reboots and OS kills via a durable `trackingEnabled` intent flag (distinct from the runtime `trackingActive` flag), START_STICKY, the boot receiver, and a watchdog coroutine that re-arms location updates if fixes stall under Doze/battery saver. It holds a `PARTIAL_WAKE_LOCK` while actively tracking (released on pause/stop/destroy) so the CPU doesn't sleep between fixes screen-off — an app exempt from battery optimization may hold it through Doze. Tracking knobs (interval, GPS priority auto/high/balanced/low, min movement distance, max accuracy) are observed as a flow and applied to the running request live. **Delivery is time-based, not movement-gated:** the `LocationRequest` sets `minUpdateDistanceMeters(0)` (and no extra batching / no wait-for-accurate) so a fix arrives every interval regardless of movement — setting it to the min-movement distance previously made FusedLocation deliver *nothing* while stopped/slow, causing multi-minute gaps in the track even with the app open. De-dup instead lives in `LocationFilter`, whose min-distance drop is overridden once `minTimeBetweenMs` (= the interval) has elapsed, so a stationary user still logs one point per interval. Points are cached in Room and uploaded by `UploadWorker` (offline-first; `scheduleNow(replace=true)` for user-initiated syncs so a job stuck in retry-backoff doesn't swallow the tap); a local CSV mirror is also written. **Wedged-backoff recovery:** automatic point-driven syncs enqueue with KEEP, so a failed `UploadWorker` run that lands in WorkManager's exponential backoff would otherwise no-op every later schedule and starve uploads for up to an hour (a cause of server-side delivery gaps even screen-on). `savePoint` watches the unsynced backlog and, once it exceeds `UPLOAD_STUCK_THRESHOLD` (~40 points ≈ 6+ min at a 10s interval, vs. a healthy 0–10), escalates that cycle's `scheduleNow` to `replace=true` to cancel the stuck job and run fresh — gated on the 60s time threshold so it recurs at most once per cycle and never interrupts a healthy in-flight flush.

**Tracking survivability stack** — defence-in-depth so tracking keeps running through OS kills, Doze, reboots and OEM battery managers:
1. *Triple-defensive `startForeground`* — `goForeground()` is called three times in `onStartCommand` (and on boot) so the FGS always beats Android 8+'s 5-second start deadline.
2. *START_STICKY + persisted resume* — on a null-intent recreation after a low-memory kill the service reads `prefs.trackingEnabled` (survives process death) and resumes, or stops if the user had turned it off.
3. *Self-resurrection* — `receiver/RestarterReceiver` (a standalone `@AndroidEntryPoint` receiver, action `com.roamly.RESTART_TRACKING`) restarts the service; the service broadcasts to it from `onDestroy` and `onTaskRemoved` when it's torn down while still enabled.
4. *Doze-piercing heartbeat* — `receiver/TrackingAlarmReceiver` uses `setExactAndAllowWhileIdle` + `ELAPSED_REALTIME_WAKEUP` (falls back to inexact `setAndAllowWhileIdle` when exact-alarm permission is denied) on a 15-min self-rescheduling loop that re-checks the service is alive even in deep Doze. Exact-alarm broadcasts are an FGS-start exemption, so this is the reliable restart path.
5. *Boot + app-update* — `receiver/BootReceiver` handles `BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`/QUICKBOOT and arms the heartbeat.
6. *Battery-optimization exemption* — Settings → Reliability fires the direct `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog (falls back to the settings list). Being allowlisted also lets the app start the FGS from the background.
7. *OEM-killer onboarding* — Settings → Reliability links to `dontkillmyapp.com/<manufacturer>` for the per-OEM allowlist steps Xiaomi/Samsung/Huawei/etc. require (no API exists for these).
8. *Multi-provider re-arm* — FusedLocation already fuses GPS + network + passive; a dynamically-registered `PROVIDERS_CHANGED` receiver in the service re-arms the request immediately when providers toggle. `LocationTrackingService.start()/stop()` are wrapped in `runCatching` so a background FGS-start blocked on Android 12+ degrades to a heartbeat retry instead of crashing.

**Stopping cleanly (two durable flags).** `trackingEnabled` = "tracking is supposed to be running now" (gates START_STICKY null-intent resume, the heartbeat, the restarter, and app-launch `resumeTrackingIfNeeded`). `autoStartTracking` = the user-facing **"Start tracking on boot"** option (the *only* thing `BootReceiver`/`MY_PACKAGE_REPLACED` keys on, so it works even while tracking is currently off — toggle it on while stopped and the next reboot starts tracking). Both are cleared by a **full stop**: `TrackingCoordinator.stopCompletely()` clears `trackingEnabled` + `autoStartTracking` + `trackingActive`, cancels the heartbeat alarm and the periodic `UploadWorker`, kicks one final flush upload, and stops the service. After a full stop nothing survives a reboot. The Stop button is gated behind a confirmation dialog in **both** places: the in-app Settings button, and the notification's Stop action (which opens `MainActivity` with `EXTRA_CONFIRM_STOP` — `MainActivity` is `singleTop` and shows the same dialog via `onNewIntent`). Starting tracking sets `autoStartTracking=true` by default (survives reboot); users can turn the boot toggle back off for a start-now-only session. `RoamlyApp.onCreate` only schedules the periodic uploader when tracking is enabled or set to start on boot, so a stopped app does no recurring background work.

**Mobile caching:** `data/cache/DiskCache.kt` is a Gson-backed JSON file cache (under `cacheDir/api_cache`) that persists the last successful response per screen so cold starts and offline opens paint last-known data instantly and refresh in the background, instead of showing an empty skeleton. Wired into the Stats, Adventures, Pals, and Journals view models. The cache is wiped on sign-out (`disk.clearAll()` in both logout paths). The process-lived `StatsCache` is still the fast warm-cache layer; `DiskCache` is the cold-start fallback beneath it.

**Mobile local-first location store:** The map and "have I been here?" no longer hit the network per screen — the full location history is mirrored on-device and queried locally. `data/local/LocationStore.kt` (singleton) backs a Room table (`synced_locations`, keyed by the server's location id so re-syncing is a dedup upsert) in `TrackingDatabase` (v2; a real `MIGRATION_1_2` adds the table without wiping the `cached_points` upload queue). Sync is **incremental**: it pulls only points newer than the newest local one, paging ascending via the locations API's existing cursor (`sort_dir=asc` + `before_value`/`before_id`, plus the `has_more`/`next_before_value`/`next_before_id` response fields). `sync()` is throttled and runs on app foreground (`MainActivity.onResume`) and on `MapViewModel` init. The map paints instantly from the store: a decimated period overview (`id % stride` sampling keeps the heatmap fast) plus full-resolution viewport dots pulled from the local DB on pan/zoom (no per-move request). "Have I been here?" scans the **entire** local history around the user — instant and complete, fixing the old under-count that came from a server result-set limit. The store is wiped on sign-out alongside the disk cache.

**Mobile feature parity:** The app mirrors the web's core consumer features — Map (time periods, heatmap, "have I been here?"), Adventures, Pals, Stats (with yearly overview + top places), Journals, Settings, and history Search (`ui/search/`, hits `/api/search/` — text queries return named POI matches that focus the map plus the days you visited a matching city/state/place). Journals (`ui/journals/`) are a bottom-nav destination: a Monday-first month calendar (entry days show the mood emoji / favorite star / photo dot), a recent-entries list, streak/lifetime stats, and a full-screen editor (title, mood/weather emoji pickers, favorite toggle, debounced-autosave body, Coil-loaded photo grid with picker upload, and that day's GPS track drawn on a Compose Canvas — no map tiles). Power-user/admin features (data table, import/export, backups, geocoding/POI jobs, API-key CRUD, profile pictures) remain web-only.

**Backups (data + media).** Two complementary backups, both per-user. The **data backup** is one JSON document (`meta.version` = **4**) covering every model that holds user-authored content: devices, locations, **adventures** (with `subtitle`/`access_pin`/cover-image names/the CMS `body` blocks + nested places, members, blurbs (+photo metadata), milestones, comments), **pals** (same social structure), **journals** (`JournalEntry` + photo metadata), and api_keys. Derived/cacheable data (Visit/VisitJob, POI, SiteStat) is deliberately excluded — it regenerates. There is **one schema** built two ways and they must stay identical: `backup_tasks._build_backup_json` builds it in memory for the **automatic S3 backup**; `views._write_backup_json` streams the same shape to disk for the **"Download Backup"** file (locations are streamed row-by-row to avoid OOM; everything else reuses the shared `_build_adventures_data`/`_build_pals_data`/`_build_journals_data` helpers). When you add a user-content model/field, extend the shared helper(s), bump `meta.version` in **both** builders, and teach `views.restore_backup` to read it. `restore_backup` accepts every version: it reads the rich nested `adventures`/`journals` keys (v4+) and still falls back to the legacy flat `trips`/`trip_places` keys (v2/v3 downloads). Restores are idempotent (`get_or_create` keyed on natural keys; social children only created on freshly-created parents to avoid dupes). The **image backup** (`_get_user_media_files` → S3) ships the actual image files the JSON only names: profile pictures, adventure covers, adventure/pal blurb photos, and journal photos. JSON stores `ImageField.name` paths only — the files travel via the image backup, so a full restore is "load JSON, then restore media."

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
- `Adventure` — time-bounded journey (device, creator, start_time, end_time, public_slug, subtitle, cover_image, cover_image_thumbnail, body). `body` is a JSONField storing an ordered list of typed blocks (heading, paragraph, map_embed, photo_grid, divider, callout, location_card). `.locations` property filters Location by device + time range.
- `AdventurePlace` — named map pin within an adventure; referenced in paragraph blocks via `[^pin:ID]` inline refs
- `AdventureMember` — shared access (roles: creator, member)
- `AdventureBlurb` — map pin with a note (latitude/longitude required for pin display); rendered as coral map markers, not as timeline cards
- `AdventureBlurbPhoto` — photo attached to a blurb (max 5)
- `AdventureMilestone` — titled event with emoji and date
- `AdventureComment` — comment on a blurb

**Pals** — multi-user group trips where each member contributes their own location track. Same social structure as Adventures (PalMember, PalBlurb, PalBlurbPhoto, PalMilestone, PalComment).

**Journals** (DayOne-style daily journaling with an on-this-day map):
- `JournalEntry` — one entry per user per calendar day (`unique_together = user, date`). Fields: title, body (plain text), mood (emoji), weather, is_favorite, optional pin (pin_latitude/pin_longitude/location_name), created/updated. The "where you went that day" map is **not** stored — it's derived on the fly from the user's `Location` points for that date (`_journal_day_track` in `views.py` returns a decimated track, total distance, the distinct cities/places visited, and a centroid).
- `JournalPhoto` — photo attached to an entry (image + thumbnail via `resize_photo`, caption, order; max 20 per entry).

Streaks (`_journal_compute_streaks`: current run ending today/yesterday + longest) and lifetime totals (entries, words, photos, this-year/-month) are computed from the set of entry dates. The page (`journals.html`) is a Monday-first month calendar (entry days are highlighted and show the mood emoji / favorite star / photo dot), a recent-entries list, and a two-pane editor **modal**: left = title + mood picker + weather + autosaving body (debounced `PATCH`-style upsert) + photo grid; right = a MapLibre map of that day's track (teal line, blue start dot, coral end dot) with distance/places/points overlays. All journal endpoints live under `/api/journals/`; the `<str:date_str>` detail route is registered **after** `stats/` and `photos/<id>/delete/` so those concrete routes aren't shadowed. The photo-upload endpoint (`journal_photos_api`) is `@csrf_exempt` like the rest of the app-facing POST API so the mobile app can multipart-upload with Bearer/session auth. Journals are now in the mobile app too (`ui/journals/`, see Mobile feature parity).

**Background jobs:**
- `GeocodingJob` — tracks geocoding progress per user (one per user, replaced on restart)
- `POIDownloadJob` — tracks OSM POI download progress
- `BackupConfig` — S3-compatible backup configuration + status

**User:**
- `UserProfile` — profile picture; created via `get_or_create` in `settings_view`
- `POI` — locally cached OpenStreetMap points of interest

## URL / API conventions

Adventure API URLs still use `/api/trips/` paths (kept for backward compat with existing clients). The view functions are also still named `trips_api`, `trip_detail`, etc. internally — only the UI and model classes use "adventure" naming.

Public adventure pages use `/adventure/<slug>/` (renders `adventure_public.html`); public API uses `/api/trip/<slug>/`.

Adventure CMS editor lives at `/adventures/<id>/edit/` (requires login + membership). New API endpoints added:
- `PATCH /api/trips/<id>/body/` — save document body JSON (also accepts `name`, `subtitle`)
- `POST /api/trips/<id>/cover/` — upload cover image
- `POST /api/trips/<id>/cover/delete/` — remove cover image
- `POST /api/trips/<id>/blurbs/<id>/update/` — edit a blurb (text, lat/lng, location_name)

Inline pin refs: `[^pin:42]` in paragraph block text references an `AdventurePlace` by id. Rendered as numbered superscript badges at display time; number computed from document order, not stored in DB.

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

## Visit time spent

`/api/visits/` and trip-scoped visit stats sum gaps between consecutive geocoded points, attributed to the earlier point's city/state/country. If the next point has the **same** place label, the full gap counts (e.g. 1pm→6pm in one town = 5h). If the label **changes**, the gap credited to the previous place is capped at 1 hour (`_visits_dwell_gap` in `views.py`).

## Import formats

`/api/import/csv/` — broad column alias matching (Garmin, Strava, GPSLogger, generic)
`/api/import/gpx/` — GPX 1.0 and 1.1, handles namespace detection
`/api/import/json/` — Google Takeout Location History (`{locations:[...]}`) and OwnTracks array format

Helper functions in `views.py`: `_get_csv_field`, `_parse_timestamp`, `_safe_float`.

## Migration notes

The `Adventure` model was renamed from `Trip` in migration `0002_rename_trip_to_adventure`. FK fields within related models (`adventure` replacing `trip`) were renamed in `0010_rename_trip_fk_to_adventure`. Both depend on `0009_trip_creator_trip_public_slug_tripblurb_and_more` which created the original social models.

When adding new models or fields, create and commit the migration file — it lives in `tracker/migrations/` which is volume-mounted into the container.
