"""Relabel stored locations with the current offline geocoder.

Geocoding only ever labels points that have no label yet (``city=''``), so an
improvement to the geocoder — a bigger place list, the border check, newly
imported US boundaries — reaches new points only. ``regeocode`` walks the
existing history and rewrites the labels that come out differently.

Two ways in: ``python manage.py regeocode`` (every account, or ``--user``), and
Settings → Background Jobs → **Re-geocode All Points** (the signed-in account),
which runs it in a background thread with progress in the cache.

Caches one lookup per ~111m cell across the pass and writes only the rows whose
label actually changed, so a 1.4M-point history takes a few minutes and leaves
everything else untouched.

The web job keeps its state in the cache rather than a model: a run is rare,
its status is worth nothing once seen, and the cache is shared by every
gunicorn worker, so a status poll served by another worker still sees it. A
heartbeat (``updated``) lets a run whose worker died be restarted rather than
looking busy forever.
"""
import logging
import time
from collections import defaultdict

from django.core.cache import cache

logger = logging.getLogger(__name__)

_CELL_PRECISION = 3  # ~111m grid for the lookup cache
_CHUNK = 5000
_FIELDS = ('city', 'state', 'country', 'country_code', 'place_name')

# A running job writes progress at least once per chunk (seconds apart); one
# that has been silent this long lost its worker. The stats recompute at the end
# is one long call with no progress of its own, so it gets far longer.
_STALE_AFTER_S = 180
_STALE_REFRESHING_S = 1800
_JOB_TTL_S = 24 * 3600


class _Stopped(Exception):
    pass


def regeocode(user_ids=None, log=None, progress=None, should_stop=None):
    """Relabel locations (all, or those of ``user_ids``); returns
    ``(rows_scanned, rows_changed, changed_user_ids)``.

    ``progress(done, total, changed)`` is called per chunk; ``should_stop()``
    is checked per chunk and stops the run (rows already relabelled stay so).
    """
    from .models import Location
    from .offline_geocode import local_reverse_geocode

    base = Location.objects.all()
    if user_ids is not None:
        base = base.filter(device__user_id__in=user_ids)
    total = base.count()
    if log:
        log(f"Re-geocoding {total} stored locations…")
    if progress:
        progress(0, total, 0)

    cell_cache = {}
    done = changed = 0
    changed_users = set()
    last_id = 0
    while True:
        if should_stop and should_stop():
            raise _Stopped()
        rows = list(
            base.filter(id__gt=last_id).order_by('id')
            .values_list('id', 'latitude', 'longitude', 'device__user_id', *_FIELDS)[:_CHUNK]
        )
        if not rows:
            break
        last_id = rows[-1][0]

        groups = defaultdict(list)
        for loc_id, lat, lon, uid, *stored in rows:
            cell = (round(lat, _CELL_PRECISION), round(lon, _CELL_PRECISION))
            new = cell_cache.get(cell)
            if new is None:
                res = local_reverse_geocode(lat, lon) or dict.fromkeys(_FIELDS, '')
                # Never store city='' — the geocoding worker selects on it and
                # would re-label the point forever.
                new = (res['city'] or 'Unknown', res['state'], res['country'],
                       res['country_code'], res['place_name'])
                cell_cache[cell] = new
            if tuple(stored) != new:
                groups[new].append(loc_id)
                changed_users.add(uid)

        for new, ids in groups.items():
            Location.objects.filter(id__in=ids).update(**dict(zip(_FIELDS, new)))
            changed += len(ids)
        done += len(rows)
        if log:
            log(f"  {done}/{total} scanned, {changed} relabelled")
        if progress:
            progress(done, total, changed)

    return done, changed, changed_users


def refresh_after(user_ids):
    """Make relabelled accounts show their new labels now: bust the response
    caches and recompute the stats snapshot (Visits, Stats, Places)."""
    from .stats_tasks import compute_snapshot
    from .views import _bust_user_cache

    for uid in user_ids:
        _bust_user_cache(uid)
        try:
            compute_snapshot(uid)
        except Exception:
            logger.exception("stats recompute after regeocode failed for user %s", uid)


# ── Web job (one account, from Settings) ─────────────────────────────────────

def _job_key(user_id):
    return f"regeocode_job:{user_id}"


def _stop_key(user_id):
    return f"regeocode_stop:{user_id}"


def _write(user_id, **fields):
    job = cache.get(_job_key(user_id)) or {}
    job.update(fields, updated=time.time())
    cache.set(_job_key(user_id), job, _JOB_TTL_S)
    return job


def get_status(user_id):
    job = cache.get(_job_key(user_id))
    if not job:
        return {'status': 'idle', 'done': 0, 'total': 0, 'changed': 0}
    limit = _STALE_REFRESHING_S if job.get('status') == 'refreshing' else _STALE_AFTER_S
    if job.get('status') in ('running', 'refreshing') and time.time() - job.get('updated', 0) > limit:
        job = _write(user_id, status='failed', error='The job stopped responding; start it again.')
    return {k: job.get(k) for k in ('status', 'done', 'total', 'changed', 'error')}


def start(user_id):
    """Start a run for ``user_id`` unless one is already going. Returns the status."""
    current = get_status(user_id)
    if current['status'] in ('running', 'refreshing'):
        return current
    # A double click must not start two threads.
    if not cache.add(f"regeocode_starting:{user_id}", 1, 10):
        return current
    cache.delete(_stop_key(user_id))
    _write(user_id, status='running', done=0, total=0, changed=0, error='')

    from .scheduler_utils import db_thread
    db_thread(_run, args=(user_id,), name=f"regeocode-{user_id}").start()
    return get_status(user_id)


def stop(user_id):
    if get_status(user_id)['status'] != 'running':
        return False
    cache.set(_stop_key(user_id), 1, 3600)
    return True


def _run(user_id):
    try:
        done, changed, users = regeocode(
            [user_id],
            progress=lambda d, t, c: _write(user_id, done=d, total=t, changed=c),
            should_stop=lambda: bool(cache.get(_stop_key(user_id))),
        )
        if users:
            _write(user_id, status='refreshing')
            refresh_after(users)
        _write(user_id, status='completed', done=done, changed=changed)
    except _Stopped:
        # Rows relabelled so far stay relabelled; refresh so they show.
        _write(user_id, status='refreshing')
        refresh_after([user_id])
        _write(user_id, status='stopped')
    except Exception as e:
        logger.exception("regeocode failed for user %s", user_id)
        _write(user_id, status='failed', error=str(e)[:300])
