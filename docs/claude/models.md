# Models

_Read when: Model reference._

## Models

**Core tracking:**
- `Device` — a tracked phone/device, belongs to a User
- `Location` — raw GPS point (lat, lon, altitude, accuracy, speed, battery, timestamp) + reverse-geocoded city/state/country
- `APIKey` — 64-char hex token for mobile push auth

**Adventures** (named journeys, formerly "Trips"; Pals was a separate, simpler multi-member feature — **removed** in migration `0059`, its one distinct capability, per-member tracks, subsumed here). An Adventure is a **shared** journey: the editor (`adventure_edit.html`) and public page (`adventure_public.html`) present it as **Day Log · Story · Map**. In the editor the left pane toggles **Story** (the freeform `body` block document) and **Day Log** (per-member per-day notes); the **Map** stays persistent on the right rather than being a hidden tab. The public page renders the same content read-only as article sections.
- `Adventure` — time-bounded journey (device, creator, start_time, end_time, public_slug, subtitle, cover_image, cover_image_thumbnail, body). `body` is the **Story** — a JSONField of typed blocks (heading, paragraph, map_embed, photo_grid, divider, callout, location_card, timeline). The `timeline` block holds `{title, events:[{date, title, note}]}` and renders as a vertical dated timeline in editor + public (no backend model — lives in body JSON; `map_embed` blocks render a live MapLibre mini-map in the editor too). `.locations` filters Location by device + time range. `trip_detail`/`trip_public_detail_api` also assemble **`member_locations`** — each member's own device track for the window — so a shared adventure paints every traveller's route (per-member colour on the public map embeds).
- `AdventureMember` — shared access (roles: creator, member). **Invite by link:** `Adventure.invite_token` (migration `0045`) powers open-join — `POST /api/trips/<id>/invite/` (any member, `rotate` to reset) mints/returns the `/adventure/join/<token>/` URL; `trip_invite_email_api` emails it (needs SMTP). `trip_join_view` (`/adventure/join/<token>/`) adds the logged-in user as a member (idempotent `get_or_create`) and redirects to the plan page, or shows `adventure_join.html` with login/signup buttons carrying `?next=` back to the join URL for anonymous visitors. Plan page surfaces the link + copy/reset/email-invite UI.
- `AdventureBlurb` — **the unified POI**: `title` + `text` (notes) + optional lat/lon + author + photos + comments + **`rating`** (1–5, null = unrated) + **`category`** (migration `0060`; slugs in `ADVENTURE_PLACE_CATEGORIES` — restaurant/cafe/bar/hotel/store/attraction/nature/transport/town/other). Located blurbs are the adventure's points of interest; a **categorised** place renders an emoji-glyph marker (else the star), and stars + a category badge show on the place card, map popup, and public page. Referenced inline in paragraph/heading/callout text via `[^pin:ID]` and by `location_card` blocks (both use the blurb id). **`AdventurePlace` was removed in migration `0037`** which copies each place into a blurb and rewrites all `[^pin:ID]`/`location_card.place_id` refs in body JSON to the new blurb ids. The `/api/trips/<id>/places/{create,<id>/update,<id>/delete}/` endpoints operate on blurbs (accept `rating`/`category`, cleaned by `_clean_rating`/`_clean_category`); `_poi_payload` carries them everywhere; `trip_detail`/public detail expose located blurbs as `places` so refs resolve.
- `AdventureBlurbPhoto` — photo **or video** on a blurb (max 5; `media_type` `image`|`video`, migration `0062` — image rows keep `image`+`thumbnail`, video rows use the `video` FileField). Also the store for story `photo_grid` blocks.
- `AdventureMilestone` — titled event with emoji and date
- `AdventureComment` — comment on a blurb
- `AdventureDayNote` / `AdventureDayPhoto` (migration `0060`; `0061` made it multi-entry) — **the Day Log**: `title` + `body` + up to 10 photos **or videos** (`AdventureDayPhoto`, `media_type` `image`|`video`, migration `0062`) + optional `place` FK to a located `AdventureBlurb`. A member may hold **several entries per day** (migration `0061` dropped the old `(adventure, author, date)` uniqueness), each editable only by its author, all grouped under the date and attributed. The day's map track is derived on the fly (the "show on map" button fits the editor map to that date's points; not stored). Deliberately **not** the personal `JournalEntry` model — journals are private/single-author, day notes are adventure-scoped and public-visible. Endpoints are **id-keyed** (all `@login_required` + `_get_trip_for_user`, writes **author-scoped** via `_own_day_note`): `GET /api/trips/<id>/days/`, `POST /api/trips/<id>/days/<YYYY-MM-DD>/create/` (new empty entry), `POST /api/trips/<id>/days/note/<note_id>/` (save title/body/`place_id`, validated by `_resolve_day_place`), `POST /api/trips/<id>/days/note/<note_id>/photos/` (multipart), `POST /api/trips/<id>/days/note/<note_id>/delete/`, `POST /api/trips/<id>/days/photos/<photo_id>/delete/`. `_adventure_day_notes`/`_day_note_payload` serialize (payload carries the linked `place`); `trip_detail`/`trip_public_detail_api` expose `day_notes`. Editor `adventure_edit.html` renders the Day Log as **list or calendar** (month grid, Monday-first, note titles shown in cells); the left pane is a draggable split with the map. **New model ⇒ build + migrate.**
- `PlannedStop` (migration `0043`) — **the forward-looking itinerary**: an ordered planned stop on an Adventure (`name`, optional lat/lon + `location_name`, `arrival_date`, `nights`, `transport` leg, `accommodation`, `notes`, `order`). Deliberately a *sibling* of `AdventureBlurb`, not merged: blurbs are past located notes, PlannedStops are future intent, so one Adventure holds both the plan and the recorded track (plan-vs-actual). Editor: the **"itinerary" panel** in `adventure_edit.html` (reorderable stop cards + "add stop" drops a numbered pin on the map; a dashed connector line joins located stops in order). Endpoints (all `@login_required` + `_get_trip_for_user`): `GET /api/trips/<id>/plan/`, `POST /api/trips/<id>/plan/create/`, `POST /api/trips/<id>/plan/<stop_id>/update/` (also reorders via `order`), `POST /api/trips/<id>/plan/<stop_id>/delete/`. `trip_detail` + `trip_public_detail_api` expose `planned_stops`; the public page (`adventure_public.html`) renders the dashed itinerary line + numbered markers on every map embed **and** an "Itinerary" list section. **New model ⇒ build + migrate.**

**Journals:**
- `JournalEntry` — one per user per calendar day (`unique_together = user, date`). Fields: title, body, mood (emoji), weather, is_favorite, optional pin (pin_latitude/pin_longitude/location_name). Day map is derived on the fly from `Location` points via `_journal_day_track` in `views.py`.
- `JournalPhoto` — photo on an entry (image + thumbnail, caption, order; max 20).

**Journal settings cog + helpers** (`tracker/journal_assist.py`, migration `0111`). A cog beside the Journals heading opens on hover (tap toggles it, since touch has no hover), and each toggle autosaves through `GET/POST /api/journals/settings/`, which applies only the keys it carries (registered **before** the `<str:date_str>` routes). `UserProfile.journal_auto_title` / `journal_auto_mood` default **off** because they send the entry to the user's provider; they need `ai_configured`. `journal_auto_weather` defaults **on**. All three are config ⇒ **excluded from backups**. **Nothing here replaces what someone typed.** `POST /api/journals/<date>/autofill/` runs from `closeEntry` after the final save, never on the autosave (which fires at every pause in typing), only if something was edited since opening, and each write is `filter(title='').update(...)`, so a title typed during the provider call wins. A reply that names a word instead of an emoji is dropped. `GET /api/journals/<date>/weather/` fills a blank field only in the form: it is saved with the entry, so opening a day stores nothing. The place is the entry's pin, else the middle fix of the day, else the latest fix for today, rounded to 2dp (~1 km) before it reaches Open-Meteo (free, no key: the forecast API under `ARCHIVE_AFTER_DAYS` old, the archive beyond, cached 30 days for past days). `°F` follows `distance_unit`. **Open-Meteo is a third party listed on `privacy.html`.** `POST /api/journals/<date>/move/` (clicking the date in the editor) moves an entry and its photos; a taken day answers 409 rather than merging, since it is one entry a day. Fetched weather moves with a refresh: if the stored value equals the old day's lookup it is replaced by the new day's, and a typed value stays. The editor saves only when `dirty`: it used to save on every close, which wrote a blank row for any day merely opened, could overwrite an entry with the empty form if it closed before loading, and could resurrect a deleted one. **AI titles** read the body *and* the quick note, so a day with only its "what I did today" jot still gets one. `POST /api/journals/title-missing/` (registered before the `<str:date_str>` routes; same `journal_auto_title` gate and rate limit) titles up to 5 untitled days per call, newest first, and the page calls it after load until none remain; it never sets moods. Web only, CSRF-checked.

Streaks (`_journal_compute_streaks`) and lifetime totals computed from entry dates. Page (`journals.html`): Monday-first calendar (plain `.card`, no coloured left-edge bar; the quick-note box has none either), a full-width entries feed below grouped by month as a card grid (12 at a time, "show older entries"), sidebar with search on top and a **today box** under it (`#j-mini-text`: always-open textarea that autosaves only `body` to today's entry after 800 ms, so it can't clobber title/mood/weather; clicking it opens the full editor with focus kept in the box, and while the editor is on today the two mirror each other and the editor does the saving; search results replace the box while searching), two-pane editor modal (left: editor + photo grid; right: MapLibre day track). Journal endpoints under `/api/journals/`; `<str:date_str>` route registered after `stats/` and `photos/<id>/delete/` to avoid shadowing. `journal_photos_api` is `@csrf_exempt` for mobile multipart upload.

**Background jobs:**
- `GeocodingJob` — geocoding progress per user
- `POIDownloadJob` — OSM POI download progress
- `TransportJob` — transport-mode detection progress
- `BackupConfig` — S3 backup config + status (`include_media` toggle, heartbeat, file count, restore-size warning)

**Admin logging** (migration `0048`; see Logging & monitoring above; excluded from backups):
- `AccessLog` — one row per HTTP request (admin traffic/IP-access log)
- `ActionLog` — significant events (logins/signups/errors/admin actions)
- `DailyLogRollup` — per-day aggregate counts, kept forever

**Roads & gap filling** (migration `0049`; see Road snapping + gap filling above; all excluded from backups):
- `RoadSegment` — an OSM way: `way_id` (unique), `name`, `highway`, `oneway`, PostGIS `LineString` `geom` (GiST), plus a vestigial empty `node_ids` (`bigint[]`). Instance-wide, no user FK. PostGIS-only.
- `RoadDownloadJob` — progress row for the road download
- `RailSegment` — an OSM subway way: `way_id` (unique), `name`, `railway`, PostGIS `LineString` `geom` (GiST). Instance-wide, no user FK. PostGIS-only. (migration `0064`)
- `RailStation` — an OSM subway station node: `node_id` (unique), `name`, PostGIS `Point` `geom` (GiST). Used for transfer stitching + gap-proximity. Instance-wide. PostGIS-only.
- `RailDownloadJob` — progress row for the subway download (clones `RoadDownloadJob` + a `stations` counter)
- `DownloadedRegion` — coverage tracking so a re-run only asks about growth since last time: `kind` = `road`/`subway`/`poi`, `key` = a `"gy,gx"` grid-cell coordinate or a `"city|state"`. See "Auto-download new regions" above.
- `DismissedSubwayGap` — a detected subway gap the user rejected (user + start/end anchor coords), so it stops resurfacing. User intent ⇒ excluded from backups.
- `EditBatch` — one map-editor action, so it can be undone as a unit; `location_ids` records real rows a paste-as-real created, `label_undo` the old labels of a relabel
- `InferredLocation` — a point the editor generated. **Never** a recorded fix, and read only by the map and the editor
- `TrashedLocation` — full snapshot of a removed real point. `reason='deleted'` (editor trash, restorable for 30 days) or `reason='overlap'` (hidden by a `DeviceOverlap`, kept until unhidden)
- `DeviceOverlap` — a window when two of the user's devices recorded the same track; `pending`/`hidden`/`dismissed` (see Device overlaps)

**User:**
- `UserProfile` — profile picture, `is_admin`, AI Ask config (`ai_ask_enabled`/`ai_base_url`/`ai_api_key`/`ai_model`/`ai_system_prompt`/`ai_allow_journals` + `ai_configured` property), `mapbox_token` (server-side Mapbox basemap token, synced to all devices), road config (`road_provider`/`osrm_url`/`snap_to_roads` (default **True**) + `road_provider_resolved` property; see Road snapping), `email_verified` (default True; see Email verification), summary-email opt-in (`summary_daily/weekly/monthly/yearly` + `summary_last_*` cursors + `summary_any` property; see AI summary emails), `email_unsub_token` (lazily-generated unsubscribe-link credential), `distance_unit` (server-side mirror of `roamly_speed_unit`, for the summary-email job), TOTP 2FA (`totp_secret`/`totp_enabled`; see TOTP two-factor authentication), `intro_seen` (default True; see `RoamlyIntro` — first-run welcome tour), `appearance` (Settings → Appearance facets, JSON; see Appearance), no-data alert config (`alert_no_data_enabled`/`alert_no_data_hours` + the `alert_no_data_last_point`/`alert_no_data_sent_at` cursors; see Tracking alerts); `get_or_create`'d in `settings_view`
- `KnownDevice` — a browser/app the user has verified (SHA-256 of the `roamly_device` cookie token), so it skips new-device email login codes (migration `0044`)
- `TOTPBackupCode` — a hashed, single-use TOTP recovery code (migration `0056`; see TOTP two-factor authentication above)
- `POI` — locally cached OpenStreetMap points of interest
- `CustomPlace` — user-defined geofence (`name`, `latitude`, `longitude`, `radius_m`, auto-assigned `color`)
- `DismissedSuggestion` — a place suggestion the user rejected, so it stops resurfacing (suggestions themselves aren't stored)

**Activities** (migrations `0081`, `0095`; see Activity recording above; **included** in backups, `meta.version` 12 / tracks 17):
- `Activity` — one deliberately recorded ride/walk/run/row. The envelope `(device, start_time, end_time)` + `kind`/`title`/`notes` + a phone-minted `client_id` (`unique_together` with `user`, so save-on-stop is idempotent). The stat columns (incl. elevation gain/loss) are a **cache**, keyed by `stats_algo_version` and `stats_point_count`. `.locations` derives the history-window fallback track. Non-spatial, so defined once rather than behind `HAS_POSTGIS`.
- `ActivityTrack` — the phone's raw 1 Hz recording as one gzipped-JSON blob, with chunked-upload state and a `smoothed` cache keyed by `algo_version`.

**Health Connect** (migration `0074`; see Health Connect above; **included** in backups, `meta.version` 10):
- `HealthSample` — one raw health record (`kind` steps/distance/calories_active/calories_total), from Health Connect, Zepp, or a file import. Deduped on `(user, external_id)`. No `Device` FK — health works without GPS tracking. **No `Meta.ordering`**, deliberately.
- `HealthWorkout` — one manually imported exercise session, with totals computed on the phone at import time.

**Cell coverage** (migration `0089`; see Cell coverage above; **included** in backups, `meta.version` 16):
- `CellSample` — one observation of one cell tower from one position, per SIM. Deduped on `(user, client_id)`. No `Device` FK (a plain `device_id` CharField, like `HealthSample`). **No `Meta.ordering`**, deliberately — the tower view is a `.values().annotate()`. Non-spatial, so defined once rather than behind `HAS_POSTGIS`. There is deliberately **no** `CellTower` model: a tower is a `GROUP BY` over these rows.

**Achievements** (migration `0108`; see Achievements above; **excluded** from backups, derived):
- `AchievementSnapshot` — per-user precomputed Achievements payload + job status/progress + the cached road-coverage result and its fingerprint.
- `AchievementUnlock` — one earned badge tier with when (back-dated where known); kept across rescans.

**Flights** (migration `0102`; see Flights above; **included** in backups, `meta.version` 20):
- `Flight` — one flight in the user's log, airports denormalised, times UTC. Non-spatial, no link to `Location`.

**Family Circle** (migration `0086`; see Family Circle above; `FamilyCircle`/`FamilyMembership`/`FamilyPlace`/`FamilyPlaceAlert` **included** in backups, `meta.version` 15; `FamilyMemberPlaceState`/`FamilyPushToken` excluded, operational):
- `FamilyCircle` — a mutual location-sharing group. `name`, `creator`, lazily-minted `invite_token`.
- `FamilyMembership` — a structural copy of `AdventureMember`'s post-migration-`0082` consent shape: `accepted_at` (NULL = invited, not consented) + `share_location` (accepting ≠ consenting to broadcast position).
- `FamilyPlace` — a circle-scoped geofence, self-serve (any accepted member may create one). Deliberately not `CustomPlace`.
- `FamilyPlaceAlert` — one member's own enter/exit subscription for a place, opt-out (both flags default `True`).
- `FamilyMemberPlaceState` — the enter/exit transition-state cache `family_tasks.py` compares each fix against.
- `FamilyPushToken` — one registered FCM token per app install. Not an FK to `Device`.

**Trip blog** (migration `0109`; see Trip blog above; blogs/members/posts/media/comments **included** in backups, `meta.version` 25; links, uses, subscribers, reactions excluded):
- `TripBlog` — a shared travel blog (name, dates, cover, invite token).
- `TripBlogMember` — a traveller; `accepted_at` + `share_location` consent, `featured` (one per user) for the nav.
- `TripPost` / `TripPostMedia` — a post (text, optional place, `local_date`, draft until published) and its photos/videos (video `status` uploading/pending/processing/ready/failed).
- `TripComment` / `TripReaction` — viewer or member comments; per-viewer emoji reactions.
- `TripShareLink` / `TripShareUse` — follower links (expiry, `max_uses`, PIN) and the browsers that used them (who viewed; no IP).
- `TripSubscriber` — double-opt-in digest address, tied to the link it came through.

**Ask chats** (migration `0112`; see Ask chat page above; **excluded** from backups):
- `AskChat` — one saved conversation (title, `titled`, `pinned`, `mode`).
- `AskMessage` — one turn; `cards` holds the map cards shown and `meta` holds the sources and follow-ups.
- `AskProposal` (migration `0114`) — a change the assistant proposed (flight / journal / place), applied only on the user's Approve; see Ask proposals.

**Geocoding reference data:**
- `Boundary` — admin polygon (`name`, `state`, `country_code`, PostGIS `geom` MultiPolygon, GiST-indexed). `kind` = `place`|`cousub`|`county` (US Census TIGER) or `adm1`|`adm2`|`adm3` (geoBoundaries, other countries). Only `place`/`cousub` are read by the geocoder. PostGIS-only.
