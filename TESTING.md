# Testing Roamly

There are currently **no tests** — `find . -name "test*.py"` matches only two
unrelated migrations, and `mobile/` has none either. Meanwhile
`.github/workflows/docker-publish.yml` builds and pushes to Docker Hub on every
push to `main` with no gate whatsoever.

This document is the plan for changing that. It is deliberately ordered by value
rather than by coverage: the point is to protect the things whose failure is
*silent*, not to reach a percentage.

---

## Why this matters here specifically

Roamly's worst failure mode is not a crash. It is **losing or corrupting a
location history quietly** — a restore that reports success with fewer points, an
aggregate that drifts, an endpoint that returns another user's track. None of
those raise an exception, so none of them show up in the error log; they are only
visible if something asserts on the result.

Two real examples from this codebase:

* `trip_add_member` inserted a membership from a bare username with no consent
  step, and the adventure payload then returned that member's own GPS track — a
  complete cross-tenant disclosure that no test would have let through.
* `trip_visits_api` held a copy of `_compute_visits_from_qs` that had drifted:
  its `states` entries silently lacked the `time_spent` key the identical field
  on the Visits page had.

---

## Setup

`requirements-dev.txt`:

```
pytest>=8.0
pytest-django>=4.8
factory-boy>=3.3
```

`pyproject.toml`:

```toml
[tool.pytest.ini_options]
DJANGO_SETTINGS_MODULE = "roamly.settings"
python_files = ["test_*.py"]
testpaths = ["tracker/tests"]
# --reuse-db is the difference between a 2-second run and a 40-second one.
addopts = "--reuse-db"
```

Everything runs in Docker (see CLAUDE.md — never run `manage.py` on the host):

```bash
sudo docker compose exec web pytest
```

**GeoDjango note.** Tests need a PostGIS-enabled template database. The
`postgis/postgis` image ships `template_postgis`; if `pytest` fails at database
creation with a missing-extension error, add to `settings.py`:

```python
DATABASES['default']['TEST'] = {'TEMPLATE': 'template_postgis'}
```

`HAS_POSTGIS` is a runtime check, so a test that asserts on spatial behaviour must
skip when it is false rather than fail.

---

## Suite 1 — Permissions matrix (build this first)

**Highest value by a wide margin.** It is one test, it is driven off the URLconf
so new endpoints are covered without anyone remembering to add them, and it
catches the entire class of bug that has actually occurred here.

```python
# tracker/tests/test_permissions.py
import pytest
from django.urls import get_resolver

# Endpoints that are public BY DESIGN. Anything not listed must reject an
# anonymous caller. Keep this list short and justify each entry — it is the
# allow-list a reviewer reads to check nothing leaked into it.
PUBLIC = {
    'landing', 'login', 'signup', 'docs', 'privacy', 'terms',
    'robots_txt', 'healthz', 'offline', 'service_worker',
    'adventure_public', 'trip_public_detail', 'trip_verify_pin',
    'contact_api', 'email_unsubscribe', 'mobile_version_check',
    'mobile_download_apk', 'password_reset_request', 'password_reset_confirm',
    'verify', 'verify_resend', 'totp_verify', 'trip_join',
}

def test_every_endpoint_rejects_anonymous(client):
    """Anonymous callers get 302/403/404 from everything not explicitly public."""
    ...

def test_every_endpoint_rejects_another_user(client, user_a, user_b, fixtures_of_a):
    """user_b must not read or mutate anything belonging to user_a."""
    ...
```

The second test is the one that matters. Build a fixture account with a device,
locations, an adventure, a journal entry, a place and an activity; then sign in as
a *different* user and walk every id-taking URL, asserting 403 or 404. Assert on
the **response body** too, not just the status: the adventure disclosure returned
200 with the victim's coordinates in it.

---

## Suite 2 — Backup round-trip

One test that guards several invariants at once:

```python
def test_backup_round_trip(user_with_everything, fresh_db):
    """Export -> restore into a clean database -> assert equality."""
```

Build an account with devices, locations, adventures carrying **body blocks with
`[^pin:ID]` refs**, journals with photos, health samples, workouts and activities.
Export via `_write_backup_json`, restore via `restore_backup`, and compare.

This single test covers:

* the `meta.version` contract (a bump that forgets `restore_backup` fails here);
* `_remap_story_body_ids` — restoring into a fresh database gives every blurb a
  new id, so a `body` copied verbatim would orphan every pin footnote;
* the `counts` integrity check added alongside version 14;
* the consent rule that a restore may only carry `accepted_at`/`share_track` for
  the restoring user's own membership.

Add a second, cheaper test that restores a **v9 backup fixture** to prove old
files still load — `restore_backup` claims to accept every version, and nothing
checks it.

---

## Suite 3 — Auth flows

* login by username and by email, including the single-active-match rule;
* the ordering: TOTP is checked before the unverified-email branch, which is
  before the new-device branch;
* rate limits return 429, and — importantly — that a rotated `X-Forwarded-For`
  does **not** reset the counter (this was a real bypass);
* `_safe_next` rejects an off-site redirect;
* the destructive-delete gate: wrong password, wrong phrase and missing TOTP each
  refuse, and the data survives.

---

## Suite 4 — Pure helpers

Fast, no database, and they encode rules that are easy to break unknowingly:

| Function | What to pin down |
|---|---|
| `_gated_distance_segments` | the `initial_state='MOVING'` escape hatch; that a suspect point earns no credit |
| `_detect_dwells` | a stay produces one dwell; the radius is the 90th percentile, not the max |
| `dwell_utils.bridges_gap` | the `min_gap_s` floor — without it a slow walk chains into one long false "stay" |
| `_visits_dwell_gap` | the 1h cross-label cap, and that a bridged gap lifts it |
| `tz_utils.aware_local` | **the DST cases.** Santiago, Havana, Beirut, Gaza and Hebron all shift at 00:00, so a day boundary can be imaginary or ambiguous. Fold semantics are opposite in the two cases — this is the subtlest code in the app |
| `_is_spike` / `_is_jitter` | a motorway drive is not a spike; a stationary phone reporting 40mph Doppler is jitter |
| `niceAxis` | 21,000 gives a tick *step* of 5,000, not a ceiling of 25,000 quartered |

---

## CI

```yaml
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgis/postgis:16-3.4
        env: {POSTGRES_DB: roamly, POSTGRES_USER: roamly, POSTGRES_PASSWORD: roamly}
        options: >-
          --health-cmd pg_isready --health-interval 10s
          --health-timeout 5s --health-retries 5
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: {python-version: '3.13'}
      - run: sudo apt-get update && sudo apt-get install -y binutils libproj-dev gdal-bin
      - run: pip install -r requirements.txt -r requirements-dev.txt
      - run: pytest
```

Then gate the publish:

```yaml
  build-and-push:
    needs: test
```

---

## Mobile

`mobile/` has no tests either. The seams worth covering first, in order:

* **`LocationFilter`** — `accept()` is a pure check and `commit()` advances the
  state. Conflating them once made capture go completely silent whenever accuracy
  degraded, which is the worst possible bug in a tracker.
* **`DriftAnchor`** — each release threshold is load-bearing and tuned against
  real-world values (walking pace vs `MOVE_SPEED_MPS`, the corroborated hard
  break). Table-driven tests over synthetic fix sequences.
* **`UpdateRepository`**'s semver comparison — pure, trivial to test, and it
  decides whether an update is offered at all.

None of these need Android instrumentation; they are plain JVM unit tests.
