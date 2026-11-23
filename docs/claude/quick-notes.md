# Quick notes ("what I did today")

_Read when: Quick notes on the map, the notes API, the evening reminder on Android._

**Stored on the day's `JournalEntry` (`quick_note`, migration `0117`).** There is no separate model: a quick note is a field of that day's one journal entry, so Journals shows it on the day (detail, list, search, Ask, the recap all read it). **The reverse is deliberately not true:** the notes endpoints and the map only ever return `quick_note`, never the journal's title or body. That one-way rule is what the user asked for ("notes display in the journal but journal posts don't display in notes"); `test_list_never_includes_journal_body` pins it.

**API** (`views.quick_notes_list_api` / `views.quick_note_api`):
- `GET /api/notes/?start=YYYY-MM-DD&end=YYYY-MM-DD` — days with a non-empty note, newest first, both bounds optional, capped at 400.
- `GET /api/notes/<date>/` — `{date, note, updated_at}`; a day with nothing returns an empty note, not a 404.
- `POST /api/notes/<date>/` `{"note": "..."}` — upsert (trimmed, ≤2000 chars). `@csrf_exempt` + `_require_api_intent` like the other app-called journal endpoints. **An empty note clears it, and deletes the entry when the note was all it held** (`_journal_entry_is_blank`: no title/body/mood/favorite/location/pin/photos; auto-filled weather does not count), so jotting and un-jotting leaves no empty day in Journals.
- `journal_save_api` also accepts `quick_note`, so the Journals editor can change it.

The date is always the **client's** local calendar day; the server does no timezone conversion for notes.

**Web map (`map.html`).** A pill in the **top-centre column** (`.map-top-center`, which also holds the load-more prompt so the two stack instead of overlapping; top-right at ≤900px, shrunk to "today" at ≤600px) reads "what did you do today?" or "today · <first line>", plus `n more` for other days with a note in the range. It opens a panel with a composer (save button, ⌘/Ctrl+Enter; emptying an existing note turns the button into **remove**) and the notes for the days the map's range covers, fetched via `ROAMLY_MAP_HOOKS.onRangeChanged` / `currentRangeDays()` (see map.md Range stepping), so the ‹ › arrows walk notes along with the track. Each item can **show day** (exact single-day range, which the arrows then step a day at a time) or **edit** (loads it into the composer with "back to today"); clicking the text unclamps it. All time lists the latest notes (60 shown, the rest are in Journals). An unsaved draft survives a reload in `roamly_note_draft` (`{date, text}`). Past midnight, reopening the panel moves "today" on. The pill and panel are in every Appearance component's map-surface `:is(...)` list, and the themes that float the nav (Pebble, Jelly, Tidepool) offset `.map-top-center` the same way as the sidebar.

**Journals (`journals.html`).** The editor shows the note as its own field (**quick note · shows on the map**, an auto-growing line between weather and the body), saved with the rest of the entry through `journal_save_api`. Entry cards show it as a `✎` line above the body snippet (the snippet is skipped when it is the same text, which the search API produces for a note-only day).

**Backup:** `_build_journals_data` exports `quick_note`, restore reads it; `meta.version` 26.
