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

import requests

logger = logging.getLogger(__name__)

# Cap the tool-call loop so a confused model can't ping-pong forever.
_MAX_TOOL_ITERATIONS = 5
# Keep at most this many of the most recent client turns (bounds token use).
_MAX_CLIENT_TURNS = 20
_REQUEST_TIMEOUT = 45


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


def build_tool_schemas(profile):
    """Tools offered to the model — journal tools only when the user opted in."""
    if getattr(profile, "ai_allow_journals", False):
        return TOOL_SCHEMAS + JOURNAL_TOOL_SCHEMAS
    return TOOL_SCHEMAS


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


def _tool_list_visited_places(user, level="city", **_):
    from . import views
    from .models import Location
    level = (level or "city").lower()
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
    locations = Location.objects.filter(device__user=user)

    def _parse(d, end=False):
        try:
            dt = datetime.datetime.fromisoformat(str(d))
        except (ValueError, TypeError):
            return None
        if end:
            dt = dt.replace(hour=23, minute=59, second=59)
        if timezone.is_naive(dt):
            dt = timezone.make_aware(dt)
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


TOOL_DISPATCH = {
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
    url = f"{base}/chat/completions"
    body = {"model": profile.ai_model, "messages": messages}
    if tools:
        body["tools"] = tools
        body["tool_choice"] = "auto"
    try:
        resp = requests.post(
            url,
            headers={
                "Authorization": f"Bearer {profile.ai_api_key}",
                "Content-Type": "application/json",
            },
            json=body,
            timeout=_REQUEST_TIMEOUT,
        )
    except requests.exceptions.Timeout:
        raise AIProviderError("The AI provider timed out. Try again.")
    except requests.exceptions.RequestException:
        raise AIProviderError("The AI provider is unreachable. Check the base URL.")

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

    try:
        data = resp.json()
    except ValueError:
        raise AIProviderError("The AI provider returned a non-JSON response.")
    if not data.get("choices"):
        raise AIProviderError("The AI provider returned an unexpected response.")
    return data


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


def _system_prompt(user, profile, tz_offset=0):
    if profile.ai_system_prompt and profile.ai_system_prompt.strip():
        return profile.ai_system_prompt.strip() + "\n\n" + _DATE_LINK_DIRECTIVE
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
        "to name specific cities and stops. If a tool returns no results, say so "
        "plainly. Be concise and conversational, and cite concrete dates from the "
        "tool results. Dwell/time values from tools are in seconds unless noted as "
        f"hours.{journals}\n\n" + _DATE_LINK_DIRECTIVE
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
        data = _provider_chat(profile, messages, tools=tools)
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
        for call in tool_calls:
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
                result = {"error": "Journal access is not enabled for this account."}
            elif handler is None:
                result = {"error": f"unknown tool '{name}'"}
            else:
                try:
                    # tz_offset is passed to every handler; those that don't need
                    # it absorb it via **_ (only day/date tools use it).
                    result = handler(user, tz_offset=tz_offset, **args)
                except Exception:
                    logger.exception("AI tool %s failed", name)
                    result = {"error": "tool failed to run"}
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


def test_connection(profile):
    """A cheap no-tool probe used by Settings → Test connection. Returns (ok, error)."""
    try:
        _provider_chat(profile, [
            {"role": "user", "content": "Reply with the single word: ok"},
        ])
        return True, None
    except AIProviderError as e:
        return False, str(e)
