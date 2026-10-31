"""Relabel stored locations with the current offline geocoder.

    python manage.py regeocode                 # every account
    python manage.py regeocode --user linus    # one account
    python manage.py regeocode --rebuild       # replace every label (recovery)
    python manage.py regeocode --overwrite-hand-labels   # replace map editor labels too

Writes only the rows whose label should change (``--rebuild``: every label that
differs), then busts each changed account's caches and recomputes its stats
snapshot. The same run is in Settings →
Background Jobs → Re-geocode All Points for the signed-in account. See
``tracker/regeocode_tasks.py``.
"""
from django.contrib.auth.models import User
from django.core.management.base import BaseCommand, CommandError

from tracker.regeocode_tasks import refresh_after, regeocode


class Command(BaseCommand):
    help = "Relabel stored locations with the current offline geocoder (only rows whose label changes)."

    def add_arguments(self, parser):
        parser.add_argument('--user', help='Only this username')
        parser.add_argument('--rebuild', action='store_true',
                            help='Replace every label with the geocoder\'s answer, not only '
                                 'boundary matches, missing and wrong-country labels. For '
                                 'recovery; load the boundaries first.')
        parser.add_argument('--overwrite-hand-labels', action='store_true',
                            help='Also replace labels set by hand in the map editor, '
                                 'which retires those edits so they can no longer be undone.')

    def handle(self, *args, **opts):
        user_ids = None
        if opts.get('user'):
            u = User.objects.filter(username=opts['user']).first()
            if not u:
                raise CommandError(f"No user '{opts['user']}'")
            user_ids = [u.id]

        done, changed, users = regeocode(user_ids, log=self.stdout.write, rebuild=opts['rebuild'],
                                         overwrite_hand=opts['overwrite_hand_labels'])
        if users:
            self.stdout.write(f"Recomputing stats for {len(users)} account(s)…")
            refresh_after(users)
        self.stdout.write(self.style.SUCCESS(f"Scanned {done}, relabelled {changed}"))
