"""AI "Ask" feature — OpenAI-compatible chat with read-only history tools.

The user asks a natural-language question about their own location history
("have I been to Old Orchard Beach?", "when was I last at the spa?"). We call
the user's configured OpenAI-compatible provider with a set of **read-only**
tools backed by Roamly's existing query helpers. The model never writes SQL — it
only picks a tool + structured args; we run the tool scoped to the requesting
user and feed the (token-trimmed) result back, looping until it answers.

Config lives per-user on UserProfile (ai_base_url / ai_api_key / ai_model /
ai_system_prompt). Provider format is OpenAI Chat Completions only.
"""

import datetime
import json
import logging
import time
from concurrent.futures import ThreadPoolExecutor

import requests

logger = logging.getLogger(__name__)

# Cap the tool-call loop so a confused model can't ping-pong forever.
_MAX_TOOL_ITERATIONS = 5
# Keep at most this many of the most recent client turns (bounds token use).
_MAX_CLIENT_TURNS = 20
_REQUEST_TIMEOUT = 45
# Tool calls from one model turn run side by side, up to this many.
_MAX_PARALLEL_TOOLS = 4

_session = requests.Session()


from .net_utils import validate_outbound_url, OutboundURLError


class AIProviderError(Exception):
    """A user-facing failure talking to the configured AI provider."""


# ── Tool schemas (OpenAI function-calling format) ──────────────────────────

TOOL_SCHEMAS = [
    {
        "type": "function",
        "function": {
            "name": "search_history",
            "description": (
                "Search the user's location history for a place name, city, state, "
                "country, point of interest, or a date/period. Use this to answer "
                "'have I been to X?', 'when was I last at Y?', 'when did I go to Z?'. "
                "Returns the days the user was there with point counts, plus any "
                "matching named places."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "A place/city/state/country/POI name, or a date like '2024', 'January 2024', 'yesterday'.",
                    }
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_day_detail",
            "description": (
                "Everything recorded on one specific calendar day (YYYY-MM-DD): every "
                "city, state, and country visited, named points of interest (stores, "
                "venues), any saved custom places entered, total distance, and the time "
                "range of activity. Use this to answer 'where did I go on <date>?' or to "
                "name specific stops on a day a broader search only matched at the state "
                "level."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "date": {"type": "string", "description": "The day to detail, YYYY-MM-DD."}
                },
                "required": ["date"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_visited_places",
            "description": (
                "List the cities, states, or countries the user has visited, most-visited "
                "first, with visit counts and first/last seen dates. Use for 'what cities "
                "have I been to?', 'how many countries have I visited?'."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "level": {
                        "type": "string",
                        "enum": ["city", "state", "country"],
                        "description": "Granularity of the list.",
                    }
                },
                "required": ["level"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_custom_places",
            "description": (
                "List the user's saved custom places (named geofences like 'Home', "
                "'Work', 'the spa') with how many points and the last time they were there."
            ),
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_custom_place_detail",
            "description": (
                "Detailed stats for one saved custom place by name: total points, days, "
                "time spent, first/last seen, and nearby cities. Use for 'when was I last "
                "at the spa?', 'how long have I spent at home?'."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "description": "The custom place name (partial match ok)."}
                },
                "required": ["name"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_distance",
            "description": (
                "Total distance the user travelled (km) over an optional ISO date range "
                "(YYYY-MM-DD). Omit dates for all-time. Use for 'how far did I travel "
                "last month?'."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "start_date": {"type": "string", "description": "Inclusive start, YYYY-MM-DD. Optional."},
                    "end_date": {"type": "string", "description": "Inclusive end, YYYY-MM-DD. Optional."},
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_history_overview",
            "description": (
                "High-level summary of the whole history: total points, distinct "
                "countries/cities/states, and the first and last recorded dates."
            ),
            "parameters": {"type": "object", "properties": {}},
        },
    },
]

# Journal tools are only offered when the user has opted in (ai_allow_journals),
# because journal entries can be personal/sensitive.
JOURNAL_TOOL_SCHEMAS = [
    {
        "type": "function",
        "function": {
            "name": "search_journals",
            "description": (
                "Search the user's personal journal entries by keyword (title/body), most "
                "recent first. Returns dates, titles, moods, and short snippets. Use to find "
                "which days the user wrote about a topic."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Keyword to search for. Omit to list recent entries."}
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_journal_entry",
            "description": (
                "Read the full journal entry for one date (YYYY-MM-DD): title, mood, weather, "
                "favorite flag, location, and the full body text."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "date": {"type": "string", "description": "The entry date, YYYY-MM-DD."}
                },
                "required": ["date"],
            },
        },
    },
]

_JOURNAL_TOOL_NAMES = {"search_journals", "read_journal_entry"}

from .ask_tools import DATA_TOOL_SCHEMAS, MAP_EXTRA_SCHEMAS  # noqa: E402

TOOL_SCHEMAS = TOOL_SCHEMAS + DATA_TOOL_SCHEMAS

# Offered only in the Ask chat, whose page can draw the card. It changes nothing:
# it validates the parameters, counts the matching points and hands the browser a
# small description of what to draw.
MAP_TOOL_SCHEMAS = [
    {
        "type": "function",
        "function": {
            "name": "show_map",
            "description": (
                "Show part of the user's own location history on a map beside the "
                "chat. Use it whenever a map would help: 'show me last Saturday', "
                "'where did I go on that trip', 'map my time in Portland'. Give a "
                "single date, or a start/end date range, and/or a place "
                "(city/state/country, exactly as the tools returned them). Short "
                "date spans draw as a route; longer spans or place filters draw as "
                "dots. You get back how many points matched; the user sees the map. "
                "Call it again for a second map."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {"type": "string", "description": "Short caption for the card."},
                    "date": {"type": "string", "description": "One day, YYYY-MM-DD."},
                    "start_date": {"type": "string", "description": "Range start, YYYY-MM-DD."},
                    "end_date": {"type": "string", "description": "Range end, YYYY-MM-DD."},
                    "city": {"type": "string"},
                    "state": {"type": "string"},
                    "country": {"type": "string"},
                },
            },
        },
    },
]


MAP_TOOL_SCHEMAS = MAP_TOOL_SCHEMAS + MAP_EXTRA_SCHEMAS
_MAP_TOOL_NAMES = {t["function"]["name"] for t in MAP_TOOL_SCHEMAS}


def build_tool_schemas(profile, maps=False):
    """Tools offered to the model — journal tools only when the user opted in,
    the map tool only where there is a map to show it on."""
    tools = list(TOOL_SCHEMAS)
    if getattr(profile, "ai_allow_journals", False):
        tools += JOURNAL_TOOL_SCHEMAS
    if maps:
        tools += MAP_TOOL_SCHEMAS
    return tools


# ── Tool implementations (read-only, user-scoped, token-trimmed) ───────────

def _tool_search_history(user, query=None, **_):
    if not query or not str(query).strip():
        return {"error": "query is required"}
    from . import views
    payload = views._run_history_search(user, str(query).strip())
    places = [
        {
            "name": p.get("place_name"),
            "total_points": p.get("total_points", 0),
            "days_count": len(p.get("days", [])),
            "is_custom": p.get("is_custom", False),
        }
        for p in payload.get("place_results", [])[:10]
    ]
    days = [{"date": d.get("date"), "count": d.get("count", 0)}
            for d in payload.get("location_results", [])[:30]]
    return {
        "query": payload.get("query"),
        "query_type": payload.get("query_type"),
        "total_days": payload.get("total_days", 0),
        "total_points": payload.get("total_points", 0),
        "days": days,
        "places": places,
    }


def _tool_get_day_detail(user, date=None, tz_offset=0, **_):
    if not date or not str(date).strip():
        return {"error": "date is required (YYYY-MM-DD)"}
    from . import views
    return views._compute_day_detail(user, str(date).strip(), tz_offset)


def _snapshot_json(user, field):
    """One JSON column of the user's finished StatsSnapshot, or None.

    The all-time tools below used to rescan the whole history on every call,
    several seconds each on a large account. The nightly snapshot holds the
    same payloads from the same helpers. Never kicks a compute — a missing
    snapshot just means the caller falls back to the live path."""
    from .models import StatsSnapshot
    snap = (StatsSnapshot.objects.filter(user=user, status='done')
            .only('id', field).first())
    return (getattr(snap, field, None) or None) if snap else None


def _tool_list_visited_places(user, level="city", **_):
    from . import views
    from .models import Location
    level = (level or "city").lower()
    data = _snapshot_json(user, 'visits_json')
    if data is None:
        locations = Location.objects.filter(device__user=user).exclude(city="")
        data = views._compute_visits_from_qs(locations)
    if level == "country":
        rows = data.get("countries", [])[:25]
        return {"level": level, "places": [
            {"name": r.get("country"), "country_code": r.get("country_code"),
             "location_count": r.get("location_count")} for r in rows]}
    if level == "state":
        rows = data.get("states", [])[:25]
        return {"level": level, "places": [
            {"name": r.get("state"), "region": r.get("country"),
             "location_count": r.get("location_count")} for r in rows]}
    rows = data.get("cities", [])[:25]
    return {"level": "city", "places": [
        {"name": r.get("city"), "region": r.get("state") or r.get("country"),
         "visit_count": r.get("visit_count"),
         "first_seen": r.get("first_seen"), "last_seen": r.get("last_seen")} for r in rows]}


def _tool_list_custom_places(user, **_):
    from . import views
    payload = views._compute_places_payload(user)
    return {"places": [
        {"name": p.get("name"), "point_count": p.get("point_count", 0),
         "last_seen": p.get("last_seen")} for p in payload.get("places", [])]}


def _tool_get_custom_place_detail(user, name=None, **_):
    if not name or not str(name).strip():
        return {"error": "name is required"}
    from . import views
    from .models import CustomPlace
    matches = list(CustomPlace.objects.filter(user=user, name__icontains=str(name).strip()))
    if not matches:
        return {"error": f"No custom place matching '{name}'. Try list_custom_places."}
    if len(matches) > 1:
        return {"disambiguation": [m.name for m in matches[:10]],
                "note": "Multiple places match; ask the user which one or call again with an exact name."}
    detail = views._compute_place_detail(user, matches[0])
    hours = round((detail.get("time_spent") or 0) / 3600.0, 1)
    return {
        "name": detail.get("name"),
        "total_points": detail.get("point_count", 0),
        "total_days": len(detail.get("days", [])),
        "time_spent_hours": hours,
        "first_seen": detail.get("first_seen"),
        "last_seen": detail.get("last_seen"),
        "cities": [{"city": c.get("city"), "state": c.get("state")} for c in detail.get("cities", [])[:10]],
    }


def _tool_get_distance(user, start_date=None, end_date=None, **_):
    from django.utils import timezone
    from . import views
    from .models import Location

    if not start_date and not end_date:
        snap = _snapshot_json(user, 'stats_json')
        dist = (snap or {}).get('distance')
        if dist:
            return {"total_km": dist.get("total_km", 0), "days_count": len(dist.get("days", []))}

    locations = Location.objects.filter(device__user=user)

    def _parse(d, end=False):
        try:
            dt = datetime.datetime.fromisoformat(str(d))
        except (ValueError, TypeError):
            return None
        if end:
            dt = dt.replace(hour=23, minute=59, second=59)
        if timezone.is_naive(dt):
            # aware_local, not make_aware: this runs in a request, so a real
            # zone is active and make_aware raises on a DST-imaginary midnight.
            from .tz_utils import aware_local
            dt = aware_local(dt, end_of_day=end)
        return dt

    if start_date:
        s = _parse(start_date)
        if s is None:
            return {"error": "start_date must be YYYY-MM-DD"}
        locations = locations.filter(timestamp__gte=s)
    if end_date:
        e = _parse(end_date, end=True)
        if e is None:
            return {"error": "end_date must be YYYY-MM-DD"}
        locations = locations.filter(timestamp__lte=e)

    data = views._compute_distance_from_qs(locations)
    return {"total_km": data.get("total_km", 0), "days_count": len(data.get("days", []))}


def _tool_get_history_overview(user, **_):
    from . import views
    from .models import Location
    snap = _snapshot_json(user, 'stats_json')
    if snap:
        # The per-day distance series is for the Stats page; the model only
        # needs the counts, and it would be thousands of tokens.
        return {k: v for k, v in snap.items() if k != 'distance'}
    locations = Location.objects.filter(device__user=user)
    return views._compute_overview_from_qs(locations, user)


def _tool_search_journals(user, query=None, **_):
    from django.db.models import Q
    from .models import JournalEntry
    qs = JournalEntry.objects.filter(user=user)
    q = (query or "").strip()
    if q:
        qs = qs.filter(Q(title__icontains=q) | Q(body__icontains=q))
    entries = []
    for e in qs.order_by("-date")[:25]:
        body = e.body or ""
        entries.append({
            "date": e.date.isoformat(),
            "title": e.title,
            "mood": e.mood,
            "snippet": (body[:200] + "…") if len(body) > 200 else body,
        })
    return {"entries": entries}


def _tool_read_journal_entry(user, date=None, **_):
    from .models import JournalEntry
    if not date or not str(date).strip():
        return {"error": "date is required (YYYY-MM-DD)"}
    try:
        d = datetime.date.fromisoformat(str(date).strip())
    except (ValueError, TypeError):
        return {"error": "date must be YYYY-MM-DD"}
    e = JournalEntry.objects.filter(user=user, date=d).first()
    if not e:
        return {"error": f"No journal entry on {date}."}
    return {
        "date": e.date.isoformat(),
        "title": e.title,
        "mood": e.mood,
        "weather": e.weather,
        "is_favorite": e.is_favorite,
        "location_name": e.location_name,
        "body": e.body or "",
    }


def _tool_show_map(user, tz_offset=0, **args):
    from . import ask_map
    try:
        card = ask_map.clean_card(args)
    except ask_map.CardError as e:
        return {"error": str(e)}
    summary = ask_map.summarize(user, card, tz_offset)
    if not summary.get("point_count"):
        return {"error": "No recorded points match that, so there is nothing to map."}
    # "_card" is lifted off by the runner: the model sees the summary, the
    # browser gets the card.
    return {"shown": True, **summary, "_card": card}


from . import ask_tools as _ask_tools  # noqa: E402

TOOL_DISPATCH = {
    **_ask_tools.DATA_DISPATCH,
    **_ask_tools.MAP_DISPATCH,
    "show_map": _tool_show_map,
    "search_history": _tool_search_history,
    "get_day_detail": _tool_get_day_detail,
    "list_visited_places": _tool_list_visited_places,
    "list_custom_places": _tool_list_custom_places,
    "get_custom_place_detail": _tool_get_custom_place_detail,
    "get_distance": _tool_get_distance,
    "get_history_overview": _tool_get_history_overview,
    "search_journals": _tool_search_journals,
    "read_journal_entry": _tool_read_journal_entry,
}


# ── Provider call ──────────────────────────────────────────────────────────

def _provider_chat(profile, messages, tools=None):
    """POST to {base_url}/chat/completions and return the parsed JSON, translating
    failures into AIProviderError with a user-facing message."""
    base = (profile.ai_base_url or "").rstrip("/")
    # Re-checked here, not just at save time: DNS is not stable, so a hostname
    # that resolved publicly when it was saved can resolve to 127.0.0.1 on the
    # next lookup. Only a check immediately before connecting catches that.
    try:
        validate_outbound_url(base, label="AI base URL")
    except OutboundURLError as exc:
        raise AIProviderError(str(exc))
    url = f"{base}/chat/completions"
    body = {"model": profile.ai_model, "messages": messages}
    if tools:
        body["tools"] = tools
        body["tool_choice"] = "auto"
    try:
        # A shared Session keeps the TLS connection alive between the loop's
        # round-trips instead of paying a handshake on each one.
        resp = _session.post(
            url,
            headers={
                "Authorization": f"Bearer {profile.ai_api_key}",
                "Content-Type": "application/json",
            },
            json=body,
            timeout=_REQUEST_TIMEOUT,
            # Without this an allow-listed host can 302 to a private address and
            # requests will follow it, defeating the check above. No
            # OpenAI-compatible provider needs a redirect here.
            allow_redirects=False,
        )
    except requests.exceptions.Timeout:
        raise AIProviderError("The AI provider timed out. Try again.")
    except requests.exceptions.RequestException:
        raise AIProviderError("The AI provider is unreachable. Check the base URL.")

    _raise_for_status(resp)

    try:
        data = resp.json()
    except ValueError:
        raise AIProviderError("The AI provider returned a non-JSON response.")
    if not data.get("choices"):
        raise AIProviderError("The AI provider returned an unexpected response.")
    return data


def _raise_for_status(resp):
    if resp.status_code in (401, 403):
        raise AIProviderError("The AI provider rejected the API key.")
    if resp.status_code == 404:
        raise AIProviderError("AI model or endpoint not found — check the model name and base URL.")
    if resp.status_code == 400:
        msg = _error_message(resp)
        if "tool" in msg.lower():
            raise AIProviderError("This model does not support tool calling.")
        raise AIProviderError(msg or "The AI provider rejected the request.")
    if resp.status_code >= 500:
        raise AIProviderError("The AI provider returned a server error. Try again.")
    if resp.status_code != 200:
        raise AIProviderError(_error_message(resp) or "Unexpected response from the AI provider.")


def _error_message(resp):
    try:
        err = resp.json().get("error")
        if isinstance(err, dict):
            return err.get("message", "")
        if isinstance(err, str):
            return err
    except (ValueError, AttributeError):
        pass
    return ""


# ── Orchestration ──────────────────────────────────────────────────────────

# Appended to every system prompt (custom or default) so the chat UI can turn
# dates into links to that day on the map. The frontend only renders internal
# ("/"-prefixed) markdown links.
_DATE_LINK_DIRECTIVE = (
    "Formatting: whenever you mention a specific calendar date, render it as a "
    "Markdown link to that day on the map — friendly text, ISO date in the URL, "
    "e.g. [Nov 27, 2025](/map/?date=2025-11-27). Only link real dates."
)


def _local_today(tz_offset):
    """Today's date in the user's local time. ``tz_offset`` is the browser's
    Date.getTimezoneOffset() (minutes, UTC − local), so local = UTC − offset."""
    try:
        tz_offset = int(tz_offset)
    except (TypeError, ValueError):
        tz_offset = 0
    now_utc = datetime.datetime.now(datetime.timezone.utc)
    return (now_utc - datetime.timedelta(minutes=tz_offset)).date()


# The chat page's extra brief: a travel companion as well as a history lookup.
# The read-only rule is stated to the model too, but it is enforced by there
# being no tool that writes — not by the prompt.
_CHAT_DIRECTIVE = (
    "You are also a travel companion. Besides looking things up, you can help "
    "plan trips, brainstorm ideas, recommend destinations, restaurants and "
    "routes, explain flights, and suggest what to do next. Facts about the user "
    "(where they went, their flights, adventures, habits) must come from the "
    "tools. General travel knowledge — destinations, seasons, tips, distances, "
    "typical flight times — may come from what you know; mark it as a "
    "suggestion and never present it as something from their history. Ground "
    "recommendations in their history when it helps (\"you loved X, so Y\"; "
    "list_unvisited for somewhere new; search_near to check if they've been "
    "close).\n\n"
    "Maps: show_map draws their own history (a day, a range, a place); "
    "show_flights_map draws their flight routes; show_places pins places you "
    "suggest or discuss (give real coordinates; connect=true for an ordered "
    "route). Use a map whenever the answer is about where — not for pure counts.\n\n"
    "Formatting: Markdown — short paragraphs, bullet or numbered lists, ### "
    "headings for longer plans, **bold** for key facts. For a day-by-day plan "
    "use a heading per day.\n\n"
    "You can only read. You cannot add, change or delete any location, flight, "
    "adventure, journal or setting; if asked to, say so and point to the page "
    "where they can do it themselves."
)

ASK_MODES = {
    "plan": ("The user wants help planning a trip. Ask at most one or two short "
             "clarifying questions only if essential (dates, budget, pace), otherwise "
             "propose a concrete day-by-day plan, pin the stops with show_places "
             "(connect=true) and note travel times between them."),
    "brainstorm": ("Brainstorm with the user: offer several varied, specific ideas "
                   "(6–10), each one line with why it fits them, then invite them to "
                   "pick one to develop. Pin the ideas with show_places when they are places."),
    "flights": ("Focus on flights: their flight log (list_flights, get_flight_stats, "
                "show_flights_map) and live flight information (lookup_flight)."),
    "recap": ("Write a warm, vivid recap of the period the user names from their "
              "history — the story of where they went, with a map."),
}


def _system_prompt(user, profile, tz_offset=0, maps=False, mode=None):
    tail = _DATE_LINK_DIRECTIVE
    if maps:
        tail += "\n\n" + _CHAT_DIRECTIVE
    if mode in ASK_MODES:
        tail += "\n\nThis conversation: " + ASK_MODES[mode]
    if profile.ai_system_prompt and profile.ai_system_prompt.strip():
        return profile.ai_system_prompt.strip() + "\n\n" + tail
    today = _local_today(tz_offset).isoformat()
    name = user.first_name or user.username
    journals = (
        " The user has allowed you to read their personal journal entries — use "
        "search_journals / read_journal_entry when a question is about how they felt "
        "or what they wrote, and treat that content as private."
        if getattr(profile, "ai_allow_journals", False) else ""
    )
    return (
        f"You are Roamly's location-history assistant for {name}. Today is {today}. "
        "All data you can access belongs to this one user — their own GPS location "
        "history (places they have physically been). Answer questions about where "
        "they have been, when, and how long. Always call the provided tools to look "
        "up facts rather than guessing or relying on prior knowledge; if a search "
        "only matches at the state level, call get_day_detail for the relevant dates "
        "to name specific cities and stops. Every round of tool calls costs the user "
        "a wait, so when several lookups are independent (several dates, or overview "
        "plus distance), request them all in the same turn rather than one at a "
        "time. If a tool returns no results, say so "
        "plainly. Be concise and conversational, and cite concrete dates from the "
        "tool results. Dwell/time values from tools are in seconds unless noted as "
        f"hours.{journals}\n\n" + tail
    )


def _sanitize_client_messages(client_messages):
    """Keep only well-formed user/assistant turns with string content — never trust
    client-supplied system/tool roles or non-string content."""
    clean = []
    for m in client_messages or []:
        if not isinstance(m, dict):
            continue
        role = m.get("role")
        content = m.get("content")
        if role in ("user", "assistant") and isinstance(content, str) and content.strip():
            clean.append({"role": role, "content": content})
    return clean[-_MAX_CLIENT_TURNS:]


def _run_one_tool(user, call, tz_offset, journals_allowed, maps=False):
    fn = (call.get("function") or {})
    name = fn.get("name")
    try:
        args = json.loads(fn.get("arguments") or "{}")
        if not isinstance(args, dict):
            args = {}
    except (ValueError, TypeError):
        args = {}
    args.pop("tz_offset", None)  # server-supplied only; never from the model
    handler = TOOL_DISPATCH.get(name)
    if name in _JOURNAL_TOOL_NAMES and not journals_allowed:
        # Defense in depth: never read journals unless opted in, even if a
        # model hallucinates the tool name.
        return {"error": "Journal access is not enabled for this account."}
    if name in _MAP_TOOL_NAMES and not maps:
        return {"error": "Maps can't be shown here."}
    if handler is None:
        return {"error": f"unknown tool '{name}'"}
    started = time.monotonic()
    try:
        # tz_offset is passed to every handler; those that don't need
        # it absorb it via **_ (only day/date tools use it).
        return handler(user, tz_offset=tz_offset, **args)
    except Exception:
        logger.exception("AI tool %s failed", name)
        return {"error": "tool failed to run"}
    finally:
        logger.info("AI tool %s took %d ms", name, (time.monotonic() - started) * 1000)


def _run_tool_calls(user, tool_calls, tz_offset, journals_allowed, maps=False):
    """Run one model turn's tool calls and return their results in call order.

    They are independent reads, so several run side by side. Worker threads
    need two things the request thread has implicitly: the active timezone
    (thread-local, and the distance/day bucketing depends on it) and their own
    DB connections, which must be closed on the way out — with conn_max_age set
    a thread's connection otherwise outlives it (see scheduler_utils.db_thread).
    """
    if len(tool_calls) == 1:
        return [_run_one_tool(user, tool_calls[0], tz_offset, journals_allowed, maps)]

    from django.db import connections
    from django.utils import timezone
    tz = timezone.get_current_timezone()

    def work(call):
        try:
            with timezone.override(tz):
                return _run_one_tool(user, call, tz_offset, journals_allowed, maps)
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=_MAX_PARALLEL_TOOLS) as pool:
        return list(pool.map(work, tool_calls))


def run_ask(user, client_messages, profile, tz_offset=0):
    """Run the tool-calling loop and return the assistant's final text answer.
    ``tz_offset`` (browser Date.getTimezoneOffset() minutes) anchors "today" and
    per-day lookups to the user's local time, not the server's UTC day."""
    messages = [{"role": "system", "content": _system_prompt(user, profile, tz_offset)}]
    messages.extend(_sanitize_client_messages(client_messages))
    if len(messages) == 1:
        raise AIProviderError("No question was provided.")

    tools = build_tool_schemas(profile)
    journals_allowed = bool(getattr(profile, "ai_allow_journals", False))

    for _ in range(_MAX_TOOL_ITERATIONS):
        started = time.monotonic()
        data = _provider_chat(profile, messages, tools=tools)
        logger.info("AI provider call took %d ms", (time.monotonic() - started) * 1000)
        choice = data["choices"][0]
        msg = choice.get("message", {}) or {}
        tool_calls = msg.get("tool_calls")

        if not tool_calls:
            return msg.get("content") or "I couldn't find an answer to that."

        # Append the assistant turn (with its tool_calls) then one tool result each.
        messages.append({
            "role": "assistant",
            "content": msg.get("content") or "",
            "tool_calls": tool_calls,
        })
        results = _run_tool_calls(user, tool_calls, tz_offset, journals_allowed)
        for call, result in zip(tool_calls, results):
            result, _card = _lift_card(result)
            messages.append({
                "role": "tool",
                "tool_call_id": call.get("id"),
                "content": json.dumps(result, default=str),
            })

    # Loop cap hit — make one final no-tool call for a best-effort summary.
    messages.append({
        "role": "user",
        "content": "Please answer now using what you've gathered so far.",
    })
    data = _provider_chat(profile, messages)
    return data["choices"][0].get("message", {}).get("content") or \
        "I gathered some data but couldn't complete the answer."


def _lift_card(result):
    """Split a tool result into (what the model sees, the map card or None)."""
    if isinstance(result, dict) and "_card" in result:
        result = dict(result)
        return result, result.pop("_card")
    return result, None


# ── Streaming (the Ask chat page) ──────────────────────────────────────────

_TOOL_STATUS = {
    "search_history": "Searching your history…",
    "get_day_detail": "Looking at a day…",
    "list_visited_places": "Listing places you've been…",
    "list_custom_places": "Checking your places…",
    "get_custom_place_detail": "Looking at a place…",
    "get_distance": "Measuring distance…",
    "get_history_overview": "Getting an overview…",
    "search_journals": "Searching your journal…",
    "read_journal_entry": "Reading a journal entry…",
    "show_map": "Drawing a map…",
    **_ask_tools.TOOL_STATUS,
}


# Base URLs that rejected ``stream_options``, so later turns don't pay a failed
# request to find out again. Per process; a restart just asks once more.
_NO_STREAM_OPTIONS = set()


def _stream_post(profile, base, messages, tools, usage):
    body = {"model": profile.ai_model, "messages": messages, "stream": True}
    if usage:
        # OpenAI's way to get token counts on a streamed reply: one extra final
        # chunk carrying ``usage``. Ollama, LM Studio, vLLM and llama.cpp honour it.
        body["stream_options"] = {"include_usage": True}
    if tools:
        body["tools"] = tools
        body["tool_choice"] = "auto"
    try:
        return _session.post(
            f"{base}/chat/completions",
            headers={
                "Authorization": f"Bearer {profile.ai_api_key}",
                "Content-Type": "application/json",
            },
            json=body,
            timeout=_REQUEST_TIMEOUT,
            allow_redirects=False,
            stream=True,
        )
    except requests.exceptions.Timeout:
        raise AIProviderError("The AI provider timed out. Try again.")
    except requests.exceptions.RequestException:
        raise AIProviderError("The AI provider is unreachable. Check the base URL.")


def _provider_stream(profile, messages, tools=None):
    """Like _provider_chat but streamed. Yields ("delta", text) as text arrives,
    then one ("final", {"content", "tool_calls", "usage", "gen_s"}). ``usage`` is
    the provider's token counts when it reports them, else None; ``gen_s`` is
    the time from the first streamed token to the last. A provider that ignores
    ``stream`` and answers with plain JSON is handled too, as one big delta."""
    base = (profile.ai_base_url or "").rstrip("/")
    try:
        validate_outbound_url(base, label="AI base URL")
    except OutboundURLError as exc:
        raise AIProviderError(str(exc))
    want_usage = base not in _NO_STREAM_OPTIONS
    resp = _stream_post(profile, base, messages, tools, want_usage)
    if want_usage and resp.status_code in (400, 422):
        # Strict OpenAI-compatible servers refuse fields they don't know. Ask
        # again without it rather than failing the turn over a statistic.
        resp.close()
        _NO_STREAM_OPTIONS.add(base)
        resp = _stream_post(profile, base, messages, tools, False)
    try:
        _raise_for_status(resp)
        if "text/event-stream" not in (resp.headers.get("Content-Type") or ""):
            started = time.monotonic()
            try:
                data = json.loads(resp.content.decode("utf-8", errors="replace"))
                msg = (data.get("choices") or [{}])[0].get("message") or {}
            except (ValueError, AttributeError):
                raise AIProviderError("The AI provider returned a non-JSON response.")
            if msg.get("content"):
                yield "delta", msg["content"]
            yield "final", {"content": msg.get("content") or "",
                            "tool_calls": msg.get("tool_calls") or None,
                            "usage": data.get("usage") or None,
                            "gen_s": time.monotonic() - started}
            return

        content = []
        calls = {}  # index -> {"id", "type", "function": {"name", "arguments"}}
        usage = None
        first = last = None
        try:
            # Raw bytes, decoded here as UTF-8. requests' own decode_unicode uses
            # the Content-Type charset, and an event stream usually names none,
            # so it fell back to Latin-1 and every emoji and dash came out as
            # "ð…" / "â…". A line is always whole, so decoding per line is safe.
            for raw_bytes in resp.iter_lines():
                if not raw_bytes:
                    continue
                raw = raw_bytes.decode("utf-8", errors="replace")
                if not raw.startswith("data:"):
                    continue
                payload = raw[5:].strip()
                if payload == "[DONE]":
                    break
                try:
                    chunk = json.loads(payload)
                except ValueError:
                    continue
                if not isinstance(chunk, dict):
                    continue
                if chunk.get("usage"):
                    usage = chunk["usage"]
                choices = chunk.get("choices") or []
                delta = (choices[0].get("delta") if choices else None) or {}
                if delta:
                    now = time.monotonic()
                    first = first or now
                    last = now
                text = delta.get("content")
                if text:
                    content.append(text)
                    yield "delta", text
                for tc in delta.get("tool_calls") or []:
                    slot = calls.setdefault(tc.get("index", len(calls)), {
                        "id": "", "type": "function",
                        "function": {"name": "", "arguments": ""}})
                    if tc.get("id"):
                        slot["id"] = tc["id"]
                    fn = tc.get("function") or {}
                    if fn.get("name"):
                        slot["function"]["name"] += fn["name"]
                    if fn.get("arguments"):
                        slot["function"]["arguments"] += fn["arguments"]
        except requests.exceptions.RequestException:
            raise AIProviderError("The connection to the AI provider dropped. Try again.")
        yield "final", {"content": "".join(content),
                        "tool_calls": [calls[i] for i in sorted(calls)] or None,
                        "usage": usage,
                        "gen_s": (last - first) if first else 0.0}
    finally:
        resp.close()


# The chat runs longer plans (several lookups, then a map or two) than the
# one-shot /api/ask/ needs.
_CHAT_MAX_ROUNDS = 8


def ask_events(user, history, profile, tz_offset=0, mode=None):
    """The Ask chat's tool loop as a stream of events (dicts):

    ``{"type": "status", "text"}``  – what it is doing now
    ``{"type": "delta", "text"}``   – a piece of the answer
    ``{"type": "map", "card"}``     – a map card to show
    ``{"type": "done", "text", "cards", "sources", "stats"}`` – finished; the
    whole answer, plus time and token counts (see :func:`_turn_stats`)

    ``history`` is user/assistant turns, newest last. Reads only: every tool
    here is a read-only query scoped to ``user``. Raises AIProviderError."""
    messages = [{"role": "system",
                 "content": _system_prompt(user, profile, tz_offset, maps=True, mode=mode)}]
    messages.extend(_sanitize_client_messages(history))
    if len(messages) == 1:
        raise AIProviderError("No question was provided.")

    tools = build_tool_schemas(profile, maps=True)
    journals_allowed = bool(getattr(profile, "ai_allow_journals", False))
    text, cards, used = [], [], []
    turn_started = time.monotonic()
    rounds = []  # per provider call: {"usage", "gen_s", "wall_s", "chars"}

    for round_no in range(_CHAT_MAX_ROUNDS + 1):
        last_round = round_no >= _CHAT_MAX_ROUNDS
        if last_round:
            messages.append({"role": "user",
                             "content": "Please answer now using what you've gathered so far."})
        started = time.monotonic()
        final = None
        pending_gap = bool(text)
        for kind, val in _provider_stream(profile, messages, None if last_round else tools):
            if kind == "delta":
                if pending_gap:
                    text.append("\n\n")
                    yield {"type": "delta", "text": "\n\n"}
                    pending_gap = False
                text.append(val)
                yield {"type": "delta", "text": val}
            else:
                final = val
        wall = time.monotonic() - started
        logger.info("AI provider stream took %d ms", wall * 1000)
        final = final or {}
        rounds.append({
            "usage": final.get("usage"), "gen_s": final.get("gen_s") or 0.0, "wall_s": wall,
            "chars": len(final.get("content") or "") + sum(
                len((c.get("function") or {}).get("arguments") or "")
                for c in final.get("tool_calls") or []),
        })
        tool_calls = final.get("tool_calls")
        if not tool_calls or last_round:
            break

        messages.append({"role": "assistant", "content": final.get("content") or "",
                         "tool_calls": tool_calls})
        for name in dict.fromkeys((c.get("function") or {}).get("name") for c in tool_calls):
            label = _TOOL_STATUS.get(name, "Looking that up…")
            used.append(label.rstrip("…"))
            yield {"type": "status", "text": label}
        results = _run_tool_calls(user, tool_calls, tz_offset, journals_allowed, maps=True)
        for call, result in zip(tool_calls, results):
            result, card = _lift_card(result)
            if card:
                cards.append(card)
                yield {"type": "map", "card": card}
            messages.append({"role": "tool", "tool_call_id": call.get("id"),
                             "content": json.dumps(result, default=str)})

    answer = "".join(text).strip() or "I couldn't find an answer to that."
    yield {"type": "done", "text": answer, "cards": cards,
           "sources": list(dict.fromkeys(used)),
           "stats": _turn_stats(rounds, time.monotonic() - turn_started)}


def _turn_stats(rounds, seconds):
    """Time and token figures for the small line under an answer.

    ``out_tokens`` is everything the model generated this turn, across every
    tool round (tool arguments are tokens it had to produce too). ``in_tokens``
    is the last round's prompt, i.e. how big the context had grown. When a
    provider reports no usage, output is estimated at ~4 characters a token and
    flagged ``estimated``. ``tok_s`` divides by streaming time only (first token
    to last), so it measures the model rather than the wait for a first token
    or the tools; a provider that didn't stream falls back to its whole call."""
    reported = [r for r in rounds if r["usage"]]
    if rounds and len(reported) == len(rounds):
        out = sum(int(r["usage"].get("completion_tokens") or 0) for r in rounds)
        in_tokens = int(rounds[-1]["usage"].get("prompt_tokens") or 0) or None
        estimated = False
    else:
        out = round(sum(r["chars"] for r in rounds) / 4)
        in_tokens = None
        estimated = True
    gen = sum(r["gen_s"] for r in rounds)
    if gen < 0.05:
        gen = sum(r["wall_s"] for r in rounds)
    return {
        "seconds": round(seconds, 2),
        "out_tokens": out,
        "in_tokens": in_tokens,
        "tok_s": round(out / gen, 1) if out and gen > 0 else None,
        "rounds": len(rounds),
        "estimated": estimated,
    }


_FOLLOWUP_PROMPT = (
    "You write the follow-up chips for a travel-history chat. Given the last "
    "exchange, reply with JSON only: {\"title\": <3-6 word chat title>, "
    "\"followups\": [<3 short questions the USER might ask next, under 60 "
    "characters each, in the user's voice>]}. Followups must be things the "
    "assistant could answer: their own travel history, flights, trip ideas, "
    "maps. Never suggest editing or deleting anything."
)


def followups(profile, question, answer):
    """(title, [follow-up questions]) for the chat page. Best effort: any
    failure returns ("", []) and the page just shows none."""
    msgs = [{"role": "system", "content": _FOLLOWUP_PROMPT},
            {"role": "user", "content": json.dumps({"question": question[:1500],
                                                    "answer": answer[:3000]})}]
    try:
        data = _provider_chat(profile, msgs)
    except AIProviderError:
        return "", []
    raw = (data["choices"][0].get("message") or {}).get("content") or ""
    start, end = raw.find("{"), raw.rfind("}")
    try:
        obj = json.loads(raw[start:end + 1]) if start >= 0 else {}
    except ValueError:
        return "", []
    title = str(obj.get("title") or "").strip().strip('"')[:80]
    items = [str(q).strip()[:90] for q in (obj.get("followups") or []) if str(q).strip()]
    return title, items[:3]


def test_connection(profile):
    """A cheap no-tool probe used by Settings → Test connection. Returns (ok, error)."""
    try:
        _provider_chat(profile, [
            {"role": "user", "content": "Reply with the single word: ok"},
        ])
        return True, None
    except AIProviderError as e:
        return False, str(e)
