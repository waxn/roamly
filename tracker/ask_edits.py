"""Changes the Ask assistant may *propose*, and applying them once the user approves.

The assistant still cannot write. Each ``propose_*`` tool below only reads and
validates, then returns a draft (``_proposal`` in its result); the chat stores
it as an ``AskProposal`` and shows it as a card. The only thing that changes a
record is the user's Approve click (``apply``), which:

* applies exactly the stored payload to one record, through the same
  validation the Flights / Journals / Places pages use;
* first checks the fields it touches still hold what they held when the change
  was proposed, so it never overwrites something the user didn't see;
* for a flight edit, re-runs the edit and refuses unless it changes exactly the
  fields the card listed — nothing else on the record can move.

What can be proposed is deliberately narrow: add a flight, edit an existing
flight's details, fill in an empty journal title/mood/weather or add text to the
end of an entry, and add a custom place. Nothing is ever deleted or replaced
in a journal, and booking references, ticket numbers and the like are not
offered to the model at all.
"""
import copy
import datetime
import hashlib
import logging
import re

from django.db import transaction
from django.utils import timezone

from .ask_tools import _fn, _float

logger = logging.getLogger(__name__)

# Approving a card older than this is refused: by then the conversation has
# moved on and the user should ask again.
PROPOSAL_TTL = datetime.timedelta(days=7)
# Proposals one assistant turn may make; a model that wants more is looping.
MAX_PER_TURN = 5

_TIME_FIELDS = ('sched_dep', 'sched_arr', 'actual_dep', 'actual_arr')
_TIME_PROPS = {}
for _f in _TIME_FIELDS:
    _TIME_PROPS[_f] = {"type": "string",
                       "description": "UTC ISO time, e.g. 2026-11-01T14:05:00Z (as lookup_flight gives it)."}
    _TIME_PROPS[f"{_f}_local"] = {"type": "string",
                                  "description": "Or the airport's local wall clock, YYYY-MM-DDTHH:MM."}

_FLIGHT_PROPS = {
    "flight_number": {"type": "string", "description": "e.g. UA234"},
    "date": {"type": "string", "description": "Local departure date, YYYY-MM-DD."},
    "dep_iata": {"type": "string", "description": "Departure airport IATA code."},
    "arr_iata": {"type": "string", "description": "Arrival airport IATA code."},
    "airline_name": {"type": "string"},
    "airline_iata": {"type": "string"},
    **_TIME_PROPS,
    "aircraft_model": {"type": "string"},
    "registration": {"type": "string"},
    "seat": {"type": "string"},
    "seat_class": {"type": "string", "enum": ["economy", "premium", "business", "first", "private"]},
    "seat_type": {"type": "string", "enum": ["window", "middle", "aisle"]},
    "reason": {"type": "string", "enum": ["leisure", "business", "crew", "other"]},
    "notes": {"type": "string"},
}
FLIGHT_KEYS = tuple(_FLIGHT_PROPS)

EDIT_TOOL_SCHEMAS = [
    _fn("propose_add_flight",
        "Propose adding a flight to the user's flight log. Saves NOTHING: the "
        "user sees a card with Approve / Deny. When they give a flight number "
        "and date, call lookup_flight first and use its route, times, airline "
        "and aircraft; otherwise only details the user stated. Never invent "
        "values.",
        _FLIGHT_PROPS, ["date", "dep_iata", "arr_iata"]),
    _fn("propose_update_flight",
        "Propose changing fields on a flight already in the log (find its id "
        "with list_flights). Only include the fields that should change. Saves "
        "NOTHING until the user approves the card.",
        {"flight_id": {"type": "integer"},
         "changes": {"type": "object", "properties": _FLIGHT_PROPS}},
        ["flight_id", "changes"]),
    _fn("propose_add_place",
        "Propose a new custom place (a named geofence like Home, Work, a "
        "school). Saves NOTHING until the user approves the card. Use real "
        "coordinates.",
        {"name": {"type": "string"},
         "latitude": {"type": "number"}, "longitude": {"type": "number"},
         "radius_m": {"type": "number", "description": "Default 150, 10–50000."},
         "notes": {"type": "string"}},
        ["name", "latitude", "longitude"]),
]

JOURNAL_EDIT_SCHEMA = _fn(
    "propose_journal_entry",
    "Propose filling in a journal entry for a day: an EMPTY title, mood "
    "(a single emoji) or weather, and/or text added to the END of the "
    "entry. Existing text is never replaced. Saves NOTHING until the user "
    "approves the card.",
    {"date": {"type": "string", "description": "YYYY-MM-DD"},
     "title": {"type": "string"}, "mood": {"type": "string", "description": "One emoji."},
     "weather": {"type": "string"},
     "append_text": {"type": "string", "description": "Text to add after what's there."}},
    ["date"])

EDIT_TOOL_NAMES = {s["function"]["name"] for s in EDIT_TOOL_SCHEMAS} | {"propose_journal_entry"}
JOURNAL_EDIT_NAMES = {"propose_journal_entry"}

TOOL_STATUS = {
    "propose_add_flight": "Preparing a flight for you to approve…",
    "propose_update_flight": "Preparing a flight change for you to approve…",
    "propose_add_place": "Preparing a place for you to approve…",
    "propose_journal_entry": "Preparing a journal change for you to approve…",
}

_AWAITING = ("Shown to the user as a card with Approve and Deny. Nothing has been "
             "saved; it changes only if they approve. Tell them it's waiting for "
             "their approval — never say it was saved.")


def edit_schemas(journals_allowed):
    return EDIT_TOOL_SCHEMAS + ([JOURNAL_EDIT_SCHEMA] if journals_allowed else [])


# ── Flights ────────────────────────────────────────────────────────────────

# Every field a proposal may change. A flight edit that would move anything
# else is refused (see _apply_flight_changes).
FLIGHT_FIELDS = (
    'flight_number', 'date', 'airline_name', 'airline_iata', 'airline_icao',
    *[f'{s}_{k}' for s in ('dep', 'arr')
      for k in ('iata', 'icao', 'name', 'city', 'country', 'lat', 'lon', 'tz')],
    *_TIME_FIELDS, 'aircraft_model', 'registration', 'seat', 'seat_class', 'seat_type',
    'reason', 'notes',
)
_FLIGHT_LABELS = {
    'flight_number': 'Flight', 'date': 'Date', 'airline_name': 'Airline',
    'dep_iata': 'From', 'arr_iata': 'To',
    'sched_dep': 'Scheduled departure', 'sched_arr': 'Scheduled arrival',
    'actual_dep': 'Actual departure', 'actual_arr': 'Actual arrival',
    'aircraft_model': 'Aircraft', 'registration': 'Registration', 'seat': 'Seat',
    'seat_class': 'Class', 'seat_type': 'Seat type', 'reason': 'Reason', 'notes': 'Notes',
}
# Fields that never change through Ask: bookkeeping and derived values.
_FLIGHT_UNTRACKED = {'id', 'user', 'created_at', 'updated_at', 'distance_km'}


def _ser(v):
    if isinstance(v, datetime.datetime):
        return v.astimezone(datetime.timezone.utc).isoformat()
    if isinstance(v, datetime.date):
        return v.isoformat()
    if isinstance(v, float):
        return round(v, 6)
    if hasattr(v, 'pk'):
        return v.pk
    if hasattr(v, 'name') and not isinstance(v, str):  # FieldFile
        return v.name or ''
    return v


def _clean_flight_args(raw):
    if not isinstance(raw, dict):
        return {}
    out = {}
    for k in FLIGHT_KEYS:
        v = raw.get(k)
        if v is None:
            continue
        v = str(v).strip()
        if v or k == 'notes':
            out[k] = v[:5000]
    return out


def _airport_label(f, side):
    code = getattr(f, f'{side}_iata') or getattr(f, f'{side}_icao')
    city = getattr(f, f'{side}_city') or getattr(f, f'{side}_name')
    return f'{code} · {city}' if city else code


def _flight_value(f, field):
    """How the card shows one field of ``f``."""
    from .views import _flight_zone
    if field in ('dep_iata', 'arr_iata'):
        return _airport_label(f, field[:3]) or ''
    v = getattr(f, field)
    if field in _TIME_FIELDS:
        if not v:
            return ''
        side = 'dep' if field.endswith('dep') else 'arr'
        tz = _flight_zone(getattr(f, f'{side}_tz'))
        return v.astimezone(tz).strftime('%Y-%m-%d %H:%M') + (' local' if tz else ' UTC')
    if field == 'date':
        return v.isoformat() if v else ''
    return str(v or '')


def _flight_summary(f):
    num = f.flight_number or 'flight'
    return f'{num} {f.dep_iata or f.dep_icao}→{f.arr_iata or f.arr_icao} on {f.date.isoformat()}'


def _duplicate(user, f):
    from .models import Flight
    if not f.flight_number:
        return None
    return Flight.objects.filter(user=user, flight_number=f.flight_number, date=f.date,
                                 dep_iata=f.dep_iata, arr_iata=f.arr_iata).first()


def _new_flight(user, data):
    from .models import Flight
    from .views import _apply_flight
    f = Flight(user=user)
    err = _apply_flight(f, {**data, 'source': 'manual'})
    return f, err


def propose_add_flight(user, **args):
    data = _clean_flight_args(args)
    f, err = _new_flight(user, data)
    if err:
        return {"error": err}
    dup = _duplicate(user, f)
    if dup:
        return {"error": f"That flight is already in the log (flight_id {dup.id}). "
                         "Use propose_update_flight to change it."}
    rows = [{"label": _FLIGHT_LABELS[k], "before": None, "after": _flight_value(f, k)}
            for k in _FLIGHT_LABELS if _flight_value(f, k)]
    summary = f'Add {_flight_summary(f)}'
    return {"proposed": summary, "note": _AWAITING, "_proposal": {
        "kind": "flight_add", "summary": summary[:200], "payload": data, "rows": rows}}


def _merged_flight_data(f, changes):
    """The flight's current values with ``changes`` laid over them, in the
    shape _apply_flight reads."""
    from .views import _FLIGHT_AIRPORT_KEYS, _flight_payload
    base = _flight_payload(f)
    for side in ('dep', 'arr'):
        if f'{side}_iata' in changes:
            # A new airport is filled from the bundled data, so the old one's
            # name/city/position must not ride along and "win" over it.
            for k in _FLIGHT_AIRPORT_KEYS:
                if k != 'iata':
                    base.pop(f'{side}_{k}', None)
    for t in _TIME_FIELDS:
        if t in changes or f'{t}_local' in changes:
            base.pop(t, None)
            base.pop(f'{t}_local', None)
    base.update(changes)
    return base


def _apply_flight_changes(f, changes):
    """(edited copy of ``f``, {field: (old, new)}, error). The copy is unsaved.
    Refuses an edit that would move any field outside FLIGHT_FIELDS."""
    from .views import _FLIGHT_AIRPORT_KEYS, _apply_flight
    g = copy.copy(f)
    err = _apply_flight(g, _merged_flight_data(f, changes))
    if err:
        return None, {}, err
    # Only what ``changes`` names may move. Re-validating the whole flight can
    # also tidy unrelated fields (a bare "BOS" gains its airport name), and an
    # edit of the seat must not quietly do that.
    allowed = set()
    for k in changes:
        k = k[:-len('_local')] if k.endswith('_local') else k
        if k in ('dep_iata', 'arr_iata'):
            allowed |= {f'{k[:3]}_{a}' for a in _FLIGHT_AIRPORT_KEYS}
        allowed.add(k)
        if k == 'airline_iata':
            allowed.add('airline_icao')
    for name in FLIGHT_FIELDS:
        if name not in allowed:
            setattr(g, name, getattr(f, name))
    diff = {}
    for field in f._meta.concrete_fields:
        name = field.attname
        if field.name in _FLIGHT_UNTRACKED:
            continue
        old, new = _ser(getattr(f, name)), _ser(getattr(g, name))
        if old == new:
            continue
        if field.name not in FLIGHT_FIELDS:
            return None, {}, f'That would also change {field.name}, which Ask may not touch.'
        diff[field.name] = (old, new)
    return g, diff, None


def propose_update_flight(user, flight_id=None, changes=None, **_):
    from .models import Flight
    try:
        f = Flight.objects.get(user=user, pk=int(flight_id))
    except (Flight.DoesNotExist, TypeError, ValueError):
        return {"error": "No flight with that flight_id in the log. Find it with list_flights."}
    changes = _clean_flight_args(changes)
    if not changes:
        return {"error": "changes must name at least one field to change."}
    g, diff, err = _apply_flight_changes(f, changes)
    if err:
        return {"error": err}
    if not diff:
        return {"error": "Those values are already what the flight has; nothing to change."}
    rows = []
    for k in _FLIGHT_LABELS:
        if k in diff or (k in ('dep_iata', 'arr_iata') and any(x.startswith(k[:4]) for x in diff)):
            rows.append({"label": _FLIGHT_LABELS[k], "before": _flight_value(f, k) or '—',
                         "after": _flight_value(g, k) or '—'})
    summary = f'Edit {_flight_summary(f)}'
    return {"proposed": summary, "fields": sorted(diff), "note": _AWAITING, "_proposal": {
        "kind": "flight_update", "target_id": f.id, "summary": summary[:200],
        "payload": {"changes": changes, "after": {k: v[1] for k, v in diff.items()}},
        "before": {k: v[0] for k, v in diff.items()}, "rows": rows}}


def _approve_flight_add(user, p):
    from .views import _kick_opensky, _match_flight_track
    f, err = _new_flight(user, p.payload)
    if err:
        return None, err
    with transaction.atomic():
        if _duplicate(user, f):
            return None, 'That flight is already in your log.'
        f.save()
    _match_flight_track(f)
    _kick_opensky(f)
    return f.id, None


def _approve_flight_update(user, p):
    from .models import Flight
    from .views import _flight_track_key, _kick_opensky, _match_flight_track
    with transaction.atomic():
        f = Flight.objects.select_for_update().filter(user=user, pk=p.target_id).first()
        if f is None:
            return None, 'That flight is no longer in your log.'
        for k, old in p.before.items():
            if _ser(getattr(f, k)) != old:
                return None, 'The flight has changed since this was proposed. Ask again.'
        g, diff, err = _apply_flight_changes(f, p.payload.get('changes') or {})
        if err:
            return None, err
        if {k: v[1] for k, v in diff.items()} != p.payload.get('after'):
            return None, 'Applying this now would change something else. Ask again.'
        before_key = _flight_track_key(f)
        if _flight_track_key(g) != before_key:
            # A different flight now: whatever OpenSky said about the old one
            # no longer applies (as flight_update_api).
            g.opensky_track = None
            g.opensky_status = g.opensky_message = ''
            g.opensky_attempts = 0
            g.opensky_checked_at = None
        g.save()
    if not g.track_device_id or _flight_track_key(g) != before_key:
        _match_flight_track(g)
    _kick_opensky(g)
    return g.id, None


# ── Journals ───────────────────────────────────────────────────────────────

_JOURNAL_LIMITS = {'title': 300, 'mood': 20, 'weather': 60}
_JOURNAL_LABELS = {'title': 'Title', 'mood': 'Mood', 'weather': 'Weather'}


def _digest(text):
    return hashlib.sha256((text or '').encode('utf-8')).hexdigest()


def _journal_date(raw):
    try:
        d = datetime.date.fromisoformat(str(raw or '').strip())
    except ValueError:
        return None
    if d > timezone.localdate() + datetime.timedelta(days=1):
        return None
    return d


def propose_journal_entry(user, date=None, title=None, mood=None, weather=None,
                          append_text=None, **_):
    from .models import JournalEntry
    d = _journal_date(date)
    if d is None:
        return {"error": "date must be YYYY-MM-DD and not in the future."}
    entry = JournalEntry.objects.filter(user=user, date=d).first()
    sets, before, rows = {}, {}, []
    for field, val in (('title', title), ('mood', mood), ('weather', weather)):
        val = str(val or '').strip()
        if not val:
            continue
        val = val[:_JOURNAL_LIMITS[field]]
        if field == 'mood' and re.search(r'[A-Za-z]{2,}', val):
            return {"error": "mood must be an emoji, not a word."}
        current = getattr(entry, field, '') if entry else ''
        if current:
            return {"error": f"The entry already has a {field}. Ask can only fill in "
                             f"empty fields, never replace them."}
        sets[field] = val
        before[field] = ''
        rows.append({"label": _JOURNAL_LABELS[field], "before": None, "after": val})
    text = str(append_text or '').strip()[:5000]
    if text:
        sets['append_text'] = text
        before['body_sha'] = _digest(entry.body if entry else '')
        rows.append({"label": "Add to the end" if entry and entry.body.strip() else "Text",
                     "before": None, "after": text})
    if not sets:
        return {"error": "Give at least one of title, mood, weather or append_text."}
    summary = f'{"Update" if entry else "Start"} journal entry for {d.isoformat()}'
    return {"proposed": summary, "note": _AWAITING, "_proposal": {
        "kind": "journal", "target_key": d.isoformat(), "summary": summary,
        "payload": sets, "before": before, "rows": rows}}


def _approve_journal(user, p):
    from .models import JournalEntry
    d = datetime.date.fromisoformat(p.target_key)
    with transaction.atomic():
        return _approve_journal_locked(user, p, d)


def _approve_journal_locked(user, p, d):
    from .models import JournalEntry
    entry, _ = JournalEntry.objects.select_for_update().get_or_create(user=user, date=d)
    for field in ('title', 'mood', 'weather'):
        if field in p.payload and getattr(entry, field):
            return None, f'The entry has a {field} now. Nothing was changed.'
    if 'body_sha' in p.before and _digest(entry.body) != p.before['body_sha']:
        return None, 'The entry has been edited since this was proposed. Ask again.'
    for field in ('title', 'mood', 'weather'):
        if field in p.payload:
            setattr(entry, field, p.payload[field][:_JOURNAL_LIMITS[field]])
    text = p.payload.get('append_text')
    if text:
        entry.body = (entry.body.rstrip() + '\n\n' + text) if entry.body.strip() else text
    entry.save()
    return entry.id, None


# ── Places ─────────────────────────────────────────────────────────────────

def propose_add_place(user, name=None, latitude=None, longitude=None, radius_m=None,
                      notes=None, **_):
    from .models import CustomPlace
    name = str(name or '').strip()[:200]
    if not name:
        return {"error": "A place needs a name."}
    lat, lon = _float(latitude), _float(longitude)
    if lat is None or lon is None or not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return {"error": "latitude and longitude must be real coordinates."}
    radius = _float(radius_m)
    radius = max(10.0, min(radius if radius is not None else 150.0, 50000.0))
    if CustomPlace.objects.filter(user=user, name__iexact=name).exists():
        return {"error": f"There is already a place called {name}."}
    notes = str(notes or '').strip()[:2000]
    payload = {"name": name, "lat": round(lat, 6), "lng": round(lon, 6),
               "radius_m": round(radius), "notes": notes}
    rows = [{"label": "Name", "before": None, "after": name},
            {"label": "Centre", "before": None, "after": f'{lat:.5f}, {lon:.5f}'},
            {"label": "Radius", "before": None, "after": f'{round(radius)} m'}]
    if notes:
        rows.append({"label": "Notes", "before": None, "after": notes})
    summary = f'Add place “{name}”'
    return {"proposed": summary, "note": _AWAITING, "_proposal": {
        "kind": "place_add", "summary": summary[:200], "payload": payload, "rows": rows}}


def _approve_place(user, p):
    from .models import CustomPlace
    from .views import _create_custom_place
    d = p.payload
    if CustomPlace.objects.filter(user=user, name__iexact=d['name']).exists():
        return None, f'There is already a place called {d["name"]}.'
    place = _create_custom_place(user, d['name'], d['lat'], d['lng'], d['radius_m'], d.get('notes', ''))
    return place.id, None


# ── Dispatch, approval and display ─────────────────────────────────────────

EDIT_DISPATCH = {
    "propose_add_flight": propose_add_flight,
    "propose_update_flight": propose_update_flight,
    "propose_add_place": propose_add_place,
    "propose_journal_entry": propose_journal_entry,
}

_APPROVERS = {
    'flight_add': _approve_flight_add,
    'flight_update': _approve_flight_update,
    'journal': _approve_journal,
    'place_add': _approve_place,
}

_LINKS = {'flight_add': '/flights/', 'flight_update': '/flights/',
          'journal': '/journals/', 'place_add': '/places/'}


def approve(user, proposal_id):
    """Apply one pending proposal. Returns the proposal (refreshed), or None
    when it isn't the user's. A refusal is recorded as ``failed`` with the
    reason; nothing is written in that case."""
    from .models import AskProposal
    p = AskProposal.objects.filter(user=user, pk=proposal_id).first()
    if p is None or p.status != 'pending':
        return p
    now = timezone.now()
    # Claim it first, so a double click (or two tabs) applies it once.
    if not AskProposal.objects.filter(pk=p.pk, status='pending').update(
            status='approved', decided_at=now):
        p.refresh_from_db()
        return p
    if p.message_id is None or now - p.created_at > PROPOSAL_TTL:
        result, err = None, 'This proposal has expired. Ask again.'
    else:
        try:
            result, err = _APPROVERS[p.kind](user, p)
        except Exception:
            logger.exception('applying Ask proposal %s failed', p.pk)
            result, err = None, 'Something went wrong applying this. Nothing was changed.'
    if err:
        AskProposal.objects.filter(pk=p.pk).update(status='failed', error=err[:300])
    else:
        AskProposal.objects.filter(pk=p.pk).update(result_id=result)
    p.refresh_from_db()
    return p


def deny(user, proposal_id):
    from .models import AskProposal
    n = AskProposal.objects.filter(user=user, pk=proposal_id, status='pending').update(
        status='denied', decided_at=timezone.now())
    p = AskProposal.objects.filter(user=user, pk=proposal_id).first()
    return p if (p and (n or p.status != 'pending')) else None


def serialize(p):
    out = {"id": p.id, "kind": p.kind, "summary": p.summary, "rows": p.rows,
           "status": p.status, "error": p.error, "link": _LINKS.get(p.kind, '')}
    if p.kind == 'place_add':
        out["pin"] = {"name": p.payload.get('name'), "lat": p.payload.get('lat'),
                      "lon": p.payload.get('lng')}
    return out


_OUTCOME = {'pending': 'still waiting for the user to approve or deny',
            'approved': 'APPROVED by the user and saved',
            'denied': 'DENIED by the user; nothing was changed',
            'failed': 'NOT applied'}


def history_note(proposals):
    """A line per proposal, appended to the assistant turn it came from when
    the conversation is replayed, so the model knows what actually happened."""
    lines = []
    for p in proposals:
        why = f' ({p.error})' if p.status == 'failed' and p.error else ''
        lines.append(f'[Proposed change: {p.summary} — {_OUTCOME.get(p.status, p.status)}{why}]')
    return '\n'.join(lines)
