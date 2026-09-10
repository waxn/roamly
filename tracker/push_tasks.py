"""Firebase Cloud Messaging — push delivery for Family Circle place alerts.

Deliberately built on `google-auth` rather than `firebase-admin`: the actual
need is "sign a service-account JWT, exchange it for a bearer token, POST a
message" — the FCM HTTP v1 API — not a whole-platform SDK pulling in
Firestore/Storage/Auth client libraries this app never touches. `requests`
(already a dependency) does the POST; `google.auth.transport.requests.Request`
works directly with it for the token refresh.

Best-effort, no retry queue — same posture `alert_tasks.py` already uses for
email: a network/5xx failure is logged to the token's `last_error` and left
alone for the next natural send to retry. Only a genuinely dead registration
(404 / UNREGISTERED / INVALID_ARGUMENT) removes the token, since keeping a
provably-dead token around only wastes every future send.

Callers (tracker/family_tasks.py) are already inside their own background
thread by the time they reach `send_push_to_user`, so everything here runs
synchronously — no second thread-spawn.
"""

import json
import logging

import requests
from django.core.cache import cache
from django.utils import timezone

from .models import FamilyPushToken, SiteConfig

logger = logging.getLogger(__name__)

_FCM_SCOPE = 'https://www.googleapis.com/auth/firebase.messaging'
_FCM_SEND_URL = 'https://fcm.googleapis.com/v1/projects/{project_id}/messages:send'

# Tokens are valid ~1h; cache a little under that so a send is never handed
# one that expired seconds ago.
_TOKEN_CACHE_KEY = 'fcm_access_token'
_TOKEN_CACHE_TTL = 55 * 60

# A dead-registration status means "this exact token is gone", not "the
# instance is misconfigured" — worth pruning. Anything else (network error,
# a 5xx from Google, invalid instance config) is left alone for the next send.
_DEAD_TOKEN_STATUSES = {'UNREGISTERED', 'INVALID_ARGUMENT'}


def bust_token_cache():
    """Called after Admin Panel saves new FCM credentials, so a stale token
    signed under the old key can't linger for up to an hour."""
    cache.delete(_TOKEN_CACHE_KEY)


def _get_fcm_access_token():
    """A bearer token for the FCM HTTP v1 API, or None if unconfigured/broken."""
    cached = cache.get(_TOKEN_CACHE_KEY)
    if cached:
        return cached

    config = SiteConfig.load()
    if not config.fcm_service_account_json:
        return None

    try:
        from google.auth.transport.requests import Request as GoogleAuthRequest
        from google.oauth2 import service_account

        info = json.loads(config.fcm_service_account_json)
        creds = service_account.Credentials.from_service_account_info(
            info, scopes=[_FCM_SCOPE],
        )
        creds.refresh(GoogleAuthRequest())
    except Exception:
        logger.exception("Failed to mint an FCM access token")
        return None

    cache.set(_TOKEN_CACHE_KEY, creds.token, _TOKEN_CACHE_TTL)
    return creds.token


def send_push_to_user(user_id, title, body, data=None):
    """Send to every FCM token registered for this user. Best-effort per
    token, so one bad registration can't block the user's other devices."""
    config = SiteConfig.load()
    if not config.fcm_project_id or not config.fcm_service_account_json:
        return

    access_token = _get_fcm_access_token()
    if not access_token:
        return

    url = _FCM_SEND_URL.format(project_id=config.fcm_project_id)
    headers = {
        'Authorization': f'Bearer {access_token}',
        'Content-Type': 'application/json',
    }
    for push_token in FamilyPushToken.objects.filter(user_id=user_id):
        _send_one(url, headers, push_token, title, body, data or {})


def _send_one(url, headers, push_token, title, body, data):
    # Data-only payload — no top-level "notification" block. That's what
    # guarantees onMessageReceived() runs app-side regardless of
    # foreground/background/killed state; a mixed payload lets the OS tray
    # intercept it directly while backgrounded, bypassing app code and
    # producing inconsistent deep-linking.
    payload = {
        'message': {
            'token': push_token.token,
            'data': {'title': title, 'body': body,
                      **{k: str(v) for k, v in data.items()}},
            'android': {'priority': 'high'},
        }
    }
    try:
        resp = requests.post(url, headers=headers, json=payload, timeout=10)
    except requests.RequestException as exc:
        push_token.last_error = str(exc)[:200]
        push_token.save(update_fields=['last_error'])
        return

    if resp.status_code == 200:
        push_token.last_used_at = timezone.now()
        push_token.last_error = ''
        push_token.save(update_fields=['last_used_at', 'last_error'])
        return

    status = ''
    try:
        status = (resp.json().get('error') or {}).get('status', '')
    except ValueError:
        pass

    if resp.status_code == 404 or status in _DEAD_TOKEN_STATUSES:
        logger.info("Deleting dead FCM token for user %s (%s)",
                    push_token.user_id, status or resp.status_code)
        push_token.delete()
        return

    logger.warning("FCM send failed (%s) for user %s", resp.status_code, push_token.user_id)
    push_token.last_error = f"{resp.status_code}: {status or resp.text[:150]}"[:200]
    push_token.save(update_fields=['last_error'])
