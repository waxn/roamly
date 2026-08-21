"""Auto-detect newly-visited US states for the optional self-hosted Valhalla
service's tile_urls list.

Unlike road_download_tasks/rail_download_tasks/poi_tasks, this module never
talks to Overpass or downloads anything itself — Valhalla's own official
Docker image (ghcr.io/valhalla/valhalla-scripted, see docker-compose.yml)
downloads its .osm.pbf extracts and builds routing tiles itself, from the
tile_urls environment variable, whenever the container (re)starts. There is
no live "add one more region" API to call.

So this module's whole job is: notice a new US state in anyone's location
history, work out its Geofabrik extract URL, and record it in
DownloadedRegion(kind='valhalla') so Admin Panel -> Downloads -> Valhalla
Tiles can show the admin the exact VALHALLA_TILE_URLS value to paste into
.env. Applying it still needs a manual `docker compose restart valhalla` —
there is no way to trigger that from inside the app without giving something
Docker-socket (i.e. host-root-equivalent) access, which was a deliberate
call not to make.
"""

import logging

logger = logging.getLogger(__name__)

GEOFABRIK_US_BASE = 'https://download.geofabrik.de/north-america/us'


def _state_slug(state):
    """'District of Columbia' -> 'district-of-columbia', matching Geofabrik's
    per-state extract filenames exactly (checked against every US state's
    actual download URL, not guessed)."""
    return state.strip().lower().replace(' ', '-')


def _state_url(state):
    return f'{GEOFABRIK_US_BASE}/{_state_slug(state)}-latest.osm.pbf'


def visited_us_states():
    """Every distinct US state anyone on the instance has a geocoded point
    in, across every device on every user's account — same instance-wide
    scope as the other three downloads. Only US is supported: Geofabrik's
    non-US regions don't follow one predictable URL pattern the way US state
    extracts do, so a non-US point contributes nothing here rather than
    guessing a URL that might be wrong.
    """
    from .models import Location

    # order_by() clears Location's Meta.ordering (['-timestamp']); without it
    # the inherited ordering forces timestamp into the SELECT and breaks
    # DISTINCT, returning a duplicate row per matching point instead of one
    # per state (same fix countries_api applies for the same reason).
    return sorted(
        s for s in Location.objects.filter(country_code__iexact='US')
        .exclude(state='').exclude(state__isnull=True)
        .order_by().values_list('state', flat=True).distinct()
        if s
    )


def sync_regions():
    """Add any newly-visited state to DownloadedRegion(kind='valhalla').

    Called both by the admin's manual "Rescan" button and the auto-download
    sweep (auto_download_tasks.py) — same "no separate auto vs manual code
    path" shape as the other three download kinds. Returns the number of
    newly-added states.
    """
    from .models import DownloadedRegion

    covered = set(DownloadedRegion.objects.filter(kind='valhalla').values_list('key', flat=True))
    new_states = [s for s in visited_us_states() if s not in covered]
    if new_states:
        DownloadedRegion.objects.bulk_create(
            [DownloadedRegion(kind='valhalla', key=s) for s in new_states],
            ignore_conflicts=True,
        )
        logger.info('valhalla tiles: %d new state(s) detected: %s',
                    len(new_states), ', '.join(new_states))
    return len(new_states)


def get_status():
    """Current state for the Admin Panel card: covered states, the
    tile_urls value to paste into .env, and a count for the UI badge."""
    from .models import DownloadedRegion

    states = sorted(DownloadedRegion.objects.filter(kind='valhalla').values_list('key', flat=True))
    return {
        'states': states,
        'count': len(states),
        'tile_urls': ' '.join(_state_url(s) for s in states),
    }
