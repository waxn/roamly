"""Relabel stored locations with the current offline geocoder.

Geocoding only ever labels points that have no label yet (``city=''``), so an
improvement to the geocoder — a bigger place list, the border check, newly
imported US boundaries — reaches new points only. This walks the existing
history and rewrites the labels that come out differently.

    python manage.py regeocode                 # every account
    python manage.py regeocode --user linus    # one account

Caches one lookup per ~111m cell across the pass and writes only the rows whose
label actually changed, so a 1.4M-point history takes a few minutes and leaves
everything else untouched. Each changed account's caches are busted and its
stats snapshot (Visits, Stats, Places) recomputed, so the new labels show up
straight away rather than after the nightly refresh.
"""
from collections import defaultdict

from django.contrib.auth.models import User
from django.core.management.base import BaseCommand, CommandError

_CELL_PRECISION = 3  # ~111m grid for the lookup cache
_CHUNK = 5000
_FIELDS = ('city', 'state', 'country', 'country_code', 'place_name')


def regeocode(user_ids=None, log=print):
    """Relabel locations (all, or those of ``user_ids``); returns
    ``(rows_scanned, rows_changed, changed_user_ids)``."""
    from tracker.models import Location
    from tracker.offline_geocode import local_reverse_geocode

    base = Location.objects.all()
    if user_ids is not None:
        base = base.filter(device__user_id__in=user_ids)
    total = base.count()
    log(f"Re-geocoding {total} stored locations…")

    cell_cache = {}
    done = changed = 0
    changed_users = set()
    last_id = 0
    while True:
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
        log(f"  {done}/{total} scanned, {changed} relabelled")

    return done, changed, changed_users


class Command(BaseCommand):
    help = "Relabel stored locations with the current offline geocoder (only rows whose label changes)."

    def add_arguments(self, parser):
        parser.add_argument('--user', help='Only this username')

    def handle(self, *args, **opts):
        from tracker.stats_tasks import compute_snapshot
        from tracker.views import _bust_user_cache

        user_ids = None
        if opts.get('user'):
            u = User.objects.filter(username=opts['user']).first()
            if not u:
                raise CommandError(f"No user '{opts['user']}'")
            user_ids = [u.id]

        done, changed, users = regeocode(user_ids, log=self.stdout.write)

        for uid in users:
            _bust_user_cache(uid)
            self.stdout.write(f"  recomputing stats for user {uid}…")
            try:
                compute_snapshot(uid)
            except Exception as e:
                self.stderr.write(self.style.WARNING(f"  stats recompute failed for user {uid}: {e}"))
        self.stdout.write(self.style.SUCCESS(f"Scanned {done}, relabelled {changed}"))
