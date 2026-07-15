# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Collaboration

**When in doubt, ask.** If there is any ambiguity about what is wanted — scope, approach, which file to edit, whether a change is intentional — ask before proceeding. Never make a judgment call silently when a quick question would resolve it.

## Versioning

This project uses [Semantic Versioning](https://semver.org/) via git tags (`v1.2.3`). The tag triggers Docker Hub publish in CI.

| Change type | Version bump | Example |
|---|---|---|
| Bug fix, security patch, copy/style tweak | patch (`1.2.x`) | `v1.2.4` |
| New feature, new model/field, new endpoint | minor (`1.x.0`) | `v1.3.0` |
| Breaking change to backup format, API contract, or non-rollback migration | major (`x.0.0`) | `v2.0.0` |

After finishing a set of changes: `git tag v1.2.3 && git push origin v1.2.3`

The backup JSON format version (in `backup_tasks.py`) must also be bumped whenever the backup schema changes.

## Commits

**Every individual fix or change gets its own commit — no batching.** Commit messages must be **properly capitalized** — capital letter after the `type(scope):` prefix (e.g. `fix(ai): Correct displayed URL`). Each commit must be signed:

```
Co-Authored-By: Claude <model> <noreply@anthropic.com>
```

Use the model that actually wrote the commit (e.g. `Claude Opus 4.8`), not a fixed name.

Also update this CLAUDE.md whenever something architectural or behavioural is added or changed.

## Running the app

Everything runs inside Docker. Never run `python manage.py` directly on the host. Do not attempt to run `docker compose` or `sudo docker compose` commands — these require a password and an interactive terminal. Tell the user to run these themselves.

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

Templates and `tracker/migrations/` are volume-mounted — template edits are live without a rebuild. Python file changes require a restart. Model changes require a new migration.

## Architecture

Single Django app (`tracker/`) inside the `roamly` project. No separate services — background work (geocoding, backups, POI download) runs in Python threads launched from views.

**Geocoding is local, never on the push path.** `push_location` (`/api/push/`) creates points with `city=''` and returns immediately; labelling happens in a background thread. Reverse geocoding lives in `tracker/offline_geocode.py`:

- **`local_reverse_geocode(lat, lon)`** — for US points, does **point-in-polygon** against the `Boundary` table (PostGIS `MultiPolygonField`, GiST-indexed): incorporated places / CDPs first (`kind='place'`), then county subdivisions (`kind='cousub'`), so a point resolves to the town that *contains* it, not nearest centroid.
- **`offline_reverse_geocode(coords)`** — international / no-US-match fallback: nearest city from the bundled-GeoNames `reverse_geocode` package (numpy/scipy), no network.

Boundaries are loaded by **`import_boundaries`** (`tracker/management/commands/`): downloads US Census TIGER shapefiles (`cb_<year>_<fips>_{place,cousub}_500k.zip`) per state, transforms to 4326, `bulk_create`s into `Boundary`. `--states ME,VA` limits states; `--regeocode` relabels all stored locations (caches one lookup per ~111m cell).

`geocoding_tasks._geocode_worker` drains the `city=''` backlog in chunks (one UPDATE per place), busting user cache after each chunk. Kicked fire-and-forget from `push_location` via `ensure_auto_geocode(user_id)` (debounced, no-op while a thread already runs), and on demand from Settings. **Adding `reverse-geocode` dep + migration + boundary import requires `docker compose up -d --build`, then `migrate` and `import_boundaries --regeocode`.**

**Database:** SQLite (dev); PostgreSQL + PostGIS when `DATABASE_URL` is set. Code checks `HAS_POSTGIS` at runtime and falls back gracefully.

**Caching:** Redis when `REDIS_URL` is set, in-memory otherwise. `_bust_user_cache()` increments `cache_gen:{user_id}`, invalidating all cached API responses for that user.

**Stats snapshots.** Stats / Visits / Places aggregate entire history — too slow live, and `_bust_user_cache` fires constantly from pushes. Served from a **per-user `StatsSnapshot`** row (migration `0029`) holding precomputed JSON (`stats_json`, `visits_json`, `yearly_json`, `places_json`) + `status`/`computed_at`. The snapshot is **decoupled from `cache_gen`** — refreshes nightly, not per push. `tracker/stats_tasks.py` starts a daemon thread from `apps.ready()` (`start_stats_scheduler`) that sweeps every ~15 min and recomputes users whose `computed_at` is before today's local date. The thread runs in every gunicorn worker; `compute_snapshot` uses a **PostgreSQL session advisory lock** (`pg_try_advisory_lock`, namespace `_LOCK_NAMESPACE`; SQLite falls back to atomic `status='running'`) so only one heavy recompute runs per user across all workers — Postgres auto-releases on process death, so a killed compute never wedges. Shared helpers (`_compute_overview_from_qs`, `_compute_distance_from_qs`, `_compute_visits_from_qs`, `_compute_yearly_payload`, `_compute_places_payload`) are called by both the snapshot worker and live views. `visits_api`/`stats_api`/`yearly_overview_api`/`distance_api`/`places_api` serve the snapshot for default all-time unfiltered requests (via `_get_snapshot_or_kick`) and compute live for device/date-range filters. `CustomPlace` mutations call `_refresh_places_snapshot` for immediate updates. On-demand recompute: `POST /api/stats/recompute/` + `GET /api/stats/recompute/status/`, surfaced in Stats/Visits/Places headers and Settings → Background Jobs. **New model ⇒ build + migrate.**

**Stats "Daily Distance" heatmap.** A GitHub-contributions-style grid on `stats.html` — one square per day for the trailing year (53 Mon-first week columns), shaded by gated distance travelled; hover for the day + distance, click through to `/map/?start=&end=`. Purely client-side: it fetches `/api/distance/?all=1&granularity=daily` (**the one distance request `distance_api` serves straight from the snapshot**) and slices the last year in the browser, so it costs one extra snapshot read and no new endpoint. It is **deliberately independent of the page's range filter** — always the last year, regardless of the toolbar. Shading buckets by **quartile of the non-zero days**, not linearly: daily distance is heavily skewed (a year of commutes plus a few travel days), so a linear ramp would flatten nearly every day into the lowest level. Respects `roamly_speed_unit`. Pure template change — no rebuild/migration.

**Admin accounts:** `UserProfile.is_admin` marks an instance admin (distinct from `is_staff`/`is_superuser`). Migration `0028` backfills `is_admin=True` for pre-existing accounts. New admins register via the signup form's `<details>` section using `ADMIN_SIGNUP_KEY` (validated in `SignUpForm.clean_admin_key`; section only shows when env key is set). Exposed to templates as `IS_ADMIN` by `tracker.context_processors.custom_js_snippet`.

**Admin panel** (`/admin-panel/`, `admin_panel_view` → `admin_panel.html`, admin-only): two tabs — **Users** (list every account; toggle `is_admin`; delete another user and all their data) and **Custom JS** (edit the instance snippet). Endpoints: `GET /api/admin/users/`, `POST /api/admin/users/<id>/toggle-admin/`, `POST /api/admin/users/<id>/delete/`. All guarded by `_require_admin`. **Request/access/action logging was removed** — there is no `AccessLog`/`ActionLog`/`AdminPanelConfig`, no `RequestLoggingMiddleware`, no `log_writer`/`log_cleanup_tasks`, no `_log_action`, and no IP capture (`UserProfile.signup_ip`/`signup_user_agent` dropped). Migration `0036` deletes those tables/fields.

**Analytics / custom JS:** Instance-wide HTML injected verbatim before `</body>` via `{{ CUSTOM_JS_SNIPPET|safe }}` (paste snippets as-given, including their `<script>` tags). Lives in the `SiteConfig` singleton (`SiteConfig.load()`, pk=1), edited from **Admin Panel → Custom JS** (admin-only, `POST /api/site/custom-js/`). The `custom_js_snippet` context processor exposes `CUSTOM_JS_SNIPPET` to **every** template, but the injection line must exist in each top-level template: `base.html` (all app pages) **and** the standalone public templates that don't extend it — `landing.html`, `login.html`, `signup.html`, `docs.html`, `privacy.html`, `terms.html`. Analytics must reach the public pages, so any new standalone template needs the injection line too. `context_processors.get_custom_js()` reads it cached (`site_custom_js`, 1h TTL); the save endpoint busts that key.

**AI "Ask" (per-user, BYO OpenAI-compatible LLM).** Optional **Ask** tab (`/ask/`, `ask_view` → `ask.html`) for natural-language questions over the user's location history. Config on `UserProfile` (`ai_ask_enabled`, `ai_base_url`, `ai_api_key`, `ai_model`, `ai_system_prompt`; migration `0031`), edited from **AI Ask** card in Settings (`POST /api/profile/ai-config/`). API key is masked as `••••••••` on GET, only overwritten on POST when value ≠ mask. `UserProfile.ai_configured` = enabled + base_url + key + model all set. The Ask tab only renders when `AI_ASK_ENABLED` is true (set in `context_processors.custom_js_snippet`); `ask_view`/`ask_api` re-check it (403 guard).

`tracker/ai_tasks.py` runs an OpenAI-compatible tool-call loop (`run_ask`): system prompt + client turns → `POST {base_url}/chat/completions` with `tools`/`tool_choice:auto` → execute tool calls scoped to `request.user`, append `role:"tool"` results, repeat ≤5×. All tools are strictly **read-only** ORM reads. `TOOL_DISPATCH` maps: `search_history`→`_run_history_search`, `get_day_detail`→`_compute_day_detail`, `list_visited_places`→`_compute_visits_from_qs`, `list_custom_places`→`_compute_places_payload`, `get_custom_place_detail`→`_compute_place_detail`, `get_distance`→`_compute_distance_from_qs`, `get_history_overview`→`_compute_overview_from_qs`. Journal tools (`search_journals`, `read_journal_entry`) are **opt-in**: only offered when `UserProfile.ai_allow_journals` is set (migration `0032`). `ask.html`'s `format()` renders only internal `/`-prefixed markdown links and `**bold**` after HTML-escaping — dates link to `/map/?date=YYYY-MM-DD` without XSS risk. Chat is ephemeral (browser-only). AI config excluded from backups. **New model field ⇒ build + migrate.**

**Response compression:** `GZipMiddleware` (first after SecurityMiddleware) gzips all dynamic responses. WhiteNoise handles its own pre-compressed statics, so no double-compression occurs.

**Design system (Field Journal, dark-first).** The web UI uses a cartographic-editorial system defined by CSS custom properties in `base.html`'s `:root` (dark, default) and `[data-theme="light"]` (paper). Palette: warm-ink backgrounds, trail-blaze orange `--primary` (#e8763d), and an earthy categorical set exposed through the **legacy clay-* token names** (`--clay-mint`=moss, `--clay-coral`=rust, `--clay-amber`=ochre, `--clay-lavender`=mauve, `--clay-sky`=map-blue) — the names were kept so all 18 base-extending templates restyle centrally; only values changed. Type: **Fraunces** (`--font-display`, serif), **IBM Plex Sans** (`--font-body`), **IBM Plex Mono** (`--font-mono`, used for all numerals/coordinates/micro-labels). Flat plates with 1px borders and hairline rules — the `--clay-shadow*` tokens now hold subtle/flat values (no puffy shadows), radii are 5–14px, and a faint topographic-contour SVG data-URI on `body::before` replaces the old gradient blobs. No `text-transform: lowercase` (removed globally), no glass blur, no gradient text/buttons. **The six standalone pages** (`landing`, `login`, `signup`, `docs`, `privacy`, `terms`) don't extend `base.html`, so each carries its own copy of the token block + `@font-face` and is dark-only (no theme toggle). Theme switches via `roamly_theme` localStorage (default `dark`) → `data-theme`, read in `base.html` and set in `settings.html`. **Map JS accents** (markers/lines/device+member track palettes) are hardcoded to the new hexes; the **speed-gradient (blue→green→amber→red) and heatmap density ramps are functional encodings and deliberately left unchanged** in `map.html`/`trips.html`/`adventures.html`/`trip_public.html`.

**Custom form controls (`RoamlyForms`).** `base.html` ships a global framework-free progressive-enhancement layer (one IIFE + `.rly-*` CSS, both inline). The native element stays in the DOM as source of truth (hidden via `.rly-hidden-native`); a flat button trigger + `<body>`-attached fixed-position popup render the custom UI, dispatching real `input`+`change` events on selection. Programmatic `el.value = x` / `el.selectedIndex = i` are intercepted via `Object.defineProperty` to keep the visible label in sync. Custom `<select>` rebuilt from live `<option>`s on each open (keyboard: ↑/↓/Enter/Esc/type-to-open); date picker is Monday-first with `min`/`max`, today/clear, and month/year grid jump. A `MutationObserver` re-runs `RoamlyForms.enhance()` for controls injected later. Opt out: `class="rly-native"`. `<select multiple>` and `<input type="datetime-local">` left native. Pure template change — no rebuild/migration.

**Landing page performance:** Hero stats read from `site_stats` cache key (1h TTL). Anonymous responses get `Cache-Control: public, max-age=300, stale-while-revalidate=86400` + `Vary: Cookie, Accept-Encoding`. Fonts are self-hosted (`tracker/static/tracker/fonts/{fraunces-latin-var,plex-sans-latin-{400,500,600,700},plex-mono-latin-{400,600}}.woff2`), the two LCP faces (`plex-sans-latin-400`, `fraunces-latin-var`) `<link rel="preload" as="font" crossorigin>`-ed, all served by WhiteNoise with 1-year immutable cache; no Google Fonts CDN anywhere (zero third-party). **New font files need `collectstatic` or `{% static %}` 500s on a missing manifest entry.** All CSS is inline and minified — edit it knowing it's a single line, re-minify after heavy edits. Heading order is strictly h1→h2→h3.

**Auth:** Session-based for web. `ApiKeyAuthMiddleware` also accepts `Authorization: Bearer <key>` for mobile apps (GPSLogger, OwnTracks).

**Mobile branding:** Adaptive launcher icon — white Roamly mark (`drawable/roamly_favicon.png`) on black background (`drawable/ic_launcher_background.xml`). `android:windowBackground` is black to kill the cold-start flash. `ui/SplashScreen.kt` shows a **procedurally-generated intro that differs on every launch**: a `SplashConfig` seeded from `System.nanoTime()` randomly picks one of four Canvas styles (`JOURNEY` wandering GPS route, `CONSTELLATION` fixes wiring together, `RADAR` sweep + blips, `GRIDZOOM` map fly-through) with random accent colors from the theme palette; the Roamly mark springs in at center in all of them. `RoamlyNavHost` plays it until intro time has elapsed and `isLoggedIn` has resolved.

**Mobile in-app updates.** The app is sideloaded (no Play Store), so it self-updates. CI (`.github/workflows/mobile-release.yml`) publishes a signed APK (`Roamly<version>.apk`) as a GitHub Release asset on each `mobile-v*` tag. Two **server** endpoints proxy that so the app only ever talks to its own configured server: `GET /api/mobile/version/` (`views.mobile_version_check`) fetches `releases/latest` from GitHub (repo = `settings.MOBILE_UPDATE_REPO`, default `waxn/roamly`, env-overridable), parses the `mobile-v*` tag → `version_name` + release notes + the `.apk` asset, and returns it — cached 15m in Django cache (`mobile_latest_release`) to stay under GitHub's 60 req/hr unauth limit (there's no invalidation on publish, so a new release is otherwise invisible until the entry expires; `?refresh=1` forces a fresh GitHub fetch, rate-limited by a 2m cooldown lock so the public bypass can't burn the GitHub quota); `GET /api/mobile/apk/` (`views.mobile_download_apk`) streams the APK, fetching it from GitHub into `MEDIA_ROOT/apk/` once and serving from disk thereafter (`media/` is gitignored + a Docker volume). Both are public (no auth). On the **mobile** side: `RoamlyApi.getLatestVersion()`/`downloadApk()` (`@Streaming`), `data/repository/UpdateRepository.kt` (semver-compares installed `versionName` vs server, downloads to `cacheDir/updates/`, installs via `FileProvider` + `ACTION_VIEW` package-installer intent — needs the new `REQUEST_INSTALL_PACKAGES` permission and routes to `ACTION_MANAGE_UNKNOWN_APP_SOURCES` if not yet granted), `ui/update/UpdateViewModel.kt` + `UpdateBanner.kt`. `RoamlyNavHost` runs a throttled (~24h, `UserPreferences.lastUpdateCheck`) on-launch check and shows a dismissible "Update available" banner; Settings → About has a manual "Check for updates" button (shared VM instance). Android can't silently install for a non-system app — the OS install-confirmation screen always shows. **Server change ⇒ build; mobile change ⇒ rebuild APK.**

**Mobile tracking:** Android app (`mobile/`, Kotlin + Jetpack Compose) uses a claymorphism design system (`ui/theme/Theme.kt` + `ui/theme/Clay.kt`). On Android 12+ applies Material You dynamic color (`dynamicDarkColorScheme`/`dynamicLightColorScheme`) for surfaces while keeping brand accents. `ClayCard`/`ClayButton` borders are 0.5dp with reduced alpha. Login is **session-based** — no API key needed at login. API key is needed **only for tracking**, set up lazily via idempotent `POST /api/keys/app/` (returns existing active key or mints one named "Roamly Android"). One key per account, never expires, never duplicated on re-login.

`tracking/LocationTrackingService.kt` is a foreground service. **Capture uses two layers** (`applyCapture`):

- **Primary: continuous `requestLocationUpdates` stream + `PARTIAL_WAKE_LOCK`** — delivers smooth per-interval cadence when not in deep Doze (screen on, charging, or moving). Doze suspends the stream and ignores the wake lock, so it only runs when the device is already awake.
- **Backup: exact-alarm cadence** (`runFixCycle` → `scheduleNextFix`) via `setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)` + `ACTION_TAKE_FIX` (FGS-start exemption). `runFixCycle` skips cheaply while the stream is fresh, takes over in Doze. Alarm cadence clamped to `MIN_ALARM_FLOOR_MS` (15s); next-fix delay measured from cycle start so acquiring time counts toward the interval.
- **`acquireBestFix`** — streams fixes for `min(interval, 10s)` budget, keeps most accurate, stops early on `maxAccuracyM` met or `ACQUIRE_STALL_MS` (3.5s) plateau. Logs best fix rather than gapping.
- **Dwell points (`dwellPointFrom`)** — when no fresh fix arrives (fused provider returns cached location, dedup rejects it), re-logs last known position stamped now (zero speed). Only while last real fix is within `DWELL_MAX_AGE_MS` (10 min).
- **Catch-up backstop** — `scheduleNextFix` arms a second alarm at `CATCHUP_MULTIPLIER` (3) × interval so a dropped alarm recovers within 3×. `runFixCycle` force-resets a `fixInProgress` flag stuck longer than acquisition budget.

Callbacks run on `HandlerThread` ("RoamlyLocCb"). No-fix retries: quick (4s × 3) → medium (10s × 3) → `MIN_ALARM_FLOOR_MS`. Priority "auto" degrades `HIGH_ACCURACY`→`BALANCED` after 2 misses; explicit priorities honoured exactly; HIGH stays first so airplane mode (GPS-only) still works. Falls back to `fusedClient.lastLocation` when budget exhausted. Coarse 15-min heartbeat (`TrackingAlarmReceiver`) remains as the ultimate liveness check.

**Battery exemption is gated, not optional.** Without it, `setExactAndAllowWhileIdle` throttles to ~9 min in Doze. Starting tracking is blocked behind the exemption gate in `SettingsScreen` (with a "Track anyway (gaps)" escape hatch). While not exempt, a coral warning banner and notification line appear. `LocationFilter` is the **single accept() authority** — never movement-gated or accuracy-gated; rejects stale (> 2× interval), duplicate/older timestamp, or teleport (> 357 m/s). **Stationary-drift suppression (`tracking/DriftAnchor.kt`, default ON, toggle in Settings → GPS "Suppress stationary drift" → `UserPreferences.suppressStationaryDrift`).** The teleport guard only catches the *physically impossible*; a parked phone whose GPS wanders off at ~30 mph is plausible and consistent, so it slips through. `DriftAnchor` uses the tell that the chip's **Doppler speed (`Location.speed`) stays ≈ 0 while stationary even as the position estimate drifts**: after a short streak of low-Doppler fixes clustered within `ENTER_RADIUS_M`, it drops an anchor at their centroid and thereafter returns drift fixes **snapped** to the anchor (speed 0), so a parked device logs one stable point per interval instead of a drift spider. It releases the anchor when Doppler shows real movement (`MOVE_SPEED_MPS`, `EXIT_MOVE_STREAK`) or on a large relocation (`HARD_BREAK_M`, covers unreliable Doppler), so a real departure passes through unchanged. Wired at the single write chokepoint — `resolve()` runs at the top of `savePoint`, so the stream, fix-cycle, and dwell paths all flow through it; `driftAnchor.reset()` on pause. No new permission (reuses `Location.speed`). Points cached in Room, uploaded by `UploadWorker` (offline-first; `replace=true` for user-initiated syncs). **Batch upload:** tries `POST /api/push/batch/` (up to 100 points, `bulk_create(ignore_conflicts=True)`), falls back to single `POST /api/push/`. **Wedged-backlog recovery:** `savePoint` escalates to `replace=true` when backlog is non-empty and no successful delivery for `UPLOAD_STUCK_AGE_MS` (120s). Count backstop `UPLOAD_STUCK_THRESHOLD` (~40) kept as ceiling. `MainActivity.onResume` force-pushes (`replace=true`) any unsynced backlog on foreground.

**Tracking survivability stack:**
1. *Triple-defensive `startForeground`* — `goForeground()` called three times in `onStartCommand` and on boot.
2. *START_STICKY + persisted resume* — null-intent recreation reads `prefs.trackingEnabled` and resumes or stops.
3. *Self-resurrection* — `receiver/RestarterReceiver` (action `com.roamly.RESTART_TRACKING`) restarts service; service broadcasts to it from `onDestroy`/`onTaskRemoved` when torn down while enabled.
4. *Doze-piercing alarms* — two independent `setExactAndAllowWhileIdle` + `ELAPSED_REALTIME_WAKEUP` alarms (fall back to inexact when exact-alarm permission denied): (a) per-point fix alarm (`scheduleNextFix`, `ACTION_TAKE_FIX`), (b) 15-min liveness heartbeat (`TrackingAlarmReceiver`).
5. *Boot + app-update* — `receiver/BootReceiver` handles `BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`/QUICKBOOT.
6. *Battery-optimization exemption* — `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog from Settings → Reliability.
7. *OEM-killer onboarding* — Settings → Reliability links to `dontkillmyapp.com/<manufacturer>`.
8. *Multi-provider re-arm* — `PROVIDERS_CHANGED` receiver re-arms capture when providers toggle. `start()/stop()` wrapped in `runCatching` so a blocked FGS-start degrades to heartbeat retry.

**Stopping cleanly.** `trackingEnabled` = tracking should be running (gates START_STICKY resume, heartbeat, restarter, app-launch `resumeTrackingIfNeeded`). `autoStartTracking` = user-facing "Start on boot" option (the only thing `BootReceiver`/`MY_PACKAGE_REPLACED` keys on). `TrackingCoordinator.stopCompletely()` clears both + `trackingActive`, cancels heartbeat and periodic `UploadWorker`, kicks a final flush, stops service. Stop button requires confirmation dialog in both in-app Settings and notification Stop action (`EXTRA_CONFIRM_STOP` via `singleTop` `onNewIntent`). Starting sets `autoStartTracking=true` by default.

**Mobile caching:** `data/cache/DiskCache.kt` — Gson-backed JSON file cache (`cacheDir/api_cache`) persisting last successful response per screen for cold-start / offline. Wired into Stats, Adventures, Pals, Journals VMs. Wiped on sign-out. `StatsCache` is the fast warm-cache layer; `DiskCache` is the cold-start fallback beneath it.

**Mobile local-first location store:** `data/local/LocationStore.kt` backs a Room table (`synced_locations`, keyed by server location id) in `TrackingDatabase` (v2; `MIGRATION_1_2` adds the table). Sync is incremental via the locations API cursor (`sort_dir=asc` + `before_value`/`before_id`; `has_more`/`next_before_value`/`next_before_id`). `sync()` runs on foreground and `MapViewModel` init; `MapViewModel` observes `store.state` (a `StateFlow<SyncState>`) and reloads on sync complete. Map paints from store: decimated overview (`id % stride`) + full-resolution viewport dots from local DB on pan/zoom. "Have I been here?" scans the entire local history. `SyncedLocationDao.inTimeRange(startMs, endMs)` supports custom date ranges. Wiped on sign-out.

**Mobile feature parity:** Map (time periods + custom date range picker via Material3 `DateRangePicker`, basemap selector, heatmap, GPS dot tap → point detail dialog, "have I been here?"), Adventures (mini-map non-scrollable; tapping opens `TripFullMapScreen` with back button + zoom; polyline info-window disabled), Pals, Stats (monthly drilldown: tapping a month bar expands a daily Canvas chart with grid lines; tapping a day navigates map to that date), Journals (day track is a real osmdroid `MapView` with teal `Polyline`; tap card opens map for that date), Settings (with **Diagnostics** page: coverage bar chart from `/api/diagnostics/location/` pre-filled with device_id, CSV share via FileProvider), Search (place results expand inline to show each day; tap a day → map date filter). `MapViewModel.navigateToDate(dateStr)` sets a single-day custom range and clears `focus` so MapScreen auto-fits the viewport; called from search, stats drilldown, and journal track taps. `setTimePeriod`/`setCustomDateRange` also clear `focus = null` so auto-fit always fires on new data. `MapViewModel` scoped to Activity (created outside `NavHost.composable` blocks in `RoamlyNavHost`) so Journal and Stats can trigger map navigation. The UI is flat Material 3: no clay gradients/shadows; `NavigationBar` + `NavigationBarItem`; system Dynamic Color on Android 12+. Power-user/admin features remain web-only.

**Backups (data + media).** **Data backup** is one JSON document (`meta.version` = **6**) covering: devices, locations, adventures (subtitle/access_pin/cover/body blocks + nested members/blurbs(POIs, with `title`)/milestones/comments), pals (same structure), journals (JournalEntry + photo metadata), custom_places (name/center/radius_m/color/notes), api_keys. Excluded: derived data (Visit/VisitJob, POI, SiteStat, StatsSnapshot, Boundary, `Location.transport_mode`) and AI config. **`DismissedSuggestion` is also excluded** — it's user intent rather than derived, so it's a deliberate call, not an oversight: after a restore, rejected place suggestions simply reappear and get re-rejected, which was judged not worth widening the backup contract for. **One schema built two ways that must stay identical:** `backup_tasks._build_backup_json` builds in memory for S3 backup; `views._write_backup_json` streams to disk for download (locations streamed row-by-row to avoid OOM; shared helpers `_build_adventures_data`/`_build_pals_data`/`_build_journals_data`/`_build_custom_places_data`). **When adding a user-content model/field:** extend the shared helpers, bump `meta.version` in **both** builders, teach `views.restore_backup` to read it. `restore_backup` accepts all versions (v6 blurbs carry `title`; v4/v5 adventure `places` and v2/v3 legacy `trip_places` are restored **as blurbs**; v5+ `custom_places`). Restores are idempotent (`get_or_create` on natural keys). **Image backup** (`_get_user_media_files` → S3) ships the actual files the JSON names. Full restore = load JSON, then restore media.

## Key files

| File | Purpose |
|------|---------|
| `tracker/models.py` | All models |
| `tracker/views.py` | Every view and API endpoint (~3900 lines) |
| `tracker/urls.py` | All URL patterns |
| `tracker/forms.py` | `SignUpForm`, `APIKeyForm`, `AdventureForm` |
| `tracker/middleware.py` | API key Bearer auth |
| `tracker/backup_tasks.py` | S3 backup logic (runs in threads) |
| `tracker/geocoding_tasks.py` | Background geocode worker + `ensure_auto_geocode` |
| `tracker/offline_geocode.py` | `local_reverse_geocode` (TIGER point-in-polygon) + `offline_reverse_geocode` (intl nearest-city) |
| `tracker/management/commands/import_boundaries.py` | Load US Census TIGER boundaries; `--regeocode` relabels all points |
| `tracker/poi_tasks.py` | OSM POI download (runs in threads) |
| `tracker/transport_tasks.py` | Transport-mode detection (journey segmentation + classify) |
| `tracker/ai_tasks.py` | AI "Ask" — tool-call loop (`run_ask`, `TOOL_DISPATCH`) |
| `tracker/image_utils.py` | `resize_image`, `resize_photo` helpers |

## Models

**Core tracking:**
- `Device` — a tracked phone/device, belongs to a User
- `Location` — raw GPS point (lat, lon, altitude, accuracy, speed, battery, timestamp) + reverse-geocoded city/state/country
- `APIKey` — 64-char hex token for mobile push auth

**Adventures** (named journeys, formerly "Trips"):
- `Adventure` — time-bounded journey (device, creator, start_time, end_time, public_slug, subtitle, cover_image, cover_image_thumbnail, body). `body` is a JSONField of typed blocks (heading, paragraph, map_embed, photo_grid, divider, callout, location_card, timeline). The `timeline` block holds `{title, events:[{date, title, note}]}` and renders as a vertical dated timeline in editor + public (no backend model — lives in body JSON; `map_embed` blocks render a live MapLibre mini-map in the editor too). `.locations` filters Location by device + time range.
- `AdventureMember` — shared access (roles: creator, member)
- `AdventureBlurb` — **the unified POI**: `title` + `text` (notes) + optional lat/lon + author + photos + comments. Located blurbs are the adventure's points of interest, rendered as star markers; referenced inline in paragraph/heading/callout text via `[^pin:ID]` and by `location_card` blocks (both use the blurb id). **`AdventurePlace` was removed in migration `0037`** which copies each place into a blurb (title=name, text=notes) and rewrites all `[^pin:ID]`/`location_card.place_id` refs in body JSON to the new blurb ids. The repurposed `/api/trips/<id>/places/{create,<id>/update,<id>/delete}/` endpoints now operate on blurbs; `trip_detail`/public detail expose located blurbs as `places` so refs resolve.
- `AdventureBlurbPhoto` — photo on a blurb (max 5)
- `AdventureMilestone` — titled event with emoji and date
- `AdventureComment` — comment on a blurb

**Pals** — multi-user group trips, each member contributes their own track. Same social structure (PalMember, PalBlurb, PalBlurbPhoto, PalMilestone, PalComment).

**Journals:**
- `JournalEntry` — one per user per calendar day (`unique_together = user, date`). Fields: title, body, mood (emoji), weather, is_favorite, optional pin (pin_latitude/pin_longitude/location_name). Day map is derived on the fly from `Location` points via `_journal_day_track` in `views.py`.
- `JournalPhoto` — photo on an entry (image + thumbnail, caption, order; max 20).

Streaks (`_journal_compute_streaks`) and lifetime totals computed from entry dates. Page (`journals.html`): Monday-first calendar, recent-entries list, two-pane editor modal (left: editor + photo grid; right: MapLibre day track). Journal endpoints under `/api/journals/`; `<str:date_str>` route registered after `stats/` and `photos/<id>/delete/` to avoid shadowing. `journal_photos_api` is `@csrf_exempt` for mobile multipart upload.

**Background jobs:**
- `GeocodingJob` — geocoding progress per user
- `POIDownloadJob` — OSM POI download progress
- `TransportJob` — transport-mode detection progress
- `BackupConfig` — S3 backup config + status

**User:**
- `UserProfile` — profile picture, `is_admin`, AI Ask config (`ai_ask_enabled`/`ai_base_url`/`ai_api_key`/`ai_model`/`ai_system_prompt`/`ai_allow_journals` + `ai_configured` property), `mapbox_token` (server-side Mapbox basemap token, synced to all devices); `get_or_create`'d in `settings_view`
- `POI` — locally cached OpenStreetMap points of interest
- `CustomPlace` — user-defined geofence (`name`, `latitude`, `longitude`, `radius_m`, auto-assigned `color`)
- `DismissedSuggestion` — a place suggestion the user rejected, so it stops resurfacing (suggestions themselves aren't stored)

**Geocoding reference data:**
- `Boundary` — US Census TIGER admin polygon (`name`, `state`, `kind` = `place`|`cousub`, PostGIS `geom` MultiPolygon, GiST-indexed). PostGIS-only.

## URL / API conventions

Adventure API URLs use `/api/trips/` paths (backward compat). View functions are still named `trips_api`, `trip_detail`, etc. — only UI and model classes use "adventure" naming.

Public adventure pages: `/adventure/<slug>/` (`adventure_public.html`); public API: `/api/trip/<slug>/`.

Adventure CMS editor: `/adventures/<id>/edit/` (login + membership required). Endpoints:
- `PATCH /api/trips/<id>/body/` — save body JSON (also accepts `name`, `subtitle`)
- `POST /api/trips/<id>/cover/` — upload cover image
- `POST /api/trips/<id>/cover/delete/` — remove cover image
- `POST /api/trips/<id>/blurbs/<id>/update/` — edit blurb (text, lat/lng, location_name)

Inline pin refs: `[^pin:42]` references a located `AdventureBlurb` (POI) by id. Number computed from document order, not stored. POIs render as coral star markers on editor + public maps; clicking opens a themed popup (author + title + notes + photos). Create/edit via the map "add POI" flow (uses the repurposed `/places/` endpoints).

## Custom Places

User-defined named geofences — **Places** tab (`/places/`, `places_view` → `places.html`). `CustomPlace`: `name` + center + `radius_m` + auto-assigned clay-palette `color` + `notes` (migration `0030`). Membership computed on the fly via `_find_nearby_locations(qs, lat, lng, radius_m)` (PostGIS `ST_DWithin` / SQLite bbox) — **no FK on `Location`, no migration, no background job**.

Places page: **suggested-places list** + card grid (point count + last-seen) + two MapLibre modals (create/edit; detail with stats, cities covered, autosaving notes). Endpoints (all `@login_required`, ownership-scoped):
- `GET/POST /api/places/` — list / create
- `GET /api/places/suggestions/` — recurring stays not yet named (registered **before** the `<int:place_id>` routes)
- `POST /api/places/suggestions/dismiss/` — reject a suggestion
- `GET /api/places/<id>/` — full stats in a single DB pass
- `POST /api/places/<id>/update/`, `POST /api/places/<id>/delete/`

**Place suggestions.** Surfaces recurring stays the user hasn't named, so Places stops being purely manual. `place_suggestions_api` clusters **`Visit` rows** (the existing dwell detection in `visit_tasks.py`) — not raw points — via `_cluster_visits`, a grid-indexed greedy merge at `_SUGGEST_RADIUS_M` (150m, matching the radius the Visit worker already used, so repeat stays at one spot collapse into one candidate). A cluster is offered only at `_SUGGEST_MIN_VISITS` (3) **and** `_SUGGEST_MIN_HOURS` (1.0), which filters out one-off stops and traffic jams.

**Suggestions are computed on the fly and never stored, and there is deliberately no `confirmed` state.** Confirming opens the existing create modal prefilled (centre + matched POI name + radius); *saving the CustomPlace* is what retires the suggestion, because clusters falling inside an existing place's radius are filtered out — the place itself is the confirmation. So the only state worth persisting is rejection: `DismissedSuggestion` (migration `0041`, user + lat/lon). **Rejecting means "don't offer this as a place", not "this stay didn't happen"** — the `Visit` rows are untouched, so Stats/Search/AI are unaffected.

Cached under a plain `suggestions:{user_id}` key (30m TTL, **not** gen-keyed — visits accrue slowly and the clustering is a whole-history pass). All three `CustomPlace` mutations `cache.delete` that key, since creating/moving/deleting a place changes which clusters it covers. Unlike the other Places endpoints, `place_suggestion_dismiss_api` is **not** `@csrf_exempt` — the template sends `X-CSRFToken` instead.

Every mutation calls `_bust_user_cache`. Custom places also appear in:
- **Data table** — `locations_api` resolves via `_place_membership` (bbox+haversine), sets `custom_place`; template prefers it over `poi_name`, renders in coral.
- **Search** — `search_api` matches `CustomPlace.name__icontains`, prepends results above OSM POIs.
- **Map layer** — optional "my places" toggle draws labeled color circles, persisted in `roamly_show_places`, re-added on `style.load`.

## Map rendering

Two modes selected by `roamly_map_renderer`:
- **Vector tile mode** (PostGIS only): `/api/tiles/<z>/<x>/<y>.pbf`
- **Classic mode** (SQLite/fallback): GeoJSON from `/api/track/`, decimated to configurable point limit

Detail points load progressively via `/api/locations/` as user pans/zooms (viewport bbox). Accumulate in client-side `accMap` (deduped by `"deviceId:timestamp"`).

Adventure map loads from `/api/trips/<id>/` (capped 30k). Under 20k: all dots immediately; over 20k: heatmap-first, dots on zoom.

**Basemaps (`map.html`).** Selected by the sidebar "basemap" buttons, persisted in `roamly_map_tile_style` (per-device UI choice, localStorage). Built-in free basemaps (always available): `Streets` (CARTO light), `Dark` (CARTO dark), `Satellite` (Esri). **Optional Mapbox basemaps** appear only when the user has a public Mapbox token (`pk.…`) set in Settings → Main Map Settings: `Mapbox Streets`/`Mapbox Outdoors`/`Mapbox Satellite`, built via `_mapboxRasterStyle` from Mapbox raster-tile URLs (style-8 raster source, same pattern as the built-ins), with matching buttons injected into the basemap group at load. If a saved Mapbox style becomes unavailable (token removed), the map falls back to `Streets`. Purely additive — no built-in basemap was removed.

The basemap buttons live in a **3-column grid** (`.sidebar-btn-group`) so the extra Mapbox buttons wrap onto their own rows instead of squishing. A `map.on('error')` handler surfaces Mapbox tile auth failures (a correct token returns tiles; a 401/403 means an invalid token or URL restrictions) to `map-info` + the console, so a blank Mapbox basemap is never a silent mystery.

**Mapbox token is stored server-side** on `UserProfile.mapbox_token` (migration `0039`) so it syncs across every device the user logs in from — web *and* the mobile app. It's a public/client-side token by design, so it is **not masked**. Get/set via `GET/POST /api/profile/mapbox-token/` (`profile_mapbox_token_api`, login-required). The `custom_js_snippet` context processor exposes it to templates as `MAPBOX_TOKEN`; `map.html` reads it inline (`const _mapboxToken = '{{ MAPBOX_TOKEN|escapejs }}'`) and `settings.html` renders the field from it + POSTs changes. **New `UserProfile` field ⇒ build + migrate.**

**Mobile basemap selector.** The mobile map (`MapScreen.kt`, osmdroid) has a layers-icon menu (top-right, left of search) listing `Streets` (OSM MAPNIK), `Satellite` (Esri), `Dark` (CARTO) plus `Mapbox Streets`/`Outdoors`/`Satellite` **when a token is present**. `basemapTileSource(name, token)` maps each label to an osmdroid `OnlineTileSourceBase` (Mapbox/Esri use custom `getTileURLString`; Esri is z/y/x order; CARTO is plain XYZ). Selection persists in `UserPreferences.mapBasemap` (per device) and is applied by a `LaunchedEffect` keyed on `basemap` + `mapboxToken`. The token is read-only on mobile (`RoamlyApi.getMapboxToken` → cached in `UserPreferences.mapboxToken`, refreshed on `MapViewModel` init) — it's only ever set on the web. osmdroid's built-in zoom buttons are hidden (`zoomController` visibility `NEVER`) in favour of the custom side FABs. **Mobile change ⇒ rebuild APK.**

**Map tools (`map.html` only):**
- **Globe** — `map.setProjection({type:'globe'|'mercator'})`. Requires **MapLibre GL v5** (globally in `base.html`, v5.24.0). Persists in `roamly_globe`; re-applied on `style.load`.
- **Scratch** — highlights visited countries. `/api/countries/` returns visited `country_code`s (uppercase ISO-2 + US `states` list); client fills `tracker/static/tracker/data/world-countries.json` (Natural Earth 110m, ~170KB). **New static files need `collectstatic` — `--build` runs it on container start.**
- **Fog of War** — dims everywhere the user has never been. **Always all-time, never date-filtered** (explored territory stays explored), so it can't be fed by the map's own points — those are viewport- and date-scoped. `GET /api/fog/` (`fog_api`) returns the DISTINCT ~110m grid cells (`_FOG_CELLS_PER_DEG` = 1000, i.e. lat/lon rounded to 3dp) over the user's whole history, as a flat `[gy, gx, …]` int array. **Deliberately not keyed on `cache_gen`** — every push bumps the gen and a full-history DISTINCT is far too expensive to redo per push, so it uses a plain `fog:{user_id}` key with a 1h TTL (same reasoning as `StatsSnapshot`). Client renders a 2D `#fog-canvas` over the map (**not** a MapLibre layer — a world polygon with tens of thousands of holes can't be tessellated) and punches holes with `destination-out` at `FOG_REVEAL_M` (200m) per cell, `FOG_ALPHA` 0.75. Redrawn on `map.on('render')` to stay glued during pan/zoom. Cells are bucketed by whole degree (`fogBucketsInView`) and the frame scans whichever is smaller — viewport buckets or the whole index — since at world zoom the viewport spans ~65k buckets. **Fog and Globe are mutually exclusive** (a flat mask can't wrap a sphere); each toggle turns the other off. Persists in `roamly_fog`.
- **Replay** — animates one day's track. Fetches from `/api/locations/` (`all=1`), builds per-device sorted arrays, draws growing trail + head dot via `requestAnimationFrame` (60s at 1×). Base layers hidden while replaying.

## User-facing preferences

All stored in `localStorage` with `roamly_` prefix:

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
| `roamly_globe` | on / off | off |
| `roamly_show_places` | on / off | off |
| `roamly_fog` | on / off | off |
| `roamly_map_tile_style` | Streets / Dark / Satellite / Mapbox Streets / Mapbox Outdoors / Mapbox Satellite | Streets |

## Visit time spent

`/api/visits/` sums gaps between consecutive geocoded points. Same place label: full gap counts. Changed label: gap capped at 1 hour (`_visits_dwell_gap` in `views.py`).

## Distance travelled

`distance_api` and `_journal_day_track` use `_gated_distance_segments` (GPS-jitter resistant): (1) resample to `_DIST_WINDOW_S` (30s) centroids; (2) anchor-gate — credit movement beyond `max(25m, 2.5× accuracy)`, drop fixes worse than 100m, reject >1000 km/h teleports, re-anchor across >2h gaps. Drawn polylines use every point; only the number is gated. `_refresh_site_stats` uses raw `ST_MakeLine` sum (vanity stat, not gated).

## Import formats

- `/api/import/csv/` — broad column alias matching (Garmin, Strava, GPSLogger, generic)
- `/api/import/gpx/` — GPX 1.0 and 1.1, handles namespace detection
- `/api/import/json/` — Google Takeout (`{locations:[...]}`) and OwnTracks array
- `/api/import/kml/` — KML and KMZ; supports `gx:Track`, `Placemark`/`Point`, `LineString`

Helper functions in `views.py`: `_get_csv_field`, `_parse_timestamp`, `_safe_float`.

## Migration notes

`Adventure` renamed from `Trip` in migration `0002_rename_trip_to_adventure`. FK fields renamed in `0010_rename_trip_fk_to_adventure`. Both depend on `0009_trip_creator_trip_public_slug_tripblurb_and_more`.

When adding models or fields, create and commit the migration file — `tracker/migrations/` is volume-mounted.

**Transport mode detection** (`tracker/transport_tasks.py`, `TransportJob`, migration `0040`, manually run from Settings → Background Jobs). Labels every point with how the user was moving, on `Location.transport_mode` (`''` = unclassified). Modes are **`still` / `walk` / `cycle` / `vehicle` / `plane`** — deliberately **no car/train split**, their speed profiles overlap too much to call reliably.

**Classifies journeys, not points.** A single fix's speed lies (a car at a red light reads 0 m/s), so `_split_journeys` cuts each device's track into runs of movement bounded by stops longer than `_STOP_GAP_S` (180s) — short stops stay *inside* the journey — and never bridges a track gap over `_MAX_BRIDGE_S` (900s). `_classify` scores each journey from its **85th-percentile** speed (not max — one bad fix shouldn't promote a walk to a flight) plus the median as a guard, against `_WALK_MAX_MPS`/`_CYCLE_MAX_MPS`/`_VEHICLE_MAX_MPS`. Every point in the journey gets the journey's mode; **points outside any journey are labelled `still`**, which keeps the whole history classified and makes re-runs idempotent rather than leaving stale modes behind.

Speed comes from `Location.speed` (Doppler — trustworthy even when the position estimate wanders, the same property `DriftAnchor` leans on), falling back to speed derived from consecutive fixes for imported history (CSV/GPX/Takeout), which has no Doppler. The worker paginates on **`(timestamp, id)`, never `id` alone** — imported points get ids in file order, which need not match time order, so an id cursor would silently skip points. The trailing journey of each chunk is carried into the next rather than classified half-scored. Endpoints: `/api/transport/detect/{,status/,stop/}` + `GET /api/transport/breakdown/` (`transport_breakdown_api` — distance/hours per mode, reusing `_gated_distance_segments` per same-mode run so totals agree with `distance_api` instead of being a second, rawer estimate). Surfaced as the **How You Travelled** card on Stats (`still` excluded from the bars — it's most of the points and isn't travelling). Does **not** auto-run. **New model + field ⇒ build + migrate.**

**POI matching** (`tracker/poi_match_tasks.py`, `POIMatchJob`, manually run from Settings → Background Jobs). Labels every GPS point with nearest named POI within 150m, stored on `Location.poi` FK (migration `0025`). In-memory degree-grid, one `UPDATE` per POI per 5k chunk. Requires POI table populated first. Endpoints: `/api/poi/match/{,status/,stop/}`. Does **not** auto-run.

`_journal_day_track` accepts `tz_offset` (browser `getTimezoneOffset()` minutes) to bind to user's local calendar midnight, not UTC.

**Quality review scan** (`_flag_suspicious_locations`, `flag_scan_api` `POST /api/diagnostics/flag-scan/`). Flags points with bad accuracy / impossible speed / altitude spikes as `Location.flag='suspect'`. Accepts an optional time window (`?start_date`+`?end_date`, `?hours=N`, or `?all=1`) parsed like `location_diagnostics_api`; when scoped, only points in the window are reset+rescanned so flags outside it survive. The Diagnostics → **Quality Review** tab has a range selector driving it (defaults to All time). `flagged_locations_api` still navigates flagged points by day/week.

**Settings jump-nav.** `settings.html` wraps its `.settings-grid` in a `.settings-layout` flex with a sticky left `.settings-nav` (desktop ≥960px). The nav is **built in JS** from each `.settings-grid > .card`'s `.card-header` text (first text node, so badges/icons don't leak in) — it auto-assigns card ids, smooth-scrolls on click, and highlights the in-view section via `IntersectionObserver`. Add a card and it appears in the nav automatically; no manual list to maintain.
