# Custom places and geofence notifications

_Read when: CustomPlace, suggestions, arrival/departure notifications._

**Geofence arrival / departure notifications** (migration `0085`). `CustomPlace` gains `notify_arrive`/`notify_leave` (both default **False** — a geofence is a map convenience first, and turning every place into a notification source by default is the wrong surprise) plus `last_inside`/`last_notified_at`.

**It rides `alert_tasks`' existing 15-minute sweep rather than starting a daemon of its own** — `CustomPlace` already stores the geometry and `_find_nearby_locations` already does the containment test, so this is a state machine over data the app has, not new machinery. It reuses that module's per-user advisory lock, and iterates its **own** user list: a place notification is independent of the no-data alert, and requiring both to be enabled would be surprising.

**Edge-triggered, and the nullable `last_inside` is the load-bearing part.** A notification fires only on a *transition*; `NULL` means "never evaluated" and sends nothing, so switching a notification on while already standing at the place does not immediately claim you just arrived — and `place_update` resets it to `NULL` whenever either flag changes, for the same reason. A fix older than `_GEOFENCE_STALE_S` (1h) is ignored rather than treated as a departure: a tracker going quiet is not the same as leaving, and `latest_fix` is reused so the "where are they now" question has one answer across both alert types. `_GEOFENCE_MIN_GAP_S` (15m) stops a device hovering on the boundary from mailing repeatedly. A send failure updates `last_inside` (the transition really did happen) but does **not** stamp `last_notified_at`. Toggles autosave from the Places detail panel. **New fields ⇒ build + migrate.**


## Custom Places

User-defined named geofences — **Places** tab (`/places/`, `places_view` → `places.html`). `CustomPlace`: `name` + center + `radius_m` + auto-assigned clay-palette `color` + `notes` (migration `0030`). Membership computed on the fly via `_find_nearby_locations(qs, lat, lng, radius_m)` (PostGIS `ST_DWithin` / SQLite bbox) — **no FK on `Location`, no migration, no background job**.

Places page: a centred name **search box** under the toolbar (client-side filter of the loaded grid, `renderPlaces()`), **suggested-places list** + card grid (point count + last-seen) + two MapLibre modals (create/edit; detail with stats, cities covered, autosaving notes). Endpoints (all `@login_required`, ownership-scoped):
- `GET/POST /api/places/` — list / create
- `GET /api/places/suggestions/` — recurring stays not yet named (registered **before** the `<int:place_id>` routes)
- `POST /api/places/suggestions/dismiss/` — reject a suggestion
- `GET /api/places/<id>/` — aggregate stats (`_compute_place_detail`). Kept **lightweight** so the detail modal opens fast on a huge place: cities via a DB `GROUP BY`, per-day counts + dwell time from a **timestamps-only** ordered scan (one narrow column). It **no longer returns a map point sample** — those stream separately (below).
- `GET /api/places/<id>/points/` — `place_points_api`, a `StreamingHttpResponse` of newline-delimited JSON coordinate chunks (`[[lng,lat],…]` per line). One ordered scan decimated to ~3000 points (stride from the total count); yields 400-point chunks as read so the detail map fills in progressively. `X-Accel-Buffering: no` so nginx doesn't buffer the stream.
- `POST /api/places/<id>/update/`, `POST /api/places/<id>/delete/`

**Detail modal load order (`openDetail` in `places.html`).** Circle + map zoom + name + autosaving notes paint **instantly from the cached place geometry** (`placesCache` already carries lat/lng/radius_m/color/notes) — no server wait. The aggregate `GET /api/places/<id>/` fills the stats/days/cities a moment later. Map points stream in via `streamDetailPoints` reading `/points/` progressively (`detailPtsToken` cancels a prior place's still-running stream so it can't paint into the current one).

**Place suggestions.** Surfaces recurring stays the user hasn't named, so Places stops being purely manual. `place_suggestions_api` clusters **`Visit` rows** (the existing dwell detection in `visit_tasks.py`) — not raw points — via `_cluster_visits`, a grid-indexed greedy merge at `_SUGGEST_RADIUS_M` (150m, matching the radius the Visit worker already used, so repeat stays at one spot collapse into one candidate). A cluster is offered only at `_SUGGEST_MIN_VISITS` (3) **and** `_SUGGEST_MIN_HOURS` (1.0), which filters out one-off stops and traffic jams.

**Suggestions are computed on the fly and never stored, and there is deliberately no `confirmed` state.** Confirming opens the existing create modal prefilled (centre + matched POI name + radius); *saving the CustomPlace* is what retires the suggestion, because clusters falling inside an existing place's radius are filtered out — the place itself is the confirmation. So the only state worth persisting is rejection: `DismissedSuggestion` (migration `0041`, user + lat/lon). **Rejecting means "don't offer this as a place", not "this stay didn't happen"** — the `Visit` rows are untouched, so Stats/Search/AI are unaffected.

Cached under a plain `suggestions:{user_id}` key (30m TTL, **not** gen-keyed — visits accrue slowly and the clustering is a whole-history pass). All three `CustomPlace` mutations `cache.delete` that key, since creating/moving/deleting a place changes which clusters it covers. Unlike the other Places endpoints, `place_suggestion_dismiss_api` is **not** `@csrf_exempt` — the template sends `X-CSRFToken` instead.

Clicking a suggestion row expands an **inline mini-map** (accordion — one row at a time). A **single** MapLibre instance (`miniHost`, created detached) is *moved into* whichever `.sg-mini` slot is open rather than one map per row, since a long queue would otherwise hold a dozen live GL contexts; `paintMini()` re-centres it and is called both on expand and on the map's `load` (whichever lands second). `renderSuggestions()` detaches `miniHost` before replacing rows, so a re-render can't destroy the shared map with the DOM it was sitting in.

Creating a place refreshes the snapshot's `places_json` **synchronously** (a background thread lost the race against the client's immediate re-fetch). The map's right-click "add place here" panel opens at the cursor and appends the POST response to `myPlaces` so the place shows at once.

Every mutation calls `_bust_user_cache`. Custom places also appear in:
- **Data table** — `locations_api` resolves via `_place_membership` (bbox+haversine), sets `custom_place`; template prefers it over `poi_name`, renders in coral.
- **Search** — `search_api` matches `CustomPlace.name__icontains`, prepends results above OSM POIs.
- **Map layer** — optional "my places" toggle, persisted in `roamly_show_places`. Draws two things: the radius **circles** as GL layers (`myplaces-fill`/`myplaces-line`), and the **name tags** as DOM `maplibregl.Marker`s (`.place-tag` — a bordered plate in the place's colour with a wedge tail whose tip sits on the exact centre). Tags are markers rather than a `symbol` layer because the design fonts have no SDF glyph PBFs, so a symbol layer could only ever render the stock demotiles font — and being outside the style, markers also survive basemap swaps untouched. Only the circles need re-adding after a style swap: `addPlacesLayers` is idempotent and guards internally, called from both `style.load` **and `styledata`** — `isStyleLoaded()` can still be false at the instant `style.load` fires and `style.load` never fires again, which is why the layer used to go missing at random.
