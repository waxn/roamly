# Roamly

Self-hosted location tracking you actually own. Push GPS from your phone, browse
your whole history on a MapLibre map, write up trips as illustrated journals,
see how long you've spent in every city, and keep every coordinate on hardware
you control.

[![Docker Hub](https://img.shields.io/docker/pulls/waxn/roamly)](https://hub.docker.com/r/waxn/roamly)

Roamly is a single Django app. There's no SaaS backend, no telemetry, and no
account that lives on someone else's server. The only thing it ever talks to on
the public internet is OpenStreetMap's Nominatim, and only to turn coordinates
into city names — it never sends who you are.

---

## What it does

**Map.** A MapLibre GL map of everywhere you've been: a heatmap, speed-colored
GPS dots, optional connecting trail lines, time-range and per-device filters. On
PostGIS it serves Mapbox vector tiles (`ST_AsMVT`) so a million-point history
stays fast; on plain SQLite it falls back to a classic clustered GeoJSON
renderer. Detail dots stream in progressively as you pan and zoom, so you're
never waiting on the whole dataset to load.

**Multiple devices.** Every phone, watch, or script gets its own API key and its
own color on the map. A device is created automatically the first time it pushes
a point, so there's nothing to set up in advance.

**Adventures.** Time-bounded journals for a single outing — a bike ride, a hike,
a road trip. Pick a device and a precise start/end, and Roamly pulls exactly the
points inside that window. Each adventure has a block-based editor (headings,
paragraphs, photo grids, callouts, embedded maps, location cards), named place
pins with radius-based dwell time, a timeline of photo posts, milestones with
emoji, and threaded comments. Publish one at a custom slug, optionally gated by a
short access pin.

**Pals.** Group trips where each member contributes their *own* device track.
Everyone's route lands on one shared map with a combined timeline, blurbs,
milestones, and profile photos — built for friends travelling together rather
than one person's history.

**Visits & stats.** Roamly attributes the gaps between consecutive geocoded
points to the place you were sitting in, so you get real dwell time per
city / state / country — not just a point count. The stats page charts distance
over time and ranks your top places; a yearly overview compares the current
week / month / year against the last.

**Search.** Query your history by date, by town, or against a local cache of
OpenStreetMap points of interest. Every raw point is browsable and sortable in
the data table, and you can jump from any row straight to its spot on the map.

**Import & export.** Bring in CSV (with broad column aliasing for Garmin,
Strava, GPSLogger, and generic exports), GPX 1.0/1.1, Google Takeout Location
History JSON, or OwnTracks JSON. Export everything back out as CSV, GPX 1.1, or a
complete JSON backup you can restore on another instance.

**Automatic backups.** Schedule data backups (and, separately, your uploaded
photos) to any S3-compatible storage — AWS S3, Backblaze B2, Cloudflare R2,
MinIO, Wasabi. Daily, weekly, or monthly, with optional retention limits.

**Reverse geocoding.** New points are geocoded the moment they arrive. For bulk
imports there's a background job that clusters nearby coordinates into a ~111 m
grid and geocodes one representative per cluster, so 100k points in a handful of
cities only costs a few thousand Nominatim requests instead of 100k.

**PWA.** Installable, works offline-ish, dark and light themes.

### The Android app

Roamly ships with a first-party Android client (`mobile/`, Kotlin + Jetpack
Compose) for people who don't want to wire up GPSLogger by hand. It logs in with
an API key — the durable credential, so it stays signed in even after the Django
session expires — and a device id is assigned automatically so uploads work
immediately.

The tracker is a foreground service built to survive Doze, battery saver,
reboots, and OS kills (durable intent flag, `START_STICKY`, a boot receiver, and
a watchdog coroutine that re-arms location updates if fixes stall). Interval, GPS
priority, minimum movement distance, and max accuracy are all tunable live.
Points are cached in Room and uploaded offline-first by a WorkManager job, with a
local CSV mirror written alongside. The app also browses your map, stats,
adventures, and pals. It's built from source with Android Studio / Gradle (no
Play Store or F-Droid listing).

### Anything that speaks HTTP

You don't need the Roamly app at all. [GPSLogger](https://gpslogger.app/)
(Android) and [OwnTracks](https://owntracks.org/) (iOS/Android) both push to
Roamly out of the box, and anything that can POST JSON — curl, a Python script,
Tasker, Home Assistant, an Apple Shortcut — works against `/api/push/`.

---

## Quick start

The fastest path is the prebuilt image from Docker Hub — no clone, no build.

```bash
# grab the compose file
curl -O https://raw.githubusercontent.com/waxn/roamly/main/docker-compose.yml

# minimal .env (use your own secrets!)
cat > .env <<EOF
POSTGRES_PASSWORD=changeme
DATABASE_URL=postgis://roamly:changeme@db:5432/roamly
SECRET_KEY=$(openssl rand -hex 32)
DEBUG=False
ALLOWED_HOSTS=localhost,127.0.0.1
CSRF_TRUSTED_ORIGINS=http://localhost:8001,http://127.0.0.1:8001
EOF

docker compose up -d
```

Roamly comes up at `http://localhost:8001`. Migrations and static files run
automatically on startup. Create an account, generate an API key under
**Settings → API keys**, point a tracker at `/api/push/`, and your points show up
on the map within seconds.

> **SQLite vs PostGIS.** Leave `DATABASE_URL` unset and Roamly runs on a single
> SQLite file — perfect for trying it out. Set it to a PostGIS connection string
> and you unlock vector tiles and spatial queries for large histories. The code
> detects PostGIS at runtime and degrades gracefully either way.

### Portainer

1. Create a new **Stack** and paste the compose snippet below.
2. Add each variable from the [Configuration](#configuration) table under
   **Environment variables**.
3. **Deploy the stack.**

```yaml
services:
  redis:
    image: redis:7-alpine
    restart: unless-stopped

  web:
    image: waxn/roamly:latest
    restart: unless-stopped
    depends_on:
      - redis
    ports:
      - "8001:8000"
    volumes:
      - ./staticfiles:/app/staticfiles
      - ./media:/app/media
      - ./migrations:/app/tracker/migrations
    environment:
      - SECRET_KEY=${SECRET_KEY}
      - DEBUG=${DEBUG:-False}
      - DATABASE_URL=${DATABASE_URL}
      - ALLOWED_HOSTS=${ALLOWED_HOSTS}
      - CSRF_TRUSTED_ORIGINS=${CSRF_TRUSTED_ORIGINS}
      - REDIS_URL=redis://redis:6379/1
```

---

## Configuration

Everything is set through environment variables — in a `.env` file next to your
compose file, or directly in your host's stack settings. Nothing is hardcoded.

| Variable | Required | Description |
|---|---|---|
| `SECRET_KEY` | Yes | Django secret key. Generate one with `openssl rand -hex 32`. |
| `DEBUG` | No | Set to `False` in production (default `True`). |
| `DATABASE_URL` | No | PostgreSQL/PostGIS connection string. Omit to use SQLite. |
| `POSTGRES_PASSWORD` | If using the bundled db container | Password for the local PostGIS container. |
| `ALLOWED_HOSTS` | Yes | Comma-separated hostnames, no protocol (e.g. `roamly.example.com`). |
| `CSRF_TRUSTED_ORIGINS` | Yes | Comma-separated origins **with** protocol (e.g. `https://roamly.example.com`). |
| `REDIS_URL` | No | Redis connection string. Omit to use an in-memory cache. |

> Always change `SECRET_KEY` and `POSTGRES_PASSWORD` before exposing Roamly to
> the internet, and put it behind HTTPS (nginx or Caddy + Let's Encrypt). Don't
> publish the database port. See the in-app docs for reverse-proxy examples.

---

## Architecture

A single Django app (`tracker/`) inside the `roamly` project. No separate
services or task queues — background work (geocoding, visit computation, POI
download, S3 backups) runs in Python threads launched from views, and stale jobs
auto-resume on startup.

- **Database** — SQLite by default; PostgreSQL + PostGIS when `DATABASE_URL` is
  set. PostGIS enables vector tiles and spatial queries; the `Location` and
  `Visit` models have dual backends so the same code runs on both.
- **Caching** — Redis when `REDIS_URL` is set, in-memory otherwise. Per-user
  cache-generation keys invalidate every cached API response for a user whenever
  their location data changes.
- **Auth** — session-based for the web UI; an API-key Bearer middleware
  authenticates mobile and script clients on every endpoint.
- **Frontend** — server-rendered Django templates with vanilla JavaScript,
  MapLibre GL, and hand-rolled canvas charts. No build step.

**Stack:** Django 6 + Gunicorn, PostgreSQL 16 / PostGIS 3.4, Redis 7, Pillow for
image handling, boto3 for S3 backups. Android client: Kotlin, Jetpack Compose,
Room, WorkManager, Hilt.

### Running from source

```bash
git clone https://github.com/waxn/roamly.git
cd roamly
cp .env.example .env   # edit it
docker compose up -d --build
```

The committed `docker-compose.yml` uses `build: .` so it always builds locally;
swap that for `image: waxn/roamly:latest` under the `web` service to run the
published image instead.

---

## Updating

```bash
docker compose pull
docker compose up -d
```

Pulls the latest image and restarts the web container; migrations run on startup.
No git pull, no rebuild. Back up first if you like:

```bash
docker compose exec db pg_dump -U roamly roamly > backup_$(date +%Y%m%d).sql
```

---

## CI/CD

Every push to `main` and every version tag (`v1.2.3`) builds and pushes a
multi-arch image (amd64 + arm64) to Docker Hub via GitHub Actions
(`.github/workflows/docker-publish.yml`).

| Git event | Docker tags published |
|---|---|
| Push to `main` | `latest`, `main` |
| Tag `v1.2.3` | `1.2.3`, `1.2`, `latest` |

Pin to a release rather than tracking `latest`:

```yaml
image: waxn/roamly:1.2.3
```

---

## Documentation

Full setup guides, the API reference, tracker configuration, and troubleshooting
live in the app itself at `/docs/`.
</content>
