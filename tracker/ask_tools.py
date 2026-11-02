"""More read-only tools for AI Ask: flights, adventures, travel habits, where
you haven't been, and the map cards the chat page can draw.

Every function here reads. None of them writes to any table, and none returns
the sensitive parts of a record (booking references, ticket numbers, passenger
names, frequent-flyer numbers, raw boarding-pass text, invite tokens, PINs).
The one network call, ``lookup_flight``, asks AeroDataBox about a public flight
number and is metered per account so a chatty model can't spend the instance's
quota.
"""
import datetime
import math
from collections import Counter

from django.core.cache import cache
from django.db.models import Q

# AeroDataBox lookups the assistant may make per account per day. Shares the
# Flights page's own budget scope's spirit but is counted separately, so a long
# chat can't use up the lookups the flight form needs.
_LOOKUP_DAILY = 15
_MAX_PINS = 25


# ── Schemas ────────────────────────────────────────────────────────────────

def _fn(name, description, properties=None, required=None):
    params = {"type": "object", "properties": properties or {}}
    if required:
        params["required"] = required
    return {"type": "function", "function": {
        "name": name, "description": description, "parameters": params}}


DATA_TOOL_SCHEMAS = [
    _fn("list_flights",
        "The user's logged flights (their own flight log), newest first: date, "
        "flight number, airline, route, aircraft, seat class, distance, times. "
        "Filter by year, airport (IATA code or city) or airline.",
        {"year": {"type": "integer"},
         "airport": {"type": "string", "description": "IATA code or city, e.g. 'SFO' or 'Tokyo'."},
         "airline": {"type": "string"},
         "limit": {"type": "integer", "description": "At most this many (default 40, max 200)."}}),
    _fn("get_flight_stats",
        "Totals over the user's flight log: flights, distance, time aloft, "
        "airports, countries, top routes/airlines/aircraft, longest and shortest. "
        "Optionally one year.",
        {"year": {"type": "integer"}}),
    _fn("lookup_flight",
        "Look up a scheduled or past flight by number and date in a live flight "
        "database (route, scheduled/actual times, aircraft, terminals). Use it "
        "for flight information the user asks about, e.g. an upcoming flight. "
        "Limited number of lookups per day.",
        {"flight_number": {"type": "string", "description": "e.g. UA123"},
         "date": {"type": "string", "description": "Departure date, YYYY-MM-DD."}},
        ["flight_number", "date"]),
    _fn("list_adventures",
        "The user's Adventures (named trips they created or joined): name, "
        "dates, subtitle, members, number of saved places and itinerary stops."),
    _fn("get_adventure",
        "Details of one Adventure by name: its itinerary (planned stops), saved "
        "places with ratings and categories, day-log entry titles and members.",
        {"name": {"type": "string"}}, ["name"]),
    _fn("get_period_summary",
        "This week / month / year compared with the previous one, a monthly "
        "breakdown, and the user's top cities, countries and places overall."),
    _fn("get_travel_modes",
        "How the user travels: distance and time by walking, cycling, vehicle "
        "and plane, all time."),
    _fn("get_stays",
        "The user's stays (places they stopped at, with arrival/leave times) "
        "between two dates, chronological, plus the top places by time. Best "
        "for 'what did I do last weekend', 'where did I spend my time'.",
        {"start_date": {"type": "string"}, "end_date": {"type": "string"}},
        ["start_date", "end_date"]),
    _fn("get_achievements",
        "The user's Achievements: level, XP, badges unlocked, recent unlocks, "
        "the badges they are closest to, and headline exploration records."),
    _fn("list_unvisited",
        "Countries, or US states, the user has never recorded a point in. "
        "Useful for trip ideas ('somewhere new').",
        {"level": {"type": "string", "enum": ["country", "us_state"]}}),
    _fn("search_near",
        "Has the user been near a coordinate? Give a latitude/longitude (from "
        "your own knowledge of where a place is) and a radius. Returns whether "
        "they were there, how many days, first/last visit and the towns.",
        {"latitude": {"type": "number"}, "longitude": {"type": "number"},
         "radius_m": {"type": "number", "description": "Default 1000, max 50000."}},
        ["latitude", "longitude"]),
]

MAP_EXTRA_SCHEMAS = [
    _fn("show_flights_map",
        "Draw the user's flights on the map beside the chat as routes between "
        "airports, weighted by how often each was flown. Optionally one year.",
        {"year": {"type": "integer"}, "title": {"type": "string"}}),
    _fn("show_places",
        "Pin suggested or discussed places on the map beside the chat — e.g. "
        "trip recommendations, an itinerary you are brainstorming, or "
        "restaurants. Give each a name, latitude, longitude and a one-line "
        "note. These are your suggestions, not the user's data. Max 25 pins.",
        {"title": {"type": "string"},
         "places": {"type": "array", "items": {"type": "object", "properties": {
             "name": {"type": "string"}, "latitude": {"type": "number"},
             "longitude": {"type": "number"}, "note": {"type": "string"},
             "order": {"type": "integer", "description": "Stop number, if this is a route."}},
             "required": ["name", "latitude", "longitude"]}},
         "connect": {"type": "boolean", "description": "Draw a line through the pins in order."}},
        ["places"]),
]

TOOL_STATUS = {
    "list_flights": "Checking your flight log…",
    "get_flight_stats": "Adding up your flights…",
    "lookup_flight": "Looking up that flight…",
    "list_adventures": "Opening your adventures…",
    "get_adventure": "Reading an adventure…",
    "get_period_summary": "Comparing periods…",
    "get_travel_modes": "Checking how you travel…",
    "get_stays": "Reading your stays…",
    "get_achievements": "Checking your achievements…",
    "list_unvisited": "Finding places you haven't been…",
    "search_near": "Checking if you've been nearby…",
    "show_flights_map": "Drawing your flights…",
    "show_places": "Pinning places…",
}


# ── Helpers ────────────────────────────────────────────────────────────────

def _int(v, default=None, lo=None, hi=None):
    try:
        n = int(v)
    except (TypeError, ValueError):
        return default
    if lo is not None:
        n = max(lo, n)
    if hi is not None:
        n = min(hi, n)
    return n


def _float(v):
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if math.isfinite(f) else None


def _day(v):
    try:
        return datetime.date.fromisoformat(str(v).strip()[:10])
    except (TypeError, ValueError):
        return None


def _iso(dt):
    return dt.isoformat() if dt else None


def _snapshot(user, field):
    from .models import StatsSnapshot
    snap = StatsSnapshot.objects.filter(user=user, status='done').only('id', field).first()
    return (getattr(snap, field, None) or None) if snap else None


def _flights(user, year=None, airport=None, airline=None):
    from .models import Flight
    qs = Flight.objects.filter(user=user)
    if year:
        qs = qs.filter(date__year=year)
    if airport:
        a = str(airport).strip()[:60]
        qs = qs.filter(Q(dep_iata__iexact=a) | Q(arr_iata__iexact=a)
                       | Q(dep_city__icontains=a) | Q(arr_city__icontains=a)
                       | Q(dep_name__icontains=a) | Q(arr_name__icontains=a))
    if airline:
        a = str(airline).strip()[:60]
        qs = qs.filter(Q(airline_name__icontains=a) | Q(airline_iata__iexact=a))
    return qs


def _hours(f):
    dep = f.actual_dep or f.sched_dep
    arr = f.actual_arr or f.sched_arr
    if dep and arr and arr > dep:
        h = (arr - dep).total_seconds() / 3600.0
        return h if h < 24 else None
    return None


def _flight_row(f):
    # Deliberately no booking ref, ticket, passenger, frequent flyer or BCBP.
    h = _hours(f)
    return {
        "date": f.date.isoformat(),
        "flight": f.flight_number or None,
        "airline": f.airline_name or f.airline_iata or None,
        "from": f"{f.dep_iata} {f.dep_city}".strip() or None,
        "to": f"{f.arr_iata} {f.arr_city}".strip() or None,
        "aircraft": f.aircraft_model or None,
        "seat_class": f.seat_class or None,
        "reason": f.reason or None,
        "distance_km": round(f.distance_km) if f.distance_km else None,
        "hours": round(h, 1) if h else None,
        "departed": _iso(f.actual_dep or f.sched_dep),
    }


# ── Data tools ─────────────────────────────────────────────────────────────

def list_flights(user, year=None, airport=None, airline=None, limit=None, **_):
    qs = _flights(user, _int(year), airport, airline).order_by('-date', '-sched_dep', '-id')
    limit = _int(limit, 40, 1, 200)
    total = qs.count()
    rows = [_flight_row(f) for f in qs[:limit]]
    out = {"total": total, "flights": rows}
    if total > limit:
        out["note"] = f"Showing the newest {limit} of {total}."
    if not total:
        out["note"] = "No flights in the user's flight log match."
    return out


def get_flight_stats(user, year=None, **_):
    flights = list(_flights(user, _int(year)))
    if not flights:
        return {"flights": 0, "note": "No flights logged" + (f" in {year}." if year else ".")}
    km = sum(f.distance_km or 0 for f in flights)
    hours = sum(_hours(f) or 0 for f in flights)
    airports = Counter()
    countries = set()
    for f in flights:
        for code, cc in ((f.dep_iata, f.dep_country), (f.arr_iata, f.arr_country)):
            if code:
                airports[code] += 1
            if cc:
                countries.add(cc)
    routes = Counter(tuple(sorted((f.dep_iata, f.arr_iata))) for f in flights if f.dep_iata and f.arr_iata)
    airlines = Counter(f.airline_name or f.airline_iata for f in flights if f.airline_name or f.airline_iata)
    aircraft = Counter(f.aircraft_model for f in flights if f.aircraft_model)
    years = Counter(f.date.year for f in flights)
    by_dist = sorted((f for f in flights if f.distance_km), key=lambda f: f.distance_km)
    return {
        "flights": len(flights),
        "distance_km": round(km),
        "earth_laps": round(km / 40075.0, 2),
        "hours_aloft": round(hours, 1),
        "airports": len(airports),
        "countries": sorted(countries),
        "top_airports": airports.most_common(8),
        "top_routes": [{"route": "–".join(r), "times": n} for r, n in routes.most_common(8)],
        "top_airlines": airlines.most_common(6),
        "top_aircraft": aircraft.most_common(6),
        "per_year": dict(sorted(years.items())),
        "longest": _flight_row(by_dist[-1]) if by_dist else None,
        "shortest": _flight_row(by_dist[0]) if by_dist else None,
        "first": _flight_row(min(flights, key=lambda f: f.date)),
        "last": _flight_row(max(flights, key=lambda f: f.date)),
    }


def lookup_flight(user, flight_number=None, date=None, **_):
    import re
    from . import flight_lookup
    from .models import SiteConfig
    cfg = SiteConfig.load()
    if not flight_lookup.configured(cfg):
        return {"error": "Live flight lookup isn't set up on this Roamly instance. "
                         "Answer from general knowledge and say it's unverified."}
    num = re.sub(r'\s+', '', str(flight_number or '').upper())
    if not re.fullmatch(r'[A-Z0-9]{2,3}\d{1,5}[A-Z]?', num):
        return {"error": "flight_number must look like UA123"}
    day = _day(date)
    if not day:
        return {"error": "date must be YYYY-MM-DD"}
    key = f'ask_flight_lookup:{user.id}:{datetime.date.today().isoformat()}'
    try:
        cache.add(key, 0, 86400)
        if cache.incr(key) > _LOOKUP_DAILY:
            return {"error": "Daily flight-lookup limit for Ask reached."}
    except Exception:
        pass
    legs, err = flight_lookup.lookup(cfg, num, day.isoformat())
    if err:
        return {"error": err}
    if not legs:
        return {"error": f"No flight {num} found on {day.isoformat()}."}
    keep = ('flight_number', 'airline_name', 'dep_iata', 'dep_name', 'dep_city', 'arr_iata',
            'arr_name', 'arr_city', 'sched_dep', 'sched_arr', 'actual_dep', 'actual_arr',
            'dep_terminal', 'arr_terminal', 'dep_gate', 'arr_gate', 'aircraft_model',
            'registration', 'status', 'distance_km')
    return {"legs": [{k: v for k, v in leg.items() if k in keep and v not in (None, '')}
                     for leg in legs]}


def _adventures(user):
    from .models import Adventure, AdventureMember
    ids = set(Adventure.objects.filter(device__user=user).values_list('id', flat=True))
    ids |= set(AdventureMember.objects.filter(user=user).values_list('adventure_id', flat=True))
    return Adventure.objects.filter(id__in=ids)


def list_adventures(user, **_):
    from django.db.models import Count
    qs = (_adventures(user)
          .annotate(n_members=Count('members', distinct=True),
                    n_places=Count('blurbs', distinct=True),
                    n_stops=Count('planned_stops', distinct=True))
          .order_by('-start_time'))
    rows = []
    for a in qs[:60]:
        rows.append({
            "name": a.name, "subtitle": a.subtitle or None,
            "start": a.start_time.date().isoformat(),
            "end": a.end_time.date().isoformat() if a.end_time and a.end_time.year < 2090 else None,
            "members": a.n_members, "saved_places": a.n_places, "itinerary_stops": a.n_stops,
        })
    return {"adventures": rows} if rows else {"adventures": [], "note": "No adventures yet."}


def get_adventure(user, name=None, **_):
    q = str(name or '').strip()
    if not q:
        return {"error": "name is required"}
    matches = list(_adventures(user).filter(name__icontains=q)[:10])
    if not matches:
        return {"error": f"No adventure matching '{q}'. Try list_adventures."}
    exact = [a for a in matches if a.name.casefold() == q.casefold()]
    if len(matches) > 1 and not exact:
        return {"disambiguation": [a.name for a in matches]}
    a = (exact or matches)[0]
    stops = [{"order": s.order, "name": s.name, "where": s.location_name or None,
              "arrive": s.arrival_date.isoformat() if s.arrival_date else None,
              "nights": s.nights, "transport": s.transport or None,
              "stay": s.accommodation or None, "notes": (s.notes or '')[:200] or None}
             for s in a.planned_stops.order_by('order', 'id')[:60]]
    places = [{"name": b.title or (b.text or '')[:40], "rating": b.rating,
               "category": b.category or None, "note": (b.text or '')[:160] or None}
              for b in a.blurbs.filter(latitude__isnull=False).order_by('id')[:60]]
    days = [{"date": n.date.isoformat(), "title": n.title or None}
            for n in a.day_notes.order_by('date', 'id')[:80]]
    members = [m.user.first_name or m.user.username
               for m in a.members.select_related('user') if m.accepted_at]
    return {
        "name": a.name, "subtitle": a.subtitle or None,
        "start": a.start_time.date().isoformat(),
        "end": a.end_time.date().isoformat() if a.end_time and a.end_time.year < 2090 else None,
        "members": members, "itinerary": stops, "saved_places": places, "day_log": days,
    }


def get_period_summary(user, **_):
    data = _snapshot(user, 'yearly_json')
    if data is None:
        from . import views
        data = views._compute_yearly_payload(user)
    out = {}
    for k, v in data.items():
        if isinstance(v, list):
            out[k] = v[:12]
        elif isinstance(v, dict):
            out[k] = {kk: (vv[:12] if isinstance(vv, list) else vv) for kk, vv in v.items()}
        else:
            out[k] = v
    return out


def get_travel_modes(user, **_):
    data = _snapshot(user, 'transport_json')
    if data is None:
        return {"error": "Travel modes haven't been computed yet (Settings → Background "
                         "Jobs → Detect Transport Modes, then Recalculate Stats)."}
    return data


def get_stays(user, start_date=None, end_date=None, **_):
    from . import summary_email_tasks as se
    from .tz_utils import aware_local
    s, e = _day(start_date), _day(end_date)
    if not s or not e:
        return {"error": "start_date and end_date must be YYYY-MM-DD"}
    if s > e:
        s, e = e, s
    if (e - s).days > 62:
        return {"error": "Keep it to two months or less; use get_period_summary for longer."}
    win_start = aware_local(datetime.datetime.combine(s, datetime.time.min))
    win_end = aware_local(datetime.datetime.combine(e + datetime.timedelta(days=1), datetime.time.min))
    visits = se._period_visits(user, win_start, win_end)
    if not visits:
        return {"stays": [], "note": "No stays recorded in that range (the Visits job may not "
                                      "have processed it yet; get_day_detail still works)."}
    from django.utils import timezone
    rows = []
    for v in visits[:150]:
        a, b = timezone.localtime(v.start_time), timezone.localtime(v.end_time)
        rows.append({"day": a.date().isoformat(), "from": a.strftime('%H:%M'), "to": b.strftime('%H:%M'),
                     "minutes": round((v.end_time - v.start_time).total_seconds() / 60),
                     "place": (v.poi.name if v.poi_id else v.place_name) or None,
                     "town": ", ".join(x for x in (v.city, v.state) if x) or None})
    top = [{"place": label, "hours": round(d.total_seconds() / 3600, 1)}
           for label, d in se._top_places(visits, 8)]
    return {"stays": rows, "top_places": top,
            "note": f"First 150 of {len(visits)} stays." if len(visits) > 150 else None}


def get_achievements(user, **_):
    from .models import AchievementSnapshot
    snap = AchievementSnapshot.objects.filter(user=user).only('id', 'payload_json').first()
    p = (snap.payload_json if snap else None) or {}
    game = p.get('game') or {}
    if not game:
        return {"error": "Achievements haven't been scanned yet (Data → Achievements)."}
    by_id = {b['id']: b for b in game.get('badges', []) if isinstance(b, dict) and 'id' in b}
    almost = [{"badge": by_id[i].get('name'), "progress": by_id[i].get('progress'),
               "next_at": by_id[i].get('next')} for i in game.get('almost', []) if i in by_id]
    metrics = {k: v for k, v in (p.get('metrics') or {}).items()
               if isinstance(v, (int, float, str)) and not isinstance(v, bool)}
    return {
        "level": game.get('level'), "badges_unlocked": game.get('unlocked'),
        "badges_total": game.get('total'), "tiers_earned": game.get('tiers_earned'),
        "recent": game.get('recent', [])[:8], "closest_next": almost,
        "metrics": dict(list(metrics.items())[:60]),
        "computed_for": p.get('computed_for'),
    }


def list_unvisited(user, level="country", **_):
    level = (level or "country").lower()
    visits = _snapshot(user, 'visits_json')
    if visits is None:
        from . import views
        from .models import Location
        visits = views._compute_visits_from_qs(
            Location.objects.filter(device__user=user).exclude(city=''))
    if level == "us_state":
        import json
        import os
        from django.conf import settings
        path = os.path.join(settings.BASE_DIR, 'tracker', 'static', 'tracker', 'data', 'us-states.json')
        try:
            with open(path) as fh:
                names = sorted(f['properties']['name'] for f in json.load(fh)['features'])
        except (OSError, ValueError, KeyError):
            return {"error": "State list unavailable."}
        been = {s.get('state') for s in visits.get('states', [])}
        return {"visited": len([n for n in names if n in been]),
                "never_visited": [n for n in names if n not in been]}
    from . import offline_geocode
    places = offline_geocode._get_places()
    if places is None:
        return {"error": "Country list unavailable."}
    been = {(c.get('country_code') or '').upper() for c in visits.get('countries', [])}
    names = places.countries
    never = sorted(n for cc, n in names.items() if cc.upper() not in been)
    return {"visited": len(been - {''}), "never_visited_count": len(never), "never_visited": never}


def search_near(user, latitude=None, longitude=None, radius_m=None, **_):
    from . import views
    from .models import Location
    lat, lon = _float(latitude), _float(longitude)
    if lat is None or lon is None or not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return {"error": "latitude/longitude are required and must be valid"}
    r = _float(radius_m) or 1000.0
    r = max(50.0, min(r, 50000.0))
    qs = views._find_nearby_locations(Location.objects.filter(device__user=user), lat, lon, r)
    first = qs.order_by('timestamp').values_list('timestamp', flat=True).first()
    if not first:
        return {"been_there": False, "radius_m": r}
    from django.db.models.functions import TruncDate
    days = qs.annotate(d=TruncDate('timestamp')).order_by().values('d').distinct().count()
    last = qs.order_by('-timestamp').values_list('timestamp', flat=True).first()
    towns = list(qs.exclude(city='').order_by().values_list('city', 'state').distinct()[:8])
    return {"been_there": True, "radius_m": r, "days": days,
            "first_visit": _iso(first), "last_visit": _iso(last),
            "towns": [", ".join(x for x in t if x) for t in towns]}


DATA_DISPATCH = {
    "list_flights": list_flights,
    "get_flight_stats": get_flight_stats,
    "lookup_flight": lookup_flight,
    "list_adventures": list_adventures,
    "get_adventure": get_adventure,
    "get_period_summary": get_period_summary,
    "get_travel_modes": get_travel_modes,
    "get_stays": get_stays,
    "get_achievements": get_achievements,
    "list_unvisited": list_unvisited,
    "search_near": search_near,
}


# ── Map tools (chat page only) ─────────────────────────────────────────────

def clean_pins(raw_places, connect=False, title=''):
    """Validate model-supplied pins into a card. Nothing here touches the DB."""
    if not isinstance(raw_places, list):
        raise ValueError('places must be a list')
    pins = []
    for p in raw_places[:_MAX_PINS]:
        if not isinstance(p, dict):
            continue
        lat, lon = _float(p.get('latitude')), _float(p.get('longitude'))
        if lat is None or lon is None or not (-90 <= lat <= 90 and -180 <= lon <= 180):
            continue
        pin = {"name": str(p.get('name') or 'Place').strip()[:80],
               "lat": round(lat, 6), "lon": round(lon, 6)}
        note = str(p.get('note') or '').strip()[:200]
        if note:
            pin["note"] = note
        order = _int(p.get('order'), None, 1, 999)
        if order:
            pin["order"] = order
        pins.append(pin)
    if not pins:
        raise ValueError('no valid places (each needs a name, latitude and longitude)')
    if any('order' in p for p in pins):
        pins.sort(key=lambda p: p.get('order', 10 ** 6))
    return {"kind": "pins", "title": str(title or '').strip()[:80] or "Suggested places",
            "pins": pins, "connect": bool(connect)}


def show_places(user, places=None, connect=False, title='', **_):
    try:
        card = clean_pins(places, connect, title)
    except ValueError as e:
        return {"error": str(e)}
    return {"shown": True, "pins": len(card["pins"]), "_card": card}


def flights_card(year=None, title=''):
    y = _int(year, None, 1900, 2200)
    card = {"kind": "flights"}
    if y:
        card["year"] = y
    card["title"] = str(title or '').strip()[:80] or (f"Flights in {y}" if y else "All flights")
    return card


def show_flights_map(user, year=None, title='', **_):
    card = flights_card(year, title)
    n = _flights(user, card.get('year')).filter(dep_lat__isnull=False, arr_lat__isnull=False).count()
    if not n:
        return {"error": "No flights with known airports to draw."}
    return {"shown": True, "flights": n, "_card": card}


def flights_geometry(user, card):
    """Great-circle arcs per route, weighted by count. Airport positions only."""
    qs = _flights(user, card.get('year')).filter(dep_lat__isnull=False, arr_lat__isnull=False)
    routes, airports = Counter(), {}
    for f in qs.only('dep_iata', 'arr_iata', 'dep_lat', 'dep_lon', 'arr_lat', 'arr_lon', 'dep_city', 'arr_city'):
        a = (f.dep_iata or f"{f.dep_lat:.2f},{f.dep_lon:.2f}", f.dep_lat, f.dep_lon, f.dep_city)
        b = (f.arr_iata or f"{f.arr_lat:.2f},{f.arr_lon:.2f}", f.arr_lat, f.arr_lon, f.arr_city)
        key = tuple(sorted([a, b], key=lambda x: x[0]))
        routes[key] += 1
        for code, lat, lon, city in (a, b):
            ap = airports.setdefault(code, {"code": code, "lat": lat, "lon": lon, "city": city or '', "n": 0})
            ap["n"] += 1
    arcs = []
    lons, lats = [], []
    for (a, b), n in routes.items():
        coords = _great_circle(a[1], a[2], b[1], b[2])
        arcs.append({"coords": coords, "n": n, "label": f"{a[0]}–{b[0]}"})
        for lon, lat in coords:
            lons.append(lon)
            lats.append(lat)
    total = sum(routes.values())
    return {"count": total, "mode": "flights", "arcs": arcs,
            "airports": list(airports.values()),
            "bounds": [min(lons), min(lats), max(lons), max(lats)] if lons else None}


def _great_circle(lat1, lon1, lat2, lon2, steps=48):
    """Points along the great circle, longitudes unwrapped so a route across
    the date line stays one continuous line."""
    p1, l1, p2, l2 = map(math.radians, (lat1, lon1, lat2, lon2))
    d = 2 * math.asin(math.sqrt(math.sin((p2 - p1) / 2) ** 2
                                + math.cos(p1) * math.cos(p2) * math.sin((l2 - l1) / 2) ** 2))
    if d < 1e-9:
        return [[lon1, lat1], [lon2, lat2]]
    out, prev = [], None
    for i in range(steps + 1):
        t = i / steps
        a = math.sin((1 - t) * d) / math.sin(d)
        b = math.sin(t * d) / math.sin(d)
        x = a * math.cos(p1) * math.cos(l1) + b * math.cos(p2) * math.cos(l2)
        y = a * math.cos(p1) * math.sin(l1) + b * math.cos(p2) * math.sin(l2)
        z = a * math.sin(p1) + b * math.sin(p2)
        lat = math.degrees(math.atan2(z, math.sqrt(x * x + y * y)))
        lon = math.degrees(math.atan2(y, x))
        if prev is not None:
            while lon - prev > 180:
                lon -= 360
            while lon - prev < -180:
                lon += 360
        prev = lon
        out.append([round(lon, 4), round(lat, 4)])
    return out


MAP_DISPATCH = {
    "show_places": show_places,
    "show_flights_map": show_flights_map,
}
