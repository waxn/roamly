# URL / API conventions

_Read when: Adventure URLs and editor endpoints._

## URL / API conventions

Adventure API URLs use `/api/trips/` paths (backward compat). View functions are still named `trips_api`, `trip_detail`, etc. — only UI and model classes use "adventure" naming.

Public adventure pages: `/adventure/<slug>/` (`adventure_public.html`); public API: `/api/trip/<slug>/`.

Adventure CMS editor: `/adventures/<id>/edit/` (login + membership required). Endpoints:
- `PATCH /api/trips/<id>/body/` — save body JSON (also accepts `name`, `subtitle`)
- `POST /api/trips/<id>/cover/` — upload cover image
- `POST /api/trips/<id>/cover/delete/` — remove cover image
- `POST /api/trips/<id>/blurbs/<id>/update/` — edit blurb (text, lat/lng, location_name)

Inline pin refs: `[^pin:42]` references a located `AdventureBlurb` (POI) by id. Number computed from document order, not stored. POIs render as coral star markers on editor + public maps; clicking opens a themed popup (author + title + notes + photos). Create/edit via the map "add POI" flow (uses the repurposed `/places/` endpoints).
