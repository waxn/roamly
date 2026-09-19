"""Shared helpers for the daemon schedulers started from ``apps.ready()``."""
import time

from django.apps import apps

# Generous ceiling. A real startup clears this in milliseconds; the timeout only
# exists so a scheduler can never wedge forever on a boot that stalled for some
# unrelated reason.
_REGISTRY_WAIT_TIMEOUT_S = 30.0
_REGISTRY_POLL_S = 0.05


def wait_for_app_registry(timeout=_REGISTRY_WAIT_TIMEOUT_S):
    """Block until Django's app registry is fully populated.

    Every scheduler thread is started from ``TrackerConfig.ready()``, which runs
    *during* ``apps.populate()`` — so a loop whose first act is a query races the
    tail of startup. Models are already imported by that phase, so the query does
    succeed, but ``apps.ready`` is still False and Django emits ``RuntimeWarning:
    Accessing the database during app initialization is discouraged`` (added in
    Django 5.0) once per such thread on every boot. The real cost is not the
    warning: a query that raises there is swallowed by the loop's own ``except``,
    so a scheduler can silently skip its *first* sweep and not retry until its
    next one — up to 6 hours later for the slowest of them.

    Waiting on the flag rather than sleeping a fixed interval means blocking for
    exactly as long as the race lasts, and leaves each loop's own cadence alone.

    Call this from inside the thread, never from ``start_*``: called from the
    starter it would block ``ready()`` itself, which is the very thing that has
    not finished, and deadlock startup.

    Returns True if the registry became ready, False on timeout — in which case
    the caller should carry on regardless, which is the pre-existing behaviour.
    """
    deadline = time.monotonic() + timeout
    while not apps.ready:
        if time.monotonic() >= deadline:
            return False
        time.sleep(_REGISTRY_POLL_S)
    return True
