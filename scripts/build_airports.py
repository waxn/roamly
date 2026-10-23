#!/usr/bin/env python3
"""Build tracker/static/tracker/data/airports.json from OurAirports.

OurAirports (https://ourairports.com/data/) is public domain. Run it with
the path to a downloaded airports.csv, or with no argument to fetch it:

    python3 scripts/build_airports.py [airports.csv]

Keeps every airport with an IATA code, plus the large and medium airports
that have none. That covers essentially every airport a passenger flight
uses, and leaves out the ~70k heliports, strips and seaplane bases that
would only make the autocomplete noisy and the file large.

Each row is compact: [iata, icao, name, city, country_code, lat, lon].
"""
import csv
import io
import json
import os
import sys
import urllib.request

SRC = 'https://davidmegginson.github.io/ourairports-data/airports.csv'
OUT = os.path.join(os.path.dirname(__file__), '..', 'tracker', 'static', 'tracker', 'data', 'airports.json')


def main():
    if len(sys.argv) > 1:
        text = open(sys.argv[1], encoding='utf-8').read()
    else:
        with urllib.request.urlopen(SRC, timeout=60) as r:
            text = r.read().decode('utf-8')

    rows = []
    for a in csv.DictReader(io.StringIO(text)):
        iata = (a.get('iata_code') or '').strip().upper()
        kind = a.get('type') or ''
        if kind == 'closed':
            continue
        if not iata and kind not in ('large_airport', 'medium_airport'):
            continue
        if iata and len(iata) != 3:
            continue
        icao = (a.get('icao_code') or a.get('gps_code') or '').strip().upper()
        try:
            lat = round(float(a['latitude_deg']), 4)
            lon = round(float(a['longitude_deg']), 4)
        except (KeyError, TypeError, ValueError):
            continue
        rows.append([iata, icao, a.get('name') or '', a.get('municipality') or '',
                     (a.get('iso_country') or '').upper(), lat, lon])

    # IATA codes first (the ones people type). A few IATA codes are claimed by
    # more than one record (usually a field the dataset still lists as open);
    # keep the one with an ICAO code where there is a choice.
    seen, out = set(), []
    for r in sorted(rows, key=lambda r: (r[0] == '', r[0] or r[1], r[1] == '')):
        if r[0] and r[0] in seen:
            continue
        if r[0]:
            seen.add(r[0])
        out.append(r)

    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, separators=(',', ':'))
    print(f'{len(out)} airports -> {os.path.normpath(OUT)}')


if __name__ == '__main__':
    main()
