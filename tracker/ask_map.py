"""Map cards for the Ask chat — read-only.

A card is a small parameter dict the assistant attaches to an answer
(``{"title", "date"}`` or ``{"title", "start_date", "end_date"}``, optionally
narrowed by ``city`` / ``state`` / ``country``). It carries no geometry: the
page asks :func:`geometry` for the points when a card is opened, so what is
drawn is always the user's own current history and a stored chat never holds a
copy of it. Nothing in this module writes.
"""
import datetime
from urllib.parse import urlencode

from django.db.models import F, Value

# A gap longer than this breaks a drawn line (same idea as the journal day map).
_SEGMENT_GAP_S = 20 * 60
# Points handed to the browser, at most roughly this many.
_MAX_POINTS = 3000
# Past this many days, or with a place filter, points are shown as dots only:
# a line across weeks of history is a tangle that says nothing.
_LINE_MAX_DAYS = 7
# Distance is a Python walk over every point, so only for short spans.
_DISTANCE_MAX_DAYS = 14

_PLACE_FIELDS = ('city', 'state', 'country')


class CardError(ValueError):
    """The card parameters can't be turned into a query."""


def _parse_day(value):
    try:
        return datetime.date.fromisoformat(str(value).strip()[:10])
    except (ValueError, TypeError):
        raise CardError('dates must be YYYY-MM-DD')


def clean_card(raw):
    """Validate untrusted card parameters and return only the keys we use.

    Three kinds: ``history`` (the default — the user's own points), ``flights``
    (their flight routes) and ``pins`` (places the assistant suggested; no user
    data at all)."""
    if not isinstance(raw, dict):
        raise CardError('invalid card')
    from . import ask_tools
    kind = raw.get('kind') or 'history'
    if kind == 'flights':
        return ask_tools.flights_card(raw.get('year'), raw.get('title'))
    if kind == 'pins':
        try:
            return ask_tools.clean_pins(raw.get('pins'), raw.get('connect'), raw.get('title'))
        except ValueError as e:
            raise CardError(str(e))
    if kind != 'history':
        raise CardError('unknown card kind')
    card = {}
    for f in _PLACE_FIELDS:
        v = str(raw.get(f) or '').strip()[:120]
        if v:
            card[f] = v
    if raw.get('date'):
        card['date'] = _parse_day(raw['date']).isoformat()
    else:
        if raw.get('start_date'):
            card['start_date'] = _parse_day(raw['start_date']).isoformat()
        if raw.get('end_date'):
            card['end_date'] = _parse_day(raw['end_date']).isoformat()
    if 'start_date' in card and 'end_date' not in card:
        card['end_date'] = card['start_date']
    if 'end_date' in card and 'start_date' not in card:
        card['start_date'] = card['end_date']
    if 'start_date' in card and card['start_date'] > card['end_date']:
        card['start_date'], card['end_date'] = card['end_date'], card['start_date']
    if not any(k in card for k in ('date', 'start_date', *_PLACE_FIELDS)):
        raise CardError('give a date, a date range, or a place')
    title = str(raw.get('title') or '').strip()[:80]
    card['title'] = title or _default_title(card)
    return card


def _default_title(card):
    if 'date' in card:
        return card['date']
    where = ', '.join(card[f] for f in _PLACE_FIELDS if f in card)
    if 'start_date' in card:
        span = f"{card['start_date']} to {card['end_date']}"
        return f"{where} · {span}" if where else span
    return where


def span_days(card):
    """Days the card covers, or None when it has no date bound."""
    if 'date' in card:
        return 1
    if 'start_date' in card:
        return (datetime.date.fromisoformat(card['end_date'])
                - datetime.date.fromisoformat(card['start_date'])).days + 1
    return None


def build_qs(user, card, tz_offset=0):
    """The user's own points matching the card. ``card`` must be clean_card()'d."""
    from . import views
    from .models import Location
    qs = Location.objects.filter(device__user=user,
                                 latitude__isnull=False, longitude__isnull=False)
    if 'date' in card:
        start, end = views._journal_day_bounds(_parse_day(card['date']), tz_offset)
        qs = qs.filter(timestamp__gte=start, timestamp__lte=end)
    elif 'start_date' in card:
        start, _ = views._journal_day_bounds(_parse_day(card['start_date']), tz_offset)
        _, end = views._journal_day_bounds(_parse_day(card['end_date']), tz_offset)
        qs = qs.filter(timestamp__gte=start, timestamp__lte=end)
    for f in _PLACE_FIELDS:
        if f in card:
            qs = qs.filter(**{f + '__iexact': card[f]})
    return qs


def _distance(qs, card):
    days = span_days(card)
    if days is None or days > _DISTANCE_MAX_DAYS:
        return None
    from . import views
    return views._compute_distance_from_qs(qs).get('total_km', 0)


def summarize(user, card, tz_offset=0):
    """Cheap facts about a card, for the model and the card header."""
    qs = build_qs(user, card, tz_offset)
    count = qs.count()
    out = {'point_count': count}
    if not count:
        return out
    out['first_ts'] = qs.order_by('timestamp').values_list('timestamp', flat=True).first().isoformat()
    out['last_ts'] = qs.order_by('-timestamp').values_list('timestamp', flat=True).first().isoformat()
    km = _distance(qs, card)
    if km is not None:
        out['distance_km'] = km
    return out


def geometry(user, card, tz_offset=0):
    """What to draw for a card: line segments for a short span, else dots;
    arcs for flights; the pins themselves for a pins card."""
    from . import views
    kind = card.get('kind')
    if kind == 'flights':
        from . import ask_tools
        return ask_tools.flights_geometry(user, card)
    if kind == 'pins':
        pins = card['pins']
        lons = [p['lon'] for p in pins]
        lats = [p['lat'] for p in pins]
        return {'count': len(pins), 'mode': 'pins', 'pins': pins, 'connect': card.get('connect'),
                'bounds': [min(lons), min(lats), max(lons), max(lats)]}
    qs = build_qs(user, card, tz_offset)
    count = qs.count()
    if not count:
        return {'count': 0, 'mode': 'dots', 'segments': [], 'points': [], 'bounds': None}

    # Thin in the database on the id, so a home town with a million fixes never
    # leaves it. Deterministic: the same points survive from one open to the next.
    k = max(1, -(-count // _MAX_POINTS))
    thin = qs.annotate(_m=F('id') % Value(k)).filter(_m=0) if k > 1 else qs
    rows = list(thin.order_by('device_id', 'timestamp')
                .values_list('latitude', 'longitude', 'timestamp', 'device_id'))

    days = span_days(card)
    lines = days is not None and days <= _LINE_MAX_DAYS and not any(f in card for f in _PLACE_FIELDS)
    segments = []
    if lines:
        gap = _SEGMENT_GAP_S * k
        prev = None
        seg = None
        for lat, lon, ts, dev in rows:
            if prev is None or dev != prev[3] or (ts - prev[2]).total_seconds() > gap:
                seg = []
                segments.append(seg)
            seg.append([views._jf(lon), views._jf(lat)])
            prev = (lat, lon, ts, dev)
        segments = [s for s in segments if len(s) >= 2]

    lats = [r[0] for r in rows]
    lons = [r[1] for r in rows]
    out = {
        'count': count,
        'thinned': k > 1,
        'mode': 'lines' if lines else 'dots',
        'segments': segments,
        'points': [[views._jf(r[1]), views._jf(r[0])] for r in rows],
        'bounds': [min(lons), min(lats), max(lons), max(lats)],
    }
    km = _distance(qs, card)
    if km is not None:
        out['distance_km'] = km
    return out


def full_map_url(card):
    """Where "open in the full map" goes."""
    if card.get('kind') == 'flights':
        return '/flights/#map'
    if card.get('kind') == 'pins':
        p = card['pins'][0]
        return '/map/?' + urlencode({'lat': p['lat'], 'lng': p['lon'], 'zoom': 11})
    q = {}
    if 'date' in card:
        q['date'] = card['date']
    elif 'start_date' in card:
        q['start'] = card['start_date']
        q['end'] = card['end_date']
    for f in _PLACE_FIELDS:
        if f in card:
            q[f] = card[f]
    return '/map/?' + urlencode(q)
