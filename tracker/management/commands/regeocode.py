"""Relabel stored locations with the current offline geocoder.

    python manage.py regeocode                 # every account
    python manage.py regeocode --user linus    # one account

Writes only the rows whose label changed, then busts each changed account's
caches and recomputes its stats snapshot. The same run is in Settings →
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

    def handle(self, *args, **opts):
        user_ids = None
        if opts.get('user'):
            u = User.objects.filter(username=opts['user']).first()
            if not u:
                raise CommandError(f"No user '{opts['user']}'")
            user_ids = [u.id]

        done, changed, users = regeocode(user_ids, log=self.stdout.write)
        if users:
            self.stdout.write(f"Recomputing stats for {len(users)} account(s)…")
            refresh_after(users)
        self.stdout.write(self.style.SUCCESS(f"Scanned {done}, relabelled {changed}"))
