import time
import threading
import logging

import requests as http_requests

logger = logging.getLogger(__name__)

_running_threads = {}
_progress = {}          # user_id -> {status, processed, total, errors, current_date}
_last_auto_trigger = {}
_AUTO_DEBOUNCE_S = 600  # 10 min between auto triggers


# ── Prompt & LLM call ────────────────────────────────────────────────────────

def _build_prompt(user, date):
    """Return the prompt string and the track dict for a given date."""
    from .models import JournalEntry, Visit
    from .views import _journal_day_track

    track = _journal_day_track(user, date)
    if track['point_count'] == 0:
        return None, track

    entry = JournalEntry.objects.filter(user=user, date=date).first()

    # Use pre-computed Visit dwell events (actual time at a place) rather than
    # the raw bounding-box POI scan that included every POI in the area.
    visits = list(
        Visit.objects.filter(
            device__user=user,
            start_time__date=date,
            poi__isnull=False,
        ).select_related('poi').order_by('start_time')
    )
    poi_parts = []
    for v in visits[:12]:
        dur_m = max(1, int((v.end_time - v.start_time).total_seconds() / 60))
        cat = v.poi.category or 'place'
        poi_parts.append(f"{v.poi.name} ({cat}, ~{dur_m} min)")
    poi_str = ', '.join(poi_parts) if poi_parts else 'none recorded'

    places_str = ', '.join(track['cities']) if track['cities'] else 'unknown'
    journal_title = (entry.title if entry else '') or ''
    journal_mood  = (entry.mood  if entry else '') or ''
    journal_body  = (entry.body  if entry else '') or ''

    prompt = (
        "You are a travel journal assistant. Write a short, vivid, first-person paragraph "
        "(3–5 sentences) summarising this person's day based on the data below. "
        "Do not invent details beyond what is provided. "
        "If location data is sparse, focus on what is known.\n\n"
        f"Date: {date.strftime('%A, %B %-d, %Y')}\n"
        f"Distance travelled: {track['distance_km']} km\n"
        f"Places visited (in order): {places_str}\n"
        f"Places dwelled at: {poi_str}\n"
        f"Journal title: {journal_title or '(none)'}\n"
        f"Mood: {journal_mood or '(none)'}\n"
        f"Journal entry: {journal_body[:2000] or '(none)'}"
    )
    return prompt, track


def _call_llm(ai_cfg, prompt):
    """POST to the configured LLM endpoint. Returns summary text or raises."""
    api_url = ai_cfg.api_url.rstrip('/')
    headers = {'Content-Type': 'application/json'}
    if ai_cfg.api_key:
        headers['Authorization'] = f'Bearer {ai_cfg.api_key}'

    payload = {
        'model': ai_cfg.model_name,
        'messages': [{'role': 'user', 'content': prompt}],
        'max_tokens': 400,
        'temperature': 0.7,
    }

    # When running in Docker, `localhost` resolves to the container, not the
    # host. Try host.docker.internal as a fallback so LMStudio / local Ollama
    # works without the user having to remember Docker networking details.
    urls_to_try = [api_url]
    for old in ('localhost', '127.0.0.1'):
        if old in api_url:
            urls_to_try.append(api_url.replace(old, 'host.docker.internal', 1))
            break

    last_exc = None
    for url in urls_to_try:
        try:
            logger.info(f"Trying LLM at {url}/chat/completions")
            resp = http_requests.post(
                f'{url}/chat/completions',
                headers=headers,
                json=payload,
                timeout=(10, 120),  # 10s connect, 120s read
            )
            resp.raise_for_status()
            return resp.json()['choices'][0]['message']['content'].strip()
        except Exception as exc:
            logger.warning(f"LLM request to {url} failed: {type(exc).__name__}: {exc}")
            last_exc = exc

    raise last_exc


def test_connection(ai_cfg):
    """Test connectivity to the configured LLM endpoint.

    Returns a dict with keys: ok (bool), tried (list), error (str or None),
    models (list or None).
    """
    api_url = ai_cfg.api_url.rstrip('/')
    headers = {}
    if ai_cfg.api_key:
        headers['Authorization'] = f'Bearer {ai_cfg.api_key}'

    urls_to_try = [api_url]
    for old in ('localhost', '127.0.0.1'):
        if old in api_url:
            urls_to_try.append(api_url.replace(old, 'host.docker.internal', 1))
            break

    tried = []
    for url in urls_to_try:
        result = {'url': f'{url}/v1/models', 'status': None, 'error': None}
        try:
            resp = http_requests.get(
                f'{url}/models',
                headers=headers,
                timeout=(5, 15),
            )
            result['status'] = resp.status_code
            if resp.ok:
                body = resp.json()
                result['models'] = [m.get('id', str(m)) for m in body.get('data', [])]
                tried.append(result)
                return {'ok': True, 'tried': tried, 'error': None, 'models': result['models']}
            else:
                result['error'] = f'HTTP {resp.status_code}: {resp.text[:200]}'
        except http_requests.exceptions.ConnectionError as exc:
            result['error'] = f'Connection refused / unreachable: {exc}'
        except http_requests.exceptions.Timeout:
            result['error'] = 'Connection timed out (5s)'
        except Exception as exc:
            result['error'] = f'{type(exc).__name__}: {exc}'
        tried.append(result)

    return {'ok': False, 'tried': tried, 'error': tried[-1]['error'] if tried else 'No URL to try', 'models': None}


def generate_one(user, date):
    """Generate and save a summary for one date. Returns (summary_obj, error_str)."""
    from .models import AIConfig, AISummary

    try:
        ai_cfg = AIConfig.objects.get(user=user, enabled=True)
    except AIConfig.DoesNotExist:
        return None, 'AI not configured or disabled'

    prompt, track = _build_prompt(user, date)
    if prompt is None:
        return None, 'No location data for this day'

    try:
        summary_text = _call_llm(ai_cfg, prompt)
    except http_requests.exceptions.Timeout:
        return None, 'AI request timed out — is the server running?'
    except http_requests.exceptions.ConnectionError as exc:
        return None, (
            f'Could not connect to AI server at {ai_cfg.api_url}. '
            'If running in Docker, try using host.docker.internal instead of localhost '
            f'(e.g. http://host.docker.internal:1234/v1). Details: {exc}'
        )
    except Exception as exc:
        return None, f'AI request failed: {exc}'

    obj, _ = AISummary.objects.update_or_create(
        user=user,
        date=date,
        defaults={
            'summary': summary_text,
            'places_json': track['places'],
            'distance_km': track['distance_km'],
            'model_used': ai_cfg.model_name,
        },
    )
    return obj, None


# ── Background bulk worker ────────────────────────────────────────────────────

def _summary_worker(user_id):
    from django.contrib.auth import get_user_model
    from .models import Location, AISummary

    User = get_user_model()
    try:
        user = User.objects.get(pk=user_id)
    except User.DoesNotExist:
        return

    prog = _progress.setdefault(user_id, {})
    prog.update(status='running', processed=0, errors=0, total=0, current_date='')

    try:
        # Collect distinct dates that have location data but no summary.
        done_dates = set(
            AISummary.objects.filter(user=user).values_list('date', flat=True)
        )
        all_dates = list(
            Location.objects.filter(device__user=user)
            .exclude(city='')
            .dates('timestamp', 'day', order='DESC')
        )
        pending = [d for d in all_dates if d not in done_dates]
        prog['total'] = len(pending)

        for date in pending:
            if prog.get('status') != 'running':
                break
            prog['current_date'] = date.isoformat()
            _, err = generate_one(user, date)
            prog['processed'] += 1
            if err:
                prog['errors'] += 1
                logger.warning(f'Summary generation failed for {user_id} {date}: {err}')
            time.sleep(1)  # avoid hammering a local LLM

    finally:
        prog['status'] = 'completed' if prog.get('status') == 'running' else prog.get('status', 'stopped')
        prog['current_date'] = ''
        _running_threads.pop(user_id, None)
        logger.info(f'Summary worker done for user {user_id}: {prog}')


def start_summary_generation(user_id):
    if _is_thread_alive(user_id):
        return _progress.get(user_id, {})
    _progress[user_id] = {'status': 'running', 'processed': 0, 'errors': 0, 'total': 0, 'current_date': ''}
    t = threading.Thread(target=_summary_worker, args=(user_id,), daemon=True)
    _running_threads[user_id] = t
    t.start()
    return _progress[user_id]


def stop_summary_generation(user_id):
    if user_id in _progress:
        _progress[user_id]['status'] = 'stopped'


def get_summary_status(user_id):
    if not _is_thread_alive(user_id) and _progress.get(user_id, {}).get('status') == 'running':
        _progress[user_id]['status'] = 'completed'
    return _progress.get(user_id, {'status': 'idle'})


def ensure_auto_summary(user_id):
    """Auto-generate summaries for recent days after geocoding. Debounced."""
    from django.utils import timezone
    from django.contrib.auth import get_user_model
    from .models import Location, AISummary, AIConfig

    now = time.monotonic()
    if now - _last_auto_trigger.get(user_id, 0.0) < _AUTO_DEBOUNCE_S:
        return
    if _is_thread_alive(user_id):
        return

    try:
        AIConfig.objects.get(user_id=user_id, enabled=True)
    except AIConfig.DoesNotExist:
        return

    _last_auto_trigger[user_id] = now

    def _auto_worker():
        User = get_user_model()
        try:
            user = User.objects.get(pk=user_id)
        except User.DoesNotExist:
            return
        # Only generate for the last 7 days to keep auto-runs quick.
        cutoff = timezone.now().date()
        from datetime import timedelta
        dates = list(
            Location.objects.filter(device__user=user)
            .exclude(city='')
            .filter(timestamp__date__gte=cutoff - timedelta(days=7))
            .dates('timestamp', 'day', order='DESC')
        )
        done = set(AISummary.objects.filter(user=user, date__in=dates).values_list('date', flat=True))
        for d in dates:
            if d not in done:
                generate_one(user, d)
                time.sleep(1)

    t = threading.Thread(target=_auto_worker, daemon=True)
    _running_threads[user_id] = t
    t.start()


def _is_thread_alive(user_id):
    t = _running_threads.get(user_id)
    return t is not None and t.is_alive()
