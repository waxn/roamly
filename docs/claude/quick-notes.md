# Quick notes ("what I did today")

_Read when: Quick notes on the map, the notes API, the evening reminder on Android._

**Stored on the day's `JournalEntry` (`quick_note`, migration `0117`).** There is no separate model: a quick note is a field of that day's one journal entry, so Journals shows it on the day (detail, list, search, Ask, the recap all read it). **The reverse is deliberately not true:** the notes endpoints and the map only ever return `quick_note`, never the journal's title or body. That one-way rule is what the user asked for ("notes display in the journal but journal posts don't display in notes"); `test_list_never_includes_journal_body` pins it.

**API** (`views.quick_notes_list_api` / `views.quick_note_api`):
- `GET /api/notes/?start=YYYY-MM-DD&end=YYYY-MM-DD` — days with a non-empty note, newest first, both bounds optional, capped at 400.
- `GET /api/notes/<date>/` — `{date, note, updated_at}`; a day with nothing returns an empty note, not a 404.
- `POST /api/notes/<date>/` `{"note": "..."}` — upsert (trimmed, ≤2000 chars). `@csrf_exempt` + `_require_api_intent` like the other app-called journal endpoints. **An empty note clears it, and deletes the entry when the note was all it held** (`_journal_entry_is_blank`: no title/body/mood/favorite/location/pin/photos; auto-filled weather does not count), so jotting and un-jotting leaves no empty day in Journals.
- `journal_save_api` also accepts `quick_note`, so the Journals editor can change it.

The date is always the **client's** local calendar day; the server does no timezone conversion for notes.

**Backup:** `_build_journals_data` exports `quick_note`, restore reads it; `meta.version` 26.
