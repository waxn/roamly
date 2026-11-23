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

Also update the relevant file under `docs/claude/` (or this CLAUDE.md, if it is a workflow rule) whenever something architectural or behavioural is added or changed.

## Running the app

Everything runs inside Docker. Never run `python manage.py` directly on the host. Do not attempt to run `docker compose` or `sudo docker compose` commands — these require a password and an interactive terminal. Tell the user to run these themselves.

```bash
# Start (builds if needed)
sudo docker compose up -d --build

# Restart without rebuild (picks up template/static changes immediately)
sudo docker compose restart web

# View logs
sudo docker compose logs web --tail=50

# Django shell
sudo docker compose exec web python manage.py shell
```

**`migrate` runs automatically on every container start** — the `web` service's `command:` in `docker-compose.yml` is `migrate --noinput && collectstatic --noinput && gunicorn ...`, so `up -d --build` (or even a plain `restart web`) already applies any pending migration. There is no separate manual migrate step in the normal workflow.

**`makemigrations` is never a deploy-time step either.** Migration files are written (by hand or via `makemigrations`) at *development* time and committed straight to git alongside the model change — `tracker/migrations/` is volume-mounted (along with `staticfiles/` and `media/`), so a rebuild already has the file; there's nothing left to generate on the deployed container. Only run `makemigrations` yourself if you've hand-edited a model with no migration file for it yet, which shouldn't happen in the normal flow.

**Templates are not volume-mounted** — a template edit needs `up -d --build` to reach the container. Python file changes need at least a `restart web`; a new model/migration needs `up -d --build` so the fresh migration file and any new dependency are both picked up.

## Tests and CI

`tracker/tests/` (pytest-django) needs PostGIS. Locally: `sudo docker compose exec web pytest` (dev requirements are not in the image). `.github/workflows/tests.yml` installs the GeoDjango libraries, starts `postgis/postgis:16-3.5` + Redis, runs `ruff check --select E9,F63,F7,F82` (syntax and undefined names only — `pyproject.toml`'s wider rule set still has ~140 existing findings) and `pytest --create-db`. `docker-publish.yml` calls it as a reusable workflow and **pushes no image unless it passes**.

## Architecture

Single Django app (`tracker/`) inside the `roamly` project. No separate services — background work (geocoding, backups, POI download) runs in Python threads launched from views.

## Detailed documentation

The detailed architecture notes live in `docs/claude/`, one file per area. **Read only the file(s) for the area you are touching** — they were split out of this file verbatim so nothing is lost, and each opens with a "Read when" line. Cross-references like "see X above" inside a doc may point at a section that now lives in a sibling file; use the table below to find it.

| File | Read when |
|---|---|
| [`docs/claude/achievements.md`](docs/claude/achievements.md) — Achievements and raw data | Achievements page/scan/badges, nav Data menu, /data/ sources. |
| [`docs/claude/activities.md`](docs/claude/activities.md) — Activity recording | Recorded rides/walks/runs, ActivityTrack, smoothing pipeline. |
| [`docs/claude/admin-panel.md`](docs/claude/admin-panel.md) — Admin panel and site config | Admin accounts, admin panel tabs, custom JS, contact form. |
| [`docs/claude/api-conventions.md`](docs/claude/api-conventions.md) — URL / API conventions | Adventure URLs and editor endpoints. |
| [`docs/claude/ask-ai.md`](docs/claude/ask-ai.md) — AI Ask, proposals and journal recap | Ask chat, tools, proposals, AI recap on Journals. |
| [`docs/claude/auth-security.md`](docs/claude/auth-security.md) — Auth, security and sessions | Login, signup, rate limits, CAPTCHA, email verification, TOTP, CSRF, uploads, audit fixes, devices. |
| [`docs/claude/backups.md`](docs/claude/backups.md) — Backups and restore | Backup format, S3, zip, restore, progress. |
| [`docs/claude/cells.md`](docs/claude/cells.md) — Cell coverage | CellSample, towers, carriers, CellScanner, Cell signal page. |
| [`docs/claude/dependencies.md`](docs/claude/dependencies.md) — Dependencies | Changing requirements.txt/constraints.txt, Gradle versions, or upgrading a pinned package. |
| [`docs/claude/downloads.md`](docs/claude/downloads.md) — Downloads, Overpass and auto-download | Road/subway/POI downloads, DownloadedRegion, Overpass pool/failover. |
| [`docs/claude/editor.md`](docs/claude/editor.md) — Map editor, trash, gap fill | /editor/, relabel, routing, trash, single missed-fix fill. |
| [`docs/claude/email.md`](docs/claude/email.md) — Summary emails and tracking alerts | AI summary emails, unsubscribe, no-data alerts. |
| [`docs/claude/family-circle.md`](docs/claude/family-circle.md) — Family Circle | Family circles, geofences, FCM push, Simple Mode. |
| [`docs/claude/flights.md`](docs/claude/flights.md) — Flights | Flight log, boarding passes, track matching, OpenSky, AeroDataBox, detected flights. |
| [`docs/claude/geocoding.md`](docs/claude/geocoding.md) — Geocoding and boundaries | Reverse geocoding, labels, re-geocode, Boundary import. |
| [`docs/claude/hardware-tracker.md`](docs/claude/hardware-tracker.md) — Hardware tracker | trackerhw/ firmware, pairing, upload, BLE. |
| [`docs/claude/health.md`](docs/claude/health.md) — Health Connect and Zepp | Health tab, HealthSample/HealthWorkout, Zepp sync, health file import. |
| [`docs/claude/import-export.md`](docs/claude/import-export.md) — Import and export formats | CSV/GPX/JSON/KML import, streamed export. |
| [`docs/claude/infra.md`](docs/claude/infra.md) — Infrastructure and runtime | DB/cache behaviour, gunicorn, schedulers/threads, indexes, compression, outbound URL rules. |
| [`docs/claude/key-files.md`](docs/claude/key-files.md) — Key files | Locating which file owns a feature. |
| [`docs/claude/local-time.md`](docs/claude/local-time.md) — Local time | Timezone derivation and activation, DST handling. |
| [`docs/claude/logging.md`](docs/claude/logging.md) — Logging and monitoring | AccessLog/ActionLog, rollups, retention, admin log endpoints, country breakdown. |
| [`docs/claude/map.md`](docs/claude/map.md) — Map rendering and map tools | /map/ rendering, tiles, basemaps, fog, globe, Trip Lines. |
| [`docs/claude/mobile-app.md`](docs/claude/mobile-app.md) — Android app: design, updates, navigation, screens | Anything under mobile/ except tracking capture. |
| [`docs/claude/mobile-tracking.md`](docs/claude/mobile-tracking.md) — Android app: location capture and tracking | LocationTrackingService, LocationSource, filters, upload, survivability. |
| [`docs/claude/models.md`](docs/claude/models.md) — Models | Model reference. |
| [`docs/claude/overlaps.md`](docs/claude/overlaps.md) — Device overlaps and notification bell | DeviceOverlap, hide/unhide, bell notifications. |
| [`docs/claude/places.md`](docs/claude/places.md) — Custom places and geofence notifications | CustomPlace, suggestions, arrival/departure notifications. |
| [`docs/claude/preferences.md`](docs/claude/preferences.md) — User-facing preferences | localStorage roamly_* preference keys. |
| [`docs/claude/quick-notes.md`](docs/claude/quick-notes.md) — Quick notes | "What I did today" notes: map bar, notes API, Android evening reminder. |
| [`docs/claude/sharing.md`](docs/claude/sharing.md) — Adventure membership and location share links | Adventure consent model, LocationShare links. |
| [`docs/claude/stats.md`](docs/claude/stats.md) — Stats, snapshots and distance | Stats/Visits/Places snapshots, Daily Distance heatmap, stats deep-links, distance travelled. |
| [`docs/claude/subway-misc.md`](docs/claude/subway-misc.md) — Subway gaps, POI matching, quality scan, settings nav | Subway gap filling, POI matching, flag scan, settings jump-nav. |
| [`docs/claude/transport-roads.md`](docs/claude/transport-roads.md) — Transport detection and road snapping | Transport modes, road snapping providers, RoadSegment. |
| [`docs/claude/trip-blog.md`](docs/claude/trip-blog.md) — Trip blog | Trip blog models, share links, posts, video, digest, Android. |
| [`docs/claude/visit-time.md`](docs/claude/visit-time.md) — Visit time spent and gap bridging | Visits dwell time, gap bridging rule. |
| [`docs/claude/web-ui.md`](docs/claude/web-ui.md) — Web UI: design system, appearance, shared components | Templates, CSS, appearance layers, RoamlyForms/Dialog/Toast/Intro, settings layout, landing/SEO. |
