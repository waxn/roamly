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

    # US counties (Achievements coverage). Census publishes these as ONE national
    # file rather than one per state, so the kind is loaded once and filtered by
    # --states afterwards.
    python manage.py import_boundaries --kinds county

`--regeocode` relabels stored locations (overwriting any nearest-city fallback
labels) using the freshly loaded boundaries, so the backlog gets the exact towns.
"""
import io
import os
import time
import tempfile
import zipfile
import urllib.request

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


# The same states by the name stored labels and STATE_NAME use.
STATE_NAMES = {
    'AL': 'Alabama', 'AK': 'Alaska', 'AZ': 'Arizona', 'AR': 'Arkansas', 'CA': 'California',
    'CO': 'Colorado', 'CT': 'Connecticut', 'DE': 'Delaware', 'DC': 'District of Columbia',
    'FL': 'Florida', 'GA': 'Georgia', 'HI': 'Hawaii', 'ID': 'Idaho', 'IL': 'Illinois',
    'IN': 'Indiana', 'IA': 'Iowa', 'KS': 'Kansas', 'KY': 'Kentucky', 'LA': 'Louisiana',
    'ME': 'Maine', 'MD': 'Maryland', 'MA': 'Massachusetts', 'MI': 'Michigan',
    'MN': 'Minnesota', 'MS': 'Mississippi', 'MO': 'Missouri', 'MT': 'Montana',
    'NE': 'Nebraska', 'NV': 'Nevada', 'NH': 'New Hampshire', 'NJ': 'New Jersey',
    'NM': 'New Mexico', 'NY': 'New York', 'NC': 'North Carolina', 'ND': 'North Dakota',
    'OH': 'Ohio', 'OK': 'Oklahoma', 'OR': 'Oregon', 'PA': 'Pennsylvania', 'RI': 'Rhode Island',
    'SC': 'South Carolina', 'SD': 'South Dakota', 'TN': 'Tennessee', 'TX': 'Texas',
    'UT': 'Utah', 'VT': 'Vermont', 'VA': 'Virginia', 'WA': 'Washington',
    'WV': 'West Virginia', 'WI': 'Wisconsin', 'WY': 'Wyoming',
}

class Command(BaseCommand):
    help = "Load US Census TIGER place + county-subdivision boundaries for offline reverse geocoding."

    def add_arguments(self, parser):
        parser.add_argument('--year', default='2023', help='TIGER vintage (default 2023)')
        parser.add_argument('--states', default='',
                            help='Comma list of state abbreviations (default: all 50 + DC)')
        parser.add_argument('--kinds', default='place,cousub',
                            help='Boundary layers to load: place, cousub, county (default: place,cousub)')
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
            # Only the US kinds being re-imported. The table also holds county
            # rows and the geoBoundaries ADM polygons boundary_download_tasks
            # fetches for other countries, which a place/cousub refresh must not
            # wipe out from under Achievements.
            stale = Boundary.objects.filter(country_code='US', kind__in=kinds)
            n = stale.count()
            stale.delete()
            self.stdout.write(f"Cleared {n} existing boundaries ({', '.join(kinds)})")

        total = 0
        if 'county' in kinds:
            url = (f"https://www2.census.gov/geo/tiger/GENZ{year}/shp/"
                   f"cb_{year}_us_county_500k.zip")
            try:
                count = self._load(url, 'county', only_states=set(states))
                total += count
                self.stdout.write(f"  US county: {count}")
            except Exception as e:
                self.stderr.write(self.style.ERROR(f"  US county: FAILED — {e}"))
        for st in states:
            fips = STATE_FIPS.get(st)
            if not fips:
                self.stderr.write(self.style.WARNING(f"Unknown state '{st}', skipping"))
                continue
            for kind in kinds:
                if kind == 'county':
                    continue
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

    def _load(self, url, kind, only_states=None):
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
                    g.transform(4326)  # TIGER cb files are NAD83 (4269)
                geos = g.geos
                if geos.geom_type == 'Polygon':
                    geos = MultiPolygon(geos)
                # The MultiPolygon(...) wrap (and occasionally .geos) drops the SRID,
                # and a NULL/0 SRID makes ST_Contains error against the 4326 query
                # point — which my caller swallows into a silent offline fallback
                # (everything reads as the nearest city). Stamp it so PIP matches.
                geos.srid = 4326
                if kind == 'county':
                    # "Waldo County" / "Orleans Parish" rather than a bare "Waldo",
                    # which would collide with the town of the same name.
                    name = (self._field(feat, 'NAMELSAD') or self._field(feat, 'NAME')).strip()
                    if only_states and self._field(feat, 'STUSPS').strip() not in only_states:
                        continue
                else:
                    name = (feat.get('NAME') or '').strip()
                state = (self._field(feat, 'STATE_NAME') or '').strip()
                if not name:
                    continue
                objs.append(Boundary(name=name, state=state, country_code='US', kind=kind, geom=geos))
            Boundary.objects.bulk_create(objs, batch_size=500)
            return len(objs)

    @staticmethod
    def _field(feat, name):
        try:
            return feat.get(name) or ''
        except Exception:  # field absent from this vintage's attribute table
            return ''

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
        """Relabel stored locations using the now-loaded boundaries (only the
        rows whose label changes; see the ``regeocode`` command)."""
        from tracker.regeocode_tasks import regeocode
        from tracker.views import _bust_user_cache

        done, changed, users = regeocode(log=self.stdout.write)
        for uid in users:
            _bust_user_cache(uid)
        self.stdout.write(self.style.SUCCESS(f"Re-geocoded {done} locations ({changed} relabelled)"))
