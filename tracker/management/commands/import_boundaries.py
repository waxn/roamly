"""Import US Census TIGER boundaries for offline point-in-polygon reverse geocoding.

Downloads the cartographic-boundary (cb) shapefiles for **places** (incorporated
cities + CDPs) and **county subdivisions** (the New England towns/townships — this
is what makes a point resolve to "Waldo" instead of the nearest bigger town) and
loads them into the Boundary table. Runs inside the web container, which has GDAL.

    # everything (50 states + DC) — a few minutes, ~tens of MB
    python manage.py import_boundaries

    # just the states you're in, to start fast
    python manage.py import_boundaries --states ME,VA

    # ...and re-label every existing point with the new boundaries in one go
    python manage.py import_boundaries --states ME,VA --regeocode

`--regeocode` relabels ALL stored locations (overwriting any nearest-city fallback
labels) using the freshly loaded boundaries, so the backlog gets the exact towns.
"""
import io
import os
import time
import tempfile
import zipfile
import urllib.request
from collections import defaultdict

from django.core.management.base import BaseCommand

# 50 states + DC → 2-digit state FIPS used in the cb filenames.
STATE_FIPS = {
    'AL': '01', 'AK': '02', 'AZ': '04', 'AR': '05', 'CA': '06', 'CO': '08',
    'CT': '09', 'DE': '10', 'DC': '11', 'FL': '12', 'GA': '13', 'HI': '15',
    'ID': '16', 'IL': '17', 'IN': '18', 'IA': '19', 'KS': '20', 'KY': '21',
    'LA': '22', 'ME': '23', 'MD': '24', 'MA': '25', 'MI': '26', 'MN': '27',
    'MS': '28', 'MO': '29', 'MT': '30', 'NE': '31', 'NV': '32', 'NH': '33',
    'NJ': '34', 'NM': '35', 'NY': '36', 'NC': '37', 'ND': '38', 'OH': '39',
    'OK': '40', 'OR': '41', 'PA': '42', 'RI': '44', 'SC': '45', 'SD': '46',
    'TN': '47', 'TX': '48', 'UT': '49', 'VT': '50', 'VA': '51', 'WA': '53',
    'WV': '54', 'WI': '55', 'WY': '56',
}

_CELL_PRECISION = 3  # ~111m grid for the re-geocode cell cache


class Command(BaseCommand):
    help = "Load US Census TIGER place + county-subdivision boundaries for offline reverse geocoding."

    def add_arguments(self, parser):
        parser.add_argument('--year', default='2023', help='TIGER vintage (default 2023)')
        parser.add_argument('--states', default='',
                            help='Comma list of state abbreviations (default: all 50 + DC)')
        parser.add_argument('--kinds', default='place,cousub',
                            help='Boundary layers to load (default: place,cousub)')
        parser.add_argument('--keep', action='store_true',
                            help='Do not clear existing boundaries first')
        parser.add_argument('--regeocode', action='store_true',
                            help='After loading, re-label every stored location with the new boundaries')

    def handle(self, *args, **opts):
        from django.contrib.gis.gdal import DataSource  # noqa: F401 (import-time GDAL check)
        from tracker.models import Boundary

        year = opts['year']
        kinds = [k.strip() for k in opts['kinds'].split(',') if k.strip()]
        states = [s.strip().upper() for s in opts['states'].split(',') if s.strip()] or list(STATE_FIPS)

        if not opts['keep']:
            n = Boundary.objects.count()
            Boundary.objects.all().delete()
            self.stdout.write(f"Cleared {n} existing boundaries")

        total = 0
        for st in states:
            fips = STATE_FIPS.get(st)
            if not fips:
                self.stderr.write(self.style.WARNING(f"Unknown state '{st}', skipping"))
                continue
            for kind in kinds:
                url = (f"https://www2.census.gov/geo/tiger/GENZ{year}/shp/"
                       f"cb_{year}_{fips}_{kind}_500k.zip")
                try:
                    count = self._load(url, kind)
                    total += count
                    self.stdout.write(f"  {st} {kind}: {count}")
                except Exception as e:
                    self.stderr.write(self.style.ERROR(f"  {st} {kind}: FAILED — {e}"))

        self.stdout.write(self.style.SUCCESS(f"Imported {total} boundaries"))

        if opts['regeocode']:
            self._regeocode_all()

    # ── helpers ──────────────────────────────────────────────────────────────

    def _load(self, url, kind):
        from django.contrib.gis.gdal import DataSource
        from django.contrib.gis.geos import MultiPolygon
        from tracker.models import Boundary

        data = self._download(url)
        with tempfile.TemporaryDirectory() as td:
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                z.extractall(td)
            shp = next((os.path.join(td, f) for f in os.listdir(td) if f.endswith('.shp')), None)
            if not shp:
                raise RuntimeError("no .shp in archive")

            layer = DataSource(shp)[0]
            objs = []
            for feat in layer:
                g = feat.geom
                if g.srid and g.srid != 4326:
                    g.transform(4326)
                geos = g.geos
                if geos.geom_type == 'Polygon':
                    geos = MultiPolygon(geos)
                name = (feat.get('NAME') or '').strip()
                state = (feat.get('STATE_NAME') or '').strip()
                if not name:
                    continue
                objs.append(Boundary(name=name, state=state, country_code='US', kind=kind, geom=geos))
            Boundary.objects.bulk_create(objs, batch_size=500)
            return len(objs)

    def _download(self, url, retries=4):
        last = None
        for i in range(retries):
            try:
                req = urllib.request.Request(url, headers={'User-Agent': 'Roamly boundary import'})
                with urllib.request.urlopen(req, timeout=60) as r:
                    return r.read()
            except Exception as e:  # Census/Cloudflare 502s transiently — retry with backoff.
                last = e
                time.sleep(2 * (i + 1))
        raise last

    def _regeocode_all(self):
        """Relabel every stored Location using the now-loaded boundaries.

        Overwrites existing city/state/country/place_name (so nearest-city fallback
        labels get upgraded to the containing town). Caches one lookup per ~111m
        cell across the whole pass, so even a 600k-point history is fast.
        """
        from tracker.models import Location
        from tracker.offline_geocode import local_reverse_geocode
        from tracker.views import _bust_user_cache
        from django.contrib.auth.models import User

        total = Location.objects.count()
        self.stdout.write(f"Re-geocoding {total} stored locations…")
        cell_cache = {}
        done = 0
        last_id = 0
        CHUNK = 5000
        while True:
            rows = list(
                Location.objects.filter(id__gt=last_id).order_by('id')
                .values_list('id', 'latitude', 'longitude')[:CHUNK]
            )
            if not rows:
                break
            last_id = rows[-1][0]

            groups = defaultdict(list)
            for loc_id, lat, lon in rows:
                cell = (round(lat, _CELL_PRECISION), round(lon, _CELL_PRECISION))
                res = cell_cache.get(cell)
                if res is None:
                    res = local_reverse_geocode(lat, lon) or {
                        'city': '', 'state': '', 'country': '',
                        'country_code': '', 'place_name': '',
                    }
                    cell_cache[cell] = res
                city = res['city'] or 'Unknown'
                key = (city, res['state'], res['country'], res['country_code'], res['place_name'])
                groups[key].append(loc_id)

            for (city, state, country, cc, place), ids in groups.items():
                Location.objects.filter(id__in=ids).update(
                    city=city, state=state, country=country,
                    country_code=cc, place_name=place,
                )
            done += len(rows)
            self.stdout.write(f"  re-geocoded {done}/{total}")

        for uid in User.objects.values_list('id', flat=True):
            _bust_user_cache(uid)
        self.stdout.write(self.style.SUCCESS(f"Re-geocoded {done} locations"))
