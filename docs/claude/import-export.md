# Import and export formats

_Read when: CSV/GPX/JSON/KML import, streamed export._

## Import formats

- `/api/import/csv/` — broad column alias matching (Garmin, Strava, GPSLogger, generic)
- `/api/import/gpx/` — GPX 1.0 and 1.1, handles namespace detection
- `/api/import/json/` — Google Takeout (`{locations:[...]}`) and OwnTracks array
- `/api/import/kml/` — KML and KMZ; supports `gx:Track`, `Placemark`/`Point`, `LineString`

Helper functions in `views.py`: `_get_csv_field`, `_parse_timestamp`, `_safe_float`.

## Export formats

`GET /api/export/csv/` (`export_csv`) and `GET /api/export/gpx/` (`export_gpx`) — plain `<a href>` downloads from Settings → Data, covering the user's entire location history.

**Both are `StreamingHttpResponse`, and must stay that way.** They originally built the whole file in memory before responding — `export_csv` wrote into an `HttpResponse` body, `export_gpx` accumulated a list of every line and `'\n'.join`ed it — on top of a queryset that materialised (and cached) a `Location` model instance per row. On a real history that is hundreds of thousands of rows: ~45 MB of output plus several hundred MB of model objects, all before a single byte went out. The gunicorn worker was OOM-killed, the request never returned, and a Cloudflare tunnel in front of the instance served a 502. Streaming also fixes the tunnel side on its own — Cloudflare's ~100s limit is on *time to first byte*, not total duration, so a stream that starts immediately can run as long as it needs.

The pattern (shared with `place_points_api`): `.values_list(...)` + `.iterator(chunk_size=5000)` so the ORM never builds a model, a generator that batches ~2000 rows per yield (per-row yields would push hundreds of thousands of tiny chunks through `GZipMiddleware` and the WSGI server), and `Cache-Control: no-store` + `X-Accel-Buffering: no`. Peak memory is under 1 MB for a 400k-row export. CSV rows are formatted by `csv.writer` over **`_EchoBuffer`**, a write-only file-like object that returns each formatted line instead of storing it. `RequestLoggingMiddleware` only reads `status_code`, so it is safe with a streaming response — any future middleware that touches `response.content` would break these.

**A falsy-vs-`None` fix rode along:** both exporters used `loc.altitude or ''` / `if loc.altitude`, which silently dropped a genuine **zero** — and speed 0 (a stationary phone) and battery 0 are extremely common, so every stationary reading exported as blank and re-imported as `None`. The checks are now `is not None`.
