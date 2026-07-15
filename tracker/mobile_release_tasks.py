"""Proactive refresh of the cached "latest mobile release" metadata.

The release cache in ``views._fetch_latest_mobile_release`` is otherwise only
refreshed *lazily* — when a request hits ``/api/mobile/version/`` after the TTL
has expired. But the mobile app throttles its own update check to ~24h, so the
endpoint is hit rarely and a freshly-published release could stay invisible for
a long time (observed: server pinned to an old version long after a new GitHub
release went live).

This daemon thread force-refreshes the cache every ~15 min so the server always
serves a current version regardless of request timing. No external
scheduler/queue — mirrors ``backup_tasks``/``stats_tasks``: a thread started
from ``apps.ready()``. It runs in every gunicorn worker; with LocMemCache that's
exactly what keeps each worker's per-process cache warm. GitHub's unauthenticated
API allows 60 req/hr/IP, and even several workers refreshing every 15 min stay
well under that.
"""

import logging
import threading
import time

logger = logging.getLogger(__name__)

REFRESH_INTERVAL = 900  # 15m — keep the release cache warm without hammering GitHub.
_scheduler_thread = None


def _mobile_release_scheduler_loop():
    from django.db import close_old_connections

    while True:
        try:
            # No DB access here, but match the established daemon pattern so a
            # stale connection can never silently wedge the loop.
            close_old_connections()
            # Lazy import so app-ready doesn't drag the whole views module.
            from .views import _fetch_latest_mobile_release
            # force=True bypasses the read cache and re-fetches from GitHub. The
            # internal refresh cooldown (120s) is far shorter than our interval,
            # so the force always takes effect.
            _fetch_latest_mobile_release(force=True)
        except Exception:
            logger.exception("Mobile release refresh error")

        time.sleep(REFRESH_INTERVAL)


def start_mobile_release_scheduler():
    """Start the release-cache refresher thread (called once on app startup)."""
    global _scheduler_thread
    if _scheduler_thread is not None and _scheduler_thread.is_alive():
        return
    _scheduler_thread = threading.Thread(target=_mobile_release_scheduler_loop, daemon=True)
    _scheduler_thread.start()
    logger.info("Mobile release scheduler started")
