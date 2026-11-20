# Visit time spent and gap bridging

_Read when: Visits dwell time, gap bridging rule._

## Visit time spent

`/api/visits/` sums gaps between consecutive geocoded points. Same place label: full gap counts. Changed label: gap capped at 1 hour (`_visits_dwell_gap` in `views.py`) — unless the gap is bridged (below).

**Gap bridging — tracking that stops and resumes in place.** A phone that is off, dead or force-stopped leaves a hole in the track, and every time-attribution path in the app independently cut the stay at a fixed time threshold and discarded that interval: an afternoon in a town with tracking off reported only the tracked minutes either side of the hole. **`tracker/dwell_utils.bridges_gap(lat1, lon1, lat2, lon2, gap_s, min_gap_s)`** is the single rule all four now share — a gap counts as time in place when the last fix before it and the first fix after it are within **`GAP_BRIDGE_RADIUS_M` (300m)** and the gap is no longer than **`GAP_BRIDGE_MAX_S` (12h)**.

The ceiling is load-bearing: two nearby fixes prove nothing about the interval between them — the phone could have been off while its owner flew somewhere and came back — so the bridge only covers spans short enough that staying put is by far the likelier explanation. Beyond it, every surface cuts the stay exactly where it always did.

**`min_gap_s` is what keeps this an escape hatch rather than a wider clustering radius**, and omitting it is a real regression, not a theoretical one. Each caller passes *its own* normal cap, so the bridge applies only to intervals that caller had already given up on. Without the floor it fires whenever the caller's ordinary merge test fails — including on a routine sampling interval that merely landed outside the clustering radius — and a slow walk with points 200m and 5 minutes apart chains step by step into one long stationary "stay" where previously there was no stop at all. Below the floor, behaviour is bit-for-bit unchanged; above it, the rule is purely additive and never shortens a stay.

**Inside a place, containment is the proof — and the distance test must not be applied on top of it.** Every point a place/POI radius scan yields is inside that place by definition, which is a *stronger* statement than `GAP_BRIDGE_RADIUS_M` makes. Running the coordinate check there anyway rejected exactly the stay it was written to credit: a campus with a 400m geofence routinely has two in-place fixes more than 300m apart, so a phone switched off at one end of a site and switched back on at the other reported only the tracked minutes either side of the hole — a 6-7h school day showing as ~10 minutes. Only the ceiling applies to those scans, and it still does.

Two wrappers in `dwell_utils` are what every dwell accumulator now calls, so the rule cannot drift again — a new surface reaching for a bare `if gap <= cap` is the failure mode, and four separate ones had already done it:

- **`credit_gap(gap_s, cap_s, lat1, lon1, lat2, lon2)`** — the general form (within the cap: full; beyond it: only if `bridges_gap` agrees).
- **`credit_gap_in_place(gap_s, cap_s)`** — for a scan already scoped to one place. Ceiling only, no coordinates, so a place-detail scan stays **timestamps-only** however wide the geofence is.

Lives in its own module because `visit_tasks` needs it and `views.py` imports `visit_tasks`. The consumers:

- **`visit_tasks._visit_worker`** — the `Visit` table (feeds place suggestions and the summary emails' stay timeline / top places). Bridges past its hardcoded 1h cut. Measured from a new `last_lat`/`last_lon` on the visit accumulator — the *last member point*, not the running centroid, since that is the fix the gap actually started at. The three duplicated visit-dict literals were factored into `_new_visit(device_id, loc)`. **Existing `Visit` rows are not retroactively recomputed** — they only change when the visit job re-runs over points with `processed_for_visits=False`.
- **`views._detect_dwells`** — the map's Trip Lines dwell blobs. Bridges past `_DWELL_MAX_GAP_S`, keeping the blobs and the Visits page agreeing about where a stop starts and ends (the invariant the function's docstring is built around). Same `last_lat`/`last_lon` treatment, via `_new_cluster`.
- **`views._compute_place_detail`** — a custom place's `time_spent`, previously capped at `_PLACE_GAP_CAP_S` (600s), so a 4h hole contributed **zero**. `credit_gap_in_place`, so the **timestamps-only** scan holds at any radius (it used to widen the `values_list` and run the endpoint check above 150m, which is the case that was wrong).
- **`views._calc_dwell_time`** — Stats → Top Places' total time for a custom place. Its `qs` must stay place-scoped for `credit_gap_in_place` to be valid.
- **`views._group_by_day`** — the per-day `time_spent` rows on Search. Takes **`in_place`**: true from the proximity ("here") and custom-place callers, false for a text/date search, where the day's points can be anywhere and a long gap has to earn its credit on distance. The `values_list` gained `latitude`/`longitude` for that branch.
- **`views._group_by_day_dicts`** — the same rows for OSM POI search results; always place-scoped, so always in-place.
- **`views._calculate_time_spent`** — an adventure blurb's time near a place. Its cap is 2h rather than 10 min; a hole past it is still credited up to the ceiling.
- **`stats_partials._scan_places`** — the incremental snapshot's per-day custom-place dwell, which is what actually feeds Stats when a snapshot exists. `PARTIALS_VERSION` was bumped to **2** so stored partials rebuild with the new crediting instead of serving stale totals forever.
- **`views._visits_dwell_gap`** — the Visits page (and, through it, the summary emails' top-cities list). The 1h cross-label cap assumes travel between towns, but a nearby boundary or a shifted geocode can put a different label on two fixes metres apart; the bridge lifts the cap there. Genuine travel is unaffected (endpoints kilometres apart). `prev`/`cur` tuples grew `lat, lon`, so **both** call sites (`_compute_visits_from_qs` and `trip_visits_api`'s inlined copy) had to widen their `values_list`.

**Summary emails need no change of their own** — `_top_cities` goes through `_visits_dwell_gap` and the stay timeline / top places read `Visit` rows, both already bridged. What they *do* need is fresh data: existing `Visit` rows are never recomputed, so an old school day stays short until the visit job re-runs over it (clear `Visit` for the user and reset `processed_for_visits=False`).

Pure Python change ⇒ **restart `web`**; no model, no migration.
