#!/usr/bin/env python3
"""Build tracker/data/places.json.gz, the offline reverse-geocoding dataset.

GeoNames (https://www.geonames.org/, CC BY 4.0) `cities500`: every populated
place with at least 500 people. The `reverse_geocode` package bundles
`cities1000`, which is missing exactly the small places people actually stop
at — a point on Campobello Island (whose villages are a few hundred people
each) was labelled after the nearest town it did have, Eastport, Maine, which
is in another country.

    python3 scripts/build_cities.py

Downloads cities500.zip, admin1CodesASCII.txt and countryInfo.txt (for
countries the package doesn't name) from GeoNames, and reads this machine's
IANA zone.tab for the timezone → country map the geocoder uses as its border
check (`/usr/share/zoneinfo/zone.tab`, or pass another path as the first
argument).

Output (gzipped JSON):
    {"countries": {cc: name},
     "admin1": [name, ...],
     "tz_country": {zone: cc},
     "rows": [[lat, lon, cc, city, admin1_index], ...]}

City and state strings are GeoNames' `name` columns and country names are
taken from `reverse_geocode`'s countries.csv (run this where that package is
installed, as it is in the app), so labels already stored for places in both
datasets come out the same — Visits groups on those strings. GeoNames has
renamed a few hundred foreign admin-1 regions since that package's snapshot,
so a full re-geocode does relabel those.
"""
import csv
import gzip
import io
import json
import os
import sys
import urllib.request
import zipfile

BASE = 'https://download.geonames.org/export/dump/'
OUT = os.path.join(os.path.dirname(__file__), '..', 'tracker', 'data', 'places.json.gz')


def package_country_names():
    """reverse_geocode's country names ("Czech Republic", not GeoNames'
    current "Czechia"), so stored labels don't split in two."""
    import reverse_geocode
    path = os.path.join(os.path.dirname(reverse_geocode.__file__), 'countries.csv')
    with open(path, encoding='utf-8') as f:
        return {code: name for code, name in csv.reader(f)}


def fetch(name):
    with urllib.request.urlopen(BASE + name, timeout=120) as r:
        return r.read()


def tsv(text):
    return csv.reader((l for l in text.splitlines() if l and not l.startswith('#')),
                      delimiter='\t', quoting=csv.QUOTE_NONE)


def main():
    zone_tab = sys.argv[1] if len(sys.argv) > 1 else '/usr/share/zoneinfo/zone.tab'

    legacy = package_country_names()
    countries = {}
    for row in tsv(fetch('countryInfo.txt').decode('utf-8')):
        countries[row[0]] = legacy.get(row[0]) or row[4].strip()

    admin1_name = {}
    for row in tsv(fetch('admin1CodesASCII.txt').decode('utf-8')):
        admin1_name[row[0]] = row[1]

    with zipfile.ZipFile(io.BytesIO(fetch('cities500.zip'))) as z:
        text = z.read('cities500.txt').decode('utf-8')

    admin1, admin1_idx, rows = [], {}, []
    for row in tsv(text):
        try:
            lat, lon = round(float(row[4]), 5), round(float(row[5]), 5)
        except ValueError:
            continue
        cc = row[8]
        if not cc:
            continue
        state = admin1_name.get(f'{cc}.{row[10]}', '')
        if state not in admin1_idx:
            admin1_idx[state] = len(admin1)
            admin1.append(state)
        rows.append([lat, lon, cc, row[1], admin1_idx[state]])

    tz_country = {}
    with open(zone_tab, encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            parts = line.rstrip('\n').split('\t')
            tz_country[parts[2]] = parts[0]

    data = {'countries': countries, 'admin1': admin1, 'tz_country': tz_country, 'rows': rows}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with gzip.open(OUT, 'wt', encoding='utf-8', compresslevel=9) as f:
        json.dump(data, f, ensure_ascii=False, separators=(',', ':'))
    print(f'{len(rows)} places, {len(admin1)} states, {len(tz_country)} zones -> {OUT} '
          f'({os.path.getsize(OUT) / 1e6:.1f} MB)')


if __name__ == '__main__':
    main()
