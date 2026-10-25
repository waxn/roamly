"""Relabel stored locations with the current offline geocoder.

Geocoding only ever labels points that have no label yet (``city=''``), so an
improvement to the geocoder — a bigger place list, the border check, newly
imported US boundaries — reaches new points only. ``regeocode`` walks the
existing history and rewrites the labels that come out differently.

Two ways in: ``python manage.py regeocode`` (every account, or ``--user``), and
Settings → Background Jobs → **Re-geocode All Points** (the signed-in account),
which runs it in a background thread with progress in the cache.

Caches one lookup per ~111m cell across the pass and writes only the rows whose
label should change (see ``_should_replace``: a containment match, a missing
label, or one in the wrong country — never just a different nearest-city
guess; ``rebuild`` replaces every label, for recovery), so a 1.4M-point history takes a few minutes and leaves
everything else untouched. A run is all or nothing: it finds every change
before writing any, then applies them in one transaction, so stopping it (or a
crash) leaves every point as it was.

The web job keeps its state in the cache rather than a model: a run is rare,
its status is worth nothing once seen, and the cache is shared by every
gunicorn worker, so a status poll served by another worker still sees it. A
heartbeat (``updated``) lets a run whose worker died be restarted rather than
looking busy forever.
"""
import logging
import time
from collections import Counter, defaultdict

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
# Kept for a month so the "relabelled towns" summary is still there when you
# come back to look at it.
_JOB_TTL_S = 30 * 24 * 3600
# Caps on what the summary carries (the cache value, and the page, stay small).
_SUMMARY_PLACES = 200
_SUMMARY_MOVES = 100


_ACTIVE = ('boundaries', 'running', 'applying', 'refreshing')


# US states whose Census town boundaries labels on this instance are built
# from. The original labels at home in Waldo, Maine came from them, and without
# them loaded the geocoder falls back to the nearest town of 1000+ (Brooks):
# relabelling with the table empty turned 800k points at home into a neighbour,
# twice. So both the re-geocode and the geocoding worker load these first
# (``ensure_boundaries``). Outside these states labels are the nearest town, as
# they always were here.
BOUNDARY_STATES = {'ME': 'Maine'}


class BoundaryError(Exception):
    pass


_BOUNDARY_LOCK = 'ensure_boundaries_lock'
_BOUNDARY_LOCK_TTL_S = 900


def ensure_boundaries(log=None, wait=True):
    """Load the town boundaries for ``BOUNDARY_STATES`` that aren't in the
    table yet. Raises ``BoundaryError`` if one still isn't there afterwards —
    never relabel against a table that is missing them. No-op without PostGIS.

    Single-flight across workers (a cache lock): with ``wait`` a second caller
    waits for the first to finish; without it, it returns at once."""
    import io
    from django.conf import settings
    from django.core.management import call_command
    from .models import Boundary

    if 'postgis' not in settings.DATABASES.get('default', {}).get('ENGINE', ''):
        return
    have = lambda name: Boundary.objects.filter(  # noqa: E731
        country_code='US', state=name, kind__in=('place', 'cousub')).exists()
    missing = [abbr for abbr, name in BOUNDARY_STATES.items() if not have(name)]
    if not missing:
        return
    if not cache.add(_BOUNDARY_LOCK, 1, _BOUNDARY_LOCK_TTL_S):
        if not wait:
            return
        deadline = time.time() + _BOUNDARY_LOCK_TTL_S
        while cache.get(_BOUNDARY_LOCK) and time.time() < deadline:
            time.sleep(3)
        missing = [abbr for abbr, name in BOUNDARY_STATES.items() if not have(name)]
        err = io.StringIO()
    else:
        try:
            if log:
                log(f"Loading town boundaries for {', '.join(BOUNDARY_STATES[a] for a in missing)}…")
            out, err = io.StringIO(), io.StringIO()
            # --keep: add these states only; never clear what is already loaded.
            call_command('import_boundaries', states=','.join(missing), kinds='place,cousub',
                         keep=True, stdout=out, stderr=err)
        finally:
            cache.delete(_BOUNDARY_LOCK)
    still = [BOUNDARY_STATES[a] for a in missing if not have(BOUNDARY_STATES[a])]
    if still:
        raise BoundaryError(
            f"Couldn't load the town boundaries for {', '.join(still)}, so nothing was "
            f"relabelled. {err.getvalue().strip()[:300]}")


class _Stopped(Exception):
    pass


def regeocode(user_ids=None, log=None, progress=None, should_stop=None, tally=None, rebuild=False):
    """Relabel locations (all, or those of ``user_ids``); returns
    ``(rows_scanned, rows_changed, changed_user_ids)``.

    **All or nothing.** The first phase only *reads*: it scans the history and
    collects every change in memory. The second applies them in a single
    transaction. ``should_stop()`` is checked throughout both, and stopping —
    in either phase — raises ``_Stopped`` with not one point changed: before
    the write phase nothing has been written, and inside it the transaction
    rolls back. Holding the changes in memory costs one id per relabelled point
    (an ``array``, 8 bytes each), and keeping the transaction to the short write
    phase means it never holds row locks for the minutes the scan takes.

    ``rebuild`` replaces *every* label with the geocoder's answer instead of
    only the ones ``_should_replace`` allows — for putting labels back after
    they were damaged, once the boundaries they came from are loaded again.

    ``progress(phase, done, total, changed)`` reports ``'scan'`` per chunk read
    and ``'apply'`` per chunk written. ``tally``, a :class:`Tally`, collects
    which places came and went.
    """
    from array import array
    from django.db import transaction
    from .models import Location
    from .offline_geocode import local_reverse_geocode

    def check_stop():
        if should_stop and should_stop():
            raise _Stopped()

    # Phase 0: the boundaries the labels come from. Writes reference data only,
    # never a point, so stopping after it still leaves every point as it was.
    if progress:
        progress('boundaries', 0, 0, 0)
    ensure_boundaries(log)
    check_stop()

    base = Location.objects.all()
    if user_ids is not None:
        base = base.filter(device__user_id__in=user_ids)
    total = base.count()
    if log:
        log(f"Checking {total} stored locations…")
    if progress:
        progress('scan', 0, total, 0)

    # ── Phase 1: read only ──
    cell_cache = {}
    changes = defaultdict(lambda: array('q'))   # new label tuple -> ids
    done = found = 0
    changed_users = set()
    last_id = 0
    while True:
        check_stop()
        rows = list(
            base.filter(id__gt=last_id).order_by('id')
            .values_list('id', 'latitude', 'longitude', 'device__user_id', *_FIELDS)[:_CHUNK]
        )
        if not rows:
            break
        last_id = rows[-1][0]

        for loc_id, lat, lon, uid, *stored in rows:
            cell = (round(lat, _CELL_PRECISION), round(lon, _CELL_PRECISION))
            hit = cell_cache.get(cell)
            if hit is None:
                res = local_reverse_geocode(lat, lon) or dict.fromkeys(_FIELDS, '')
                # Never store city='' — the geocoding worker selects on it and
                # would re-label the point forever.
                hit = ((res['city'] or 'Unknown', res['state'], res['country'],
                        res['country_code'], res['place_name']),
                       res.get('source', 'nearest'), res.get('zone_cc', ''))
                cell_cache[cell] = hit
            new, source, zone_cc = hit
            stored = tuple(stored)
            if stored != new and (rebuild or _should_replace(stored, source, zone_cc)):
                changes[new].append(loc_id)
                changed_users.add(uid)
                found += 1
                final = new
            else:
                final = stored
            if tally is not None:
                tally.add(stored[4], final[4])

        done += len(rows)
        if log:
            log(f"  {done}/{total} checked, {found} to relabel")
        if progress:
            progress('scan', done, total, found)

    # ── Phase 2: one transaction ──
    if log:
        log(f"Relabelling {found} locations…")
    applied = 0
    if found:
        with transaction.atomic():
            for new, ids in changes.items():
                fields = dict(zip(_FIELDS, new))
                for i in range(0, len(ids), _CHUNK):
                    check_stop()
                    part = ids[i:i + _CHUNK].tolist()
                    Location.objects.filter(id__in=part).update(**fields)
                    applied += len(part)
                    if progress:
                        progress('apply', applied, found, found)

    return done, found, changed_users


def _should_replace(stored, source, zone_cc):
    """Whether a re-geocode may overwrite ``stored`` with a new label.

    Only for a reason better than "the geocoder now guesses differently". A
    stored label may have come from a containment match (a US town boundary,
    since cleared or never loaded here) or from the old online geocoder, and
    both beat a nearest-city guess: a re-geocode that replaced any label that
    differed, run with no boundaries loaded, relabelled 810k points at home in
    Waldo, Maine as neighbouring Morrill, whose centre was closer. So a label is
    replaced only when:

      - the new one is a ``'boundary'`` match — the point is *inside* that town;
      - the stored one is empty or 'Unknown' — there is nothing to lose;
      - the stored country disagrees with the point's timezone country — the
        label is on the wrong side of a border (Campobello labelled Eastport).
    """
    city, cc = stored[0], stored[3]
    if source == 'boundary':
        return True
    if not city or city == 'Unknown':
        return True
    return bool(zone_cc and cc and cc != zone_cc)


class Tally:
    """Point counts per place label before and after a run, plus how many
    points moved from each old label to each new one.

    Built from the rows the scan reads anyway, so it costs no extra query. A
    place is *added* if no point carried its label before and some do now, and
    *removed* the other way round; that is what "which towns changed" means to
    someone reading their Visits page."""

    def __init__(self):
        self.before = Counter()
        self.after = Counter()
        self.moves = Counter()

    def add(self, old, new):
        if old:
            self.before[old] += 1
        if new:
            self.after[new] += 1
        if old != new:
            self.moves[(old or '(no label)', new or '(no label)')] += 1

    def summary(self):
        added = sorted(((p, n) for p, n in self.after.items() if p not in self.before),
                       key=lambda x: -x[1])
        removed = sorted(((p, n) for p, n in self.before.items() if p not in self.after),
                         key=lambda x: -x[1])
        moves = self.moves.most_common(_SUMMARY_MOVES)
        return {
            'added': [[p, n] for p, n in added[:_SUMMARY_PLACES]],
            'added_total': len(added),
            'removed': [[p, n] for p, n in removed[:_SUMMARY_PLACES]],
            'removed_total': len(removed),
            'moves': [[o, n, c] for (o, n), c in moves],
            'moves_total': len(self.moves),
        }


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
    # The stats refresh and the boundary download are long single calls with no
    # progress of their own.
    limit = _STALE_REFRESHING_S if job.get('status') in ('refreshing', 'boundaries') else _STALE_AFTER_S
    if job.get('status') in _ACTIVE and time.time() - job.get('updated', 0) > limit:
        job = _write(user_id, status='failed', error='The job stopped responding; start it again.')
    return {k: job.get(k) for k in ('status', 'done', 'total', 'changed', 'applied', 'error', 'summary', 'finished')}


def start(user_id, rebuild=False):
    """Start a run for ``user_id`` unless one is already going. Returns the status."""
    current = get_status(user_id)
    if current['status'] in _ACTIVE:
        return current
    # A double click must not start two threads.
    if not cache.add(f"regeocode_starting:{user_id}", 1, 10):
        return current
    cache.delete(_stop_key(user_id))
    _write(user_id, status='running', done=0, total=0, changed=0, applied=0, error='', summary=None, finished=None)

    from .scheduler_utils import db_thread
    db_thread(_run, args=(user_id, rebuild), name=f"regeocode-{user_id}").start()
    return get_status(user_id)


def stop(user_id):
    # Once the stats refresh starts, the relabel has committed; too late to undo.
    if get_status(user_id)['status'] not in ('boundaries', 'running', 'applying'):
        return False
    cache.set(_stop_key(user_id), 1, 3600)
    return True


def _run(user_id, rebuild=False):
    tally = Tally()

    def progress(phase, d, t, c):
        if phase == 'boundaries':
            _write(user_id, status='boundaries')
        elif phase == 'scan':
            _write(user_id, status='running', done=d, total=t, changed=c)
        else:
            _write(user_id, status='applying', applied=d, changed=t)

    try:
        done, changed, users = regeocode(
            [user_id],
            progress=progress,
            should_stop=lambda: bool(cache.get(_stop_key(user_id))),
            tally=tally,
            rebuild=rebuild,
        )
        if users:
            _write(user_id, status='refreshing')
            refresh_after(users)
        _write(user_id, status='completed', done=done, changed=changed,
               summary=tally.summary(), finished=time.time())
    except _Stopped:
        # Nothing was written (see regeocode), so there is nothing to refresh
        # and no summary to show.
        _write(user_id, status='stopped', summary=None, finished=time.time())
    except Exception as e:
        logger.exception("regeocode failed for user %s", user_id)
        _write(user_id, status='failed', error=str(e)[:300], summary=None, finished=time.time())
