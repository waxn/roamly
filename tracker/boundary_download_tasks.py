"""geoBoundaries region/district polygons for countries outside the US.

The US has Census TIGER towns and counties (``import_boundaries``). Everywhere
else, Achievements would only know the bare country — so for every country
someone on the instance has spent more than ``MIN_DAYS`` local days in, this
fetches the **geoBoundaries gbOpen** ADM1 (regions/provinces), ADM2 (districts/
counties) and, where the country publishes one, ADM3 (municipalities) layers
into the same ``Boundary`` table, as ``kind`` ``adm1``/``adm2``/``adm3``.

Instance-wide reference data like roads and POIs: one download serves every
account, coverage is tracked in ``DownloadedRegion(kind='boundary',
key=<ISO3>)``, and a country is marked covered only once every level was
genuinely *answered* (a missing level is an answer; a timeout is not), so a
network failure retries on the next run instead of leaving a gap forever.

Uses the *simplified* geometry files: containment of ~200m cell centres does not
need survey-grade borders, and the full files are tens of MB per country.

Run from the achievements scan (``ensure_boundaries``) and the 6-hourly
auto-download sweep, both gated on ``SiteConfig.auto_download_boundaries``.
The reverse geocoder only reads ``kind`` place/cousub, so these rows never
change a point's label.
"""
import json
import logging

from django.core.cache import cache
from django.db import connection

logger = logging.getLogger(__name__)

MIN_DAYS = 7
LEVELS = ('ADM1', 'ADM2', 'ADM3')
API = 'https://www.geoboundaries.org/api/current/gbOpen/{iso3}/{level}/'
TIMEOUT_S = 90
LOCK_TTL_S = 1800
# The download lives in this module's own table rows, not per user, so any
# user's scan may trigger it — this lock is what stops two scans fetching one
# country at the same time.
_LOCK_KEY = 'boundary_dl:{iso3}'

# ISO 3166-1 alpha-2 → alpha-3. Location.country_code is alpha-2 (from the
# geocoder); geoBoundaries keys by alpha-3.
_ISO3 = dict(p.split(':') for p in (
    'AD:AND AE:ARE AF:AFG AG:ATG AI:AIA AL:ALB AM:ARM AO:AGO AQ:ATA AR:ARG AS:ASM AT:AUT '
    'AU:AUS AW:ABW AX:ALA AZ:AZE BA:BIH BB:BRB BD:BGD BE:BEL BF:BFA BG:BGR BH:BHR BI:BDI '
    'BJ:BEN BL:BLM BM:BMU BN:BRN BO:BOL BQ:BES BR:BRA BS:BHS BT:BTN BW:BWA BY:BLR BZ:BLZ '
    'CA:CAN CC:CCK CD:COD CF:CAF CG:COG CH:CHE CI:CIV CK:COK CL:CHL CM:CMR CN:CHN CO:COL '
    'CR:CRI CU:CUB CV:CPV CW:CUW CX:CXR CY:CYP CZ:CZE DE:DEU DJ:DJI DK:DNK DM:DMA DO:DOM '
    'DZ:DZA EC:ECU EE:EST EG:EGY EH:ESH ER:ERI ES:ESP ET:ETH FI:FIN FJ:FJI FK:FLK FM:FSM '
    'FO:FRO FR:FRA GA:GAB GB:GBR GD:GRD GE:GEO GF:GUF GG:GGY GH:GHA GI:GIB GL:GRL GM:GMB '
    'GN:GIN GP:GLP GQ:GNQ GR:GRC GT:GTM GU:GUM GW:GNB GY:GUY HK:HKG HN:HND HR:HRV HT:HTI '
    'HU:HUN ID:IDN IE:IRL IL:ISR IM:IMN IN:IND IQ:IRQ IR:IRN IS:ISL IT:ITA JE:JEY JM:JAM '
    'JO:JOR JP:JPN KE:KEN KG:KGZ KH:KHM KI:KIR KM:COM KN:KNA KP:PRK KR:KOR KW:KWT KY:CYM '
    'KZ:KAZ LA:LAO LB:LBN LC:LCA LI:LIE LK:LKA LR:LBR LS:LSO LT:LTU LU:LUX LV:LVA LY:LBY '
    'MA:MAR MC:MCO MD:MDA ME:MNE MF:MAF MG:MDG MH:MHL MK:MKD ML:MLI MM:MMR MN:MNG MO:MAC '
    'MP:MNP MQ:MTQ MR:MRT MS:MSR MT:MLT MU:MUS MV:MDV MW:MWI MX:MEX MY:MYS MZ:MOZ NA:NAM '
    'NC:NCL NE:NER NF:NFK NG:NGA NI:NIC NL:NLD NO:NOR NP:NPL NR:NRU NU:NIU NZ:NZL OM:OMN '
    'PA:PAN PE:PER PF:PYF PG:PNG PH:PHL PK:PAK PL:POL PM:SPM PR:PRI PS:PSE PT:PRT PW:PLW '
    'PY:PRY QA:QAT RE:REU RO:ROU RS:SRB RU:RUS RW:RWA SA:SAU SB:SLB SC:SYC SD:SDN SE:SWE '
    'SG:SGP SH:SHN SI:SVN SK:SVK SL:SLE SM:SMR SN:SEN SO:SOM SR:SUR SS:SSD ST:STP SV:SLV '
    'SX:SXM SY:SYR SZ:SWZ TC:TCA TD:TCD TG:TGO TH:THA TJ:TJK TK:TKL TL:TLS TM:TKM TN:TUN '
    'TO:TON TR:TUR TT:TTO TV:TUV TW:TWN TZ:TZA UA:UKR UG:UGA US:USA UY:URY UZ:UZB VA:VAT '
    'VC:VCT VE:VEN VG:VGB VI:VIR VN:VNM VU:VUT WF:WLF WS:WSM XK:XKX YE:YEM YT:MYT ZA:ZAF '
    'ZM:ZMB ZW:ZWE'
).split())

KIND_FOR_LEVEL = {'ADM1': 'adm1', 'ADM2': 'adm2', 'ADM3': 'adm3'}


def iso3(cc2):
    return _ISO3.get((cc2 or '').upper(), '')


def _has_postgis():
    from .models import HAS_POSTGIS
    return HAS_POSTGIS


def qualifying_countries(user=None):
    """ISO2 codes with more than MIN_DAYS distinct (UTC) days of fixes —
    for one user, or for anyone on the instance when ``user`` is None.

    UTC days, deliberately: this only gates a download, and one query across
    every account cannot localise per user. A day either side of the line makes
    no practical difference to "spent a week there".
    """
    from django.db.models.functions import TruncDate
    from .models import Location

    qs = Location.objects.exclude(country_code='').exclude(country_code='US')
    if user is not None:
        qs = qs.filter(device__user=user)
    rows = (qs.annotate(d=TruncDate('timestamp'))
            .order_by().values('country_code', 'd').distinct())
    days = {}
    for r in rows.iterator(chunk_size=10000):
        cc = (r['country_code'] or '').upper()
        days[cc] = days.get(cc, 0) + 1
    return {cc: n for cc, n in days.items() if n > MIN_DAYS}


def downloaded_countries():
    from .models import DownloadedRegion
    return set(DownloadedRegion.objects.filter(kind='boundary').values_list('key', flat=True))


def _fetch_level(iso, level):
    """Return a GeoJSON FeatureCollection, ``None`` if the country publishes no
    such level (an answer), or raise on any other failure (not an answer)."""
    import requests

    meta = requests.get(API.format(iso3=iso, level=level), timeout=TIMEOUT_S,
                        headers={'User-Agent': 'Roamly boundary download'})
    if meta.status_code == 404:
        return None
    meta.raise_for_status()
    try:
        info = meta.json()
    except ValueError:
        # The API answers an unpublished level with an HTML/text error page.
        return None
    if isinstance(info, list):
        info = info[0] if info else {}
    url = info.get('simplifiedGeometryGeoJSON') or info.get('gjDownloadURL')
    if not url:
        return None
    if not url.startswith('https://'):
        raise RuntimeError(f'unexpected geometry URL for {iso} {level}')
    gj = requests.get(url, timeout=TIMEOUT_S, headers={'User-Agent': 'Roamly boundary download'})
    gj.raise_for_status()
    return gj.json()


def _store_level(cc2, kind, fc):
    from django.contrib.gis.geos import GEOSGeometry, MultiPolygon
    from .models import Boundary

    objs = []
    for feat in (fc or {}).get('features', []):
        props = feat.get('properties') or {}
        name = (props.get('shapeName') or props.get('name') or '').strip()[:200]
        geom = feat.get('geometry')
        if not name or not geom:
            continue
        try:
            g = GEOSGeometry(json.dumps(geom), srid=4326)
        except Exception:
            continue
        if g.geom_type == 'Polygon':
            g = MultiPolygon(g)
        elif g.geom_type != 'MultiPolygon':
            continue
        g.srid = 4326  # see import_boundaries: a dropped SRID breaks containment
        objs.append(Boundary(name=name, state='', country_code=cc2, kind=kind, geom=g))
    Boundary.objects.bulk_create(objs, batch_size=200)
    return len(objs)


def _assign_parents(cc2):
    """Fill ``state`` on ADM2/ADM3 rows with the ADM1 that contains them —
    geoBoundaries files carry no parent field."""
    with connection.cursor() as cur:
        cur.execute(
            """
            UPDATE tracker_boundary c SET state = p.name
              FROM tracker_boundary p
             WHERE c.country_code = %s AND c.kind IN ('adm2', 'adm3')
               AND p.country_code = %s AND p.kind = 'adm1'
               AND ST_Contains(p.geom, ST_PointOnSurface(c.geom))
            """,
            [cc2, cc2],
        )


def download_country(cc2, progress=None):
    """Fetch every ADM level for one country. Returns ``(ok, count, error)``.

    Replaces any partial rows from an earlier failed attempt, and records
    coverage only when every level answered.
    """
    from django.db import transaction
    from .models import Boundary, DownloadedRegion

    iso = iso3(cc2)
    if not iso or not _has_postgis():
        return False, 0, 'unsupported'
    lock = _LOCK_KEY.format(iso3=iso)
    if not cache.add(lock, 1, LOCK_TTL_S):
        return False, 0, 'busy'
    try:
        layers = {}
        for level in LEVELS:
            if progress:
                progress(f'Downloading {level} boundaries for {cc2}')
            try:
                layers[level] = _fetch_level(iso, level)
            except Exception as e:
                logger.warning('geoBoundaries %s %s failed: %s', iso, level, e)
                return False, 0, f'{level}: {e}'[:200]
            if layers[level] is None and level == 'ADM1':
                break  # no ADM1 ⇒ nothing below it either
        with transaction.atomic():
            Boundary.objects.filter(country_code=cc2, kind__in=KIND_FOR_LEVEL.values()).delete()
            total = 0
            for level, fc in layers.items():
                if fc:
                    total += _store_level(cc2, KIND_FOR_LEVEL[level], fc)
            if total:
                _assign_parents(cc2)
            DownloadedRegion.objects.get_or_create(kind='boundary', key=iso)
        logger.info('geoBoundaries: stored %d polygons for %s', total, iso)
        return True, total, ''
    finally:
        cache.delete(lock)


def ensure_boundaries(user=None, progress=None, stop=None, force=False):
    """Download every qualifying country not yet covered. Best-effort: returns
    a list of ``(cc2, ok, count, error)`` and never raises. ``force`` runs it
    even with auto-download switched off (the Admin Panel's Download Now)."""
    from .models import SiteConfig

    out = []
    try:
        if not _has_postgis() or not (force or SiteConfig.load().auto_download_boundaries):
            return out
        have = downloaded_countries()
        for cc2 in sorted(qualifying_countries(user)):
            if stop and stop():
                break
            iso = iso3(cc2)
            if not iso or iso in have:
                continue
            ok, n, err = download_country(cc2, progress=progress)
            out.append((cc2, ok, n, err))
    except Exception:
        logger.exception('boundary auto-download failed')
    return out


# ── Admin Panel "Download Now" job ───────────────────────────────────────────
#
# One instance-wide run, state in the cache (shared across gunicorn workers) so
# the card can show what it is doing and how it ended. It loads the US town
# town boundaries the geocoder labels from (every state with points,
# ``regeocode_tasks.boundary_states``) and the US county outlines Achievements
# uses first, then the geoBoundaries countries: an admin clicking "Download" on the
# boundaries card expects the boundaries labels depend on to be among them.

_JOB_KEY = 'boundary_job'
_JOB_TTL_S = 7 * 24 * 3600
JOB_STALE_S = 900  # one Census or geoBoundaries call is well under this


def _job_write(**fields):
    import time
    job = cache.get(_JOB_KEY) or {}
    job.update(fields, updated=time.time())
    cache.set(_JOB_KEY, job, _JOB_TTL_S)
    return job


def job_status():
    import time
    job = cache.get(_JOB_KEY)
    if not job:
        return {'status': 'idle'}
    if job.get('status') == 'running' and time.time() - job.get('updated', 0) > JOB_STALE_S:
        job = _job_write(status='failed', stage='',
                         error='The download stopped responding; start it again.')
    return job


def start_job():
    """Start the download unless one is running. Returns the job status."""
    current = job_status()
    if current.get('status') == 'running':
        return current
    if not cache.add('boundary_job_starting', 1, 10):
        return current
    _job_write(status='running', stage='Starting…', step=0, steps=0,
               error='', results=[], us=None, finished=None)
    from .scheduler_utils import db_thread
    db_thread(_run_job, name='boundary-download').start()
    return job_status()


def _run_job():
    import time
    from .regeocode_tasks import BoundaryError, boundary_states
    from .regeocode_tasks import ensure_boundaries as ensure_us

    errors = []
    countries = []
    try:
        if not _has_postgis():
            raise RuntimeError('Boundaries need PostGIS.')
        have = downloaded_countries()
        countries = [cc for cc in sorted(qualifying_countries())
                     if iso3(cc) and iso3(cc) not in have]
        steps = 1 + len(countries)
        us_names = ', '.join(sorted(boundary_states().values())) or 'no US states yet'
        _job_write(stage=f'Loading US boundaries ({us_names})', step=0, steps=steps)
        us = {'ok': True, 'error': ''}
        try:
            ensure_us(log=lambda m: _job_write(stage=m), counties=True)
        except BoundaryError as e:
            us = {'ok': False, 'error': str(e)}
        except Exception as e:
            logger.exception('US boundary load failed')
            us = {'ok': False, 'error': f'{type(e).__name__}: {e}'[:400]}
        if not us['ok']:
            errors.append(us['error'])
        _job_write(us=us, step=1)

        results = []
        for i, cc2 in enumerate(countries):
            _job_write(stage=f'Downloading region boundaries for {cc2}', step=1 + i)
            ok, n, err = download_country(cc2, progress=lambda m: _job_write(stage=m))
            results.append({'cc': cc2, 'ok': ok, 'count': n, 'error': err})
            if not ok:
                errors.append(f'{cc2}: {err}')
            _job_write(results=results, step=2 + i)
        _job_write(status='failed' if errors else 'completed', stage='',
                   error='\n'.join(errors)[:1000], finished=time.time())
    except Exception as e:
        logger.exception('boundary download job failed')
        _job_write(status='failed', stage='', error=f'{type(e).__name__}: {e}'[:400],
                   finished=time.time())


def us_counts():
    """US place/cousub polygons per state — what the geocoder labels from."""
    from django.db.models import Count
    from .models import Boundary
    if not _has_postgis():
        return []
    return [{'state': r['state'], 'n': r['n']} for r in
            Boundary.objects.filter(country_code='US', kind__in=('place', 'cousub'))
            .order_by().values('state').annotate(n=Count('id')).order_by('state')]


def status():
    """What the Admin Panel's Boundaries card shows."""
    from django.db.models import Count
    from .models import Boundary, SiteConfig

    have = downloaded_countries()
    counts = {}
    if _has_postgis():
        for r in (Boundary.objects.exclude(country_code='US')
                  .filter(kind__in=KIND_FOR_LEVEL.values())
                  .order_by().values('country_code', 'kind').annotate(n=Count('id'))):
            counts.setdefault(r['country_code'], {})[r['kind']] = r['n']
    inv = {v: k for k, v in _ISO3.items()}
    countries = []
    for iso in sorted(have):
        cc2 = inv.get(iso, '')
        c = counts.get(cc2, {})
        countries.append({'iso3': iso, 'cc': cc2, 'adm1': c.get('adm1', 0),
                          'adm2': c.get('adm2', 0), 'adm3': c.get('adm3', 0)})
    return {'enabled': SiteConfig.load().auto_download_boundaries,
            'postgis': _has_postgis(), 'countries': countries,
            'us': us_counts(), 'us_missing': _us_missing(), 'us_counties': _us_counties(),
            'job': job_status()}


def _us_missing():
    if not _has_postgis():
        return []
    from .regeocode_tasks import missing_states
    try:
        return sorted(missing_states().values())
    except Exception:
        return []


def _us_counties():
    from .models import Boundary
    if not _has_postgis():
        return 0
    return Boundary.objects.filter(country_code='US', kind='county').count()


def delete_all():
    from .models import Boundary, DownloadedRegion
    n = Boundary.objects.filter(kind__in=KIND_FOR_LEVEL.values()).exclude(country_code='US').delete()[0]
    DownloadedRegion.objects.filter(kind='boundary').delete()
    return n
