# User-facing preferences

_Read when: localStorage roamly_* preference keys._

## User-facing preferences

All stored in `localStorage` with `roamly_` prefix:

| Key | Values | Default |
|-----|--------|---------|
| `roamly_theme` | dark / light | dark |
| `roamly_speed_unit` | kmh / mph | kmh. Also picks the clock on Health: mph → 12-hour, kmh → 24-hour |
| `roamly_ach_filter` | Achievements category filter (`all` or a section's `data-cat`) | all |
| `roamly_ach_collapsed` | JSON array of minimised Achievements section keys | `[]` |
| `roamly_map_renderer` | vector / classic | vector |
| `roamly_speed_gradient` | on / off | on |
| `roamly_connect_lines` | on / off | off (Trip Lines mode on `/map/`: selective road-snapped lines + dwell blobs; a simpler accumulated-layer line overlay on `/editor/`) |
| `roamly_dwell_blobs` | on / off | on (Trip Lines mode only — draw stops as sized circles instead of a squiggly tangle) |
| `roamly_dwell_min_minutes` | 5 / 15 / 30 / 60 | 30 (minimum stay before it counts as a dwell blob) |
| `roamly_dwell_min_points` | 0 / 100 / 250 / 500 | 0 = off (independent point-count floor, alongside duration) |
| `roamly_dwell_radius_m` | 150 / 250 / 400 | 150 (how close points must be to count as the same stop) |
| `roamly_year_ago` | on / off | on (the header's "a year ago today" chip; Settings → General Preferences. `off` stops the page from even asking the server) |
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
| `roamly_show_inferred` | on / off | on (filled-gap layer on the main map) |
| `roamly_transport_modes` | comma list of walk / cycle / vehicle / plane / still / none | unset = every mode (map sidebar **transport** filter; `none` = unclassified; removed again once every mode is back on) |
| `roamly_map_sidebar` | JSON `{display, track, extra, panel, panel_narrow}` of booleans | unset = map display open, track + extra data closed, panel open (folded at ≤768px) — see map.md Sidebar layout |
| `roamly_load_more_pos` | corner / top | corner (where the map's "load all points" prompt sits: bottom-left as one tile with the total-vs-loaded count (which replaces the separate point-count tile while zoomed in), or top-centre under the notes pill; Settings → Appearance → Map layout) |
| `roamly_note_draft` | JSON `{date, text}` | unset (unsaved quick-note draft on the map; cleared on save) |
| `roamly_hide_suspect` | on / off | on ("hide outliers" — drops `flag='suspect'` points from the map) |
| `roamly_fog` | on / off | off |
| `roamly_map_tile_style` | Streets / Dark / Satellite / Mapbox Streets / Mapbox Outdoors / Mapbox Satellite | Streets |
| `roamly_snap_roads` | on / off | on (sidebar `snapped view` — display only, this browser; the account-wide master switch is `UserProfile.snap_to_roads`) |
| `roamly_intro_active` | '1' / unset | unset (whether the welcome tour is currently in progress — see `RoamlyIntro`) |
| `roamly_intro_step` | integer index | unset (which tour step to resume on the next page load) |
| `roamly_cell_signal` | on / off | off (per-sample signal layer; follows the map's range picker) |
| `roamly_cell_coverage` | on / off | off (measured coverage squares per carrier; follows the range picker) |
| `roamly_cell_towers` | on / off | off (derived tower positions; **always all-time**, never date-filtered) |
| `roamly_hide_track` | on / off | off (hide the GPS layers so the cell layers read alone) |
| `roamly_editor_min_gap_s` | 5–900 (seconds) | 60 (map editor: recorded points further apart than this are a gap to fill) |
