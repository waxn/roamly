import csv
import gzip
import io
import json
import secrets
import logging
import math
import os
import tempfile
import threading
import time
import uuid
import urllib.parse
import urllib.request
from collections import defaultdict, Counter
from datetime import datetime, date, time as dt_time, timedelta, timezone as dt_timezone
from itertools import groupby

from django.conf import settings
from django.contrib import messages
from django.contrib.auth import authenticate, login, logout
from django.contrib.auth.decorators import login_required
from django.db.models import Count, Min, Max, Avg, Q, Case, When, Value, F, IntegerField, FloatField
from django.db.models.functions import Coalesce, Cast, Round
from django.http import FileResponse, JsonResponse, HttpResponse, StreamingHttpResponse
from django.shortcuts import render, redirect, get_object_or_404
from django.utils import timezone
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods, require_POST

from django.core.cache import cache
from django.core.serializers.json import DjangoJSONEncoder
from django.db import connection, transaction
from django.contrib.staticfiles import finders

from .forms import SignUpForm, APIKeyForm, AdventureForm
from .models import (
    Device, Location, APIKey, Adventure, POI, BackupConfig,
    UserProfile, Pal, PalMember, PalBlurb, PalBlurbPhoto, PalMilestone, PalComment,
    AdventureMember, AdventureBlurb, AdventureBlurbPhoto, AdventureMilestone, AdventureComment,
    SiteStat, JournalEntry, JournalPhoto, Visit, CustomPlace, SiteConfig, StatsSnapshot,
    DismissedSuggestion, PlannedStop, KnownDevice, TOTPBackupCode,
    InferredLocation, EditBatch, TrashedLocation, RoadSegment, ROADS_AVAILABLE_CACHE_KEY,
)
from .email_utils import email_enabled, gen_code, send_code_email, send_invite_email, send_password_reset_email, send_contact_email
from .image_utils import resize_image, resize_photo
from . import geoip_utils
from .geocoding_tasks import start_geocoding, get_status as get_geocoding_status, stop_geocoding, ensure_auto_geocode
from .poi_tasks import start_poi_download, get_poi_status, stop_poi_download
from .backup_tasks import (
    test_s3_connection, run_backup_now, get_backup_status, stop_backup_now,
    run_image_backup_now, get_image_backup_status, _build_pals_data,
    _build_adventures_data, _build_journals_data, _build_custom_places_data,
)

logger = logging.getLogger(__name__)


def _bust_user_cache(user_id):
    """Increment the per-user cache generation so all cached API responses are invalidated."""
    key = f"cache_gen:{user_id}"
    val = (cache.get(key) or 0) + 1
    cache.set(key, val, timeout=86400 * 30)


def _jf(value):
    """Return None if value is NaN/Infinity — JSON can't represent those floats."""
    if isinstance(value, float) and (math.isnan(value) or math.isinf(value)):
        return None
    return value


# Check for PostGIS
try:
    from django.contrib.gis.geos import Polygon, Point
    HAS_POSTGIS = True
except Exception:
    HAS_POSTGIS = False
    Polygon = None
    Point = None


# ---------------------------------------------------------------------------
# PWA Service Worker
# ---------------------------------------------------------------------------

def service_worker(request):
    sw_path = finders.find('tracker/sw.js')
    with open(sw_path) as f:
        response = HttpResponse(f.read(), content_type='application/javascript')
    response['Service-Worker-Allowed'] = '/'
    response['Cache-Control'] = 'no-cache'
    return response


# ---------------------------------------------------------------------------
# Reverse Geocoding
# ---------------------------------------------------------------------------

def reverse_geocode(lat, lon):
    """Reverse geocode coordinates using OpenStreetMap Nominatim."""
    try:
        url = (
            f'https://nominatim.openstreetmap.org/reverse'
            f'?lat={lat}&lon={lon}&format=json&zoom=10&addressdetails=1'
        )
        headers = {
            'User-Agent': 'Roamly/0.8 (self-hosted location tracker)',
            'Accept-Language': 'en',
        }
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode())

        if 'error' in data:
            return None

        address = data.get('address', {})
        city = (
            address.get('city') or address.get('town') or
            address.get('village') or address.get('municipality') or
            address.get('county') or address.get('suburb') or ''
        )
        state = address.get('state') or address.get('province') or address.get('region') or ''
        country = address.get('country', '')
        country_code = address.get('country_code', '').upper()
        place_name = data.get('display_name', '')

        return {
            'city': city, 'state': state,
            'country': country, 'country_code': country_code,
            'place_name': place_name,
        }
    except Exception as e:
        logger.warning(f"Geocoding failed for {lat},{lon}: {e}")
        return None


# ---------------------------------------------------------------------------
# API Key Auth
# ---------------------------------------------------------------------------

def get_api_key_user(request):
    """Extract and validate API key from request."""
    auth_header = request.headers.get('Authorization', '')
    if auth_header.startswith('Bearer '):
        key = auth_header[7:]
    else:
        key = request.GET.get('api_key') or request.POST.get('api_key')
        if not key and request.body:
            try:
                data = json.loads(request.body.decode('utf-8'))
                key = data.get('api_key')
                request._cached_json_body = data
            except Exception:
                pass

    if not key:
        return None

    try:
        api_key = APIKey.objects.select_related('user').get(key=key, is_active=True)
        api_key.last_used = timezone.now()
        api_key.save(update_fields=['last_used'])
        return api_key.user
    except APIKey.DoesNotExist:
        return None


# ---------------------------------------------------------------------------
# Auth Views
# ---------------------------------------------------------------------------

def _refresh_site_stats():
    if not cache.add('site_stats_refresh_lock', '1', timeout=300):
        return
    try:
        total_points = Location.objects.count()
        total_cities = (
            Location.objects
            .exclude(city__isnull=True).exclude(city='')
            .values('city', 'country_code').distinct().count()
        )
        # PostGIS: per-device LineString from points ordered by timestamp,
        # length on geography returns metres (great-circle). Python sums devices.
        with connection.cursor() as cur:
            cur.execute("""
                SELECT ST_Length(ST_MakeLine(location::geometry ORDER BY timestamp)::geography)
                FROM tracker_location
                WHERE location IS NOT NULL
                GROUP BY device_id
                HAVING COUNT(*) > 1
            """)
            total_meters = int(sum(row[0] or 0 for row in cur.fetchall()))
        SiteStat.objects.update_or_create(
            pk=1,
            defaults={
                'total_points': total_points,
                'total_cities': total_cities,
                'total_meters': total_meters,
            },
        )
        cache.set('site_stats', {
            'site_total_points': total_points,
            'site_total_cities': total_cities,
            'site_total_meters': total_meters,
        }, 3600)
    finally:
        cache.delete('site_stats_refresh_lock')


def landing_view(request):
    """Landing page - show marketing page for non-authenticated users.

    The marketing copy is identical for everyone; only three hero stats vary
    (refreshed daily). We serve those from the cache so the hot path never
    touches the DB, and let anonymous responses be cached by the browser/CDN.
    """
    stats = cache.get('site_stats')
    if stats is None:
        stat, created = SiteStat.objects.get_or_create(pk=1)
        stale = created or (timezone.now() - stat.updated_at).total_seconds() > 86400
        if stale:
            threading.Thread(target=_refresh_site_stats, daemon=True).start()
        stats = {
            'site_total_points': stat.total_points,
            'site_total_cities': stat.total_cities,
            'site_total_meters': stat.total_meters,
        }
        # Short TTL so a freshly refreshed stat shows up within the hour while
        # still keeping the DB out of the request path for the common case.
        cache.set('site_stats', stats, 3600)
    response = render(request, 'tracker/landing.html', stats)
    if not request.user.is_authenticated:
        # Static marketing page — let browsers/proxies serve it instantly and
        # revalidate lazily. Vary on Cookie so logged-in variants never leak.
        response['Cache-Control'] = 'public, max-age=300, stale-while-revalidate=86400'
        response['Vary'] = 'Cookie, Accept-Encoding'
    return response


def docs_view(request):
    """Documentation page - tutorials, setup guides, and API reference."""
    return render(request, 'tracker/docs.html')


def terms_view(request):
    return render(request, 'tracker/terms.html')


def privacy_view(request):
    return render(request, 'tracker/privacy.html')


def robots_txt(request):
    site_url = request.build_absolute_uri('/').rstrip('/')
    lines = [
        'User-agent: *',
        'Disallow: /admin/',
        'Disallow: /api/',
        'Disallow: /map/',
        'Disallow: /data/',
        'Disallow: /stats/',
        'Disallow: /visits/',
        'Disallow: /search/',
        'Disallow: /trips/',
        'Disallow: /pals/',
        'Disallow: /settings/',
        'Disallow: /login/',
        'Disallow: /signup/',
        '',
        f'Sitemap: {site_url}/sitemap.xml',
    ]
    return HttpResponse('\n'.join(lines), content_type='text/plain')


# ── Email verification helpers (only active when settings.EMAIL_ENABLED) ─────
_CODE_TTL = 900  # 15 minutes
_DEVICE_COOKIE = 'roamly_device'
_DEVICE_COOKIE_AGE = 60 * 60 * 24 * 365 * 2  # 2 years


def _safe_next(request, default='tracker:map'):
    """Return a safe local redirect target from ?next=, else a default."""
    from django.utils.http import url_has_allowed_host_and_scheme
    nxt = request.POST.get('next') or request.GET.get('next') or ''
    if nxt and url_has_allowed_host_and_scheme(nxt, allowed_hosts={request.get_host()}, require_https=request.is_secure()):
        return nxt
    return default


def _hash_token(token):
    import hashlib
    return hashlib.sha256((token or '').encode()).hexdigest()


def _is_known_device(user, request):
    token = request.COOKIES.get(_DEVICE_COOKIE, '')
    if not token:
        return False
    return KnownDevice.objects.filter(user=user, token_hash=_hash_token(token)).exists()


def _trust_device(user, request, response):
    """Register this browser/app as a known device and set the cookie on `response`."""
    import secrets as _secrets
    token = request.COOKIES.get(_DEVICE_COOKIE, '') or _secrets.token_urlsafe(32)
    KnownDevice.objects.get_or_create(
        user=user, token_hash=_hash_token(token),
        defaults={'label': request.META.get('HTTP_USER_AGENT', '')[:200]},
    )
    response.set_cookie(_DEVICE_COOKIE, token, max_age=_DEVICE_COOKIE_AGE,
                        httponly=True, samesite='Lax', secure=not settings.DEBUG)
    return response


def _mask_email(email):
    if not email or '@' not in email:
        return email or ''
    name, dom = email.split('@', 1)
    keep = name[0] if name else ''
    return f"{keep}{'•' * max(2, len(name) - 1)}@{dom}"


def _start_verification(request, user, purpose, next_url=''):
    """Generate + email a code, stash pending state in the session."""
    code = gen_code()
    cache.set(f'email_code:{purpose}:{user.id}', code, _CODE_TTL)
    send_code_email(user.email, code, purpose)
    request.session['pending_verify'] = {
        'user_id': user.id, 'purpose': purpose, 'email': user.email, 'next': next_url,
    }


def _generate_totp_backup_codes(user, count=8):
    """Replace this user's backup codes with `count` fresh 10-digit ones.
    Numeric-only (rather than alphanumeric) so the same digit-only code field
    works for both a TOTP code and a backup code, on web and mobile alike.
    Returns the plaintext codes — shown to the user once, at generation time;
    only the hash is persisted."""
    TOTPBackupCode.objects.filter(user=user).delete()
    codes = []
    for _ in range(count):
        code = f'{secrets.randbelow(10 ** 10):010d}'
        codes.append(code)
        TOTPBackupCode.objects.create(user=user, code_hash=_hash_token(code))
    return codes


def _verify_totp_or_backup(profile, code):
    """Check a submitted login code against the TOTP secret, falling back to
    unused backup codes (consuming one on match). Setup/confirm/regenerate
    verify against the secret directly instead — this is only for the login
    challenge, where the user may have lost their authenticator app."""
    import pyotp
    code = (code or '').strip().replace(' ', '')
    if profile.totp_secret and code.isdigit() and len(code) == 6:
        if pyotp.TOTP(profile.totp_secret).verify(code, valid_window=1):
            return True
    if code:
        bc = TOTPBackupCode.objects.filter(
            user=profile.user, code_hash=_hash_token(code), used_at__isnull=True,
        ).first()
        if bc:
            bc.used_at = timezone.now()
            bc.save(update_fields=['used_at'])
            return True
    return False


def _totp_qr_data_uri(secret, user):
    """Render the otpauth:// enrollment URI as a base64 PNG so the settings
    page can <img src> it directly — no client-side QR library needed."""
    import pyotp, qrcode, io, base64
    uri = pyotp.TOTP(secret).provisioning_uri(name=user.email or user.username, issuer_name='Roamly')
    img = qrcode.make(uri, box_size=6, border=2)
    buf = io.BytesIO()
    img.save(buf, format='PNG')
    return uri, f"data:image/png;base64,{base64.b64encode(buf.getvalue()).decode()}"


def _is_app_client(request):
    """True when the request comes from the Roamly mobile app (it sets this header)."""
    return request.META.get('HTTP_X_ROAMLY_CLIENT') == 'app'


def _client_ip(request):
    """Best-effort client IP. Behind the reverse proxy the real address is the
    first entry of X-Forwarded-For; fall back to REMOTE_ADDR for direct hits."""
    xff = request.META.get('HTTP_X_FORWARDED_FOR', '')
    if xff:
        return xff.split(',')[0].strip()
    return request.META.get('REMOTE_ADDR', '') or 'unknown'


def _log_action(request, action, description='', user=None):
    """Queue an ActionLog row for the admin panel via the batched writer.

    Best-effort and non-blocking — a logging failure must never break the flow
    it's recording, so the whole thing is wrapped in try/except."""
    try:
        u = user if user is not None else (request.user if request.user.is_authenticated else None)
        uid = u.id if u else None
        from .log_writer import enqueue_action
        enqueue_action(
            user_id=uid, action=action, description=description,
            ip_address=_client_ip(request), timestamp=timezone.now(),
        )
    except Exception:
        pass


def _rate_limited(request, scope, limit, window_s):
    """Return True if this client has already made `limit` requests to `scope`
    within the trailing `window_s` seconds. IP-keyed, cache-backed (Redis in
    prod, LocMem in dev). Fails **open** — a cache hiccup must never lock users
    out of signing in. Call once per request that should count toward the limit."""
    key = f'ratelimit:{scope}:{_client_ip(request)}'
    try:
        cache.add(key, 0, window_s)  # arm the window on the first hit (no-op after)
        count = cache.incr(key)
    except ValueError:
        # Key expired between add and incr — treat as the first hit of a new window.
        cache.set(key, 1, window_s)
        count = 1
    except Exception:
        return False  # cache unavailable: don't block legitimate access
    return count > limit


def login_view(request):
    is_app = _is_app_client(request)
    if request.user.is_authenticated and not is_app:
        return redirect('tracker:map')
    if request.method == 'POST':
        if _rate_limited(request, 'login', 10, 300):
            msg = 'Too many login attempts. Please wait a few minutes and try again.'
            if is_app:
                return JsonResponse({'status': 'error', 'message': msg}, status=429)
            messages.error(request, msg)
            return render(request, 'tracker/login.html', {'email_enabled': email_enabled()}, status=429)
        username = request.POST.get('username')
        password = request.POST.get('password')
        user = authenticate(request, username=username, password=password)
        if user is None and username and '@' in username:
            # Allow signing in with an email address. Resolve to a username only
            # when it maps to exactly one active account (legacy duplicate emails
            # stay username-only — never ambiguously logged in). Everything below
            # keys off the returned user, so no other change is needed.
            from django.contrib.auth.models import User as AuthUser
            matches = list(AuthUser.objects.filter(email__iexact=username.strip(), is_active=True)[:2])
            if len(matches) == 1:
                username = matches[0].username
                user = authenticate(request, username=username, password=password)
        if user:
            next_url = _safe_next(request)
            profile, _ = UserProfile.objects.get_or_create(user=user)
            if email_enabled() and user.email and not profile.email_verified:
                # Signed up but never verified — resume signup verification.
                _start_verification(request, user, 'signup', next_url)
                if is_app:
                    return JsonResponse({'status': 'verify', 'purpose': 'signup', 'email': _mask_email(user.email)})
                return redirect('tracker:verify')
            if profile.totp_enabled:
                # Authenticator-app 2FA — independent of email_enabled(), and
                # checked on every login rather than only new devices. The
                # emailed code stays reachable as a fallback (totp_email_fallback)
                # for a user who can't get at their authenticator right now.
                request.session['pending_totp'] = {'user_id': user.id, 'next': next_url}
                if is_app:
                    return JsonResponse({
                        'status': 'totp_required',
                        'email_available': bool(email_enabled() and user.email),
                        'email': _mask_email(user.email) if email_enabled() else '',
                    })
                return redirect('tracker:totp_verify')
            if email_enabled() and user.email and not _is_known_device(user, request):
                # New/unrecognized device — challenge with an emailed code.
                _start_verification(request, user, 'login', next_url)
                if is_app:
                    return JsonResponse({'status': 'verify', 'purpose': 'login', 'email': _mask_email(user.email)})
                return redirect('tracker:verify')
            login(request, user)
            _log_action(request, 'login', user=user)
            # Both app and web get a 302 on success — the app already reads the
            # session cookie off the redirect, so older builds keep working; only
            # the new-device *verify* path (below) is app-specific JSON.
            resp = redirect(next_url)
            if email_enabled() and user.email:
                _trust_device(user, request, resp)
            return resp
        _log_action(request, 'login_fail', description=f"username={username or ''}"[:200])
        if is_app:
            return JsonResponse({'status': 'error', 'message': 'Invalid username or password'}, status=401)
        messages.error(request, 'Invalid username or password.')
    return render(request, 'tracker/login.html', {'email_enabled': email_enabled()})


def password_reset_request(request):
    """Send a password-reset link to a submitted email (requires SMTP)."""
    if not email_enabled():
        return render(request, 'tracker/password_reset_request.html', {'disabled': True})
    sent = False
    if request.method == 'POST':
        if _rate_limited(request, 'pwreset', 5, 3600):
            return render(request, 'tracker/password_reset_request.html', {
                'rate_limited': True,
            }, status=429)
        from django.contrib.auth.models import User as AuthUser
        from django.contrib.auth.tokens import default_token_generator
        from django.utils.http import urlsafe_base64_encode
        from django.utils.encoding import force_bytes
        email = (request.POST.get('email') or '').strip()
        if email:
            for user in AuthUser.objects.filter(email__iexact=email, is_active=True):
                if not user.email:
                    continue
                uid = urlsafe_base64_encode(force_bytes(user.pk))
                token = default_token_generator.make_token(user)
                url = request.build_absolute_uri(f'/reset/{uid}/{token}/')
                send_password_reset_email(user.email, url, user.username)
        # Always show the same message — never reveal whether an account exists.
        sent = True
    return render(request, 'tracker/password_reset_request.html', {'sent': sent})


def password_reset_confirm(request, uidb64, token):
    """Validate the reset token and let the user choose a new password."""
    from django.contrib.auth.models import User as AuthUser
    from django.contrib.auth.tokens import default_token_generator
    from django.utils.http import urlsafe_base64_decode
    from django.utils.encoding import force_str
    try:
        uid = force_str(urlsafe_base64_decode(uidb64))
        user = AuthUser.objects.get(pk=uid)
    except (TypeError, ValueError, OverflowError, AuthUser.DoesNotExist):
        user = None
    valid = user is not None and default_token_generator.check_token(user, token)
    done = False
    if request.method == 'POST' and _rate_limited(request, 'pwconfirm', 10, 900):
        messages.error(request, 'Too many attempts. Please wait a few minutes and try again.')
        return render(request, 'tracker/password_reset_confirm.html', {'valid': valid, 'done': done}, status=429)
    if request.method == 'POST' and valid:
        from django.contrib.auth.password_validation import validate_password
        from django.core.exceptions import ValidationError
        p1 = request.POST.get('password1') or ''
        p2 = request.POST.get('password2') or ''
        if p1 != p2:
            messages.error(request, 'The two passwords do not match.')
        else:
            try:
                validate_password(p1, user)
            except ValidationError as exc:
                for m in exc.messages:
                    messages.error(request, m)
            else:
                user.set_password(p1)
                user.save()
                done = True  # token is now invalid (password changed)
    return render(request, 'tracker/password_reset_confirm.html', {'valid': valid, 'done': done})


def signup_view(request):
    if request.user.is_authenticated:
        return redirect('tracker:map')
    email_required = email_enabled()
    if request.method == 'POST':
        if _rate_limited(request, 'signup', 5, 3600):
            return render(request, 'tracker/signup.html', {
                'form': SignUpForm(),
                'admin_signup_enabled': bool(getattr(settings, 'ADMIN_SIGNUP_KEY', '')),
                'email_required': email_required,
                'rate_limited': True,
            }, status=429)
        form = SignUpForm(request.POST)
        if email_required and not request.POST.get('email', '').strip():
            form.add_error('email', 'An email address is required.')
        if form.is_valid():
            user = form.save()
            # A valid admin key (validated in the form) makes this an admin account.
            UserProfile.objects.update_or_create(
                user=user, defaults={'is_admin': form.is_admin_signup},
            )
            next_url = _safe_next(request)
            if email_enabled() and user.email:
                # Verify-to-activate: don't sign in until the emailed code is entered.
                UserProfile.objects.filter(user=user).update(email_verified=False)
                _start_verification(request, user, 'signup', next_url)
                return redirect('tracker:verify')
            login(request, user)
            _log_action(request, 'signup', user=user)
            return redirect(next_url)
    else:
        form = SignUpForm()
    return render(request, 'tracker/signup.html', {
        'form': form,
        'admin_signup_enabled': bool(getattr(settings, 'ADMIN_SIGNUP_KEY', '')),
        'email_required': email_required,
    })


def verify_view(request):
    """Enter the emailed code to finish signup activation or new-device login."""
    is_app = _is_app_client(request)
    pv = request.session.get('pending_verify')
    if not pv:
        if is_app:
            return JsonResponse({'status': 'error', 'message': 'No pending verification'}, status=400)
        return redirect('tracker:login')
    from django.contrib.auth.models import User as AuthUser
    user = AuthUser.objects.filter(id=pv['user_id']).first()
    if not user:
        request.session.pop('pending_verify', None)
        if is_app:
            return JsonResponse({'status': 'error', 'message': 'No pending verification'}, status=400)
        return redirect('tracker:login')
    purpose = pv['purpose']
    if request.method == 'POST':
        if _rate_limited(request, 'verify', 10, 900):
            msg = 'Too many attempts. Please wait a few minutes and try again.'
            if is_app:
                return JsonResponse({'status': 'error', 'message': msg}, status=429)
            messages.error(request, msg)
            return render(request, 'tracker/verify_code.html', {
                'purpose': purpose,
                'email_masked': _mask_email(pv.get('email')),
                'resend_url': '/verify/resend/',
                'from_totp': bool(pv.get('from_totp')),
            }, status=429)
        code = (request.POST.get('code') or '').strip()
        real = cache.get(f'email_code:{purpose}:{user.id}')
        if real and code == real:
            cache.delete(f'email_code:{purpose}:{user.id}')
            if purpose == 'signup':
                UserProfile.objects.filter(user=user).update(email_verified=True)
            login(request, user)
            _log_action(request, 'signup' if purpose == 'signup' else 'login', user=user)
            request.session.pop('pending_verify', None)
            resp = JsonResponse({'status': 'ok'}) if is_app else redirect(pv.get('next') or 'tracker:map')
            _trust_device(user, request, resp)  # trust the device that just verified
            return resp
        if is_app:
            return JsonResponse({'status': 'error', 'message': 'Invalid or expired code'}, status=400)
        messages.error(request, 'Invalid or expired code. Try again or resend.')
    return render(request, 'tracker/verify_code.html', {
        'purpose': purpose,
        'email_masked': _mask_email(pv.get('email')),
        'resend_url': '/verify/resend/',
        'from_totp': bool(pv.get('from_totp')),
    })


@require_POST
def verify_use_totp(request):
    """Go back to the authenticator-app challenge after choosing the emailed
    code. Only offered when the emailed code *was* reached from the TOTP page
    (`from_totp`), so this can never downgrade a plain new-device challenge —
    it just restores the pending state totp_email_fallback swapped out."""
    is_app = _is_app_client(request)
    pv = request.session.get('pending_verify') or {}
    from django.contrib.auth.models import User as AuthUser
    user = AuthUser.objects.filter(id=pv.get('user_id')).first() if pv.get('from_totp') else None
    profile = UserProfile.objects.filter(user=user).first() if user else None
    if not profile or not profile.totp_enabled:
        if is_app:
            return JsonResponse({'status': 'error', 'message': 'No pending verification'}, status=400)
        return redirect('tracker:verify' if pv else 'tracker:login')
    request.session['pending_totp'] = {'user_id': user.id, 'next': pv.get('next', '')}
    request.session.pop('pending_verify', None)
    if is_app:
        return JsonResponse({'status': 'totp_required'})
    return redirect('tracker:totp_verify')


@require_POST
def verify_resend(request):
    pv = request.session.get('pending_verify')
    if not pv:
        return redirect('tracker:login')
    if _rate_limited(request, 'verify_resend', 4, 900):
        messages.error(request, 'Too many code requests. Please wait a few minutes and try again.')
        return redirect('tracker:verify')
    from django.contrib.auth.models import User as AuthUser
    user = AuthUser.objects.filter(id=pv['user_id']).first()
    if user:
        _start_verification(request, user, pv['purpose'], pv.get('next', ''))
        messages.error(request, 'A new code is on its way.')
    return redirect('tracker:verify')


def totp_verify_view(request):
    """Enter a code from an authenticator app (or a backup code) to finish a
    login for an account with TOTP enabled. Unlike verify_view's emailed
    code, this runs on every login regardless of email_enabled() or device
    trust — TOTP is the stronger, always-on alternate 2FA method. Mirrors
    verify_view's session-pending pattern otherwise."""
    is_app = _is_app_client(request)
    pv = request.session.get('pending_totp')
    if not pv:
        if is_app:
            return JsonResponse({'status': 'error', 'message': 'No pending verification'}, status=400)
        return redirect('tracker:login')
    from django.contrib.auth.models import User as AuthUser
    user = AuthUser.objects.filter(id=pv['user_id']).first()
    profile = UserProfile.objects.filter(user=user).first() if user else None
    if not user or not profile or not profile.totp_enabled:
        request.session.pop('pending_totp', None)
        if is_app:
            return JsonResponse({'status': 'error', 'message': 'No pending verification'}, status=400)
        return redirect('tracker:login')
    # The emailed code stays available as a fallback for a user who can't reach
    # their authenticator app — offered as a button, never taken automatically.
    ctx = {
        'email_fallback': bool(email_enabled() and user.email),
        'email_masked': _mask_email(user.email),
    }
    if request.method == 'POST':
        if _rate_limited(request, 'totp', 10, 900):
            msg = 'Too many attempts. Please wait a few minutes and try again.'
            if is_app:
                return JsonResponse({'status': 'error', 'message': msg}, status=429)
            messages.error(request, msg)
            return render(request, 'tracker/totp_verify.html', ctx, status=429)
        code = request.POST.get('code') or ''
        if _verify_totp_or_backup(profile, code):
            login(request, user)
            _log_action(request, 'login', user=user)
            request.session.pop('pending_totp', None)
            resp = JsonResponse({'status': 'ok'}) if is_app else redirect(pv.get('next') or 'tracker:map')
            if email_enabled() and user.email:
                _trust_device(user, request, resp)  # skip the emailed-code flow too, going forward
            return resp
        if is_app:
            return JsonResponse({'status': 'error', 'message': 'Invalid code'}, status=400)
        messages.error(request, 'Invalid code. Try again, or use a backup code.')
    return render(request, 'tracker/totp_verify.html', ctx)


@require_POST
def totp_email_fallback(request):
    """Swap a pending TOTP challenge for an emailed code.

    TOTP is the primary factor once enabled, but an authenticator app can be
    out of reach (phone lost, app not to hand) while the mailbox isn't — so
    the challenge page offers this as a second route rather than dead-ending
    at the backup codes. Requires SMTP and an address on the account; the
    pending state simply moves from `pending_totp` to `pending_verify`, marked
    `from_totp` so the emailed-code page can offer the way back. Shares the
    resend flood budget, since it too sends mail."""
    is_app = _is_app_client(request)
    pv = request.session.get('pending_totp')
    if not pv:
        if is_app:
            return JsonResponse({'status': 'error', 'message': 'No pending verification'}, status=400)
        return redirect('tracker:login')
    from django.contrib.auth.models import User as AuthUser
    user = AuthUser.objects.filter(id=pv['user_id']).first()
    if not user or not email_enabled() or not user.email:
        if is_app:
            return JsonResponse({'status': 'error', 'message': 'Email codes are not available'}, status=400)
        messages.error(request, 'Email codes are not available on this account.')
        return redirect('tracker:totp_verify')
    if _rate_limited(request, 'verify_resend', 4, 900):
        msg = 'Too many code requests. Please wait a few minutes and try again.'
        if is_app:
            return JsonResponse({'status': 'error', 'message': msg}, status=429)
        messages.error(request, msg)
        return redirect('tracker:totp_verify')
    _start_verification(request, user, 'login', pv.get('next', ''))
    stashed = request.session['pending_verify']
    stashed['from_totp'] = True
    request.session['pending_verify'] = stashed  # reassign: nested edits aren't tracked
    request.session.pop('pending_totp', None)
    if is_app:
        return JsonResponse({'status': 'verify', 'purpose': 'login', 'email': _mask_email(user.email)})
    return redirect('tracker:verify')


def logout_view(request):
    _log_action(request, 'logout')
    logout(request)
    return redirect('tracker:login')


# ---------------------------------------------------------------------------
# Page Views
# ---------------------------------------------------------------------------

@login_required
def map_view(request):
    devices = Device.objects.filter(user=request.user)
    return render(request, 'tracker/map.html', {
        'devices': devices,
        'has_postgis': HAS_POSTGIS,
    })


@login_required
def data_table(request):
    devices = Device.objects.filter(user=request.user)
    return render(request, 'tracker/data_table.html', {'devices': devices})


@login_required
def stats_view(request):
    return render(request, 'tracker/stats.html')


@login_required
def visits_view(request):
    return render(request, 'tracker/visits.html')


@login_required
def adventures_view(request):
    devices = Device.objects.filter(user=request.user)
    adventures = Adventure.objects.filter(device__user=request.user)
    return render(request, 'tracker/adventures.html', {'devices': devices, 'adventures': adventures})


@login_required
def adventure_edit_view(request, trip_id):
    trip = _get_trip_for_user(trip_id, request.user)
    devices = Device.objects.filter(user=request.user)
    is_creator = trip.device.user == request.user or trip.creator == request.user
    return render(request, 'tracker/adventure_edit.html', {
        'trip': trip,
        'trip_id': trip_id,
        'devices': devices,
        'is_creator': is_creator,
    })


@login_required
def adventure_plan_view(request, trip_id):
    """The trip planning hub: itinerary, people & invites, members map."""
    trip = _get_trip_for_user(trip_id, request.user)
    is_creator = trip.device.user == request.user or trip.creator == request.user
    return render(request, 'tracker/adventure_plan.html', {
        'trip': trip,
        'trip_id': trip_id,
        'is_creator': is_creator,
        'email_enabled': email_enabled(),
    })


@login_required
def places_view(request):
    return render(request, 'tracker/places.html', {'has_postgis': HAS_POSTGIS})


@login_required
def ask_view(request):
    """The AI 'Ask' chat page — only reachable when the user has configured AI."""
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    if not profile.ai_configured:
        return redirect('tracker:map')
    return render(request, 'tracker/ask.html')


@login_required
def settings_view(request):
    api_keys = APIKey.objects.filter(user=request.user)
    devices = Device.objects.filter(user=request.user)
    form = APIKeyForm()
    backup_config = BackupConfig.objects.filter(user=request.user).first()
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    ctx = {
        'api_keys': api_keys,
        'devices': devices,
        'form': form,
        'has_postgis': HAS_POSTGIS,
        'backup_config': backup_config,
        'is_admin': profile.is_admin,
        # Per-user AI Ask config (key is never sent to the template — only a flag).
        'ai_ask_enabled': profile.ai_ask_enabled,
        'ai_base_url': profile.ai_base_url,
        'ai_model': profile.ai_model,
        'ai_system_prompt': profile.ai_system_prompt,
        'ai_allow_journals': profile.ai_allow_journals,
        'ai_api_key_set': bool(profile.ai_api_key),
        # Summary emails — only usable when SMTP + AI Ask are both configured.
        'summary_available': bool(email_enabled() and profile.ai_configured),
        'email_enabled': email_enabled(),
        'ai_configured': profile.ai_configured,
        'summary_daily': profile.summary_daily,
        'summary_weekly': profile.summary_weekly,
        'summary_monthly': profile.summary_monthly,
        'summary_yearly': profile.summary_yearly,
        # TOTP two-factor auth
        'totp_enabled': profile.totp_enabled,
        'totp_backup_remaining': (
            TOTPBackupCode.objects.filter(user=request.user, used_at__isnull=True).count()
            if profile.totp_enabled else 0
        ),
    }
    return render(request, 'tracker/settings.html', ctx)


@login_required
@require_POST
def site_custom_js_api(request):
    """Save the instance-wide custom JS snippet. Admins only."""
    from .context_processors import CUSTOM_JS_CACHE_KEY
    from django.core.cache import cache

    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    if not profile.is_admin:
        return JsonResponse({'error': 'Admin access required.'}, status=403)

    try:
        custom_js = json.loads(request.body).get('custom_js', '')
    except (json.JSONDecodeError, AttributeError):
        custom_js = request.POST.get('custom_js', '')

    config = SiteConfig.load()
    config.custom_js = custom_js or ''
    config.save(update_fields=['custom_js', 'updated_at'])
    cache.delete(CUSTOM_JS_CACHE_KEY)
    _log_action(request, 'custom_js_save')
    return JsonResponse({'ok': True})


@login_required
@require_POST
def site_contact_email_api(request):
    """Save the instance contact address (shown in footers). Admins only."""
    from .context_processors import CONTACT_EMAIL_CACHE_KEY
    from django.core.cache import cache
    from django.core.validators import validate_email
    from django.core.exceptions import ValidationError

    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    if not profile.is_admin:
        return JsonResponse({'error': 'Admin access required.'}, status=403)

    try:
        contact_email = json.loads(request.body).get('contact_email', '')
    except (json.JSONDecodeError, AttributeError):
        contact_email = request.POST.get('contact_email', '')
    contact_email = (contact_email or '').strip()
    if contact_email:
        try:
            validate_email(contact_email)
        except ValidationError:
            return JsonResponse({'error': 'Enter a valid email address.'}, status=400)

    config = SiteConfig.load()
    config.contact_email = contact_email
    config.save(update_fields=['contact_email', 'updated_at'])
    cache.delete(CONTACT_EMAIL_CACHE_KEY)
    return JsonResponse({'ok': True, 'contact_email': contact_email})


@csrf_exempt
def contact_api(request):
    """Public contact form → email the instance contact address.

    Unauthenticated (the landing page is public). CSRF-exempt: the landing page
    is cached per-anonymous-visitor so an embedded token would mismatch, and a
    forged submission only mails the admin — no victim-side state changes. A
    filled ``website`` honeypot field is silently accepted as spam (returns ok
    without sending) so bots get no signal. Requires SMTP + a contact address.
    """
    if request.method != 'POST':
        return JsonResponse({'error': 'Method not allowed.'}, status=405)

    from .context_processors import get_contact_email
    contact_to = get_contact_email()
    if not (email_enabled() and contact_to):
        return JsonResponse({'error': 'Contact form is unavailable.'}, status=400)

    try:
        payload = json.loads(request.body)
    except (json.JSONDecodeError, AttributeError):
        payload = request.POST

    # Honeypot — a real user never fills this hidden field.
    if (payload.get('website') or '').strip():
        return JsonResponse({'ok': True})

    name = (payload.get('name') or '').strip()[:120]
    sender_email = (payload.get('email') or '').strip()[:254]
    message = (payload.get('message') or '').strip()[:5000]
    if not sender_email or not message:
        return JsonResponse({'error': 'Email and message are required.'}, status=400)
    from django.core.validators import validate_email
    from django.core.exceptions import ValidationError
    try:
        validate_email(sender_email)
    except ValidationError:
        return JsonResponse({'error': 'Enter a valid email address.'}, status=400)

    sent = send_contact_email(contact_to, name, sender_email, message)
    if not sent:
        return JsonResponse({'error': 'Could not send message. Try again later.'}, status=500)
    return JsonResponse({'ok': True})


# ---------------------------------------------------------------------------
# AI "Ask" — per-user config + chat (see tracker/ai_tasks.py)
# ---------------------------------------------------------------------------

# Shown in place of the real key on GET, and treated as "unchanged" on POST.
_AI_KEY_MASK = '••••••••'


@login_required
def profile_ai_config_api(request):
    """GET: whether AI Ask is enabled/configured for the requesting user (used
    by the mobile app to decide whether to show the Ask tab). POST: save the
    requesting user's own AI Ask config. The API key is only overwritten when
    a new (non-masked) value is supplied — mirrors the BackupConfig.secret_key
    pattern."""
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    if request.method != 'POST':
        return JsonResponse({'ai_ask_enabled': profile.ai_ask_enabled, 'configured': profile.ai_configured})

    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        return JsonResponse({'error': 'Invalid request.'}, status=400)

    profile.ai_ask_enabled = bool(data.get('ai_ask_enabled'))
    profile.ai_base_url = (data.get('ai_base_url') or '').strip().rstrip('/')[:500]
    profile.ai_model = (data.get('ai_model') or '').strip()[:200]
    profile.ai_system_prompt = (data.get('ai_system_prompt') or '').strip()
    profile.ai_allow_journals = bool(data.get('ai_allow_journals'))

    new_key = (data.get('ai_api_key') or '').strip()
    if new_key and new_key != _AI_KEY_MASK:
        profile.ai_api_key = new_key[:500]

    profile.save(update_fields=[
        'ai_ask_enabled', 'ai_base_url', 'ai_model', 'ai_system_prompt',
        'ai_allow_journals', 'ai_api_key',
    ])
    return JsonResponse({'ok': True, 'configured': profile.ai_configured})


_SUMMARY_PERIODS = ('daily', 'weekly', 'monthly', 'yearly')


@require_http_methods(["GET", "POST"])
def email_unsubscribe_view(request, token):
    """Unauthenticated recap-email preferences page. The token is the
    credential (same trust model as the password-reset link / trip-invite
    join link) so a logged-out recipient can act on it straight from their
    inbox. ?period=<cadence> on a GET one-click-unsubscribes from just that
    cadence before rendering the full form for further changes."""
    # The token is the credential. Be tolerant of the ways an email path mangles
    # it before it gets back here: plain-text line-wrapping can staple on
    # surrounding whitespace/newlines, and some mail scanners / click-trackers
    # case-fold the URL. Try the exact value first, then a trimmed
    # case-insensitive match (tokens are 24 random base64url chars, so a
    # case-folded collision between two users is astronomically unlikely).
    token = (token or '').strip()
    profile = (UserProfile.objects.filter(email_unsub_token=token)
               .select_related('user').first())
    if not profile and token:
        profile = (UserProfile.objects.filter(email_unsub_token__iexact=token)
                   .select_related('user').first())
    if not profile:
        return render(request, 'tracker/email_unsubscribe.html', {'invalid': True}, status=404)

    saved = False
    if request.method == 'POST':
        for period in _SUMMARY_PERIODS:
            setattr(profile, f'summary_{period}', bool(request.POST.get(f'summary_{period}')))
        profile.save(update_fields=[f'summary_{p}' for p in _SUMMARY_PERIODS])
        saved = True
    else:
        one_click = request.GET.get('period')
        if one_click in _SUMMARY_PERIODS and getattr(profile, f'summary_{one_click}'):
            setattr(profile, f'summary_{one_click}', False)
            profile.save(update_fields=[f'summary_{one_click}'])
            saved = True

    return render(request, 'tracker/email_unsubscribe.html', {
        'profile': profile,
        'saved': saved,
        'one_click_period': request.GET.get('period') if request.method == 'GET' else None,
    })


@login_required
def profile_summary_email_api(request):
    """GET: the user's summary-email cadence flags + whether the feature is
    available (SMTP + AI both configured). POST: save the four cadence toggles,
    or (with ``test`` set) fire an immediate test send of one cadence. Mirrors
    profile_ai_config_api. See tracker/summary_email_tasks.py."""
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    available = bool(email_enabled() and profile.ai_configured)

    if request.method != 'POST':
        return JsonResponse({
            'available': available,
            'email_enabled': email_enabled(),
            'ai_configured': profile.ai_configured,
            'summary_daily': profile.summary_daily,
            'summary_weekly': profile.summary_weekly,
            'summary_monthly': profile.summary_monthly,
            'summary_yearly': profile.summary_yearly,
        })

    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        return JsonResponse({'error': 'Invalid request.'}, status=400)

    # Test send: fire one cadence immediately (bypasses due-check, no cursor change).
    test_period = data.get('test')
    if test_period:
        if not available:
            return JsonResponse(
                {'error': 'Summary emails need both SMTP and AI Ask configured.'}, status=400)
        from .summary_email_tasks import send_summary_now
        send_summary_now(request.user.id, str(test_period))
        return JsonResponse({'ok': True, 'test': True})

    profile.summary_daily = bool(data.get('summary_daily'))
    profile.summary_weekly = bool(data.get('summary_weekly'))
    profile.summary_monthly = bool(data.get('summary_monthly'))
    profile.summary_yearly = bool(data.get('summary_yearly'))
    profile.save(update_fields=[
        'summary_daily', 'summary_weekly', 'summary_monthly', 'summary_yearly',
    ])
    return JsonResponse({'ok': True, 'available': available})


@login_required
def profile_mapbox_token_api(request):
    """Get or set the requesting user's Mapbox access token. Stored server-side
    so the token follows the user to every device — the web map and the mobile
    app both read it (mobile is read-only; the token is only set here / from
    Settings on the web). It's a public token by design, so it's returned in
    full (not masked)."""
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    if request.method == 'POST':
        try:
            data = json.loads(request.body or '{}')
        except (json.JSONDecodeError, ValueError):
            return JsonResponse({'error': 'Invalid request.'}, status=400)
        profile.mapbox_token = (data.get('mapbox_token') or '').strip()[:500]
        profile.save(update_fields=['mapbox_token'])
        return JsonResponse({'ok': True, 'mapbox_token': profile.mapbox_token})
    return JsonResponse({'mapbox_token': profile.mapbox_token})


@login_required
def profile_distance_unit_api(request):
    """Get or set the requesting user's distance-unit preference ('kmh'/'mph').

    Server-side mirror of the roamly_speed_unit localStorage key — the map and
    stats pages keep reading localStorage directly (no round trip needed for
    those), this endpoint exists so the summary-email background job, which
    has no browser to ask, can still honor the user's unit."""
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    if request.method == 'POST':
        try:
            data = json.loads(request.body or '{}')
        except (json.JSONDecodeError, ValueError):
            return JsonResponse({'error': 'Invalid request.'}, status=400)
        unit = data.get('distance_unit')
        if unit in ('kmh', 'mph'):
            profile.distance_unit = unit
            profile.save(update_fields=['distance_unit'])
        return JsonResponse({'ok': True, 'distance_unit': profile.distance_unit})
    return JsonResponse({'distance_unit': profile.distance_unit})


@login_required
def totp_status_api(request):
    """Whether TOTP is enabled + remaining unused backup codes, so the
    settings page can repaint after setup/regenerate without a full reload."""
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    remaining = (TOTPBackupCode.objects.filter(user=request.user, used_at__isnull=True).count()
                 if profile.totp_enabled else 0)
    return JsonResponse({'enabled': profile.totp_enabled, 'backup_codes_remaining': remaining})


@login_required
@require_POST
def totp_setup_api(request):
    """Start (or restart) enrollment: mint a fresh secret, not yet enabled
    until totp_confirm_api verifies possession of it. Restarting overwrites
    any earlier unconfirmed secret — nothing is trusted until confirmed, so
    an abandoned setup attempt can't leave a stale secret behind."""
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    import pyotp
    secret = pyotp.random_base32()
    profile.totp_secret = secret
    profile.totp_enabled = False
    profile.save(update_fields=['totp_secret', 'totp_enabled'])
    otpauth_url, qr_code = _totp_qr_data_uri(secret, request.user)
    return JsonResponse({'secret': secret, 'otpauth_url': otpauth_url, 'qr_code': qr_code})


@login_required
@require_POST
def totp_confirm_api(request):
    """Finish enrollment: the submitted code must validate against the secret
    minted by totp_setup_api, proving the authenticator app is actually
    configured before TOTP starts gating logins. Also mints backup codes."""
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    if not profile.totp_secret:
        return JsonResponse({'status': 'error', 'message': 'Start setup first.'}, status=400)
    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        return JsonResponse({'status': 'error', 'message': 'Invalid request.'}, status=400)
    import pyotp
    code = (data.get('code') or '').strip()
    if not pyotp.TOTP(profile.totp_secret).verify(code, valid_window=1):
        return JsonResponse({'status': 'error', 'message': 'Invalid code.'}, status=400)
    profile.totp_enabled = True
    profile.save(update_fields=['totp_enabled'])
    codes = _generate_totp_backup_codes(request.user)
    _log_action(request, 'totp_enable')
    return JsonResponse({'status': 'ok', 'backup_codes': codes})


@login_required
@require_POST
def totp_disable_api(request):
    """Disable TOTP and wipe the secret + backup codes. Requires the account
    password rather than a TOTP code, so losing the authenticator app doesn't
    also lock the user out of turning 2FA off."""
    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        return JsonResponse({'status': 'error', 'message': 'Invalid request.'}, status=400)
    if not request.user.check_password(data.get('password') or ''):
        return JsonResponse({'status': 'error', 'message': 'Incorrect password.'}, status=400)
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    profile.totp_enabled = False
    profile.totp_secret = ''
    profile.save(update_fields=['totp_enabled', 'totp_secret'])
    TOTPBackupCode.objects.filter(user=request.user).delete()
    _log_action(request, 'totp_disable')
    return JsonResponse({'status': 'ok'})


@login_required
@require_POST
def totp_backup_codes_regenerate_api(request):
    """Invalidate all existing backup codes and mint a fresh set. Requires a
    valid TOTP code (proves the app still works), unlike disable which
    requires the password — this is routine maintenance for someone who
    already has 2FA working, not an account-recovery path."""
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    if not profile.totp_enabled:
        return JsonResponse({'status': 'error', 'message': 'TOTP is not enabled.'}, status=400)
    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        return JsonResponse({'status': 'error', 'message': 'Invalid request.'}, status=400)
    import pyotp
    code = (data.get('code') or '').strip()
    if not pyotp.TOTP(profile.totp_secret).verify(code, valid_window=1):
        return JsonResponse({'status': 'error', 'message': 'Invalid code.'}, status=400)
    codes = _generate_totp_backup_codes(request.user)
    _log_action(request, 'totp_regen_backup')
    return JsonResponse({'status': 'ok', 'backup_codes': codes})


@login_required
@require_POST
def ask_test_api(request):
    """Probe the user's AI provider (no tools) so Settings can show a precise
    diagnosis. Tests posted values when given (so you can test before saving),
    falling back to the stored key when the key field is blank/masked."""
    from . import ai_tasks
    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        data = {}

    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    # Build a transient (unsaved) profile view from posted overrides.
    base_url = (data.get('ai_base_url') or profile.ai_base_url or '').strip().rstrip('/')
    model = (data.get('ai_model') or profile.ai_model or '').strip()
    posted_key = (data.get('ai_api_key') or '').strip()
    api_key = posted_key if (posted_key and posted_key != _AI_KEY_MASK) else profile.ai_api_key
    if not (base_url and api_key and model):
        return JsonResponse({'ok': False, 'error': 'Enter a base URL, API key, and model first.'})

    profile.ai_base_url, profile.ai_api_key, profile.ai_model = base_url, api_key, model
    ok, err = ai_tasks.test_connection(profile)
    return JsonResponse({'ok': ok, 'error': err})


@csrf_exempt
@login_required
@require_POST
def ask_api(request):
    """Run one Ask turn: take the client's message history, run the tool-calling
    loop scoped to this user, and return the assistant's reply.

    CSRF-exempt so the mobile app (session cookie, no CSRF token) can POST here,
    matching the other mobile-hit endpoints. Session auth still required."""
    from . import ai_tasks
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    if not profile.ai_ask_enabled:
        return JsonResponse({'error': 'AI Ask is disabled.'}, status=403)
    if not profile.ai_configured:
        return JsonResponse({'error': 'AI Ask is not configured.'}, status=503)

    try:
        body = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError, AttributeError):
        return JsonResponse({'error': 'Invalid request.'}, status=400)
    messages = body.get('messages', [])
    tz_offset = body.get('tz_offset', 0)
    if not isinstance(messages, list):
        return JsonResponse({'error': 'Invalid request.'}, status=400)

    try:
        reply = ai_tasks.run_ask(request.user, messages, profile, tz_offset=tz_offset)
    except ai_tasks.AIProviderError as e:
        return JsonResponse({'error': str(e)}, status=502)
    except Exception:
        logger.exception('ask_api failed')
        return JsonResponse({'error': 'Something went wrong answering that.'}, status=500)
    return JsonResponse({'reply': reply})


# ---------------------------------------------------------------------------
# Location API
# ---------------------------------------------------------------------------

@csrf_exempt
@require_http_methods(["POST", "GET"])
def push_location(request):
    """Receive location data from mobile devices (OwnTracks + standard format)."""
    user = get_api_key_user(request)
    if not user:
        return JsonResponse({"error": "Invalid or missing API key"}, status=401)

    if request.method == "GET":
        data = dict(request.GET)
        data = {k: v[0] if isinstance(v, list) and len(v) == 1 else v for k, v in data.items()}
    elif hasattr(request, '_cached_json_body'):
        data = request._cached_json_body
    else:
        try:
            data = json.loads(request.body.decode('utf-8'))
        except (json.JSONDecodeError, UnicodeDecodeError):
            # Fallback to form-encoded POST data
            if request.POST:
                data = dict(request.POST)
                data = {k: v[0] if isinstance(v, list) and len(v) == 1 else v for k, v in data.items()}
            else:
                return JsonResponse({"error": "Invalid JSON"}, status=400)

    # Detect format
    if data.get("_type") == "location":
        device_id = data.get("tid") or data.get("device_id", "unknown")
        latitude = data.get("lat")
        longitude = data.get("lon")
        timestamp = data.get("tst")
        if timestamp:
            timestamp = datetime.fromtimestamp(int(timestamp), tz=dt_timezone.utc)
        altitude = data.get("alt")
        accuracy = data.get("acc")
        speed = data.get("vel")
        battery = data.get("batt")
    else:
        device_id = data.get("device_id")
        latitude = data.get("latitude")
        longitude = data.get("longitude")
        timestamp = data.get("timestamp")
        if timestamp:
            if isinstance(timestamp, (int, float)):
                timestamp = datetime.fromtimestamp(int(timestamp), tz=dt_timezone.utc)
            elif isinstance(timestamp, str):
                try:
                    timestamp = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
                except ValueError:
                    timestamp = datetime.fromtimestamp(int(timestamp), tz=dt_timezone.utc)
        altitude = data.get("altitude")
        accuracy = data.get("accuracy")
        speed = data.get("speed")
        battery = data.get("battery")

    if not device_id:
        return JsonResponse({"error": "device_id required"}, status=400)
    if latitude is None or longitude is None:
        return JsonResponse({"error": "latitude and longitude required"}, status=400)

    try:
        latitude = float(latitude)
        longitude = float(longitude)
    except (TypeError, ValueError):
        return JsonResponse({"error": "Invalid latitude/longitude"}, status=400)

    # Only the impossible is rejected here. Quality filtering (speed, accuracy, teleports)
    # belongs on the device, where it is reversible and has the neighbouring fixes to reason
    # about; the server refusing a point loses it for good.
    if not (-90.0 <= latitude <= 90.0 and -180.0 <= longitude <= 180.0):
        return JsonResponse({"error": "latitude/longitude out of range"}, status=400)

    if not timestamp:
        timestamp = timezone.now()

    device, _ = Device.objects.get_or_create(
        user=user, device_id=str(device_id),
        defaults={'name': str(device_id)}
    )

    loc_defaults = {
        'altitude': _safe_float(altitude),
        'accuracy': _safe_float(accuracy),
        'speed': _safe_float(speed),
        'battery': _safe_float(battery),
    }

    from django.db import IntegrityError
    try:
        location = Location.objects.create(
            device=device,
            latitude=latitude,
            longitude=longitude,
            timestamp=timestamp,
            **loc_defaults,
        )
        created = True
    except IntegrityError:
        # Duplicate location (same device+lat+lng+timestamp) — return existing
        try:
            location = Location.objects.get(
                device=device, latitude=latitude,
                longitude=longitude, timestamp=timestamp,
            )
        except Location.DoesNotExist:
            location = None
        created = False

    if created and location:
        # Geocoding is deliberately OFF the request path. A blocking Nominatim call
        # here (up to 10s/point, and rate-limited above ~1 req/sec) was stalling
        # every upload and starving the mobile uploader into multi-minute backoff
        # gaps. New points land with city='' and get labelled by the background
        # cluster worker instead — kicked here, fire-and-forget, debounced.
        ensure_auto_geocode(user.id)

    _bust_user_cache(user.id)
    loc_id = location.id if location else None
    return JsonResponse({"status": "ok", "location_id": loc_id, "device": str(device_id)})


@csrf_exempt
@require_http_methods(["POST"])
def push_location_batch(request):
    """Batch-upload up to 500 location points in a single request.
    Accepts a JSON array of point objects (same fields as /api/push/).
    Duplicates are silently skipped via INSERT … ON CONFLICT DO NOTHING.
    Used by the mobile uploader to drain large offline backlogs in far fewer
    HTTP round-trips than the single-point endpoint."""
    user = get_api_key_user(request)
    if not user:
        return JsonResponse({"error": "Invalid or missing API key"}, status=401)

    try:
        points_data = json.loads(request.body.decode('utf-8'))
    except (json.JSONDecodeError, UnicodeDecodeError):
        return JsonResponse({"error": "Invalid JSON"}, status=400)

    if not isinstance(points_data, list):
        return JsonResponse({"error": "Expected JSON array"}, status=400)

    if len(points_data) > 500:
        return JsonResponse({"error": "Batch too large (max 500)"}, status=400)

    devices = {}
    to_create = []

    for raw in points_data:
        device_id = str(raw.get("device_id", "")).strip()
        if not device_id:
            continue
        try:
            lat = float(raw["latitude"])
            lng = float(raw["longitude"])
        except (KeyError, TypeError, ValueError):
            continue
        # Impossible coordinates only — see push_location.
        if not (-90.0 <= lat <= 90.0 and -180.0 <= lng <= 180.0):
            continue

        if device_id not in devices:
            device, _ = Device.objects.get_or_create(
                user=user, device_id=device_id, defaults={'name': device_id}
            )
            devices[device_id] = device

        ts_raw = raw.get("timestamp")
        ts = timezone.now()
        if ts_raw:
            try:
                if isinstance(ts_raw, str):
                    ts = datetime.fromisoformat(ts_raw.replace("Z", "+00:00"))
                elif isinstance(ts_raw, (int, float)):
                    ts = datetime.fromtimestamp(int(ts_raw), tz=dt_timezone.utc)
            except (ValueError, OSError):
                pass

        loc_obj = Location(
            device=devices[device_id],
            latitude=lat,
            longitude=lng,
            timestamp=ts,
            altitude=_safe_float(raw.get("altitude")),
            accuracy=_safe_float(raw.get("accuracy")),
            speed=_safe_float(raw.get("speed")),
            battery=_safe_float(raw.get("battery")),
        )
        # bulk_create() below bypasses Location.save(), which is what normally
        # populates the PostGIS `location` PointField from lat/lon. Without this,
        # every batch-uploaded point has location=NULL and is invisible to all
        # spatial queries (Places membership/last-seen, "have I been here",
        # search, map "my places") — freezing them at the last single-pushed
        # point. Set it here exactly as save() and the restore path do.
        if HAS_POSTGIS and Point:
            loc_obj.location = Point(lng, lat, srid=4326)
        to_create.append(loc_obj)

    accepted = 0
    if to_create:
        try:
            created = Location.objects.bulk_create(to_create, ignore_conflicts=True)
            accepted = len(created)
        except Exception:
            for loc in to_create:
                try:
                    loc.save()
                    accepted += 1
                except Exception:
                    pass
        ensure_auto_geocode(user.id)
        _bust_user_cache(user.id)

    return JsonResponse({"status": "ok", "accepted": accepted, "submitted": len(to_create)})


@login_required
def locations_bounds_api(request):
    """Return the bounding box of all user locations matching current filters. Fast — no point data returned."""
    device_id = request.GET.get('device_id')
    all_time = request.GET.get('all')
    start_date = request.GET.get('start_date')
    end_date = request.GET.get('end_date')
    hours = request.GET.get('hours', '24')

    qs = Location.objects.filter(device__user=request.user)

    if start_date and end_date:
        try:
            start = datetime.fromisoformat(start_date).replace(hour=0, minute=0, second=0)
            end_dt = datetime.fromisoformat(end_date).replace(hour=23, minute=59, second=59)
            if timezone.is_naive(start):
                start = timezone.make_aware(start)
            if timezone.is_naive(end_dt):
                end_dt = timezone.make_aware(end_dt)
            qs = qs.filter(timestamp__gte=start, timestamp__lte=end_dt)
        except (ValueError, TypeError):
            pass
    elif not all_time:
        qs = qs.filter(timestamp__gte=timezone.now() - timedelta(hours=int(hours)))

    if device_id:
        qs = qs.filter(device__device_id=device_id)

    agg = qs.aggregate(
        min_lat=Min('latitude'), max_lat=Max('latitude'),
        min_lng=Min('longitude'), max_lng=Max('longitude'),
    )

    if agg['min_lat'] is None:
        return JsonResponse({'bounds': None})

    return JsonResponse({'bounds': [
        [agg['min_lng'], agg['min_lat']],
        [agg['max_lng'], agg['max_lat']],
    ]})


@login_required
def track_api(request):
    """Return GPS track as decimated path per device — single request, no tiles.

    Returns at most MAX_POINTS_PER_DEVICE coordinates per device, evenly sampled,
    so the response is fast regardless of time range. Cached in Redis.
    """
    MAX_POINTS = 4000
    STATIONARY_MIN_INTERVAL_S = 600
    # Movement is measured as displacement from the last KEPT point (the anchor),
    # not from the immediately previous point. That separates a slow walk (which
    # keeps drifting away from the anchor) from stationary GPS jitter (which orbits
    # it), so the threshold can be small enough to preserve a stroll without
    # re-densifying a place you just sat still in.
    MOVEMENT_DISTANCE_M = 20
    MOVEMENT_SPEED_MPS = 1.1
    STATIONARY_GAP_FORCE_KEEP_S = 1800

    device_id = request.GET.get('device_id')
    all_time = request.GET.get('all')
    hours_param = request.GET.get('hours', '24')
    start_date = request.GET.get('start_date')
    end_date = request.GET.get('end_date')

    gen = cache.get(f"cache_gen:{request.user.id}", 0)
    cache_key = f"track:{request.user.id}:{gen}:{request.GET.urlencode()}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

    qs = Location.objects.filter(
        device__user=request.user,
        latitude__isnull=False,
        longitude__isnull=False,
    ).select_related('device').order_by('device_id', 'timestamp')

    cache_ttl = 60

    if start_date and end_date:
        try:
            start = datetime.fromisoformat(start_date).replace(hour=0, minute=0, second=0)
            end_dt = datetime.fromisoformat(end_date).replace(hour=23, minute=59, second=59)
            if timezone.is_naive(start):
                start = timezone.make_aware(start)
            if timezone.is_naive(end_dt):
                end_dt = timezone.make_aware(end_dt)
            qs = qs.filter(timestamp__gte=start, timestamp__lte=end_dt)
            if end_dt < timezone.now() - timedelta(hours=1):
                cache_ttl = 3600  # historical range, cache 1 hour
        except (ValueError, TypeError):
            pass
    elif not all_time:
        h = int(hours_param)
        qs = qs.filter(timestamp__gte=timezone.now() - timedelta(hours=h))
        if h >= 720:
            cache_ttl = 600
        elif h >= 168:
            cache_ttl = 300
        elif h >= 24:
            cache_ttl = 120

    if device_id:
        qs = qs.filter(device__device_id=device_id)

    # Group by device, then decimate each device's track independently
    devices_map = {}
    for loc in qs.values('id', 'latitude', 'longitude', 'timestamp', 'speed', 'city', 'state', 'country',
                          'flag', 'device__device_id', 'device__name').iterator(chunk_size=5000):
        did = loc['device__device_id']
        if did not in devices_map:
            devices_map[did] = {
                'id': did,
                'name': loc['device__name'] or did,
                'points': [],
            }
        devices_map[did]['points'].append({
            'c': [_jf(loc['longitude']), _jf(loc['latitude'])],
            'ts': int(loc['timestamp'].timestamp()),
            'id': loc['id'],
            'speed': _jf(loc['speed']),
            'city': loc['city'],
            'state': loc['state'],
            'country': loc['country'],
            'flag': loc['flag'],
        })

    result_devices = []
    for dev in devices_map.values():
        pts = dev['points']
        total = len(pts)

        if total <= 2:
            sampled = pts
        else:
            sampled = [pts[0]]
            anchor = pts[0]            # last point we kept; movement is measured from here
            last_kept_ts = pts[0]['ts']

            for i in range(1, total):
                prev = pts[i - 1]
                cur = pts[i]

                dt = max(0, cur['ts'] - prev['ts'])
                # Net displacement from the anchor decides "did I actually go
                # somewhere"; the step distance from the previous point only feeds
                # the speed fallback.
                dist_anchor_m = _haversine_km(anchor['c'][1], anchor['c'][0], cur['c'][1], cur['c'][0]) * 1000
                step_m = _haversine_km(prev['c'][1], prev['c'][0], cur['c'][1], cur['c'][0]) * 1000

                # The phone floors slow-walk speed to 0, so a stored 0 means
                # "unknown", not "stationary" — fall back to the computed speed in
                # that case so a stroll isn't mistaken for sitting still.
                speed_mps = cur.get('speed')
                if (speed_mps is None or speed_mps == 0) and dt > 0:
                    speed_mps = step_m / dt

                is_moving = (
                    dist_anchor_m >= MOVEMENT_DISTANCE_M
                    or (speed_mps is not None and speed_mps >= MOVEMENT_SPEED_MPS)
                )

                if is_moving:
                    sampled.append(cur)
                    anchor = cur
                    last_kept_ts = cur['ts']
                    continue

                if (
                    cur['ts'] - last_kept_ts >= STATIONARY_MIN_INTERVAL_S
                    or dt >= STATIONARY_GAP_FORCE_KEEP_S
                ):
                    sampled.append(cur)
                    anchor = cur
                    last_kept_ts = cur['ts']

            # Always include the final point so the line ends at the latest fix
            if sampled[-1] is not pts[-1]:
                sampled.append(pts[-1])

        if len(sampled) > MAX_POINTS:
            stride = max(1, len(sampled) // MAX_POINTS)
            sampled = sampled[::stride]
            if sampled[-1] is not pts[-1]:
                sampled.append(pts[-1])

        sampled = [
            {
                'c': p['c'],
                'ts': p['ts'],
                'id': p['id'],
                'speed': p['speed'],
                'city': p['city'],
                'state': p['state'],
                'country': p['country'],
                # Carried all the way from the query and then dropped here, which quietly
                # made the map's suspect styling dead code — it read a field that was never
                # in the payload, so every point looked normal.
                'flag': p['flag'],
            }
            for p in sampled
        ]

        result_devices.append({
            'id': dev['id'],
            'name': dev['name'],
            'total': total,
            'hidden': max(0, total - len(sampled)),
            'points': sampled,
        })

    payload = {'devices': result_devices}
    cache.set(cache_key, payload, timeout=cache_ttl)
    return JsonResponse(payload)


@login_required
def locations_api(request):
    """Get locations with spatial filtering."""
    try:
        return _locations_api_inner(request)
    except Exception as exc:
        import traceback
        logger.error("locations_api error: %s\n%s", exc, traceback.format_exc())
        return JsonResponse({"error": str(exc), "type": type(exc).__name__}, status=500)


def _locations_api_inner(request):
    device_id = request.GET.get("device_id")
    all_time = request.GET.get("all")
    limit = min(int(request.GET.get("limit", 5000)), 50000)
    offset = int(request.GET.get("offset", 0))
    sort_key = request.GET.get('sort_key', 'timestamp')
    sort_dir = request.GET.get('sort_dir', 'desc').lower()
    before_value = request.GET.get('before_value')
    before_id = request.GET.get('before_id')
    start_date = request.GET.get("start_date")
    end_date = request.GET.get("end_date")
    min_lng = request.GET.get("min_lng")
    min_lat = request.GET.get("min_lat")
    max_lng = request.GET.get("max_lng")
    max_lat = request.GET.get("max_lat")

    if sort_dir not in ("asc", "desc"):
        sort_dir = "desc"

    sort_config = {
        'timestamp': {'field': 'timestamp', 'kind': 'datetime'},
        'speed': {'field': 'sort_value', 'kind': 'number', 'source': 'speed'},
        'battery': {'field': 'sort_value', 'kind': 'number', 'source': 'battery'},
        'lat': {'field': 'sort_value', 'kind': 'number', 'source': 'latitude'},
        'lng': {'field': 'sort_value', 'kind': 'number', 'source': 'longitude'},
        'city': {'field': 'sort_value', 'kind': 'text', 'source': 'city'},
        'state': {'field': 'sort_value', 'kind': 'text', 'source': 'state'},
        'country_code': {'field': 'sort_value', 'kind': 'text', 'source': 'country_code'},
        'country': {'field': 'sort_value', 'kind': 'text', 'source': 'country'},
        'poi_name': {'field': 'sort_value', 'kind': 'text', 'source': 'poi_name'},
        'device': {'field': 'sort_value', 'kind': 'text', 'source': 'device_sort'},
    }
    if sort_key not in sort_config:
        sort_key = 'timestamp'
    config = sort_config[sort_key]
    sort_field = config['field']
    sort_source = config.get('source', sort_field)

    def _cursor_value_for_loc(loc):
        if sort_key == 'timestamp':
            return loc.timestamp.isoformat()
        value = getattr(loc, sort_source, None)
        if sort_key == 'device':
            value = loc.device.name or loc.device.device_id
        if config['kind'] == 'number':
            if value is None:
                return -1e308
            return _jf(value)
        return value or ''

    def _parse_cursor_value(raw):
        if raw is None:
            return None
        try:
            if config['kind'] == 'number':
                return float(raw)
            if sort_key == 'timestamp':
                dt = datetime.fromisoformat(raw)
                if timezone.is_naive(dt):
                    dt = timezone.make_aware(dt)
                return dt
            return str(raw)
        except (TypeError, ValueError):
            return None

    locations = Location.objects.filter(device__user=request.user)

    if start_date and end_date:
        try:
            start = datetime.fromisoformat(start_date).replace(hour=0, minute=0, second=0)
            end = datetime.fromisoformat(end_date).replace(hour=23, minute=59, second=59)
            if timezone.is_naive(start):
                start = timezone.make_aware(start)
            if timezone.is_naive(end):
                end = timezone.make_aware(end)
            locations = locations.filter(timestamp__gte=start, timestamp__lte=end)
        except (ValueError, TypeError):
            pass
    elif not all_time:
        hours = int(request.GET.get("hours", 24))
        since = timezone.now() - timedelta(hours=hours)
        locations = locations.filter(timestamp__gte=since)

    if device_id:
        locations = locations.filter(device__device_id=device_id)

    # Additional filter parameters
    q = request.GET.get('q')
    min_speed = request.GET.get('min_speed')
    max_speed = request.GET.get('max_speed')
    min_battery = request.GET.get('min_battery')
    max_battery = request.GET.get('max_battery')
    city = request.GET.get('city')
    state = request.GET.get('state')
    country_code = request.GET.get('country_code')

    if q:
        q = q.strip()
        # search across city/state/country
        locations = locations.filter(Q(city__icontains=q) | Q(state__icontains=q) | Q(country__icontains=q))

    try:
        if min_speed is not None and min_speed != '':
            locations = locations.filter(speed__gte=float(min_speed))
    except (ValueError, TypeError):
        pass
    try:
        if max_speed is not None and max_speed != '':
            locations = locations.filter(speed__lte=float(max_speed))
    except (ValueError, TypeError):
        pass
    try:
        if min_battery is not None and min_battery != '':
            locations = locations.filter(battery__gte=int(min_battery))
    except (ValueError, TypeError):
        pass
    try:
        if max_battery is not None and max_battery != '':
            locations = locations.filter(battery__lte=int(max_battery))
    except (ValueError, TypeError):
        pass

    if city:
        locations = locations.filter(city__iexact=city)
    if state:
        locations = locations.filter(state__iexact=state)
    if country_code:
        locations = locations.filter(country_code__iexact=country_code)

    # Annotate each point's Place name, up front so it can also be a sort key.
    # Prefer the per-point POI stored by the "Match POIs" job; fall back to the
    # POI of any dwell Visit that contains the point's timestamp.
    from django.db.models import Subquery, OuterRef, CharField as _CharField
    poi_subq = Visit.objects.filter(
        device_id=OuterRef('device_id'),
        start_time__lte=OuterRef('timestamp'),
        end_time__gte=OuterRef('timestamp'),
        poi__isnull=False,
    ).values('poi__name')[:1]
    locations = locations.annotate(
        poi_name=Coalesce('poi__name', Subquery(poi_subq, output_field=_CharField()), Value('')),
    )

    if sort_key == 'device':
        locations = locations.annotate(sort_value=Coalesce('device__name', 'device__device_id', Value('')))
    elif config['kind'] == 'text':
        locations = locations.annotate(sort_value=Coalesce(sort_source, Value('')))
    elif config['kind'] == 'number':
        locations = locations.annotate(sort_value=Coalesce(sort_source, Value(-1e308), output_field=FloatField()))

    if min_lng and min_lat and max_lng and max_lat:
        try:
            _min_lng, _min_lat = float(min_lng), float(min_lat)
            _max_lng, _max_lat = float(max_lng), float(max_lat)
            if HAS_POSTGIS and Polygon and hasattr(Location, 'location'):
                bbox = Polygon.from_bbox((_min_lng, _min_lat, _max_lng, _max_lat))
                locations = locations.filter(location__within=bbox)
            else:
                locations = locations.filter(
                    latitude__gte=_min_lat, latitude__lte=_max_lat,
                    longitude__gte=_min_lng, longitude__lte=_max_lng,
                )
        except (ValueError, TypeError):
            pass

    try:
        before_id_int = int(before_id) if before_id else None
    except (ValueError, TypeError):
        before_id_int = None

    sort_lookup = 'timestamp' if sort_key == 'timestamp' else 'sort_value'
    if sort_dir == 'asc':
        order_prefix = ''
        cursor_cmp = 'gt'
        tie_cmp = 'gt'
    else:
        order_prefix = '-'
        cursor_cmp = 'lt'
        tie_cmp = 'lt'

    cursor_value = _parse_cursor_value(before_value)
    if cursor_value is not None:
        cursor_filter = Q(**{f'{sort_lookup}__{cursor_cmp}': cursor_value})
        if before_id_int is not None:
            cursor_filter |= Q(**{sort_lookup: cursor_value}) & Q(**{f'id__{tie_cmp}': before_id_int})
        locations = locations.filter(cursor_filter)

    order_fields = [f'{order_prefix}{sort_lookup}', f'{order_prefix}id']

    locations = locations.select_related('device').order_by(*order_fields)
    page = list(locations[: limit + 1])
    has_more = len(page) > limit
    locations = page[:limit]

    # User-defined custom places override the Place column when a point falls
    # inside one. Resolved in-Python over this small page (a user has only a few
    # places) rather than as a spatial join; Place *sort* still uses poi_name.
    custom_places = list(CustomPlace.objects.filter(user=request.user))

    devices_data = {}
    locations_data = []
    last_cursor_ts = None
    last_cursor_id = None
    last_cursor_value = None
    for loc in locations:
        did = loc.device.device_id
        if did not in devices_data:
            devices_data[did] = {
                "device_id": did,
                "name": loc.device.name or did,
                "locations": [],
            }
        cp = _place_membership(custom_places, loc.latitude, loc.longitude) if custom_places else None
        custom_place = cp.name if cp else ''
        devices_data[did]["locations"].append({
            "id": loc.id,
            "lat": _jf(loc.latitude),
            "lng": _jf(loc.longitude),
            "timestamp": loc.timestamp.isoformat(),
            "altitude": _jf(loc.altitude),
            "accuracy": _jf(loc.accuracy),
            "speed": _jf(loc.speed),
            "battery": _jf(loc.battery),
            "city": loc.city,
            "state": loc.state,
            "country": loc.country,
            "country_code": loc.country_code,
            "place_name": loc.place_name,
            "poi_name": getattr(loc, 'poi_name', None) or '',
            "custom_place": custom_place,
            "flag": loc.flag,
        })
        locations_data.append({
            "id": loc.id,
            "device": loc.device.name or did,
            "lat": _jf(loc.latitude),
            "lng": _jf(loc.longitude),
            "timestamp": loc.timestamp.isoformat(),
            "altitude": _jf(loc.altitude),
            "accuracy": _jf(loc.accuracy),
            "speed": _jf(loc.speed),
            "battery": _jf(loc.battery),
            "city": loc.city,
            "state": loc.state,
            "country": loc.country,
            "country_code": loc.country_code,
            "place_name": loc.place_name,
            "poi_name": getattr(loc, 'poi_name', None) or '',
            "custom_place": custom_place,
            "flag": loc.flag,
        })
        # track last cursor (the last item in this page — remember results are desc)
        last_cursor_ts = loc.timestamp
        last_cursor_id = loc.id
        last_cursor_value = _cursor_value_for_loc(loc)

    resp = {"devices": list(devices_data.values()), "locations": locations_data, "sort_key": sort_key, "sort_dir": sort_dir, "has_more": has_more}
    if last_cursor_ts is not None and has_more:
        resp['next_before_value'] = last_cursor_value
        resp['next_before_id'] = last_cursor_id
    return JsonResponse(resp)


def _tile_coords(lat, lng, z):
    """Convert lat/lng to tile x/y at zoom z."""
    import math
    n = 2 ** z
    x = int((lng + 180) / 360 * n)
    lat_r = math.radians(lat)
    y = int((1 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2 * n)
    return x, max(0, min(n - 1, y))


@login_required
@require_POST
def seed_tiles(request):
    """Pre-generate and cache all tiles covering the user's data in the background."""
    if not HAS_POSTGIS:
        return JsonResponse({'error': 'PostGIS required'}, status=400)

    # Skip seeding for large time ranges — too many tiles, would hammer the DB
    # and starve real tile requests. Let them warm up on-demand instead.
    hours_param = request.GET.get('hours')
    if hours_param and int(hours_param) >= 168:
        return JsonResponse({'status': 'skipped'})

    qs_params = request.GET.urlencode()
    user_id = request.user.id

    # Get bounding box of user data
    from django.db.models import Min, Max
    agg = Location.objects.filter(device__user=request.user).aggregate(
        min_lat=Min('latitude'), max_lat=Max('latitude'),
        min_lng=Min('longitude'), max_lng=Max('longitude'),
    )
    if agg['min_lat'] is None:
        return JsonResponse({'tiles': 0})

    min_lat, max_lat = agg['min_lat'], agg['max_lat']
    min_lng, max_lng = agg['min_lng'], agg['max_lng']

    def do_seed():
        # Seed zoom levels 2-8 only — z9/z10 tile counts grow too large even for
        # moderate bounding boxes and compete with real requests on limited workers
        tiles_to_seed = []
        for z in range(2, 9):
            x0, y1 = _tile_coords(max_lat, min_lng, z)  # top-left
            x1, y0 = _tile_coords(min_lat, max_lng, z)  # bottom-right
            for tx in range(max(0, x0 - 1), x1 + 2):
                for ty in range(max(0, y0 - 1), y1 + 2):
                    tiles_to_seed.append((z, tx, ty))

        # Hard cap to avoid runaway seeding
        tiles_to_seed = tiles_to_seed[:500]

        from django.test import RequestFactory
        from django.contrib.auth.models import User
        factory = RequestFactory()
        user = User.objects.get(id=user_id)

        for z, tx, ty in tiles_to_seed:
            cache_key = f"tile:{user_id}:{z}:{tx}:{ty}:{qs_params}"
            if cache.get(cache_key) is not None:
                continue  # already cached
            # Build a fake request and call the view directly
            fake_req = factory.get(f'/api/tiles/{z}/{tx}/{ty}.pbf?' + qs_params)
            fake_req.user = user
            try:
                vector_tile(fake_req, z, tx, ty)
            except Exception:
                pass

    threading.Thread(target=do_seed, daemon=True).start()
    return JsonResponse({'status': 'seeding'})


@login_required
def vector_tile(request, z, x, y):
    """Serve Mapbox Vector Tiles generated by PostGIS ST_AsMVT."""
    if not HAS_POSTGIS:
        return HttpResponse(status=404)

    # Parse filter params (same as locations_api)
    device_id = request.GET.get("device_id")
    all_time = request.GET.get("all")
    start_date = request.GET.get("start_date")
    end_date = request.GET.get("end_date")
    hours = request.GET.get("hours", "24")

    params = {"z": z, "x": x, "y": y, "user_id": request.user.id}
    extra_where = []
    # Historical tiles (not recent) can be cached much longer
    cache_ttl = 30

    if start_date and end_date:
        try:
            start = datetime.fromisoformat(start_date).replace(hour=0, minute=0, second=0)
            end_dt = datetime.fromisoformat(end_date).replace(hour=23, minute=59, second=59)
            if timezone.is_naive(start):
                start = timezone.make_aware(start)
            if timezone.is_naive(end_dt):
                end_dt = timezone.make_aware(end_dt)
            extra_where.append("AND l.timestamp >= %(ts_start)s AND l.timestamp <= %(ts_end)s")
            params["ts_start"] = start
            params["ts_end"] = end_dt
            # Historical date ranges don't change — cache for 1 hour
            if end_dt < timezone.now() - timedelta(hours=1):
                cache_ttl = 3600
        except (ValueError, TypeError):
            pass
    elif all_time:
        # All-time tiles are expensive but stable — cache for 5 minutes
        cache_ttl = 300
    else:
        h = int(hours)
        since = timezone.now() - timedelta(hours=h)
        extra_where.append("AND l.timestamp >= %(since)s")
        params["since"] = since
        # Scale cache TTL with time range — large ranges change slowly
        if h >= 720:
            cache_ttl = 600   # 10 min for 30-day view
        elif h >= 168:
            cache_ttl = 300   # 5 min for 7-day view
        elif h >= 24:
            cache_ttl = 120   # 2 min for multi-day views

    if device_id:
        extra_where.append("AND d.device_id = %(device_id)s")
        params["device_id"] = device_id

    filter_clause = "\n              ".join(extra_where)

    # Check cache
    cache_key = f"tile:{request.user.id}:{z}:{x}:{y}:{request.GET.urlencode()}"
    cached = cache.get(cache_key)
    if cached is not None:
        response = HttpResponse(cached, content_type="application/x-protobuf")
        response["Cache-Control"] = f"public, max-age={cache_ttl}"
        return response

    # Spatial thinning via ST_SnapToGrid — keeps one point per grid cell so
    # coverage is always uniform with no gaps. Grid shrinks as zoom increases.
    if z <= 4:
        grid_deg = 0.5
    elif z <= 6:
        grid_deg = 0.1
    elif z <= 8:
        grid_deg = 0.02
    else:
        grid_deg = 0.0

    params["grid_deg"] = grid_deg

    if grid_deg > 0:
        thinning = f"""
        DISTINCT ON (ST_SnapToGrid(l.location::geometry, %(grid_deg)s))"""
        order_clause = f"ORDER BY ST_SnapToGrid(l.location::geometry, %(grid_deg)s), l.id"
    else:
        thinning = ""
        order_clause = ""

    sql = f"""
    WITH bounds AS (
        SELECT ST_TileEnvelope(%(z)s, %(x)s, %(y)s) AS geom,
               ST_Transform(ST_TileEnvelope(%(z)s, %(x)s, %(y)s), 4326) AS geom_4326
    ),
    thinned AS (
        SELECT {thinning}
            l.location, l.id, l.speed, l.battery, l.city, l.state, l.country,
            l.flag, l.timestamp, d.device_id, COALESCE(d.name, d.device_id) AS device_name
        FROM tracker_location l
        JOIN tracker_device d ON l.device_id = d.id
        CROSS JOIN bounds
        WHERE d.user_id = %(user_id)s
          AND l.location IS NOT NULL
          AND l.location::geometry && bounds.geom_4326
          AND ST_Intersects(l.location::geometry, bounds.geom_4326)
              {filter_clause}
        {order_clause}
        LIMIT 50000
    ),
    pts AS (
        SELECT
            ST_AsMVTGeom(ST_Transform(t.location::geometry, 3857), bounds.geom, 4096, 256, true) AS geom,
            t.id, t.speed, t.battery, t.city, t.state, t.country, t.flag,
            EXTRACT(EPOCH FROM t.timestamp)::bigint AS ts,
            t.device_id, t.device_name
        FROM thinned t CROSS JOIN bounds
    )
    SELECT ST_AsMVT(pts.*, 'locations') FROM pts;
    """

    with connection.cursor() as cursor:
        cursor.execute(sql, params)
        points_tile = cursor.fetchone()[0]

    tile_data = bytes(points_tile)

    if not tile_data:
        return HttpResponse(status=204)

    cache.set(cache_key, tile_data, timeout=cache_ttl)

    response = HttpResponse(tile_data, content_type="application/x-protobuf")
    response["Cache-Control"] = f"public, max-age={cache_ttl}"
    return response


@login_required
def locations_geojson_api(request):
    """Return locations as GeoJSON FeatureCollection."""
    device_id = request.GET.get("device_id")
    all_time = request.GET.get("all")
    limit = min(int(request.GET.get("limit", 5000)), 50000)

    locations = Location.objects.filter(device__user=request.user)

    if not all_time:
        hours = int(request.GET.get("hours", 24))
        since = timezone.now() - timedelta(hours=hours)
        locations = locations.filter(timestamp__gte=since)

    if device_id:
        locations = locations.filter(device__device_id=device_id)

    locations = locations.select_related('device').order_by('timestamp')[:limit]

    features = []
    for loc in locations:
        features.append({
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [_jf(loc.longitude), _jf(loc.latitude)]},
            "properties": {
                "timestamp": loc.timestamp.isoformat(),
                "device": loc.device.name or loc.device.device_id,
                "city": loc.city,
                "country": loc.country,
            },
        })

    return JsonResponse({"type": "FeatureCollection", "features": features})


# ---------------------------------------------------------------------------
# Stats & Visits API
# ---------------------------------------------------------------------------

# ── Reusable compute helpers ───────────────────────────────────────────────
# The heavy aggregations below are extracted so both the live API views and the
# nightly StatsSnapshot worker (tracker/stats_tasks.py) run identical logic.
# Each takes a prepared queryset / user and returns a plain dict — no request,
# no caching.

def _compute_overview_from_qs(locations, user):
    """Overall counts (points, countries, cities, states, devices, span)."""
    total = locations.count()
    countries = locations.exclude(country='').values('country').distinct().count()
    cities = locations.exclude(city='').values('city', 'country').distinct().count()
    states = locations.exclude(state='').values('state', 'country').distinct().count()
    devices = Device.objects.filter(user=user).count()
    first = locations.order_by('timestamp').values_list('timestamp', flat=True).first()
    last = locations.order_by('-timestamp').values_list('timestamp', flat=True).first()
    return {
        "total_points": total,
        "countries": countries,
        "cities": cities,
        "states": states,
        "devices": devices,
        "first_location": first.isoformat() if first else None,
        "last_location": last.isoformat() if last else None,
    }


def _compute_distance_from_qs(locations, granularity='daily'):
    """Gated daily/hourly distance buckets + total_km for a location queryset."""
    rows = locations.order_by('device', 'timestamp').values_list(
        'device_id', 'latitude', 'longitude', 'timestamp', 'accuracy'
    ).iterator(chunk_size=10000)
    bucket_km = defaultdict(float)
    total_km = 0.0
    for _dev_id, grp in groupby(rows, key=lambda r: r[0]):
        dev_points = ((lat, lon, ts, acc) for _d, lat, lon, ts, acc in grp)
        for cdt, km in _gated_distance_segments(dev_points):
            key = cdt.strftime('%Y-%m-%d %H') if granularity == "hourly" else cdt.strftime('%Y-%m-%d')
            bucket_km[key] += km
            total_km += km
    keys = sorted(bucket_km.keys())
    return {
        "days": keys,
        "distances": [round(bucket_km[k], 2) for k in keys],
        "total_km": round(total_km, 2),
    }


def _compute_transport_breakdown_from_qs(qs):
    """Distance and time split by transport mode.

    Distance reuses the same gated segments as distance_api, so the totals here
    agree with the headline number instead of being a second, rawer estimate.
    """
    from .transport_tasks import MODES

    per_mode = {m: {'km': 0.0, 'points': 0, 'seconds': 0.0} for m in MODES}
    unclassified = 0

    # order_by() is required: Location has Meta.ordering ['-timestamp'], which a
    # values_list(...).distinct() inherits — forcing timestamp into the SELECT and
    # breaking the DISTINCT, so this would yield one row per point rather than one
    # per device.
    for dev_id in qs.order_by().values_list('device_id', flat=True).distinct():
        rows = (
            qs.filter(device_id=dev_id)
            .order_by('timestamp', 'id')
            .values('latitude', 'longitude', 'timestamp', 'accuracy', 'transport_mode')
            .iterator(chunk_size=5000)
        )
        # Walk each run of same-mode points as its own track so the gated
        # distance never credits movement across a mode change.
        for mode, group in groupby(rows, key=lambda r: r['transport_mode']):
            g = list(group)
            if not mode:
                unclassified += len(g)
                continue
            bucket = per_mode.setdefault(mode, {'km': 0.0, 'points': 0, 'seconds': 0.0})
            bucket['points'] += len(g)
            if len(g) > 1:
                bucket['seconds'] += (g[-1]['timestamp'] - g[0]['timestamp']).total_seconds()
            pts = [(r['latitude'], r['longitude'], r['timestamp'], r['accuracy']) for r in g]
            # These runs are already classified *motion* (still/unclassified runs
            # are skipped above), so start MOVING — otherwise each run would drop
            # its leading ≤_DIST_EXIT_RADIUS_M and the per-mode totals would fall
            # short of the headline whole-track distance.
            for _ts, km in _gated_distance_segments(pts, initial_state='MOVING'):
                bucket['km'] += km

    modes = [
        {
            'mode': m,
            'km': round(v['km'], 2),
            'points': v['points'],
            'hours': round(v['seconds'] / 3600.0, 2),
        }
        for m, v in per_mode.items() if v['points']
    ]
    modes.sort(key=lambda d: -d['km'])
    return {'modes': modes, 'unclassified': unclassified}


def _get_snapshot_or_kick(user):
    """Return the user's StatsSnapshot if a finished computation exists, else
    trigger a background compute (once) and return None so the caller falls back
    to a live computation for this one request. This is the bridge that makes the
    Stats/Visits/Places pages instant: the all-time, unfiltered views read from
    the snapshot instead of recomputing the whole history live."""
    # Decide on the small status columns only; defer the heavy JSON blobs so the
    # common (snapshot-ready) request loads just the one field it serves (via
    # _snapshot_response's deferred attribute access) instead of all four.
    snap = (StatsSnapshot.objects.filter(user=user)
            .only('id', 'status', 'computed_at')
            .first())
    if snap and snap.status == 'done' and snap.computed_at:
        return snap
    try:
        from .stats_tasks import start_stats_compute
        start_stats_compute(user.id)
    except Exception:
        pass
    return None


def _snapshot_response(snap, field):
    """Serialize a snapshot JSON field with its freshness metadata attached."""
    payload = dict(getattr(snap, field) or {})
    payload['computed_at'] = snap.computed_at.isoformat() if snap.computed_at else None
    payload['source'] = 'snapshot'
    return JsonResponse(payload)


@login_required
def stats_api(request):
    """Overall statistics with time filtering."""
    device_id = request.GET.get("device_id")
    all_time = request.GET.get("all")
    start_date = request.GET.get("start_date")
    end_date = request.GET.get("end_date")

    # Default all-time view (no filters) → serve the precomputed snapshot instantly.
    if all_time and not device_id and not (start_date and end_date):
        snap = _get_snapshot_or_kick(request.user)
        if snap:
            return _snapshot_response(snap, 'stats_json')

    gen = cache.get(f"cache_gen:{request.user.id}", 0)
    cache_key = f"stats:{request.user.id}:{gen}:{request.GET.urlencode()}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

    locations = Location.objects.filter(device__user=request.user)

    if start_date and end_date:
        try:
            start = datetime.fromisoformat(start_date).replace(hour=0, minute=0, second=0)
            end = datetime.fromisoformat(end_date).replace(hour=23, minute=59, second=59)
            if timezone.is_naive(start):
                start = timezone.make_aware(start)
            if timezone.is_naive(end):
                end = timezone.make_aware(end)
            locations = locations.filter(timestamp__gte=start, timestamp__lte=end)
        except (ValueError, TypeError):
            pass
    elif not all_time:
        hours = int(request.GET.get("hours", 24))
        since = timezone.now() - timedelta(hours=hours)
        locations = locations.filter(timestamp__gte=since)

    if device_id:
        locations = locations.filter(device__device_id=device_id)

    result = _compute_overview_from_qs(locations, request.user)
    cache.set(cache_key, result, timeout=600)
    return JsonResponse(result)


@login_required
def countries_api(request):
    """Distinct visited country (and US state) codes for the scratch map."""
    gen = cache.get(f"cache_gen:{request.user.id}", 0)
    cache_key = f"countries:{request.user.id}:{gen}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

    qs = Location.objects.filter(device__user=request.user)
    rows = (
        qs.exclude(country_code__isnull=True).exclude(country_code='')
        .values('country_code', 'country').distinct()
    )
    names = {}
    for r in rows:
        cc = (r['country_code'] or '').upper()
        if cc:
            names.setdefault(cc, r['country'] or cc)

    # US states the user has set foot in (postal-abbrev or full name as stored).
    # order_by() clears Location's Meta.ordering ['-timestamp']; without it the
    # inherited ordering forces timestamp into the SELECT and breaks DISTINCT,
    # returning a duplicate state per matching point (and scanning the whole set).
    states = sorted(
        s for s in qs.filter(country_code__iexact='US')
        .exclude(state='').exclude(state__isnull=True)
        .order_by().values_list('state', flat=True).distinct()
        if s
    )

    result = {
        'codes': sorted(names.keys()),
        'names': names,
        'count': len(names),
        'states': states,
    }
    cache.set(cache_key, result, timeout=600)
    return JsonResponse(result)


# Fog of war grid: 0.001° ≈ 111m of latitude. Cells narrow toward the poles,
# which only makes the dedup finer — the client reveals a fixed metre radius.
_FOG_CELLS_PER_DEG = 1000


@login_required
def fog_api(request):
    """Distinct ~110m cells the user has ever been in, for the fog-of-war mask.

    Deliberately *not* keyed on cache_gen. Every push increments the gen, and a
    full-history DISTINCT is far too expensive to redo per push. All-time
    exploration barely moves in an hour, so a plain TTL is the right trade —
    same reasoning as StatsSnapshot being decoupled from the gen.
    """
    cache_key = f"fog:{request.user.id}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

    # order_by() clears Location's Meta.ordering ['-timestamp']; without it the
    # inherited ordering forces timestamp into the SELECT and breaks DISTINCT.
    rows = (
        Location.objects.filter(device__user=request.user)
        .annotate(
            gy=Cast(Round(F('latitude') * _FOG_CELLS_PER_DEG), IntegerField()),
            gx=Cast(Round(F('longitude') * _FOG_CELLS_PER_DEG), IntegerField()),
        )
        .order_by().values_list('gy', 'gx').distinct()
    )

    # Flat [y0, x0, y1, x1, ...] of grid indices — a third the JSON of objects,
    # and gzip compresses the runs well.
    cells = []
    for gy, gx in rows.iterator(chunk_size=10000):
        if gy is None or gx is None:
            continue
        cells.append(gy)
        cells.append(gx)

    result = {'cells': cells, 'count': len(cells) // 2, 'per_deg': _FOG_CELLS_PER_DEG}
    cache.set(cache_key, result, timeout=3600)
    return JsonResponse(result)


@login_required
def yearly_overview_api(request):
    """Yearly overview: week/month/year stats with comparisons and top places."""
    # Always all-time → serve the precomputed snapshot when available.
    snap = _get_snapshot_or_kick(request.user)
    if snap:
        return _snapshot_response(snap, 'yearly_json')

    gen = cache.get(f"cache_gen:{request.user.id}", 0)
    cache_key = f"yearly:{request.user.id}:{gen}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

    result = _compute_yearly_payload(request.user)
    cache.set(cache_key, result, timeout=1800)
    return JsonResponse(result)


def _compute_yearly_payload(user):
    """Week/month/year comparison stats, monthly breakdown, and top
    cities/countries/places (POI visits + custom places). Always all-time."""
    now = timezone.now()
    qs = Location.objects.filter(device__user=user)

    def _period_stats(start, end):
        period = qs.filter(timestamp__gte=start, timestamp__lt=end)
        pts = period.count()
        countries = period.exclude(country='').values('country').distinct().count()
        cities = period.exclude(city='').values('city', 'country').distinct().count()
        return {"points": pts, "countries": countries, "cities": cities}

    week_start = (now - timedelta(days=now.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
    last_week_start = week_start - timedelta(days=7)
    month_start = now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    last_month_start = (month_start - timedelta(days=1)).replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    year_start = now.replace(month=1, day=1, hour=0, minute=0, second=0, microsecond=0)
    last_year_start = year_start.replace(year=year_start.year - 1)

    this_week = _period_stats(week_start, now)
    last_week = _period_stats(last_week_start, week_start)
    this_month = _period_stats(month_start, now)
    last_month = _period_stats(last_month_start, month_start)
    this_year = _period_stats(year_start, now)
    last_year = _period_stats(last_year_start, year_start)

    # Monthly breakdown for this year
    monthly = []
    for m in range(1, now.month + 1):
        m_start = year_start.replace(month=m)
        m_end = year_start.replace(month=m + 1) if m < 12 else now.replace(year=now.year + 1, month=1, day=1)
        period = qs.filter(timestamp__gte=m_start, timestamp__lt=m_end)
        monthly.append({"month": m, "points": period.count()})

    # Top cities (all time)
    top_cities = list(
        qs.exclude(city='').values('city', 'country').annotate(count=Count('id')).order_by('-count')[:10]
    )
    top_countries = list(
        qs.exclude(country='').values('country').annotate(count=Count('id')).order_by('-count')[:10]
    )

    # Top POI places by visit count (from pre-computed Visit table)
    from .models import Visit as VisitModel
    from django.db.models import ExpressionWrapper, DurationField, F, Sum
    top_places_raw = list(
        VisitModel.objects.filter(device__user=user, poi__isnull=False)
        .values('poi_id', 'poi__name', 'poi__address')
        .annotate(
            visit_count=Count('id'),
            total_duration=Sum(ExpressionWrapper(
                F('end_time') - F('start_time'), output_field=DurationField()
            )),
        )
        .order_by('-visit_count')[:10]
    )
    top_places = []
    for p in top_places_raw:
        dur_s = int(p['total_duration'].total_seconds()) if p['total_duration'] else 0
        label = p['poi__name']
        if p['poi__address']:
            label = f"{p['poi__name']}, {p['poi__address']}"
        top_places.append({
            'name': label,
            'count': p['visit_count'],
            'total_time': dur_s,
        })

    # User-defined custom places count as visits too — a "visit" is a distinct
    # day with points inside the geofence; total_time is the dwell sum. Merged
    # into the same Top Places list and re-ranked.
    for p in CustomPlace.objects.filter(user=user):
        nearby = _find_nearby_locations(qs, p.latitude, p.longitude, p.radius_m)
        day_count = len(_group_by_day(nearby))
        if not day_count:
            continue
        top_places.append({
            'name': p.name,
            'count': day_count,
            'total_time': _calc_dwell_time(nearby),
            'is_custom': True,
        })
    top_places.sort(key=lambda x: -x['count'])
    top_places = top_places[:10]

    result = {
        "this_week": this_week, "last_week": last_week,
        "this_month": this_month, "last_month": last_month,
        "this_year": this_year, "last_year": last_year,
        "monthly_breakdown": monthly,
        "top_cities": top_cities,
        "top_countries": top_countries,
        "top_places": top_places,
        "year": now.year,
    }
    return result


def _haversine_km(lat1, lon1, lat2, lon2):
    """Calculate distance between two points in km using haversine formula."""
    R = 6371.0
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


# ── Distance accumulation (GPS-jitter resistant) ──────────────────────────────
# Summing the straight line between every consecutive fix turns a stationary
# device that logs rapidly (e.g. GPSLogger at 1 fix/sec) into thousands of phantom
# miles: each fix jitters by metres-to-tens-of-metres and that random walk piles
# up. Three-stage defence: (1) resample to a short time-window *centroid* —
# averaging N jittery fixes collapses their noise by ~√N, so a stationary
# cluster's centroids barely move; (2) *anchor-gate* the centroids — only credit
# movement that clears the GPS error bars, and don't let a stray fix walk the
# anchor off true position; (3) a STATIONARY↔MOVING *dwell* state machine — a
# parked phone whose position slowly wanders (and whose Doppler speed reads a
# nonzero ~1-2 mph, indistinguishable from a real slow walk) still oscillates
# around one centre. The gate alone can't catch that: each ~30-100 m drift hop
# clears it and walks the anchor along, integrating to miles. So credit is only
# released once the track *sustains* a departure well past a dwell radius (real
# travel) and is suppressed while it merely wanders in place.
_DIST_WINDOW_S = 30            # resample window: centroid of all fixes in each 30s bin
_DIST_MIN_GATE_M = 25.0        # ignore moves below this even with a perfect fix
_DIST_ACC_GATE_MULT = 2.5      # ...otherwise require 2.5× the (worse) reported accuracy
_DIST_DEFAULT_ACC_M = 15.0     # assumed accuracy when a fix reports none
_DIST_MAX_ACCURACY_M = 100.0   # drop fixes less accurate than this from the distance stream
_DIST_MAX_SPEED_MPS = 280.0    # ~1000 km/h: a faster segment is a GPS teleport, not travel
_DIST_GAP_RESET_S = 7200       # >2h between fixes → separate trip, re-anchor
# Dwell state machine (kills stationary-drift phantom miles without a speed filter):
_DIST_DWELL_RADIUS_M = 50.0    # inner "home/settled" radius (hysteresis low)
_DIST_EXIT_RADIUS_M = 150.0    # sustained net displacement to confirm a real departure (hysteresis high)
_DIST_EXIT_ACC_MULT = 2.5      # exit bar for coarse fixes: max(150, 2.5× worst accuracy)
_DIST_EXIT_CONFIRM_S = 60.0    # min continuous time outside dwell before an exit can confirm
_DIST_SETTLE_S = 180.0         # continuous in-radius dwell to declare arrival → STATIONARY
_DIST_DWELL_EMA_ALPHA = 0.2    # pull dwell/settle centre toward incoming centroids


def _seg_distance_m(anchor, pt):
    """Metres to credit for moving anchor→pt, and whether to advance the anchor.

    `anchor`/`pt` are ``(lat, lon, epoch_seconds, accuracy)``. Rejects
    sub-uncertainty jitter and teleport spikes. Returns
    ``(credit_metres, advance_anchor)``; the caller keeps the same anchor when
    ``advance_anchor`` is False so back-and-forth noise can't add.
    """
    a_lat, a_lon, a_ts, a_acc = anchor
    lat, lon, ts, acc = pt
    dt = ts - a_ts
    if dt <= 0 or dt > _DIST_GAP_RESET_S:
        return 0.0, True   # out of order / separate trip → re-anchor, count nothing
    d_m = _haversine_km(a_lat, a_lon, lat, lon) * 1000.0
    # Must clear the combined position uncertainty (and a hard floor): a "move"
    # smaller than your error bars is indistinguishable from noise.
    gate = max(_DIST_MIN_GATE_M,
               _DIST_ACC_GATE_MULT * max(a_acc or _DIST_DEFAULT_ACC_M, acc or _DIST_DEFAULT_ACC_M))
    if d_m < gate:
        return 0.0, False  # within noise → keep the anchor put
    if d_m / dt > _DIST_MAX_SPEED_MPS:
        return 0.0, False  # implausible jump → ignore the spike, keep the anchor
    return d_m, True       # real movement → count it and advance


def _gated_distance_segments(points, initial_state='STATIONARY'):
    """Yield ``(ts, km)`` for each credited travel segment of one device's track.

    `points` is a time-ordered iterable of ``(lat, lon, ts, accuracy)`` (``ts`` a
    datetime). Fixes are resampled to ``_DIST_WINDOW_S`` centroids to kill GPS
    jitter, then run through a STATIONARY↔MOVING dwell machine so a parked phone
    whose position slowly drifts credits ~0 while genuine travel counts in full.
    ``ts`` of each yield is the window's last fix time (for day/hour bucketing).

    While STATIONARY the ``anchor`` is the dwell centre: nothing is credited until
    the track *sustains* a departure past ``_DIST_EXIT_RADIUS_M`` for
    ``_DIST_EXIT_CONFIRM_S`` (then one straight-line ``anchor→exit`` is credited,
    so an in-place wander that eventually leaves counts only the net departure).
    While MOVING the anchor walks forward and each hop is credited via
    [_seg_distance_m] exactly as before (so full travel paths and round-trip legs
    are preserved), until the track settles within ``_DIST_DWELL_RADIUS_M`` for
    ``_DIST_SETTLE_S`` and snaps back to STATIONARY.

    `initial_state` lets callers that pre-split a track into already-classified
    *motion* runs (``_compute_transport_breakdown_from_qs``) start in ``'MOVING'``
    so each run credits from its first gated hop, keeping their totals in step with
    the headline whole-track pass, which uses the ``'STATIONARY'`` default.
    """
    state = initial_state    # 'STATIONARY' | 'MOVING'
    anchor = None            # (lat, lon, epoch, acc): dwell centre / last-credited pos
    exc_since = None         # epoch the track first went continuously outside the dwell radius
    settle_center = None     # (lat, lon) arrival candidate while MOVING
    settle_since = None      # epoch that candidate began
    win = None               # [win_id, sum_lat, sum_lon, sum_epoch, sum_acc, n, last_dt]

    def _finalize(w):
        nonlocal state, anchor, exc_since, settle_center, settle_since
        n = w[5]
        clat, clon, cep, cacc = w[1] / n, w[2] / n, w[3] / n, w[4] / n
        cdt = w[6]
        if anchor is None:
            anchor = (clat, clon, cep, cacc)
            return None

        # Gap / out-of-order reset applies in either state: a >2h gap is a new trip.
        dt = cep - anchor[2]
        if dt <= 0 or dt > _DIST_GAP_RESET_S:
            state = 'STATIONARY'
            anchor = (clat, clon, cep, cacc)
            exc_since = None
            settle_center = None
            settle_since = None
            return None

        worst_acc = max(anchor[3] or _DIST_DEFAULT_ACC_M, cacc or _DIST_DEFAULT_ACC_M)

        if state == 'STATIONARY':
            d = _haversine_km(anchor[0], anchor[1], clat, clon) * 1000.0
            if d < _DIST_DWELL_RADIUS_M:
                # Still inside the dwell neighbourhood: track the true centre so a
                # skewed first fix or a slowly-repositioned phone stays centred.
                a = _DIST_DWELL_EMA_ALPHA
                anchor = (anchor[0] + a * (clat - anchor[0]),
                          anchor[1] + a * (clon - anchor[1]),
                          cep,
                          anchor[3] + a * (cacc - anchor[3]))
                exc_since = None
                return None
            # Outside the dwell neighbourhood — a candidate departure.
            if exc_since is None:
                exc_since = cep
            rexit = max(_DIST_EXIT_RADIUS_M, _DIST_EXIT_ACC_MULT * worst_acc)
            sustained = (cep - exc_since) >= _DIST_EXIT_CONFIRM_S
            speed_ok = (d / dt) <= _DIST_MAX_SPEED_MPS
            if d >= rexit and sustained and speed_ok:
                # Confirmed real departure: credit the net move only, then travel.
                anchor = (clat, clon, cep, cacc)
                state = 'MOVING'
                exc_since = None
                settle_center = None
                settle_since = None
                return (cdt, d / 1000.0)
            return None  # outside but unconfirmed → hold the anchor, credit nothing

        # state == 'MOVING': credit each gated hop, and watch for arrival.
        credit_m, advance = _seg_distance_m(anchor, (clat, clon, cep, cacc))
        if advance:
            anchor = (clat, clon, cep, cacc)
        if (settle_center is None or
                _haversine_km(settle_center[0], settle_center[1], clat, clon) * 1000.0
                >= _DIST_DWELL_RADIUS_M):
            settle_center = (clat, clon)
            settle_since = cep
        else:
            a = _DIST_DWELL_EMA_ALPHA
            settle_center = (settle_center[0] + a * (clat - settle_center[0]),
                             settle_center[1] + a * (clon - settle_center[1]))
            if cep - settle_since >= _DIST_SETTLE_S:
                state = 'STATIONARY'
                anchor = (settle_center[0], settle_center[1], cep, cacc)
                exc_since = None
                settle_center = None
                settle_since = None
        return (cdt, credit_m / 1000.0) if credit_m else None

    for lat, lon, ts, acc in points:
        if acc is not None and acc > _DIST_MAX_ACCURACY_M:
            continue
        epoch = ts.timestamp()
        wid = int(epoch // _DIST_WINDOW_S)
        a = acc if acc is not None else _DIST_DEFAULT_ACC_M
        if win is None:
            win = [wid, lat, lon, epoch, a, 1, ts]
        elif wid == win[0]:
            win[1] += lat; win[2] += lon; win[3] += epoch; win[4] += a; win[5] += 1; win[6] = ts
        else:
            seg = _finalize(win)
            if seg:
                yield seg
            win = [wid, lat, lon, epoch, a, 1, ts]
    if win is not None:
        seg = _finalize(win)
        if seg:
            yield seg


@login_required
def distance_api(request):
    """Daily distance travelled, computed from sequential location points."""
    device_id = request.GET.get("device_id")
    all_time = request.GET.get("all")
    start_date = request.GET.get("start_date")
    end_date = request.GET.get("end_date")
    granularity = request.GET.get("granularity", "daily")

    # Default all-time daily view → serve the precomputed snapshot's distance.
    if all_time and granularity == "daily" and not device_id and not (start_date and end_date):
        snap = _get_snapshot_or_kick(request.user)
        if snap:
            dist = dict((snap.stats_json or {}).get('distance') or {})
            dist['computed_at'] = snap.computed_at.isoformat() if snap.computed_at else None
            dist['source'] = 'snapshot'
            return JsonResponse(dist)

    gen = cache.get(f"cache_gen:{request.user.id}", 0)
    cache_key = f"distance:{request.user.id}:{gen}:{request.GET.urlencode()}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

    locations = Location.objects.filter(device__user=request.user)

    if start_date and end_date:
        try:
            start = datetime.fromisoformat(start_date).replace(hour=0, minute=0, second=0)
            end = datetime.fromisoformat(end_date).replace(hour=23, minute=59, second=59)
            if timezone.is_naive(start):
                start = timezone.make_aware(start)
            if timezone.is_naive(end):
                end = timezone.make_aware(end)
            locations = locations.filter(timestamp__gte=start, timestamp__lte=end)
        except (ValueError, TypeError):
            pass
    elif not all_time:
        hours = int(request.GET.get("hours", 24))
        since = timezone.now() - timedelta(hours=hours)
        locations = locations.filter(timestamp__gte=since)

    if device_id:
        locations = locations.filter(device__device_id=device_id)

    result = _compute_distance_from_qs(locations, granularity)
    cache.set(cache_key, result, timeout=600)
    return JsonResponse(result)


# ── Location diagnostics ─────────────────────────────────────────────────────
# A Settings → Diagnostics tool for inspecting tracking health over a chosen device +
# time range: gaps between consecutive points (the clearest signal of dropouts), plus
# accuracy / reported-speed / implied-speed-between-points / altitude / battery ranges.

# Cap the single-pass analysis so a huge range can't OOM a worker; ~300k ≈ 30 days at a
# 10s interval. Beyond it the API asks the user to narrow the range.
_DIAG_MAX_POINTS = 300_000
# Number of equal-time buckets in the gap timeline sparkline (worst gap + point count
# per bucket across the range) — enough resolution to spot where tracking dropped.
_DIAG_TIMELINE_BUCKETS = 120
# Max bins in a single bar's drill-down line graph; per-minute bins coarsen past this.
_DETAIL_MAX_BINS = 300


def _fmt_duration(seconds):
    """Compact human duration ('45s', '3m 5s', '2h 10m', '1d 4h') from seconds."""
    seconds = int(round(seconds or 0))
    if seconds < 60:
        return f"{seconds}s"
    m, s = divmod(seconds, 60)
    if m < 60:
        return f"{m}m {s}s" if s else f"{m}m"
    h, m = divmod(m, 60)
    if h < 24:
        return f"{h}h {m}m" if m else f"{h}h"
    d, h = divmod(h, 24)
    return f"{d}d {h}h" if h else f"{d}d"


def _compute_location_diagnostics(qs, dev_names, window_start=None, window_end=None):
    """Diagnostic stats over a (user/device/time-filtered) Location queryset.

    Gaps and implied speed are **per device** — only consecutive points of the same
    device are compared — computed in one streaming pass grouped by device. `dev_names`
    maps Device.pk → display name for labelling the largest gaps.

    The gap/coverage timeline is bucketed across the **requested window**
    (`window_start`/`window_end`, e.g. the full 30 days the user asked for) rather than
    just the span of the returned data, so stretches with no points at all show up as
    empty (grey) buckets instead of being silently cropped out.
    """
    from itertools import groupby

    total = qs.count()
    if total == 0:
        return {"count": 0}
    if total > _DIAG_MAX_POINTS:
        return {"count": total, "error": "too_many_points", "limit": _DIAG_MAX_POINTS}

    # Global data span up front (cheap, indexed) — used for the headline stats. The
    # timeline, however, is bucketed across the *requested* window (falling back to the
    # data span for "all time", which has no explicit window).
    span_agg = qs.aggregate(tmin=Min('timestamp'), tmax=Max('timestamp'))
    ts_first, ts_last = span_agg['tmin'], span_agg['tmax']
    tl_start = window_start or ts_first
    tl_end = window_end or ts_last
    tl_span = (tl_end - tl_start).total_seconds() if tl_start and tl_end else 0
    tl_bucket = (tl_span / _DIAG_TIMELINE_BUCKETS) if tl_span > 0 else 0
    tl_maxgap = [0.0] * _DIAG_TIMELINE_BUCKETS   # worst gap starting in each time bucket
    tl_points = [0] * _DIAG_TIMELINE_BUCKETS     # point count per bucket (coverage)

    def _bucket(ts):
        if tl_bucket <= 0:
            return 0
        return max(0, min(_DIAG_TIMELINE_BUCKETS - 1,
                          int((ts - tl_start).total_seconds() / tl_bucket)))

    rows = qs.order_by('device_id', 'timestamp').values_list(
        'device_id', 'timestamp', 'accuracy', 'speed', 'altitude', 'battery',
        'latitude', 'longitude',
    ).iterator()
    acc_min = acc_max = None; acc_sum = 0.0; acc_n = 0; acc_missing = 0
    acc_u20 = acc_u50 = acc_o100 = 0
    spd_min = spd_max = None; spd_sum = 0.0; spd_n = 0; spd_missing = 0
    alt_min = alt_max = None; alt_sum = 0.0; alt_n = 0
    bat_min = bat_max = None
    gaps = []
    notable_gaps = []          # (seconds, prev_ts, cur_ts, device_id) for gaps > 60s
    n_over_60 = n_over_300 = n_over_900 = 0
    cspd_min = cspd_max = None; cspd_sum = 0.0; cspd_n = 0; cspd_max_at = None
    total_km = 0.0

    for dev_id, grp in groupby(rows, key=lambda r: r[0]):
        prev_ts = prev_lat = prev_lon = None
        dev_pts = []
        for (_d, ts, acc, spd, alt, bat, lat, lon) in grp:
            tl_points[_bucket(ts)] += 1
            if acc is None:
                acc_missing += 1
            else:
                acc_min = acc if acc_min is None else min(acc_min, acc)
                acc_max = acc if acc_max is None else max(acc_max, acc)
                acc_sum += acc; acc_n += 1
                if acc <= 20:
                    acc_u20 += 1
                if acc <= 50:
                    acc_u50 += 1
                if acc > 100:
                    acc_o100 += 1
            if spd is None:
                spd_missing += 1
            else:
                spd_min = spd if spd_min is None else min(spd_min, spd)
                spd_max = spd if spd_max is None else max(spd_max, spd)
                spd_sum += spd; spd_n += 1
            if alt is not None:
                alt_min = alt if alt_min is None else min(alt_min, alt)
                alt_max = alt if alt_max is None else max(alt_max, alt)
                alt_sum += alt; alt_n += 1
            if bat is not None:
                bat_min = bat if bat_min is None else min(bat_min, bat)
                bat_max = bat if bat_max is None else max(bat_max, bat)
            dev_pts.append((lat, lon, ts, acc))
            if prev_ts is not None:
                dt = (ts - prev_ts).total_seconds()
                if dt > 0:
                    gaps.append(dt)
                    b = _bucket(prev_ts)
                    if dt > tl_maxgap[b]:
                        tl_maxgap[b] = dt
                    if dt > 60:
                        n_over_60 += 1
                        notable_gaps.append((dt, prev_ts, ts, dev_id))
                    if dt > 300:
                        n_over_300 += 1
                    if dt > 900:
                        n_over_900 += 1
                    cs = (_haversine_km(prev_lat, prev_lon, lat, lon) * 1000.0) / dt
                    cspd_min = cs if cspd_min is None else min(cspd_min, cs)
                    if cspd_max is None or cs > cspd_max:
                        cspd_max = cs; cspd_max_at = ts
                    cspd_sum += cs; cspd_n += 1
            prev_ts, prev_lat, prev_lon = ts, lat, lon
        for _cdt, km in _gated_distance_segments(dev_pts):
            total_km += km

    gaps.sort()

    def _pct(p):
        return gaps[min(len(gaps) - 1, int(p * len(gaps)))] if gaps else None

    notable_gaps.sort(key=lambda r: -r[0])
    span_s = (ts_last - ts_first).total_seconds() if ts_first and ts_last else 0

    # Per-bucket coverage: actual points ÷ points expected at the typical cadence (the
    # median gap). Bounded to [0,1], so the timeline reads the same at any zoom level and
    # a single multi-day outlier gap can't crush the rest of the chart. 1.0 = tracked as
    # expected, 0 = no data in that slice.
    interval_s = max(_pct(0.5) or 0.0, 1.0)
    expected = max(tl_bucket / interval_s, 1.0) if tl_bucket > 0 else 1.0
    tl_coverage = [round(min(1.0, p / expected), 3) for p in tl_points]

    return {
        "count": total,
        "span": {"start": ts_first.isoformat(), "end": ts_last.isoformat(),
                 "seconds": span_s, "human": _fmt_duration(span_s)},
        "points_per_hour": round(total / (span_s / 3600.0), 1) if span_s > 0 else None,
        "distance_km": round(total_km, 2),
        "timeline": {
            "buckets": _DIAG_TIMELINE_BUCKETS,
            "bucket_seconds": round(tl_bucket, 1),
            "start": tl_start.isoformat() if tl_start else None,
            "end": tl_end.isoformat() if tl_end else None,
            "interval_s": round(interval_s, 1),
            "max_gap": [round(x) for x in tl_maxgap],
            "points": tl_points,
            "coverage": tl_coverage,
        },
        "gaps": {
            "min_s": gaps[0] if gaps else None,
            "max_s": gaps[-1] if gaps else None,
            "avg_s": round(sum(gaps) / len(gaps), 1) if gaps else None,
            "median_s": _pct(0.5),
            "p90_s": _pct(0.9),
            "over_60s": n_over_60, "over_300s": n_over_300, "over_900s": n_over_900,
            "top": [{"seconds": round(s), "human": _fmt_duration(s),
                     "start": a.isoformat(), "end": b.isoformat(),
                     "device": dev_names.get(d, str(d))} for (s, a, b, d) in notable_gaps[:5]],
        },
        "accuracy": {
            "min": round(acc_min, 1) if acc_min is not None else None,
            "max": round(acc_max, 1) if acc_max is not None else None,
            "avg": round(acc_sum / acc_n, 1) if acc_n else None,
            "missing": acc_missing,
            "under_20": acc_u20, "under_50": acc_u50, "over_100": acc_o100,
        },
        "speed": {
            "min_ms": round(spd_min, 3) if spd_min is not None else None,
            "max_ms": round(spd_max, 3) if spd_max is not None else None,
            "avg_ms": round(spd_sum / spd_n, 3) if spd_n else None,
            "missing": spd_missing,
        },
        "speed_between": {
            "min_ms": round(cspd_min, 3) if cspd_min is not None else None,
            "max_ms": round(cspd_max, 3) if cspd_max is not None else None,
            "avg_ms": round(cspd_sum / cspd_n, 3) if cspd_n else None,
            "max_at": cspd_max_at.isoformat() if cspd_max_at else None,
            "samples": cspd_n,
        },
        "altitude": {
            "min": round(alt_min, 1) if alt_min is not None else None,
            "max": round(alt_max, 1) if alt_max is not None else None,
            "avg": round(alt_sum / alt_n, 1) if alt_n else None,
            "missing": total - alt_n,
        },
        "battery": {
            "min": round(bat_min) if bat_min is not None else None,
            "max": round(bat_max) if bat_max is not None else None,
        },
    }


@login_required
def diagnostics_view(request):
    """Settings → Diagnostics page: inspect tracking health for a device + time range."""
    devices = Device.objects.filter(user=request.user)
    return render(request, 'tracker/diagnostics.html',
                  {'devices': devices, 'has_postgis': HAS_POSTGIS})


@login_required
def location_diagnostics_api(request):
    """Stats over the user's points for the chosen device + time range (see
    `_compute_location_diagnostics`). Mirrors `distance_api`'s filter parsing."""
    device_id = request.GET.get("device_id") or ""
    all_time = request.GET.get("all")
    start_date = request.GET.get("start_date")
    end_date = request.GET.get("end_date")

    qs = Location.objects.filter(device__user=request.user)
    rng = {}
    win_start = win_end = None   # requested window for the timeline (None for all-time)
    if start_date and end_date:
        try:
            start = datetime.fromisoformat(start_date).replace(hour=0, minute=0, second=0)
            end = datetime.fromisoformat(end_date).replace(hour=23, minute=59, second=59)
            if timezone.is_naive(start):
                start = timezone.make_aware(start)
            if timezone.is_naive(end):
                end = timezone.make_aware(end)
            qs = qs.filter(timestamp__gte=start, timestamp__lte=end)
            rng = {"start": start.isoformat(), "end": end.isoformat()}
            win_start, win_end = start, end
        except (ValueError, TypeError):
            pass
    elif not all_time:
        try:
            hours = int(request.GET.get("hours", 24))
        except (ValueError, TypeError):
            hours = 24
        now = timezone.now()
        since = now - timedelta(hours=hours)
        qs = qs.filter(timestamp__gte=since)
        rng = {"hours": hours}
        win_start, win_end = since, now
    else:
        rng = {"all": True}

    if device_id:
        qs = qs.filter(device__device_id=device_id)

    dev_names = {d.id: (d.name or d.device_id) for d in Device.objects.filter(user=request.user)}
    result = _compute_location_diagnostics(qs, dev_names, window_start=win_start, window_end=win_end)
    result["range"] = rng
    result["device_id"] = device_id or "all"
    return JsonResponse(result)


@login_required
def location_diagnostics_detail_api(request):
    """Drill-down for one timeline bar: point counts binned within [start, end].

    Bins are per-minute by default and only coarsen (to whole-minute multiples) once a
    window is wide enough that per-minute would blow past ``_DETAIL_MAX_BINS`` — so the
    line graph stays light for a multi-day bar but is literally points-per-minute for the
    common short bars. Also returns per-bin average accuracy for context.
    """
    start_s = request.GET.get("start")
    end_s = request.GET.get("end")
    device_id = request.GET.get("device_id") or ""
    try:
        start = datetime.fromisoformat(start_s)
        end = datetime.fromisoformat(end_s)
    except (ValueError, TypeError):
        return JsonResponse({"error": "bad_range"}, status=400)
    if timezone.is_naive(start):
        start = timezone.make_aware(start)
    if timezone.is_naive(end):
        end = timezone.make_aware(end)
    if end <= start:
        return JsonResponse({"error": "bad_range"}, status=400)

    span_s = (end - start).total_seconds()
    bin_s = 60
    if span_s / bin_s > _DETAIL_MAX_BINS:
        bin_s = int(math.ceil(span_s / _DETAIL_MAX_BINS / 60.0)) * 60
    nbins = int(math.ceil(span_s / bin_s))
    counts = [0] * nbins
    acc_sum = [0.0] * nbins
    acc_n = [0] * nbins

    qs = Location.objects.filter(device__user=request.user,
                                 timestamp__gte=start, timestamp__lte=end)
    if device_id:
        qs = qs.filter(device__device_id=device_id)
    start_epoch = start.timestamp()
    for ts, acc in qs.order_by('timestamp').values_list('timestamp', 'accuracy').iterator():
        b = int((ts.timestamp() - start_epoch) / bin_s)
        if b < 0 or b >= nbins:
            continue
        counts[b] += 1
        if acc is not None:
            acc_sum[b] += acc
            acc_n[b] += 1
    avg_acc = [round(acc_sum[i] / acc_n[i], 1) if acc_n[i] else None for i in range(nbins)]

    return JsonResponse({
        "start": start.isoformat(),
        "end": end.isoformat(),
        "bin_seconds": bin_s,
        "bins": counts,
        "avg_accuracy": avg_acc,
        "total": sum(counts),
    })


# ── Quality flag scanning ────────────────────────────────────────────────────

_FLAG_BAD_ACCURACY_M = 200.0   # fixes worse than this get flagged
_FLAG_MAX_SPEED_MPS  = 200.0   # ~720 km/h, computed between consecutive stored points
_FLAG_MAX_ALT_JUMP_M = 500.0   # altitude change in < 5 min triggers a flag

# Isolated-spike detection. The absolute speed bar above is ~720 km/h, so the outlier that
# actually happens — a stationary phone emitting one fix a couple of km away — sails under it
# at ~66 m/s, and lowering the bar far enough to catch it would flag every motorway drive and
# every flight. What separates a glitch from fast travel is that the track *comes back*: the
# point before and the point after are near each other, and only the middle one is far away.
_FLAG_SPIKE_SPEED_MPS = 15.0   # ~54 km/h implied, required both into and out of the point
_FLAG_SPIKE_MIN_M     = 200.0  # ...over at least this much displacement (ignore GPS jitter)
_FLAG_SPIKE_RETURN_M  = 200.0  # ...with the two neighbours this close to each other

# Stationary-drift detection. Some GPS chips, indoors, emit a wandering position *and* a
# garbage Doppler velocity while the device is parked — 34-48 mph reported from a phone that
# never left the house. DriftAnchor on the device can't suppress it (it assumes a stationary
# phone reports ~0 Doppler, and this chip reports the opposite), and `_is_spike` can't catch
# it either — the point isn't far from its neighbours, it's clustered *with* them. The tell is
# that the reported speed is far more than the tiny distance actually covered can support.
_FLAG_JITTER_SPEED_MPS = 8.0     # only bother when the reported speed clearly implies travel (~18 mph)
_FLAG_JITTER_MAX_DISP_M = 100.0  # ...while the point stayed within this of BOTH neighbours
_FLAG_JITTER_RATIO      = 0.4    # ...and moved under this fraction of what speed×dt implies


def _is_jitter(prev, cur, nxt):
    """Is `cur` a stationary-drift point — a high reported speed with almost no movement?

    True when the point's own reported speed is fast, yet it sits close to both neighbours and
    covered far less ground on each side than that speed over the elapsed time would require.
    Genuine travel fails it twice over: at any real speed the neighbours are hundreds of metres
    away, and the distance covered matches the speed. The absolute-distance gate is what keeps
    a lone Doppler glitch mid-journey (where the track is still visibly moving through) from
    being flagged — only a point clustered with both neighbours qualifies.
    """
    speed = cur.speed
    if speed is None or speed < _FLAG_JITTER_SPEED_MPS:
        return False
    dt_in  = (cur.timestamp - prev.timestamp).total_seconds()
    dt_out = (nxt.timestamp - cur.timestamp).total_seconds()
    if not (0 < dt_in < 3600 and 0 < dt_out < 3600):
        return False
    d_in  = _haversine_km(prev.latitude, prev.longitude, cur.latitude, cur.longitude) * 1000
    d_out = _haversine_km(cur.latitude, cur.longitude, nxt.latitude, nxt.longitude) * 1000
    if d_in >= _FLAG_JITTER_MAX_DISP_M or d_out >= _FLAG_JITTER_MAX_DISP_M:
        return False
    return (d_in < _FLAG_JITTER_RATIO * speed * dt_in and
            d_out < _FLAG_JITTER_RATIO * speed * dt_out)


def _is_spike(prev, cur, nxt):
    """Is `cur` an isolated out-and-back excursion between its two neighbours?

    True when the track leaves and returns fast (both implied speeds high) over a real
    distance, while `prev` and `nxt` stay close to each other. Genuine fast travel fails the
    last clause — a plane or a motorway keeps going, it does not come back to where it was.
    """
    dt_in  = (cur.timestamp - prev.timestamp).total_seconds()
    dt_out = (nxt.timestamp - cur.timestamp).total_seconds()
    if not (0 < dt_in < 3600 and 0 < dt_out < 3600):
        return False

    d_in  = _haversine_km(prev.latitude, prev.longitude, cur.latitude, cur.longitude) * 1000
    if d_in < _FLAG_SPIKE_MIN_M:
        return False
    d_out = _haversine_km(cur.latitude, cur.longitude, nxt.latitude, nxt.longitude) * 1000
    if d_out < _FLAG_SPIKE_MIN_M:
        return False
    if d_in / dt_in <= _FLAG_SPIKE_SPEED_MPS or d_out / dt_out <= _FLAG_SPIKE_SPEED_MPS:
        return False

    span = _haversine_km(prev.latitude, prev.longitude, nxt.latitude, nxt.longitude) * 1000
    return span < _FLAG_SPIKE_RETURN_M


def _flag_suspicious_locations(user, window_start=None, window_end=None):
    """Scan a user's locations (skipping user-accepted 'ok' ones) and mark any
    with bad accuracy, impossible speed between consecutive points, or extreme altitude
    jumps as 'suspect'. Returns the number of newly flagged points.

    When window_start/window_end are given, only points in that range are scanned
    (and only their existing 'suspect' flags are reset), leaving flags outside the
    window untouched."""
    qs = Location.objects.filter(device__user=user).exclude(flag='ok')
    if window_start is not None:
        qs = qs.filter(timestamp__gte=window_start)
    if window_end is not None:
        qs = qs.filter(timestamp__lte=window_end)
    qs.filter(flag='suspect').update(flag='', flag_reason='')

    to_update = []

    def finalize(loc):
        """Commit whatever reasons accumulated on `loc` once it can gain no more."""
        if loc is not None and loc._reasons:
            loc.flag        = 'suspect'
            loc.flag_reason = ','.join(loc._reasons)
            to_update.append(loc)

    # Sliding window of three consecutive same-device points. The spike rule needs the point
    # *after* the candidate as well as the one before, so a point is only final once its
    # successor has been read — but the stream still has to stay a stream, since a user's
    # whole history does not fit in memory.
    p0 = p1 = None

    for loc in (
        qs
        .select_related('device')
        .order_by('device_id', 'timestamp')
        .only('id', 'device_id', 'accuracy', 'altitude', 'speed',
              'latitude', 'longitude', 'timestamp', 'flag')
    ):
        loc._reasons = []

        if loc.accuracy is not None and loc.accuracy > _FLAG_BAD_ACCURACY_M:
            loc._reasons.append('accuracy')

        same_as_prev = p1 is not None and p1.device_id == loc.device_id
        if same_as_prev:
            dt = (loc.timestamp - p1.timestamp).total_seconds()
            if 0 < dt < 3600:
                dist_m = _haversine_km(p1.latitude, p1.longitude,
                                       loc.latitude, loc.longitude) * 1000
                if dist_m / dt > _FLAG_MAX_SPEED_MPS:
                    loc._reasons.append('speed')
                if (loc.altitude is not None and p1.altitude is not None
                        and abs(loc.altitude - p1.altitude) > _FLAG_MAX_ALT_JUMP_M
                        and dt < 300):
                    loc._reasons.append('altitude')
            # p1 now has both neighbours, so the checks that need a point on each side can
            # run and p1 can be closed out.
            if p0 is not None and p0.device_id == p1.device_id:
                if _is_spike(p0, p1, loc):
                    p1._reasons.append('spike')
                if _is_jitter(p0, p1, loc):
                    p1._reasons.append('jitter')

        finalize(p1)
        p0, p1 = p1, loc

    finalize(p1)

    if to_update:
        chunk = 500
        for i in range(0, len(to_update), chunk):
            Location.objects.bulk_update(to_update[i:i + chunk], ['flag', 'flag_reason'])

    return len(to_update)


@login_required
@require_http_methods(["POST"])
def flag_scan_api(request):
    """Run the suspicious-point scan for the current user, optionally scoped to a
    time window (?start_date & ?end_date, or ?hours=N, or ?all=1 for everything).
    Mirrors `location_diagnostics_api`'s filter parsing. Returns the count."""
    all_time = request.GET.get("all")
    start_date = request.GET.get("start_date")
    end_date = request.GET.get("end_date")
    win_start = win_end = None
    if start_date and end_date:
        try:
            start = datetime.fromisoformat(start_date).replace(hour=0, minute=0, second=0)
            end = datetime.fromisoformat(end_date).replace(hour=23, minute=59, second=59)
            if timezone.is_naive(start):
                start = timezone.make_aware(start)
            if timezone.is_naive(end):
                end = timezone.make_aware(end)
            win_start, win_end = start, end
        except (ValueError, TypeError):
            pass
    elif not all_time:
        try:
            hours = int(request.GET.get("hours", 24))
        except (ValueError, TypeError):
            hours = 24
        win_start = timezone.now() - timedelta(hours=hours)

    count = _flag_suspicious_locations(request.user, window_start=win_start, window_end=win_end)
    return JsonResponse({'status': 'ok', 'flagged': count})


@login_required
def flagged_locations_api(request):
    """Suspect locations for a given day (or week), with prev/next navigation.
    ?date=YYYY-MM-DD  — defaults to today
    ?group=day|week   — defaults to day
    Returns both the flagged-only list and a minimal all-points list for the map."""
    from datetime import date as _date, timedelta as _td, datetime as _dt, timezone as _tz

    group    = request.GET.get('group', 'day')
    date_str = request.GET.get('date', '')
    try:
        current = _dt.strptime(date_str, '%Y-%m-%d').date() if date_str else _date.today()
    except ValueError:
        current = _date.today()

    if group == 'week':
        start_d = current - _td(days=current.weekday())
        end_d   = start_d + _td(days=7)
        label   = f"Week of {start_d.strftime('%b %-d')}"
    else:
        start_d = current
        end_d   = current + _td(days=1)
        label   = current.strftime('%b %-d, %Y')

    t0 = _dt.combine(start_d, _dt.min.time()).replace(tzinfo=_tz.utc)
    t1 = _dt.combine(end_d,   _dt.min.time()).replace(tzinfo=_tz.utc)
    qs = Location.objects.filter(device__user=request.user, timestamp__gte=t0, timestamp__lt=t1)

    flagged_rows = list(
        qs.filter(flag='suspect').order_by('timestamp').values(
            'id', 'latitude', 'longitude', 'timestamp',
            'accuracy', 'speed', 'altitude', 'flag_reason',
            'city', 'state', 'country',
            'device__device_id', 'device__name',
        )
    )
    all_rows = list(qs.order_by('timestamp').values('id', 'latitude', 'longitude', 'flag'))

    # Find surrounding days that have flagged points for prev/next navigation
    flagged_dates = sorted(
        Location.objects.filter(device__user=request.user, flag='suspect')
                        .dates('timestamp', 'day', order='ASC')
    )
    prev_date = next_date = None
    for d in flagged_dates:
        if d < current:
            prev_date = d
        elif d > current and next_date is None:
            next_date = d

    return JsonResponse({
        'group':      group,
        'date':       current.isoformat(),
        'label':      label,
        'prev_date':  prev_date.isoformat() if prev_date else None,
        'next_date':  next_date.isoformat() if next_date else None,
        'flagged': [{
            'id':          f['id'],
            'lat':         _jf(f['latitude']),
            'lng':         _jf(f['longitude']),
            'timestamp':   f['timestamp'].isoformat(),
            'accuracy':    _jf(f['accuracy']),
            'speed':       _jf(f['speed']),
            'altitude':    _jf(f['altitude']),
            'flag_reason': f['flag_reason'],
            'city':        f['city'],
            'state':       f['state'],
            'country':     f['country'],
            'device':      f['device__name'] or f['device__device_id'],
        } for f in flagged_rows],
        'all_points': [{'id': p['id'], 'lat': _jf(p['latitude']),
                        'lng': _jf(p['longitude']), 'flag': p['flag']}
                       for p in all_rows],
    })


@login_required
@require_http_methods(["POST"])
def accept_flag_api(request, location_id):
    """Mark a flagged location as user-accepted so it won't be re-flagged on future scans."""
    updated = Location.objects.filter(
        id=location_id, device__user=request.user
    ).update(flag='ok', flag_reason='')
    if not updated:
        return JsonResponse({'error': 'Not found'}, status=404)
    _bust_user_cache(request.user.id)
    return JsonResponse({'status': 'ok'})


_VISITS_MAX_GAP_CROSS = 3600  # cap only when geocoded place label changes


def _visits_dwell_gap(prev, cur, level):
    """Seconds between two points for visit time attribution.

    prev/cur: (timestamp, city, state, country, country_code).
    Count the full interval when 
    still at the same place; cap at 1 hour when the
    label changes (e.g. travel between towns with sparse pings).
    """
    raw = (cur[0] - prev[0]).total_seconds()
    if raw <= 0:
        return 0
    if level == 'city':
        same = (prev[1], prev[2], prev[3], prev[4]) == (cur[1], cur[2], cur[3], cur[4])
    elif level == 'state':
        same = (prev[2], prev[3]) == (cur[2], cur[3])
    else:
        same = prev[3] == cur[3]
    return raw if same else min(raw, _VISITS_MAX_GAP_CROSS)


@login_required
def visits_api(request):
    """Aggregated city/state/country visit statistics."""
    device_id = request.GET.get("device_id")

    # Default all-time view (no device filter) → serve the precomputed snapshot.
    if not device_id:
        snap = _get_snapshot_or_kick(request.user)
        if snap:
            return _snapshot_response(snap, 'visits_json')

    gen = cache.get(f"cache_gen:{request.user.id}", 0)
    cache_key = f"visits:{request.user.id}:{gen}:{request.GET.urlencode()}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

    locations = Location.objects.filter(device__user=request.user).exclude(city='')
    if device_id:
        locations = locations.filter(device__device_id=device_id)

    result = _compute_visits_from_qs(locations)
    cache.set(cache_key, result, timeout=600)
    return JsonResponse(result)


def _compute_visits_from_qs(locations):
    """Build the cities/states/countries visit payload (counts + dwell time)
    from a prepared, city-excluded Location queryset."""
    city_stats = locations.values('city', 'state', 'country', 'country_code').annotate(
        count=Count('id'), first_seen=Min('timestamp'), last_seen=Max('timestamp')
    ).order_by('-count')

    cities = [{
        "city": s['city'], "state": s['state'],
        "country": s['country'], "country_code": s['country_code'],
        "visit_count": s['count'],
        "first_seen": s['first_seen'].isoformat() if s['first_seen'] else None,
        "last_seen": s['last_seen'].isoformat() if s['last_seen'] else None,
    } for s in city_stats]

    country_stats = locations.values('country', 'country_code').annotate(
        location_count=Count('id'),
        city_count=Count('city', distinct=True),
        state_count=Count('state', distinct=True),
    ).order_by('-location_count')

    countries = [{
        "country": s['country'], "country_code": s['country_code'],
        "location_count": s['location_count'],
        "city_count": s['city_count'], "state_count": s['state_count'],
    } for s in country_stats]

    state_stats = locations.values('state', 'country').annotate(
        count=Count('id'), city_count=Count('city', distinct=True)
    ).order_by('-count')

    states = [{
        "state": s['state'], "country": s['country'],
        "location_count": s['count'], "city_count": s['city_count'],
    } for s in state_stats if s['state']]

    # Time spent per city/state/country — attribute each gap to the earlier point's place
    time_city = defaultdict(float)
    time_state = defaultdict(float)
    time_country = defaultdict(float)

    points = locations.order_by('timestamp').values_list(
        'timestamp', 'city', 'state', 'country', 'country_code',
    )
    prev = None
    for ts, city, state_val, country_val, cc in points.iterator():
        cur = (ts, city, state_val, country_val, cc)
        if prev:
            if prev[1]:
                gap = _visits_dwell_gap(prev, cur, 'city')
                if gap > 0:
                    time_city[(prev[1], prev[2], prev[3], prev[4])] += gap
            if prev[2]:
                gap = _visits_dwell_gap(prev, cur, 'state')
                if gap > 0:
                    time_state[(prev[2], prev[3])] += gap
            if prev[3]:
                gap = _visits_dwell_gap(prev, cur, 'country')
                if gap > 0:
                    time_country[prev[3]] += gap
        prev = cur

    # Attach time_spent to existing result lists
    for c in cities:
        key = (c['city'], c['state'], c['country'], c['country_code'])
        c['time_spent'] = round(time_city.get(key, 0))

    for s in states:
        key = (s['state'], s['country'])
        s['time_spent'] = round(time_state.get(key, 0))

    for c in countries:
        c['time_spent'] = round(time_country.get(c['country'], 0))

    return {"cities": cities, "states": states, "countries": countries}


# ---------------------------------------------------------------------------
# Trips API
# ---------------------------------------------------------------------------

def _is_trip_member(trip, user):
    if trip.device.user == user:
        return True
    return AdventureMember.objects.filter(adventure=trip, user=user).exists()


def _get_trip_for_user(trip_id, user):
    trip = get_object_or_404(Adventure, id=trip_id)
    if not _is_trip_member(trip, user):
        from django.http import Http404
        raise Http404
    return trip


def _body_word_count(body):
    """Count words across all text-bearing blocks in a document body list."""
    words = 0
    for block in body:
        btype = block.get('type')
        content = block.get('content', {})
        if btype in ('heading', 'paragraph', 'callout'):
            words += len(content.get('text', '').split())
    return words


@login_required
def trips_api(request):
    owned_ids = Adventure.objects.filter(device__user=request.user).values_list('id', flat=True)
    member_ids = AdventureMember.objects.filter(user=request.user).values_list('adventure_id', flat=True)
    all_ids = set(list(owned_ids) + list(member_ids))
    trips = Adventure.objects.filter(id__in=all_ids).select_related('device').order_by('-start_time')
    data = []
    for trip in trips:
        loc_count = trip.locations.count()
        member_count = trip.members.count()
        is_creator = trip.device.user == request.user or (trip.creator == request.user)
        word_count = _body_word_count(trip.body or [])
        read_time_min = max(1, round(word_count / 200)) if word_count else 0
        data.append({
            "id": trip.id,
            "name": trip.name,
            "description": trip.description,
            "subtitle": trip.subtitle,
            "device": trip.device.name or trip.device.device_id,
            "start_time": trip.start_time.isoformat(),
            "end_time": trip.end_time.isoformat(),
            "location_count": loc_count,
            "member_count": member_count,
            "is_public": bool(trip.public_slug),
            "is_creator": is_creator,
            "cover_thumbnail": trip.cover_image_thumbnail.url if trip.cover_image_thumbnail else None,
            "word_count": word_count,
            "read_time_min": read_time_min,
        })
    return JsonResponse({"trips": data})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def create_trip(request):
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)

    device_id = data.get('device_id')
    if not device_id:
        return JsonResponse({"error": "device_id required"}, status=400)

    try:
        device = Device.objects.get(user=request.user, device_id=device_id)
    except Device.DoesNotExist:
        return JsonResponse({"error": "Device not found"}, status=404)

    try:
        start_raw = data.get('start_time', '').strip()
        if not start_raw:
            return JsonResponse({"error": "Start date is required"}, status=400)
        start = datetime.fromisoformat(start_raw.replace('Z', '+00:00'))
        if timezone.is_naive(start):
            start = timezone.make_aware(start)

        end_raw = data.get('end_time', '').strip()
        if end_raw:
            end = datetime.fromisoformat(end_raw.replace('Z', '+00:00'))
            # Date-only: set to end of day
            if len(end_raw) <= 10:
                end = end.replace(hour=23, minute=59, second=59)
            if timezone.is_naive(end):
                end = timezone.make_aware(end)
        else:
            # No end date — open-ended adventure, far future so all points are captured
            end = timezone.make_aware(datetime(2099, 12, 31, 23, 59, 59))
    except (KeyError, ValueError) as e:
        return JsonResponse({"error": f"Invalid dates: {e}"}, status=400)

    trip = Adventure.objects.create(
        device=device,
        creator=request.user,
        name=data.get('name', 'Untitled Adventure'),
        description=data.get('description', ''),
        start_time=start,
        end_time=end,
    )
    AdventureMember.objects.create(adventure=trip, user=request.user, role='creator')

    return JsonResponse({"status": "ok", "trip_id": trip.id})


def _calculate_time_spent(trip_locations, place_lat, place_lng, place_radius_m):
    """Calculate time spent near a place based on location points within radius."""
    nearby_timestamps = []
    for loc in trip_locations:
        dist_km = _haversine_km(loc.latitude, loc.longitude, place_lat, place_lng)
        if dist_km * 1000 <= place_radius_m:
            nearby_timestamps.append(loc.timestamp)

    if len(nearby_timestamps) < 2:
        return len(nearby_timestamps) * 60  # 1 min per single point

    nearby_timestamps.sort()
    total_seconds = 0
    for i in range(1, len(nearby_timestamps)):
        gap = (nearby_timestamps[i] - nearby_timestamps[i - 1]).total_seconds()
        if gap <= 7200:  # skip gaps > 2 hours
            total_seconds += gap
    return int(total_seconds)


def _format_duration(seconds):
    """Format seconds into human-readable duration."""
    if seconds < 60:
        return f"{seconds}s"
    minutes = seconds // 60
    if minutes < 60:
        return f"{minutes}m"
    hours = minutes // 60
    remaining_min = minutes % 60
    if hours < 24:
        return f"{hours}h {remaining_min}m" if remaining_min else f"{hours}h"
    days = hours // 24
    remaining_hours = hours % 24
    return f"{days}d {remaining_hours}h" if remaining_hours else f"{days}d"


@login_required
def trip_detail(request, trip_id):
    try:
        return _trip_detail_inner(request, trip_id)
    except Exception as exc:
        import traceback
        logger.error("trip_detail error: %s\n%s", exc, traceback.format_exc())
        return JsonResponse({"error": str(exc), "type": type(exc).__name__}, status=500)


def _trip_detail_inner(request, trip_id):
    trip = _get_trip_for_user(trip_id, request.user)
    LOCATION_LIMIT = 30000
    location_qs = trip.locations
    total_count = location_qs.count()
    locations = list(location_qs[:LOCATION_LIMIT])
    locs = [{
        "lat": _jf(l.latitude), "lng": _jf(l.longitude),
        "timestamp": l.timestamp.isoformat(),
        "city": l.city, "country": l.country,
        "speed": _jf(l.speed),
    } for l in locations]

    # POIs are located blurbs; surface them as `places` so inline pin refs and
    # location_card blocks (which reference blurb ids) resolve.
    places = []
    for b in trip.blurbs.all():
        if b.latitude is None or b.longitude is None:
            continue
        places.append({
            "id": b.id,
            "name": b.title or "",
            "latitude": b.latitude,
            "longitude": b.longitude,
            "notes": b.text,
        })

    # Photo store: photos live on blurbs; photo_grid body blocks reference them
    # by id. Expose a flat {id: {url, thumb}} map so the editor can resolve them.
    photos_map = {}
    for b in trip.blurbs.prefetch_related('photos'):
        for p in b.photos.all():
            photos_map[p.id] = {
                "url": p.image.url,
                "thumb": p.thumbnail.url if p.thumbnail else p.image.url,
            }

    # Planned stops (forward-looking itinerary) — ordered.
    planned_stops = [_planned_stop_payload(s) for s in trip.planned_stops.all()]

    is_creator = trip.device.user == request.user or trip.creator == request.user
    owner_user = trip.device.user
    members = []
    member_locations = {}
    for m in trip.members.select_related('user'):
        members.append({
            "user_id": m.user.id,
            "username": m.user.username,
            "role": m.role,
            "avatar": _get_user_avatar(m.user),
        })
        if m.user != owner_user:
            member_locs = list(
                Location.objects.filter(
                    device__user=m.user,
                    timestamp__gte=trip.start_time,
                    timestamp__lte=trip.end_time,
                ).order_by('timestamp')[:LOCATION_LIMIT]
            )
            if member_locs:
                member_locations[m.user.username] = [{
                    "lat": _jf(l.latitude), "lng": _jf(l.longitude),
                    "timestamp": l.timestamp.isoformat(),
                    "speed": _jf(l.speed),
                } for l in member_locs]

    return JsonResponse({
        "id": trip.id,
        "name": trip.name,
        "description": trip.description,
        "subtitle": trip.subtitle,
        "body": trip.body or [],
        "cover_image": trip.cover_image.url if trip.cover_image else None,
        "cover_thumbnail": trip.cover_image_thumbnail.url if trip.cover_image_thumbnail else None,
        "device_name": trip.device.name or trip.device.device_id,
        "start_time": trip.start_time.isoformat(),
        "end_time": trip.end_time.isoformat(),
        "locations": locs,
        "total_location_count": total_count,
        "places": places,
        "planned_stops": planned_stops,
        "photos": photos_map,
        "is_creator": is_creator,
        "is_public": bool(trip.public_slug),
        "public_slug": trip.public_slug,
        "access_pin": trip.access_pin,
        "invite_token": trip.invite_token,
        "invite_url": request.build_absolute_uri(f"/adventure/join/{trip.invite_token}/") if trip.invite_token else None,
        "members": members,
        "member_locations": member_locations,
    })


@login_required
@csrf_exempt
@require_http_methods(["POST", "DELETE"])
def delete_trip(request, trip_id):
    trip = get_object_or_404(Adventure, id=trip_id, device__user=request.user)
    trip.delete()
    return JsonResponse({"status": "ok"})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def update_trip(request, trip_id):
    trip = _get_trip_for_user(trip_id, request.user)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    if 'description' in data:
        trip.description = data['description']
    if 'name' in data:
        trip.name = data['name']
    if 'subtitle' in data:
        trip.subtitle = data['subtitle'][:400]
    if 'start_time' in data and data['start_time']:
        try:
            start = datetime.fromisoformat(data['start_time'].replace('Z', '+00:00'))
            trip.start_time = timezone.make_aware(start) if timezone.is_naive(start) else start
        except (ValueError, TypeError):
            pass
    if 'end_time' in data:
        raw = (data['end_time'] or '').strip()
        if raw:
            try:
                end = datetime.fromisoformat(raw.replace('Z', '+00:00'))
                trip.end_time = timezone.make_aware(end) if timezone.is_naive(end) else end
            except (ValueError, TypeError):
                pass
        else:
            trip.end_time = timezone.make_aware(datetime(2099, 12, 31, 23, 59, 59))
    if 'device_id' in data and data['device_id']:
        try:
            device = Device.objects.get(user=request.user, device_id=data['device_id'])
            trip.device = device
        except Device.DoesNotExist:
            pass
    if 'public_slug' in data and trip.public_slug is not None:
        new_slug = (data['public_slug'] or '').strip().lower()
        new_slug = ''.join(c for c in new_slug if c.isalnum() or c in '-_')[:64]
        if new_slug and new_slug != trip.public_slug:
            if not Adventure.objects.filter(public_slug=new_slug).exclude(id=trip.id).exists():
                trip.public_slug = new_slug
            else:
                return JsonResponse({"error": "That link is already taken"}, status=400)
    if 'access_pin' in data:
        raw_pin = (data['access_pin'] or '').strip()
        trip.access_pin = raw_pin[:20]
    trip.save()
    return JsonResponse({"status": "ok", "public_slug": trip.public_slug})


def _poi_payload(b):
    return {
        "id": b.id,
        "name": b.title or "",
        "latitude": b.latitude,
        "longitude": b.longitude,
        "notes": b.text,
    }


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def create_trip_place(request, trip_id):
    """Create a POI (a located AdventureBlurb) on an adventure."""
    trip = _get_trip_for_user(trip_id, request.user)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)

    lat = data.get('latitude')
    lng = data.get('longitude')
    if lat is None or lng is None:
        return JsonResponse({"error": "latitude and longitude required"}, status=400)

    blurb = AdventureBlurb.objects.create(
        adventure=trip,
        author=request.user,
        title=(data.get('name') or '')[:200],
        text=data.get('notes', ''),
        latitude=float(lat),
        longitude=float(lng),
    )
    return JsonResponse({"status": "ok", "place": _poi_payload(blurb)})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def update_trip_place(request, trip_id, place_id):
    """Update a POI (AdventureBlurb identified by place_id)."""
    trip = _get_trip_for_user(trip_id, request.user)
    blurb = get_object_or_404(AdventureBlurb, id=place_id, adventure=trip)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)

    if 'name' in data:
        blurb.title = (data['name'] or '')[:200]
    if 'notes' in data:
        blurb.text = data['notes'] or ''
    if data.get('latitude') is not None:
        blurb.latitude = float(data['latitude'])
    if data.get('longitude') is not None:
        blurb.longitude = float(data['longitude'])
    blurb.save()
    return JsonResponse({"status": "ok", "place": _poi_payload(blurb)})


@login_required
@csrf_exempt
@require_http_methods(["POST", "DELETE"])
def delete_trip_place(request, trip_id, place_id):
    trip = _get_trip_for_user(trip_id, request.user)
    blurb = get_object_or_404(AdventureBlurb, id=place_id, adventure=trip)
    blurb.delete()
    return JsonResponse({"status": "ok"})


# ---------------------------------------------------------------------------
# Trip Planning API (PlannedStop — forward-looking itinerary)
# ---------------------------------------------------------------------------

def _planned_stop_payload(stop):
    return {
        "id": stop.id,
        "name": stop.name,
        "latitude": _jf(stop.latitude),
        "longitude": _jf(stop.longitude),
        "location_name": stop.location_name,
        "arrival_date": stop.arrival_date.isoformat() if stop.arrival_date else None,
        "nights": stop.nights,
        "transport": stop.transport,
        "notes": stop.notes,
        "accommodation": stop.accommodation,
        "order": stop.order,
    }


def _apply_planned_stop_fields(stop, data):
    """Copy editable fields from a request dict onto a PlannedStop (unsaved)."""
    if 'name' in data:
        stop.name = (data['name'] or '')[:200]
    if 'location_name' in data:
        stop.location_name = (data['location_name'] or '')[:300]
    if 'latitude' in data:
        stop.latitude = _safe_float(data['latitude']) if data['latitude'] is not None else None
    if 'longitude' in data:
        stop.longitude = _safe_float(data['longitude']) if data['longitude'] is not None else None
    if 'arrival_date' in data:
        stop.arrival_date = _parse_date_only(data['arrival_date'])
    if 'nights' in data:
        try:
            stop.nights = max(0, int(data['nights']))
        except (TypeError, ValueError):
            stop.nights = 0
    if 'transport' in data:
        stop.transport = (data['transport'] or '')[:30]
    if 'notes' in data:
        stop.notes = data['notes'] or ''
    if 'accommodation' in data:
        stop.accommodation = (data['accommodation'] or '')[:300]
    if 'order' in data:
        try:
            stop.order = max(0, int(data['order']))
        except (TypeError, ValueError):
            pass


@login_required
@require_http_methods(["GET"])
def trip_plan_list(request, trip_id):
    """List the planned stops (itinerary) for an adventure."""
    trip = _get_trip_for_user(trip_id, request.user)
    stops = [_planned_stop_payload(s) for s in trip.planned_stops.all()]
    return JsonResponse({"planned_stops": stops})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def create_trip_plan_stop(request, trip_id):
    """Create a planned itinerary stop on an adventure."""
    trip = _get_trip_for_user(trip_id, request.user)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    name = (data.get('name') or '').strip()
    if not name:
        return JsonResponse({"error": "name required"}, status=400)
    stop = PlannedStop(adventure=trip)
    _apply_planned_stop_fields(stop, data)
    # Default order to the end of the itinerary when the client didn't supply one.
    if 'order' not in data:
        last = trip.planned_stops.order_by('-order').first()
        stop.order = (last.order + 1) if last else 0
    stop.save()
    return JsonResponse({"status": "ok", "stop": _planned_stop_payload(stop)})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def update_trip_plan_stop(request, trip_id, stop_id):
    """Update a planned itinerary stop (also handles reorder via `order`)."""
    trip = _get_trip_for_user(trip_id, request.user)
    stop = get_object_or_404(PlannedStop, id=stop_id, adventure=trip)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    _apply_planned_stop_fields(stop, data)
    stop.save()
    return JsonResponse({"status": "ok", "stop": _planned_stop_payload(stop)})


@login_required
@csrf_exempt
@require_http_methods(["POST", "DELETE"])
def delete_trip_plan_stop(request, trip_id, stop_id):
    trip = _get_trip_for_user(trip_id, request.user)
    stop = get_object_or_404(PlannedStop, id=stop_id, adventure=trip)
    stop.delete()
    return JsonResponse({"status": "ok"})


# ---------------------------------------------------------------------------
# Trip invites (open join by link, optional email)
# ---------------------------------------------------------------------------

def _ensure_invite_token(trip):
    """Create the invite token if missing; return it."""
    if not trip.invite_token:
        import secrets as _secrets
        trip.invite_token = _secrets.token_urlsafe(18)[:32]
        trip.save(update_fields=['invite_token'])
    return trip.invite_token


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_invite_api(request, trip_id):
    """Create (or rotate) the invite link. Any member may share it."""
    trip = _get_trip_for_user(trip_id, request.user)
    try:
        data = json.loads(request.body or '{}')
    except json.JSONDecodeError:
        data = {}
    if data.get('rotate'):
        import secrets as _secrets
        trip.invite_token = _secrets.token_urlsafe(18)[:32]
        trip.save(update_fields=['invite_token'])
    else:
        _ensure_invite_token(trip)
    url = request.build_absolute_uri(f"/adventure/join/{trip.invite_token}/")
    return JsonResponse({"status": "ok", "invite_token": trip.invite_token, "invite_url": url})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_invite_email_api(request, trip_id):
    """Email the invite link to an address (requires SMTP configured)."""
    trip = _get_trip_for_user(trip_id, request.user)
    try:
        data = json.loads(request.body or '{}')
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    email = (data.get('email') or '').strip()
    if not email or '@' not in email:
        return JsonResponse({"error": "A valid email is required"}, status=400)
    if not email_enabled():
        return JsonResponse({"error": "Email isn't configured on this server — share the link instead."}, status=400)
    _ensure_invite_token(trip)
    url = request.build_absolute_uri(f"/adventure/join/{trip.invite_token}/")
    ok = send_invite_email(email, trip.name, url, request.user.username)
    if not ok:
        return JsonResponse({"error": "Could not send the email — check the server's SMTP settings."}, status=502)
    return JsonResponse({"status": "ok"})


def trip_join_view(request, token):
    """Open-join landing: log in / sign up and be added to the trip."""
    trip = Adventure.objects.filter(invite_token=token).first()
    if not trip:
        return render(request, 'tracker/adventure_join.html', {'invalid': True}, status=404)
    if request.user.is_authenticated:
        AdventureMember.objects.get_or_create(
            adventure=trip, user=request.user, defaults={'role': 'member'},
        )
        return redirect('tracker:adventure_plan', trip_id=trip.id)
    # Anonymous: show a small landing with login/signup carrying ?next back here.
    return render(request, 'tracker/adventure_join.html', {
        'trip': trip,
        'next': f"/adventure/join/{token}/",
        'cover': trip.cover_image_thumbnail.url if trip.cover_image_thumbnail else (trip.cover_image.url if trip.cover_image else ''),
    })


# ---------------------------------------------------------------------------
# Trip Social API (members, timeline, blurbs, milestones, comments)
# ---------------------------------------------------------------------------

@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_add_member(request, trip_id):
    trip = get_object_or_404(Adventure, id=trip_id, device__user=request.user)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    username = data.get('username', '').strip()
    if not username:
        return JsonResponse({"error": "username required"}, status=400)
    from django.contrib.auth.models import User as AuthUser
    try:
        user = AuthUser.objects.get(username=username)
    except AuthUser.DoesNotExist:
        return JsonResponse({"error": "User not found"}, status=404)
    if user == request.user:
        return JsonResponse({"error": "You are already the creator"}, status=400)
    member, created = AdventureMember.objects.get_or_create(adventure=trip, user=user, defaults={'role': 'member'})
    if not created:
        return JsonResponse({"error": "Already a member"}, status=400)
    return JsonResponse({"status": "ok", "user_id": user.id, "username": user.username})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_remove_member(request, trip_id, user_id):
    trip = get_object_or_404(Adventure, id=trip_id, device__user=request.user)
    from django.contrib.auth.models import User as AuthUser
    user = get_object_or_404(AuthUser, id=user_id)
    AdventureMember.objects.filter(adventure=trip, user=user).exclude(role='creator').delete()
    return JsonResponse({"status": "ok"})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_toggle_public(request, trip_id):
    trip = get_object_or_404(Adventure, id=trip_id, device__user=request.user)
    if trip.public_slug:
        trip.public_slug = None
    else:
        trip.public_slug = str(uuid.uuid4()).replace('-', '')[:32]
    trip.save()
    return JsonResponse({"status": "ok", "is_public": bool(trip.public_slug), "public_slug": trip.public_slug})


@login_required
def trip_timeline_api(request, trip_id):
    trip = _get_trip_for_user(trip_id, request.user)
    page = int(request.GET.get('page', 1))
    per_page = 50
    events = []
    for b in trip.blurbs.select_related('author').prefetch_related('photos', 'comments'):
        events.append({
            'type': 'blurb',
            'id': b.id,
            'author': b.author.username,
            'author_id': b.author.id,
            'avatar': _get_user_avatar(b.author),
            'title': b.title,
            'text': b.text,
            'latitude': b.latitude,
            'longitude': b.longitude,
            'location_name': b.location_name,
            'photos': [{'id': p.id, 'url': p.image.url, 'thumb': p.thumbnail.url if p.thumbnail else p.image.url} for p in b.photos.all()],
            'comment_count': b.comments.count(),
            'created_at': b.created_at.isoformat(),
            'sort_key': b.created_at.isoformat(),
            'can_delete': b.author == request.user or trip.device.user == request.user,
        })
    for m in trip.milestones.select_related('author'):
        events.append({
            'type': 'milestone',
            'id': m.id,
            'author': m.author.username,
            'author_id': m.author.id,
            'title': m.title,
            'description': m.description,
            'emoji': m.emoji,
            'date': m.date.isoformat(),
            'created_at': m.created_at.isoformat(),
            'sort_key': m.date.isoformat(),
            'can_delete': m.author == request.user or trip.device.user == request.user,
        })
    events.sort(key=lambda e: e['sort_key'])
    total = len(events)
    start = (page - 1) * per_page
    events = events[start:start + per_page]
    return JsonResponse({'events': events, 'page': page, 'has_more': start + per_page < total})


@login_required
@require_http_methods(["POST"])
def trip_create_blurb(request, trip_id):
    trip = _get_trip_for_user(trip_id, request.user)
    text = request.POST.get('text', '').strip()
    title = request.POST.get('title', '').strip()
    has_photos = bool(request.FILES.getlist('photos'))
    if not text and not title and not has_photos:
        return JsonResponse({"error": "Title, text, or a photo is required"}, status=400)
    lat = request.POST.get('latitude')
    lng = request.POST.get('longitude')
    blurb = AdventureBlurb.objects.create(
        adventure=trip, author=request.user, title=title[:200], text=text,
        latitude=float(lat) if lat else None,
        longitude=float(lng) if lng else None,
        location_name=request.POST.get('location_name', ''),
    )
    photos = request.FILES.getlist('photos')
    photo_ids = []
    for i, photo_file in enumerate(photos[:5]):
        if photo_file.size > 10 * 1024 * 1024:
            continue
        full_file, thumb_file = resize_photo(photo_file)
        p = AdventureBlurbPhoto.objects.create(blurb=blurb, image=full_file, thumbnail=thumb_file, order=i)
        photo_ids.append(p.id)
    return JsonResponse({"status": "ok", "blurb_id": blurb.id, "photo_ids": photo_ids})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_delete_blurb(request, trip_id, blurb_id):
    trip = _get_trip_for_user(trip_id, request.user)
    blurb = get_object_or_404(AdventureBlurb, id=blurb_id, adventure=trip)
    if blurb.author != request.user and trip.device.user != request.user:
        return JsonResponse({"error": "Permission denied"}, status=403)
    blurb.delete()
    return JsonResponse({"status": "ok"})


@login_required
def trip_blurb_comments(request, trip_id, blurb_id):
    trip = _get_trip_for_user(trip_id, request.user)
    blurb = get_object_or_404(AdventureBlurb, id=blurb_id, adventure=trip)
    comments = []
    for c in blurb.comments.select_related('author'):
        comments.append({
            'id': c.id,
            'author': c.author.username if c.author else c.guest_name,
            'author_id': c.author.id if c.author else None,
            'avatar': _get_user_avatar(c.author) if c.author else None,
            'text': c.text,
            'created_at': c.created_at.isoformat(),
            'can_delete': c.author == request.user or trip.device.user == request.user,
        })
    return JsonResponse({'comments': comments})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_create_comment(request, trip_id, blurb_id):
    trip = _get_trip_for_user(trip_id, request.user)
    blurb = get_object_or_404(AdventureBlurb, id=blurb_id, adventure=trip)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    text = data.get('text', '').strip()
    if not text:
        return JsonResponse({"error": "Text required"}, status=400)
    comment = AdventureComment.objects.create(blurb=blurb, author=request.user, text=text)
    return JsonResponse({"status": "ok", "comment_id": comment.id})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_delete_comment(request, trip_id, comment_id):
    trip = _get_trip_for_user(trip_id, request.user)
    comment = get_object_or_404(AdventureComment, id=comment_id, blurb__adventure=trip)
    if comment.author != request.user and trip.device.user != request.user:
        return JsonResponse({"error": "Permission denied"}, status=403)
    comment.delete()
    return JsonResponse({"status": "ok"})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_create_milestone(request, trip_id):
    trip = _get_trip_for_user(trip_id, request.user)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    title = data.get('title', '').strip()
    if not title:
        return JsonResponse({"error": "Title required"}, status=400)
    date_raw = data.get('date', '').strip()
    try:
        date_val = datetime.fromisoformat(date_raw.replace('Z', '+00:00'))
        if timezone.is_naive(date_val):
            date_val = timezone.make_aware(date_val)
    except (ValueError, AttributeError):
        date_val = timezone.now()
    milestone = AdventureMilestone.objects.create(
        adventure=trip, author=request.user,
        title=title,
        description=data.get('description', ''),
        emoji=data.get('emoji', '🏁'),
        date=date_val,
    )
    return JsonResponse({"status": "ok", "milestone_id": milestone.id})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_delete_milestone(request, trip_id, milestone_id):
    trip = _get_trip_for_user(trip_id, request.user)
    milestone = get_object_or_404(AdventureMilestone, id=milestone_id, adventure=trip)
    if milestone.author != request.user and trip.device.user != request.user:
        return JsonResponse({"error": "Permission denied"}, status=403)
    milestone.delete()
    return JsonResponse({"status": "ok"})


@login_required
@csrf_exempt
@require_http_methods(["PATCH", "POST"])
def trip_update_body(request, trip_id):
    trip = _get_trip_for_user(trip_id, request.user)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    body = data.get('body')
    if not isinstance(body, list):
        return JsonResponse({"error": "body must be a list"}, status=400)
    if 'name' in data:
        trip.name = data['name']
    if 'subtitle' in data:
        trip.subtitle = data['subtitle'][:400]
    trip.body = body
    trip.save(update_fields=['body', 'name', 'subtitle'])
    return JsonResponse({"status": "ok", "word_count": _body_word_count(body)})


@login_required
@require_http_methods(["POST"])
def trip_upload_cover(request, trip_id):
    trip = _get_trip_for_user(trip_id, request.user)
    photo_file = request.FILES.get('cover')
    if not photo_file:
        return JsonResponse({"error": "cover file required"}, status=400)
    if photo_file.size > 20 * 1024 * 1024:
        return JsonResponse({"error": "File too large (max 20 MB)"}, status=400)
    full_file, thumb_file = resize_photo(photo_file, max_width=2000, thumb_size=600)
    if trip.cover_image:
        trip.cover_image.delete(save=False)
    if trip.cover_image_thumbnail:
        trip.cover_image_thumbnail.delete(save=False)
    trip.cover_image = full_file
    trip.cover_image_thumbnail = thumb_file
    trip.save(update_fields=['cover_image', 'cover_image_thumbnail'])
    return JsonResponse({
        "status": "ok",
        "cover_image": trip.cover_image.url,
        "cover_thumbnail": trip.cover_image_thumbnail.url if trip.cover_image_thumbnail else None,
    })


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_delete_cover(request, trip_id):
    trip = _get_trip_for_user(trip_id, request.user)
    if trip.cover_image:
        trip.cover_image.delete(save=False)
    if trip.cover_image_thumbnail:
        trip.cover_image_thumbnail.delete(save=False)
    trip.cover_image = None
    trip.cover_image_thumbnail = None
    trip.save(update_fields=['cover_image', 'cover_image_thumbnail'])
    return JsonResponse({"status": "ok"})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def trip_update_blurb(request, trip_id, blurb_id):
    trip = _get_trip_for_user(trip_id, request.user)
    blurb = get_object_or_404(AdventureBlurb, id=blurb_id, adventure=trip)
    if blurb.author != request.user and trip.device.user != request.user:
        return JsonResponse({"error": "Permission denied"}, status=403)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    if 'title' in data:
        blurb.title = (data['title'] or '')[:200]
    if 'text' in data:
        blurb.text = data['text']
    if 'latitude' in data:
        blurb.latitude = float(data['latitude']) if data['latitude'] is not None else None
    if 'longitude' in data:
        blurb.longitude = float(data['longitude']) if data['longitude'] is not None else None
    if 'location_name' in data:
        blurb.location_name = data['location_name']
    blurb.save()
    return JsonResponse({"status": "ok"})


@login_required
def trip_visits_api(request, trip_id):
    """Visit stats (cities/states/countries) scoped to this trip's device and time range."""
    trip = _get_trip_for_user(trip_id, request.user)
    locations = Location.objects.filter(
        device=trip.device,
        timestamp__gte=trip.start_time,
        timestamp__lte=trip.end_time,
    ).exclude(city='')

    city_stats = locations.values('city', 'state', 'country', 'country_code').annotate(
        count=Count('id'), first_seen=Min('timestamp'), last_seen=Max('timestamp')
    ).order_by('-count')

    cities = [{
        "city": s['city'], "state": s['state'],
        "country": s['country'], "country_code": s['country_code'],
        "visit_count": s['count'],
        "first_seen": s['first_seen'].isoformat() if s['first_seen'] else None,
        "last_seen": s['last_seen'].isoformat() if s['last_seen'] else None,
    } for s in city_stats]

    country_stats = locations.values('country', 'country_code').annotate(
        location_count=Count('id'),
        city_count=Count('city', distinct=True),
        state_count=Count('state', distinct=True),
    ).order_by('-location_count')

    countries = [{
        "country": s['country'], "country_code": s['country_code'],
        "location_count": s['location_count'],
        "city_count": s['city_count'], "state_count": s['state_count'],
    } for s in country_stats]

    state_stats = locations.values('state', 'country').annotate(
        count=Count('id'), city_count=Count('city', distinct=True)
    ).order_by('-count')

    states = [{
        "state": s['state'], "country": s['country'],
        "location_count": s['count'], "city_count": s['city_count'],
    } for s in state_stats if s['state']]

    time_city = defaultdict(float)
    time_country = defaultdict(float)
    points = locations.order_by('timestamp').values_list('timestamp', 'city', 'state', 'country', 'country_code')
    prev = None
    for ts, city, state_val, country_val, cc in points.iterator():
        cur = (ts, city, state_val, country_val, cc)
        if prev:
            if prev[1]:
                gap = _visits_dwell_gap(prev, cur, 'city')
                if gap > 0:
                    time_city[(prev[1], prev[2], prev[3], prev[4])] += gap
            if prev[3]:
                gap = _visits_dwell_gap(prev, cur, 'country')
                if gap > 0:
                    time_country[prev[3]] += gap
        prev = cur

    for c in cities:
        key = (c['city'], c['state'], c['country'], c['country_code'])
        c['time_spent'] = round(time_city.get(key, 0))
    for c in countries:
        c['time_spent'] = round(time_country.get(c['country'], 0))

    return JsonResponse({"cities": cities, "states": states, "countries": countries})


# ---------------------------------------------------------------------------
# Public Trip API (no auth required)
# ---------------------------------------------------------------------------

def _check_public_pin(request, trip):
    """Return True if the request may access this public adventure."""
    if not trip.access_pin:
        return True
    session_key = f'adv_pin_ok_{trip.public_slug}'
    return request.session.get(session_key) is True


@csrf_exempt
@require_http_methods(["POST"])
def trip_verify_pin(request, slug):
    trip = get_object_or_404(Adventure, public_slug=slug)
    if not trip.access_pin:
        return JsonResponse({"status": "ok"})
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    if data.get('pin', '').strip() == trip.access_pin:
        request.session[f'adv_pin_ok_{slug}'] = True
        return JsonResponse({"status": "ok"})
    return JsonResponse({"error": "Incorrect PIN"}, status=403)


def trip_public_detail_api(request, slug):
    trip = get_object_or_404(Adventure, public_slug=slug)
    if not _check_public_pin(request, trip):
        return JsonResponse({"error": "PIN required"}, status=403)
    locations = list(trip.locations)
    locs = [{
        "lat": _jf(l.latitude), "lng": _jf(l.longitude),
        "timestamp": l.timestamp.isoformat(),
        "city": l.city, "country": l.country,
    } for l in locations]
    members = [{"username": m.user.username, "role": m.role} for m in trip.members.select_related('user')]
    blurbs = []
    places = []
    for b in trip.blurbs.select_related('author').prefetch_related('photos'):
        blurbs.append({
            "id": b.id,
            "author": b.author.username,
            "title": b.title,
            "text": b.text,
            "latitude": b.latitude,
            "longitude": b.longitude,
            "location_name": b.location_name,
            "photos": [{"id": p.id, "url": p.image.url, "thumb": p.thumbnail.url if p.thumbnail else p.image.url}
                       for p in b.photos.all()],
            "created_at": b.created_at.isoformat(),
        })
        # POIs (located blurbs) resolve inline pin refs / location_card blocks.
        if b.latitude is not None and b.longitude is not None:
            places.append({"id": b.id, "name": b.title or "", "latitude": b.latitude,
                           "longitude": b.longitude, "notes": b.text})
    return JsonResponse({
        "id": trip.id,
        "name": trip.name,
        "description": trip.description,
        "subtitle": trip.subtitle,
        "body": trip.body or [],
        "cover_image": trip.cover_image.url if trip.cover_image else None,
        "cover_thumbnail": trip.cover_image_thumbnail.url if trip.cover_image_thumbnail else None,
        "start_time": trip.start_time.isoformat(),
        "end_time": trip.end_time.isoformat(),
        "locations": locs,
        "members": members,
        "places": places,
        "planned_stops": [_planned_stop_payload(s) for s in trip.planned_stops.all()],
        "blurbs": blurbs,
    })


def trip_public_timeline_api(request, slug):
    trip = get_object_or_404(Adventure, public_slug=slug)
    if not _check_public_pin(request, trip):
        return JsonResponse({"error": "PIN required"}, status=403)
    events = []
    for b in trip.blurbs.select_related('author').prefetch_related('photos', 'comments'):
        events.append({
            'type': 'blurb',
            'id': b.id,
            'author': b.author.username,
            'title': b.title,
            'text': b.text,
            'latitude': b.latitude,
            'longitude': b.longitude,
            'location_name': b.location_name,
            'photos': [{'id': p.id, 'url': p.image.url, 'thumb': p.thumbnail.url if p.thumbnail else p.image.url} for p in b.photos.all()],
            'comment_count': b.comments.count(),
            'created_at': b.created_at.isoformat(),
            'sort_key': b.created_at.isoformat(),
        })
    for m in trip.milestones.select_related('author'):
        events.append({
            'type': 'milestone',
            'id': m.id,
            'author': m.author.username,
            'title': m.title,
            'description': m.description,
            'emoji': m.emoji,
            'date': m.date.isoformat(),
            'sort_key': m.date.isoformat(),
        })
    events.sort(key=lambda e: e['sort_key'])
    return JsonResponse({'events': events})


def trip_public_blurb_comments(request, slug, blurb_id):
    trip = get_object_or_404(Adventure, public_slug=slug)
    if not _check_public_pin(request, trip):
        return JsonResponse({"error": "PIN required"}, status=403)
    blurb = get_object_or_404(AdventureBlurb, id=blurb_id, adventure=trip)
    comments = []
    for c in blurb.comments.select_related('author').order_by('created_at'):
        author = c.author.username if c.author else (c.guest_name or 'guest')
        comments.append({
            'id': c.id,
            'author': author,
            'is_guest': c.author is None,
            'text': c.text,
            'created_at': c.created_at.isoformat(),
        })
    return JsonResponse({'comments': comments})


@csrf_exempt
@require_http_methods(["POST"])
def trip_public_create_comment(request, slug, blurb_id):
    trip = get_object_or_404(Adventure, public_slug=slug)
    blurb = get_object_or_404(AdventureBlurb, id=blurb_id, adventure=trip)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    text = data.get('text', '').strip()
    name = data.get('guest_name', '').strip()
    if not text:
        return JsonResponse({"error": "Text required"}, status=400)
    if not name:
        return JsonResponse({"error": "Name required"}, status=400)
    if len(name) > 100:
        return JsonResponse({"error": "Name too long"}, status=400)
    comment = AdventureComment.objects.create(blurb=blurb, author=None, guest_name=name, text=text)
    return JsonResponse({"status": "ok", "comment": {
        'id': comment.id,
        'author': name,
        'is_guest': True,
        'text': text,
        'created_at': comment.created_at.isoformat(),
    }})


def trip_public_view(request, slug):
    trip = get_object_or_404(Adventure, public_slug=slug)
    description = trip.subtitle or trip.description or f'An adventure from {trip.start_time.strftime("%b %d")} to {trip.end_time.strftime("%b %d, %Y")} on Roamly.'
    requires_pin = bool(trip.access_pin) and not _check_public_pin(request, trip)
    return render(request, 'tracker/adventure_public.html', {
        'trip': trip,
        'slug': slug,
        'seo_description': description,
        'seo_canonical': request.build_absolute_uri(),
        'requires_pin': requires_pin,
        'standalone': True,
    })

adventure_public_view = trip_public_view


# ---------------------------------------------------------------------------
# Geocoding API
# ---------------------------------------------------------------------------

@login_required
@csrf_exempt
@require_http_methods(["POST"])
def geocode_api(request):
    """Start batch geocoding of all un-geocoded locations."""
    total = Location.objects.filter(device__user=request.user, city='').count()

    if not total:
        return JsonResponse({"status": "nothing_to_geocode", "total": 0})

    job = start_geocoding(request.user.id, total)
    return JsonResponse({
        "status": "started",
        "total": job.total,
        "processed": job.processed,
        "errors": job.errors,
    })


@login_required
def geocode_status(request):
    return JsonResponse(get_geocoding_status(request.user.id))


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def geocode_stop(request):
    """Stop a running geocoding task."""
    if stop_geocoding(request.user.id):
        return JsonResponse({"status": "stopped"})
    return JsonResponse({"status": "no_active_task"})


# ---------------------------------------------------------------------------
# Export / Import
# ---------------------------------------------------------------------------

@login_required
def export_csv(request):
    """Export locations as CSV."""
    locations = Location.objects.filter(device__user=request.user).select_related('device').order_by('timestamp')

    response = HttpResponse(content_type='text/csv')
    response['Content-Disposition'] = 'attachment; filename="roamly_export.csv"'

    writer = csv.writer(response)
    writer.writerow([
        'device', 'latitude', 'longitude', 'altitude', 'accuracy',
        'speed', 'battery', 'timestamp', 'city', 'state', 'country', 'country_code',
    ])
    for loc in locations:
        writer.writerow([
            loc.device.device_id, loc.latitude, loc.longitude,
            loc.altitude or '', loc.accuracy or '', loc.speed or '',
            loc.battery or '', loc.timestamp.isoformat(),
            loc.city, loc.state, loc.country, loc.country_code,
        ])
    return response


@login_required
def export_gpx(request):
    """Export locations as GPX."""
    locations = Location.objects.filter(device__user=request.user).order_by('timestamp')

    gpx_lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<gpx version="1.1" creator="Roamly">',
        '  <trk>',
        '    <name>Roamly Export</name>',
        '    <trkseg>',
    ]
    for loc in locations:
        ele = f'      <ele>{loc.altitude}</ele>' if loc.altitude else ''
        gpx_lines.append(f'      <trkpt lat="{loc.latitude}" lon="{loc.longitude}">')
        if ele:
            gpx_lines.append(ele)
        gpx_lines.append(f'        <time>{loc.timestamp.isoformat()}</time>')
        gpx_lines.append('      </trkpt>')
    gpx_lines += ['    </trkseg>', '  </trk>', '</gpx>']

    response = HttpResponse('\n'.join(gpx_lines), content_type='application/gpx+xml')
    response['Content-Disposition'] = 'attachment; filename="roamly_export.gpx"'
    return response


def _write_backup_json(user, f, progress=None):
    """Write backup JSON to a file-like object row-by-row to avoid OOM on large datasets.

    ``progress(stage, done, total)`` is called as each section completes and
    periodically through the (dominant) locations scan, so the browser can show
    a real progress bar instead of an indeterminate spinner.
    """
    encoder = DjangoJSONEncoder()

    def report(stage, done=0, total=0):
        if progress:
            progress(stage, done, total)

    # Counted up front so the caller can weight the locations scan against the
    # small sections and turn elapsed time into a meaningful ETA.
    report('Counting locations')
    loc_total = Location.objects.filter(device__user=user).count()

    report('Collecting devices')
    meta = {'version': 7, 'exported_at': timezone.now().isoformat(), 'username': user.username}
    devices = [{'device_id': d.device_id, 'name': d.name}
               for d in Device.objects.filter(user=user)]
    # Same complete, nested schema as the S3 backup (_build_backup_json) so the
    # downloaded file and the automatic S3 backup contain identical data.
    report('Collecting adventures')
    adventures = _build_adventures_data(user)
    api_keys = [
        {'name': k.name, 'key': k.key, 'is_active': k.is_active, 'created_at': k.created_at}
        for k in APIKey.objects.filter(user=user)
    ]
    report('Collecting pals')
    pals = _build_pals_data(user)
    report('Collecting journals')
    journals = _build_journals_data(user)
    report('Collecting places')
    custom_places = _build_custom_places_data(user)

    f.write(b'{"meta":' + encoder.encode(meta).encode() + b',')
    f.write(b'"devices":' + encoder.encode(devices).encode() + b',')
    f.write(b'"adventures":' + encoder.encode(adventures).encode() + b',')
    f.write(b'"api_keys":' + encoder.encode(api_keys).encode() + b',')
    f.write(b'"pals":' + encoder.encode(pals).encode() + b',')
    f.write(b'"journals":' + encoder.encode(journals).encode() + b',')
    f.write(b'"custom_places":' + encoder.encode(custom_places).encode() + b',')

    report('Writing locations', 0, loc_total)
    f.write(b'"locations":[')
    first = True
    written = 0
    qs = (Location.objects.filter(device__user=user)
          .select_related('device').order_by('timestamp').iterator(chunk_size=2000))
    for loc in qs:
        if not first:
            f.write(b',')
        first = False
        f.write(encoder.encode({
            'device_id': loc.device.device_id,
            'latitude': _jf(loc.latitude), 'longitude': _jf(loc.longitude),
            'altitude': _jf(loc.altitude), 'accuracy': _jf(loc.accuracy),
            'speed': _jf(loc.speed), 'battery': _jf(loc.battery),
            'timestamp': loc.timestamp,
            'city': loc.city, 'state': loc.state,
            'country': loc.country, 'country_code': loc.country_code,
            'place_name': loc.place_name,
        }).encode())
        written += 1
        if written % 5000 == 0:
            report('Writing locations', written, loc_total)
    f.write(b']}')
    report('Writing locations', loc_total, loc_total)


# Share of the progress bar given to the small sections before the locations
# scan; the scan itself fills the rest.
_BACKUP_PREP_PCT = 12
_BACKUP_STAGES = ['Counting locations', 'Collecting devices', 'Collecting adventures',
                  'Collecting pals', 'Collecting journals', 'Collecting places',
                  'Writing locations']


def _backup_tmp_path(job_id):
    # Written gzipped so the download can carry a real Content-Length (see
    # export_backup_download) and the temp file stays small.
    return os.path.join(tempfile.gettempdir(), f'roamly_backup_{job_id}.json.gz')


@login_required
@require_POST
def export_backup_start(request):
    """Start async backup generation, return a job ID to poll."""
    job_id = str(uuid.uuid4())
    tmp_path = _backup_tmp_path(job_id)
    key = f'backup_job:{request.user.id}:{job_id}'
    started = time.time()

    def put(payload):
        # Refreshes the TTL on every update, so a multi-hour backup of a huge
        # history can't have its own job record expire out from under it.
        cache.set(key, payload, timeout=3600)

    put({'status': 'running', 'stage': 'Starting', 'pct': 0, 'started': started})

    user = request.user

    def generate():
        try:
            def progress(stage, done, total):
                try:
                    idx = _BACKUP_STAGES.index(stage)
                except ValueError:
                    idx = 0
                if total:
                    pct = _BACKUP_PREP_PCT + (100 - _BACKUP_PREP_PCT) * done / total
                else:
                    pct = _BACKUP_PREP_PCT * idx / len(_BACKUP_STAGES)
                put({'status': 'running', 'stage': stage, 'pct': round(pct, 1),
                     'done': done, 'total': total, 'started': started})

            # Level 5: the JSON compresses ~10x either way, and this is on the
            # user's clock while they wait for the bar to fill.
            with gzip.open(tmp_path, 'wb', compresslevel=5) as f:
                _write_backup_json(user, f, progress=progress)
                raw_size = f.tell()
            put({'status': 'ready', 'stage': 'Ready', 'pct': 100, 'started': started,
                 'size': os.path.getsize(tmp_path), 'raw_size': raw_size})
        except Exception as e:
            logger.error(f'Backup generation failed: {e}')
            put({'status': 'error', 'message': str(e)})

    threading.Thread(target=generate, daemon=True).start()
    return JsonResponse({'job_id': job_id})


@login_required
def export_backup_status(request, job_id):
    """Poll backup generation progress."""
    job = cache.get(f'backup_job:{request.user.id}:{job_id}')
    if job is None:
        return JsonResponse({'status': 'not_found'}, status=404)
    if not isinstance(job, dict):  # a job started before this deploy
        return JsonResponse({'status': job})
    out = dict(job)
    pct = out.get('pct') or 0
    if out['status'] == 'running' and pct > 1:
        elapsed = time.time() - out.get('started', time.time())
        out['eta'] = max(0, round(elapsed * (100 - pct) / pct))
    out.pop('started', None)
    return JsonResponse(out)


@login_required
def export_backup_download(request, job_id):
    """Download a completed backup file."""
    job = cache.get(f'backup_job:{request.user.id}:{job_id}')
    status = job.get('status') if isinstance(job, dict) else job
    if status != 'ready':
        return HttpResponse('Backup not ready', status=404)
    tmp_path = _backup_tmp_path(job_id)
    if not os.path.exists(tmp_path):
        return HttpResponse('Backup file not found', status=404)
    filename = f'roamly_backup_{timezone.now().strftime("%Y-%m-%d")}.json'
    cache.delete(f'backup_job:{request.user.id}:{job_id}')

    # The file is stored gzipped. Handing the browser the compressed bytes with
    # Content-Encoding: gzip both sets a real Content-Length (so the download
    # shows a total and a progress bar rather than a bare rising byte count)
    # and keeps GZipMiddleware off it — it skips responses that already carry a
    # Content-Encoding, and for a streaming response it otherwise *deletes*
    # Content-Length, which is what made the size unknown. The browser
    # transparently inflates it, so the saved file is still plain JSON.
    if 'gzip' in request.META.get('HTTP_ACCEPT_ENCODING', ''):
        # FileResponse sets Content-Length from the file size, which is exactly
        # the number of bytes on the wire here.
        response = FileResponse(open(tmp_path, 'rb'), content_type='application/json',
                                as_attachment=True, filename=filename)
        response['Content-Encoding'] = 'gzip'
    else:
        # Non-browser client (curl without --compressed). Inflate as we stream;
        # hand-rolled rather than FileResponse because seeking a GzipFile to
        # find its length would decompress the whole thing up front.
        raw_size = job.get('raw_size') if isinstance(job, dict) else None

        def _inflate():
            with gzip.open(tmp_path, 'rb') as gz:
                for chunk in iter(lambda: gz.read(64 * 1024), b''):
                    yield chunk

        response = StreamingHttpResponse(_inflate(), content_type='application/json')
        response['Content-Disposition'] = f'attachment; filename="{filename}"'
        if raw_size:
            response['Content-Encoding'] = 'identity'  # keep GZipMiddleware off Content-Length
            response['Content-Length'] = str(raw_size)

    def _cleanup():
        time.sleep(60)
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
    threading.Thread(target=_cleanup, daemon=True).start()
    return response


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def restore_backup(request):
    """Restore user data from a JSON backup file."""
    f = request.FILES.get('file')
    if not f:
        return JsonResponse({'error': 'No file uploaded'}, status=400)

    try:
        raw = f.read()
        # Backups are served gzipped over the wire; a client that saved the
        # compressed bytes without inflating them should still restore.
        if raw[:2] == b'\x1f\x8b':
            raw = gzip.decompress(raw)
        data = json.loads(raw.decode('utf-8-sig'))
    except (json.JSONDecodeError, UnicodeDecodeError, OSError, EOFError) as e:
        return JsonResponse({'error': f'Invalid JSON file: {e}'}, status=400)

    meta = data.get('meta', {})
    if 'version' not in meta:
        return JsonResponse({'error': 'Not a valid Roamly backup file (missing meta.version)'}, status=400)

    user = request.user
    counts = {'devices': 0, 'locations': 0, 'trips': 0, 'trip_places': 0,
              'adventures': 0, 'api_keys': 0, 'pals': 0, 'journals': 0,
              'custom_places': 0}
    errors = 0

    try:
        with transaction.atomic():
            # Restore devices (small count, get_or_create is fine)
            device_map = {}
            all_device_ids = set()
            for loc in data.get('locations', []):
                all_device_ids.add(loc.get('device_id', ''))
            for t in data.get('trips', []):
                all_device_ids.add(t.get('device_id', ''))
            for a in data.get('adventures', []):
                all_device_ids.add(a.get('device_id', ''))
            for d in data.get('devices', []):
                all_device_ids.add(d.get('device_id', ''))
            all_device_ids.discard('')

            device_names = {d['device_id']: d.get('name', d['device_id']) for d in data.get('devices', [])}
            for device_id in all_device_ids:
                device, created = Device.objects.get_or_create(
                    user=user, device_id=device_id,
                    defaults={'name': device_names.get(device_id, device_id)}
                )
                device_map[device_id] = device
                if created:
                    counts['devices'] += 1

            # Restore locations using bulk_create with ignore_conflicts
            BATCH_SIZE = 1000
            loc_batch = []
            loc_total = 0
            for loc in data.get('locations', []):
                try:
                    device = device_map.get(loc.get('device_id'))
                    if not device:
                        errors += 1
                        continue
                    ts = _parse_timestamp(loc['timestamp'])
                    loc_obj = Location(
                        device=device,
                        latitude=loc['latitude'],
                        longitude=loc['longitude'],
                        timestamp=ts,
                        altitude=loc.get('altitude'),
                        accuracy=loc.get('accuracy'),
                        speed=loc.get('speed'),
                        battery=loc.get('battery'),
                        city=loc.get('city', ''),
                        state=loc.get('state', ''),
                        country=loc.get('country', ''),
                        country_code=loc.get('country_code', ''),
                        place_name=loc.get('place_name', ''),
                    )
                    if HAS_POSTGIS and Point:
                        loc_obj.location = Point(float(loc['longitude']), float(loc['latitude']), srid=4326)
                    loc_batch.append(loc_obj)
                    if len(loc_batch) >= BATCH_SIZE:
                        created = Location.objects.bulk_create(loc_batch, ignore_conflicts=True)
                        loc_total += len(created)
                        loc_batch = []
                except Exception as e:
                    errors += 1
                    logger.warning(f"Backup restore location error: {e}")
            if loc_batch:
                created = Location.objects.bulk_create(loc_batch, ignore_conflicts=True)
                loc_total += len(created)
            counts['locations'] = loc_total

            # Restore trips (small count, get_or_create is fine)
            trip_map = {}
            for t in data.get('trips', []):
                try:
                    device = device_map.get(t.get('device_id'))
                    if not device:
                        errors += 1
                        continue
                    start = _parse_timestamp(t['start_time'])
                    end = _parse_timestamp(t['end_time'])
                    trip, created = Adventure.objects.get_or_create(
                        device=device,
                        name=t['name'],
                        start_time=start,
                        defaults={
                            'description': t.get('description', ''),
                            'end_time': end,
                        }
                    )
                    trip_key = (t['device_id'], t['name'], str(start))
                    trip_map[trip_key] = trip
                    if created:
                        counts['trips'] += 1
                except Exception as e:
                    errors += 1
                    logger.warning(f"Backup restore trip error: {e}")

            # Restore trip places
            for tp in data.get('trip_places', []):
                try:
                    trip_key = (tp['trip_device_id'], tp['trip_name'], str(_parse_timestamp(tp['trip_start_time'])))
                    trip = trip_map.get(trip_key)
                    if not trip:
                        errors += 1
                        continue
                    _, created = AdventureBlurb.objects.get_or_create(
                        adventure=trip,
                        title=(tp.get('name') or '')[:200],
                        latitude=tp['latitude'],
                        longitude=tp['longitude'],
                        defaults={
                            'author': trip.creator or user,
                            'text': tp.get('notes', ''),
                        }
                    )
                    if created:
                        counts['trip_places'] += 1
                except Exception as e:
                    errors += 1
                    logger.warning(f"Backup restore trip place error: {e}")

            # Restore API keys
            for k in data.get('api_keys', []):
                try:
                    _, created = APIKey.objects.get_or_create(
                        user=user, key=k['key'],
                        defaults={
                            'name': k.get('name', 'Restored Key'),
                            'is_active': k.get('is_active', True),
                        }
                    )
                    if created:
                        counts['api_keys'] += 1
                except Exception as e:
                    errors += 1
                    logger.warning(f"Backup restore API key error: {e}")

            # Restore pals
            from django.contrib.auth import get_user_model
            AuthUser = get_user_model()
            import datetime

            for pal_data in data.get('pals', []):
                try:
                    start_date = datetime.date.fromisoformat(str(pal_data['start_date'])[:10])
                    end_date = datetime.date.fromisoformat(str(pal_data['end_date'])[:10])
                    pal, pal_created = Pal.objects.get_or_create(
                        creator=user,
                        name=pal_data['name'],
                        start_date=start_date,
                        defaults={
                            'description': pal_data.get('description', ''),
                            'end_date': end_date,
                            'public_slug': pal_data.get('public_slug'),
                        }
                    )
                    if pal_created:
                        counts['pals'] += 1

                    # Members
                    for m in pal_data.get('members', []):
                        try:
                            member_user = AuthUser.objects.get(username=m['username'])
                            PalMember.objects.get_or_create(
                                pal=pal, user=member_user,
                                defaults={'role': m.get('role', 'member')}
                            )
                        except AuthUser.DoesNotExist:
                            pass

                    # Blurbs (only on new pals to avoid duplicates)
                    if pal_created:
                        for b in pal_data.get('blurbs', []):
                            try:
                                try:
                                    blurb_author = AuthUser.objects.get(username=b['author_username'])
                                except AuthUser.DoesNotExist:
                                    blurb_author = user
                                blurb = PalBlurb.objects.create(
                                    pal=pal,
                                    author=blurb_author,
                                    text=b.get('text', ''),
                                    latitude=b['latitude'],
                                    longitude=b['longitude'],
                                    location_name=b.get('location_name', ''),
                                )
                                for ph in b.get('photos', []):
                                    if ph.get('image'):
                                        PalBlurbPhoto.objects.create(
                                            blurb=blurb, image=ph['image'],
                                            thumbnail=ph.get('thumbnail') or '',
                                            order=ph.get('order', 0),
                                        )
                                for c in b.get('comments', []):
                                    try:
                                        comment_author = None
                                        if c.get('author_username'):
                                            try:
                                                comment_author = AuthUser.objects.get(username=c['author_username'])
                                            except AuthUser.DoesNotExist:
                                                pass
                                        PalComment.objects.create(
                                            blurb=blurb,
                                            author=comment_author,
                                            guest_name=c.get('guest_name', ''),
                                            text=c['text'],
                                        )
                                    except Exception:
                                        pass
                            except Exception as e:
                                errors += 1
                                logger.warning(f"Backup restore blurb error: {e}")

                        for m in pal_data.get('milestones', []):
                            try:
                                try:
                                    milestone_author = AuthUser.objects.get(username=m['author_username'])
                                except AuthUser.DoesNotExist:
                                    milestone_author = user
                                PalMilestone.objects.create(
                                    pal=pal,
                                    author=milestone_author,
                                    title=m['title'],
                                    description=m.get('description', ''),
                                    emoji=m.get('emoji', '🏁'),
                                    date=_parse_timestamp(m['date']),
                                )
                            except Exception as e:
                                errors += 1
                                logger.warning(f"Backup restore milestone error: {e}")

                except Exception as e:
                    errors += 1
                    logger.warning(f"Backup restore pal error: {e}")

            # Restore adventures (rich nested format, v4+). Legacy v2/v3 backups
            # carry their adventures under "trips"/"trip_places" (handled above);
            # this block is a no-op for those.
            for a in data.get('adventures', []):
                try:
                    device = device_map.get(a.get('device_id'))
                    if not device:
                        errors += 1
                        continue
                    start = _parse_timestamp(a['start_time'])
                    end = _parse_timestamp(a.get('end_time')) or start
                    creator = user
                    if a.get('creator_username'):
                        creator = AuthUser.objects.filter(username=a['creator_username']).first() or user
                    adv, adv_created = Adventure.objects.get_or_create(
                        device=device, name=a['name'], start_time=start,
                        defaults={
                            'description': a.get('description', ''),
                            'subtitle': a.get('subtitle', ''),
                            'access_pin': a.get('access_pin', ''),
                            'end_time': end,
                            'creator': creator,
                            'public_slug': a.get('public_slug') or None,
                            'body': a.get('body') or [],
                        }
                    )
                    if adv_created:
                        counts['adventures'] += 1
                    else:
                        # Backfill the CMS body/subtitle onto a pre-existing adventure
                        # so re-importing an enriched backup fills in blank fields.
                        update_fields = []
                        if not adv.body and a.get('body'):
                            adv.body = a['body']
                            update_fields.append('body')
                        if not adv.subtitle and a.get('subtitle'):
                            adv.subtitle = a['subtitle']
                            update_fields.append('subtitle')
                        if update_fields:
                            adv.save(update_fields=update_fields)

                    # Places (legacy v4/v5 backups) restore as POIs (blurbs)
                    for p in a.get('places', []):
                        try:
                            _, created = AdventureBlurb.objects.get_or_create(
                                adventure=adv, title=(p.get('name') or '')[:200],
                                latitude=p['latitude'], longitude=p['longitude'],
                                defaults={
                                    'author': adv.creator or user,
                                    'text': p.get('notes', ''),
                                }
                            )
                            if created:
                                counts['trip_places'] += 1
                        except Exception:
                            pass

                    # Members
                    for m in a.get('members', []):
                        member_user = AuthUser.objects.filter(username=m.get('username')).first()
                        if member_user:
                            AdventureMember.objects.get_or_create(
                                adventure=adv, user=member_user,
                                defaults={'role': m.get('role', 'member')}
                            )

                    # Blurbs + milestones only on freshly-created adventures (avoid dupes)
                    if adv_created:
                        for b in a.get('blurbs', []):
                            try:
                                blurb_author = AuthUser.objects.filter(username=b.get('author_username')).first() or user
                                blurb = AdventureBlurb.objects.create(
                                    adventure=adv, author=blurb_author,
                                    title=(b.get('title') or '')[:200],
                                    text=b.get('text', ''),
                                    latitude=b.get('latitude'), longitude=b.get('longitude'),
                                    location_name=b.get('location_name', ''),
                                )
                                for ph in b.get('photos', []):
                                    if ph.get('image'):
                                        AdventureBlurbPhoto.objects.create(
                                            blurb=blurb, image=ph['image'],
                                            thumbnail=ph.get('thumbnail') or '',
                                            order=ph.get('order', 0),
                                        )
                                for c in b.get('comments', []):
                                    comment_author = None
                                    if c.get('author_username'):
                                        comment_author = AuthUser.objects.filter(username=c['author_username']).first()
                                    AdventureComment.objects.create(
                                        blurb=blurb, author=comment_author,
                                        guest_name=c.get('guest_name', ''),
                                        text=c.get('text', ''),
                                    )
                            except Exception as e:
                                errors += 1
                                logger.warning(f"Backup restore adventure blurb error: {e}")
                        for m in a.get('milestones', []):
                            try:
                                milestone_author = AuthUser.objects.filter(username=m.get('author_username')).first() or user
                                AdventureMilestone.objects.create(
                                    adventure=adv, author=milestone_author,
                                    title=m['title'], description=m.get('description', ''),
                                    emoji=m.get('emoji', '🏁'),
                                    date=_parse_timestamp(m['date']),
                                )
                            except Exception as e:
                                errors += 1
                                logger.warning(f"Backup restore adventure milestone error: {e}")
                        # Planned stops (v7+ itinerary)
                        for s in a.get('planned_stops', []):
                            try:
                                PlannedStop.objects.create(
                                    adventure=adv,
                                    name=(s.get('name') or '')[:200],
                                    latitude=s.get('latitude'), longitude=s.get('longitude'),
                                    location_name=(s.get('location_name') or '')[:300],
                                    arrival_date=_parse_date_only(s.get('arrival_date')),
                                    nights=s.get('nights') or 0,
                                    transport=(s.get('transport') or '')[:30],
                                    notes=s.get('notes', ''),
                                    accommodation=(s.get('accommodation') or '')[:300],
                                    order=s.get('order') or 0,
                                )
                            except Exception as e:
                                errors += 1
                                logger.warning(f"Backup restore planned stop error: {e}")
                except Exception as e:
                    errors += 1
                    logger.warning(f"Backup restore adventure error: {e}")

            # Restore journals
            for j in data.get('journals', []):
                try:
                    jdate = datetime.date.fromisoformat(str(j['date'])[:10])
                    entry, j_created = JournalEntry.objects.get_or_create(
                        user=user, date=jdate,
                        defaults={
                            'title': j.get('title', ''),
                            'body': j.get('body', ''),
                            'mood': j.get('mood', ''),
                            'weather': j.get('weather', ''),
                            'is_favorite': j.get('is_favorite', False),
                            'pin_latitude': j.get('pin_latitude'),
                            'pin_longitude': j.get('pin_longitude'),
                            'location_name': j.get('location_name', ''),
                        }
                    )
                    if j_created:
                        counts['journals'] += 1
                        for ph in j.get('photos', []):
                            if ph.get('image'):
                                JournalPhoto.objects.create(
                                    entry=entry, image=ph['image'],
                                    thumbnail=ph.get('thumbnail') or '',
                                    caption=ph.get('caption', ''),
                                    order=ph.get('order', 0),
                                )
                except Exception as e:
                    errors += 1
                    logger.warning(f"Backup restore journal error: {e}")

            # Restore custom places (user-defined geofences). Idempotent on
            # (user, name, lat, lng); color falls back to the clay palette.
            for idx, cp in enumerate(data.get('custom_places', [])):
                try:
                    _, created = CustomPlace.objects.get_or_create(
                        user=user, name=cp['name'],
                        latitude=cp['latitude'], longitude=cp['longitude'],
                        defaults={
                            'radius_m': cp.get('radius_m', 150),
                            'color': cp.get('color') or _PLACE_COLORS[idx % len(_PLACE_COLORS)],
                            'notes': cp.get('notes', ''),
                        }
                    )
                    if created:
                        counts['custom_places'] += 1
                except Exception as e:
                    errors += 1
                    logger.warning(f"Backup restore custom place error: {e}")

    except Exception as e:
        logger.error(f"Backup restore failed: {e}")
        return JsonResponse({'error': f'Restore failed: {e}'}, status=500)

    # Refresh the Places snapshot so restored custom places show immediately
    # rather than waiting for the nightly recompute.
    if counts['custom_places']:
        _bust_user_cache(user.id)
        _refresh_places_snapshot(user)

    return JsonResponse({'status': 'ok', 'restored': counts, 'errors': errors})


def _safe_float(value):
    """Convert a value to float, returning None for empty/invalid/NaN values."""
    if value is None:
        return None
    value = str(value).strip()
    if not value:
        return None
    try:
        result = float(value)
        if math.isnan(result) or math.isinf(result):
            return None
        return result
    except (ValueError, TypeError):
        return None


def _get_csv_field(row, *candidates):
    """Get a field from a CSV row, trying multiple possible column names."""
    for name in candidates:
        val = row.get(name)
        if val is not None and str(val).strip():
            return str(val).strip()
        for key in row:
            if key and key.strip().lower() == name.lower():
                val = row[key]
                if val is not None and str(val).strip():
                    return str(val).strip()
    return None


def _parse_date_only(value):
    """Parse a plain YYYY-MM-DD calendar date string into a date (or None)."""
    if not value:
        return None
    value = str(value).strip()
    if not value:
        return None
    try:
        return datetime.strptime(value[:10], '%Y-%m-%d').date()
    except (ValueError, TypeError):
        return None


def _parse_timestamp(value):
    """Parse a timestamp string in various formats."""
    if not value:
        return None
    value = str(value).strip()
    if not value:
        return None

    # Try ISO format first
    for fmt_value in [value, value.replace('Z', '+00:00')]:
        try:
            ts = datetime.fromisoformat(fmt_value)
            if timezone.is_naive(ts):
                ts = timezone.make_aware(ts)
            return ts
        except (ValueError, TypeError):
            pass

    # Try common formats
    for fmt in [
        '%Y-%m-%d %H:%M:%S',
        '%Y-%m-%dT%H:%M:%S',
        '%Y-%m-%d %H:%M:%S%z',
        '%Y-%m-%dT%H:%M:%S%z',
        '%Y/%m/%d %H:%M:%S',
        '%m/%d/%Y %H:%M:%S',
        '%d/%m/%Y %H:%M:%S',
        '%Y-%m-%d %H:%M',
        '%d-%m-%Y %H:%M:%S',
        '%d.%m.%Y %H:%M:%S',
        '%Y.%m.%d %H:%M:%S',
        '%Y-%m-%dT%H:%M:%S.%f',
        '%Y-%m-%dT%H:%M:%S.%f%z',
        '%a %b %d %H:%M:%S %Z %Y',
        '%Y-%m-%d',
        '%m/%d/%Y',
        '%d/%m/%Y',
    ]:
        try:
            ts = datetime.strptime(value, fmt)
            if timezone.is_naive(ts):
                ts = timezone.make_aware(ts)
            return ts
        except (ValueError, TypeError):
            pass

    # Try unix timestamp
    try:
        ts_float = float(value)
        if ts_float > 1e12:  # milliseconds
            ts_float /= 1000
        return datetime.fromtimestamp(ts_float, tz=dt_timezone.utc)
    except (ValueError, TypeError, OverflowError, OSError):
        pass

    return None


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def import_csv(request):
    """Import locations from CSV."""
    f = request.FILES.get('file')
    if not f:
        return JsonResponse({"error": "No file uploaded"}, status=400)

    try:
        decoded = f.read().decode('utf-8-sig')  # utf-8-sig handles BOM
    except UnicodeDecodeError:
        f.seek(0)
        decoded = f.read().decode('latin-1')

    reader = csv.DictReader(io.StringIO(decoded))
    count = 0
    errors = 0
    first_error = None
    override_device_id = request.POST.get('device_id', '').strip()

    for row in reader:
        try:
            device_id = override_device_id or (
                _get_csv_field(row, 'device', 'device_id', 'deviceId', 'Device') or 'import'
            )
            device, _ = Device.objects.get_or_create(
                user=request.user, device_id=device_id,
                defaults={'name': device_id}
            )

            lat = _safe_float(
                _get_csv_field(row, 'latitude', 'lat', 'Latitude', 'Lat',
                               'position_lat', 'y', 'Y', 'LATITUDE', 'LAT')
            )
            lon = _safe_float(
                _get_csv_field(row, 'longitude', 'lng', 'lon', 'long',
                               'Longitude', 'Lng', 'Lon', 'Long',
                               'position_long', 'x', 'X', 'LONGITUDE', 'LON')
            )

            if lat is None or lon is None:
                raise ValueError(
                    f"Missing lat/lon. Columns found: {list(row.keys())}"
                )

            if not (-90 <= lat <= 90) or not (-180 <= lon <= 180):
                raise ValueError(f"Coordinates out of range: lat={lat} lon={lon}")

            ts_raw = _get_csv_field(
                row, 'timestamp', 'time', 'datetime', 'date', 'created_at',
                'Timestamp', 'Time', 'DateTime', 'Date', 'timestamp_iso',
                'local_timestamp', 'utc_timestamp', 'time_local', 'date_time',
                'recorded_at', 'starttime', 'date/time',
            )
            ts = _parse_timestamp(ts_raw)
            if ts is None:
                ts = timezone.now()

            Location.objects.get_or_create(
                device=device,
                latitude=lat,
                longitude=lon,
                timestamp=ts,
                defaults={
                    'altitude': _safe_float(
                        _get_csv_field(row, 'altitude', 'alt', 'elevation', 'ele',
                                       'altitude_m', 'enhanced_altitude', 'gps_altitude')
                    ),
                    'accuracy': _safe_float(
                        _get_csv_field(row, 'accuracy', 'acc', 'hdop', 'horizontal_accuracy',
                                       'position_accuracy', 'gps_accuracy')
                    ),
                    'speed': _safe_float(
                        _get_csv_field(row, 'speed', 'vel', 'velocity', 'speed_m_s',
                                       'enhanced_speed', 'ground_speed', 'speed_ms')
                    ),
                    'battery': _safe_float(
                        _get_csv_field(row, 'battery', 'batt', 'battery_level', 'battery_pct')
                    ),
                    'city': _get_csv_field(row, 'city', 'City', 'locality') or '',
                    'state': _get_csv_field(row, 'state', 'State', 'province', 'region', 'administrative_area') or '',
                    'country': _get_csv_field(row, 'country', 'Country', 'country_name') or '',
                    'country_code': _get_csv_field(row, 'country_code', 'countryCode', 'cc', 'iso_country') or '',
                }
            )
            count += 1
        except Exception as e:
            errors += 1
            if first_error is None:
                first_error = str(e)
            logger.warning(f"CSV import error on row {count + errors}: {e}")

    result = {"status": "ok", "imported": count, "errors": errors}
    if first_error and errors > 0:
        result["first_error"] = first_error
    return JsonResponse(result)


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def import_gpx(request):
    """Import locations from GPX file."""
    f = request.FILES.get('file')
    if not f:
        return JsonResponse({"error": "No file uploaded"}, status=400)

    import xml.etree.ElementTree as ET

    try:
        content = f.read().decode('utf-8-sig')
    except UnicodeDecodeError:
        f.seek(0)
        content = f.read().decode('latin-1')

    try:
        root = ET.fromstring(content)
    except ET.ParseError as e:
        return JsonResponse({"error": f"Invalid GPX XML: {e}"}, status=400)

    # Detect namespace from root element (supports GPX 1.0 and 1.1)
    root_tag = root.tag
    gpx_ns = ''
    if root_tag.startswith('{'):
        gpx_ns = root_tag.split('}')[0] + '}'

    device_id = request.POST.get('device_id', 'gpx-import')
    device, _ = Device.objects.get_or_create(
        user=request.user, device_id=device_id,
        defaults={'name': device_id}
    )

    count = 0
    errors = 0
    first_error = None

    def _find_points(tag):
        """Find elements by tag, trying detected namespace then bare tag."""
        if gpx_ns:
            pts = root.findall(f'.//{gpx_ns}{tag}')
            if pts:
                return pts
        return root.findall(f'.//{tag}')

    def _find_child(parent, tag):
        """Find child element, trying detected namespace then bare tag."""
        if gpx_ns:
            el = parent.find(f'{gpx_ns}{tag}')
            if el is not None:
                return el
        return parent.find(tag)

    # Collect track points, route points, and waypoints
    trkpts = list(_find_points('trkpt'))
    trkpts += list(_find_points('rtept'))
    trkpts += list(_find_points('wpt'))

    if not trkpts:
        return JsonResponse({
            "error": "No track points, route points, or waypoints found in GPX file",
            "imported": 0, "errors": 0,
        }, status=400)

    for pt in trkpts:
        try:
            lat = float(pt.get('lat'))
            lon = float(pt.get('lon'))

            time_el = _find_child(pt, 'time')
            if time_el is not None and time_el.text:
                ts = _parse_timestamp(time_el.text)
            else:
                ts = timezone.now()

            ele_el = _find_child(pt, 'ele')
            alt = float(ele_el.text) if ele_el is not None and ele_el.text else None

            Location.objects.get_or_create(
                device=device, latitude=lat, longitude=lon, timestamp=ts,
                defaults={'altitude': alt}
            )
            count += 1
        except Exception as e:
            errors += 1
            if first_error is None:
                first_error = str(e)
            logger.warning(f"GPX import error: {e}")

    result = {"status": "ok", "imported": count, "errors": errors}
    if first_error and errors > 0:
        result["first_error"] = first_error
    return JsonResponse(result)


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def import_json(request):
    """Import locations from JSON (Google Takeout Location History or OwnTracks export)."""
    f = request.FILES.get('file')
    if not f:
        return JsonResponse({"error": "No file uploaded"}, status=400)

    try:
        content = f.read().decode('utf-8-sig')
        data = json.loads(content)
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        return JsonResponse({"error": f"Invalid JSON: {e}"}, status=400)

    override_device_id = request.POST.get('device_id', '').strip()
    count = 0
    errors = 0
    first_error = None

    def _import_one(lat, lon, ts, device_id_val, **kwargs):
        nonlocal count, errors, first_error
        try:
            if lat is None or lon is None:
                raise ValueError("Missing lat/lon")
            if not (-90 <= lat <= 90) or not (-180 <= lon <= 180):
                raise ValueError(f"Coordinates out of range: {lat}, {lon}")
            dev_id = override_device_id or device_id_val or 'json-import'
            device, _ = Device.objects.get_or_create(
                user=request.user, device_id=dev_id, defaults={'name': dev_id}
            )
            Location.objects.get_or_create(
                device=device, latitude=lat, longitude=lon,
                timestamp=ts or timezone.now(),
                defaults=kwargs,
            )
            count += 1
        except Exception as e:
            errors += 1
            if first_error is None:
                first_error = str(e)

    # Google Takeout Records format (new): {"locations": [...], "timelineObjects": [...]}
    # or {"semanticSegments": [...]}
    if isinstance(data, dict) and 'locations' in data:
        for loc in data['locations']:
            lat_raw = loc.get('latitudeE7') or loc.get('latitude')
            lon_raw = loc.get('longitudeE7') or loc.get('longitude')
            lat = float(lat_raw) / 1e7 if lat_raw and abs(float(lat_raw)) > 90 else _safe_float(lat_raw)
            lon = float(lon_raw) / 1e7 if lon_raw and abs(float(lon_raw)) > 180 else _safe_float(lon_raw)
            ts_raw = loc.get('timestamp') or loc.get('timestampMs')
            ts = _parse_timestamp(ts_raw)
            _import_one(lat, lon, ts, 'google-takeout',
                        altitude=_safe_float(loc.get('altitude')),
                        accuracy=_safe_float(loc.get('accuracy') or loc.get('horizontalAccuracy')))

    # OwnTracks JSON array: [{"_type": "location", "lat": ..., "lon": ..., "tst": ...}, ...]
    elif isinstance(data, list) and data and isinstance(data[0], dict):
        for loc in data:
            if loc.get('_type') == 'location' or ('lat' in loc and 'lon' in loc):
                lat = _safe_float(loc.get('lat') or loc.get('latitude'))
                lon = _safe_float(loc.get('lon') or loc.get('longitude'))
                ts_raw = loc.get('tst') or loc.get('timestamp') or loc.get('time')
                ts = _parse_timestamp(str(ts_raw)) if ts_raw else None
                device_id_val = str(loc.get('tid') or loc.get('device_id') or 'owntracks')
                _import_one(lat, lon, ts, device_id_val,
                            altitude=_safe_float(loc.get('alt') or loc.get('altitude')),
                            accuracy=_safe_float(loc.get('acc') or loc.get('accuracy')),
                            speed=_safe_float(loc.get('vel') or loc.get('speed')),
                            battery=_safe_float(loc.get('batt') or loc.get('battery')))
            elif 'latitude' in loc or 'lat' in loc:
                lat = _safe_float(loc.get('latitude') or loc.get('lat'))
                lon = _safe_float(loc.get('longitude') or loc.get('lon') or loc.get('lng'))
                ts = _parse_timestamp(str(loc.get('timestamp') or loc.get('time') or ''))
                _import_one(lat, lon, ts, 'json-import',
                            altitude=_safe_float(loc.get('altitude') or loc.get('alt')),
                            accuracy=_safe_float(loc.get('accuracy')),
                            speed=_safe_float(loc.get('speed')))
    else:
        return JsonResponse({"error": "Unrecognized JSON format. Expected Google Takeout {'locations':[...]} or OwnTracks array [{...}]."}, status=400)

    result = {"status": "ok", "imported": count, "errors": errors}
    if first_error and errors > 0:
        result["first_error"] = first_error
    return JsonResponse(result)


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def import_kml(request):
    """Import locations from KML file (FlightRadar24, Google Earth, etc.)."""
    f = request.FILES.get('file')
    if not f:
        return JsonResponse({"error": "No file uploaded"}, status=400)

    import xml.etree.ElementTree as ET
    import re as _re
    import zipfile as _zipfile
    import io as _io

    raw = f.read()
    # KMZ is a zip archive containing doc.kml (or the first *.kml entry)
    if f.name.lower().endswith('.kmz') or raw[:2] == b'PK':
        try:
            with _zipfile.ZipFile(_io.BytesIO(raw)) as zf:
                kml_names = [n for n in zf.namelist() if n.lower().endswith('.kml')]
                if not kml_names:
                    return JsonResponse({"error": "No .kml file found inside KMZ archive"}, status=400)
                # Prefer doc.kml; otherwise take the first one
                entry = next((n for n in kml_names if n.lower() == 'doc.kml'), kml_names[0])
                raw = zf.read(entry)
        except _zipfile.BadZipFile as e:
            return JsonResponse({"error": f"Invalid KMZ archive: {e}"}, status=400)

    try:
        content = raw.decode('utf-8-sig')
    except UnicodeDecodeError:
        content = raw.decode('latin-1')

    try:
        root = ET.fromstring(content)
    except ET.ParseError as e:
        return JsonResponse({"error": f"Invalid KML XML: {e}"}, status=400)

    # Detect KML namespace from root element tag
    root_tag = root.tag
    kml_ns = ''
    if root_tag.startswith('{'):
        kml_ns = root_tag[:root_tag.index('}') + 1]

    GX_NS = '{http://www.google.com/kml/ext/2.2}'

    def _ns(tag):
        """Try element search with detected KML namespace, then bare tag."""
        return f'{kml_ns}{tag}' if kml_ns else tag

    def _iter_either(parent, tag):
        """Iterate elements matching tag with or without KML namespace."""
        results = list(parent.iter(_ns(tag)))
        if not results and kml_ns:
            results = list(parent.iter(tag))
        return results

    def _parse_kml_coord(text):
        """Parse a single KML coordinate string: lon,lat[,alt] → (lat, lon, alt)."""
        parts = text.strip().split(',')
        if len(parts) < 2:
            return None
        lon = _safe_float(parts[0])
        lat = _safe_float(parts[1])
        alt = _safe_float(parts[2]) if len(parts) > 2 else None
        if lat is None or lon is None:
            return None
        if not (-90 <= lat <= 90) or not (-180 <= lon <= 180):
            return None
        return lat, lon, alt

    device_id = request.POST.get('device_id', 'kml-import')
    device, _ = Device.objects.get_or_create(
        user=request.user, device_id=device_id,
        defaults={'name': device_id}
    )

    points = []  # list of (lat, lon, alt_or_None, ts_or_None)

    def _parse_fr24_description(text):
        """Extract altitude (m) and speed (m/s) from FlightRadar24 CDATA HTML.

        FR24 embeds these as labelled <span> pairs inside the description, e.g.:
          <b>Altitude:</b> <span>35000 ft</span>
          <b>Speed:</b>    <span>450 kt</span>
        """
        if not text:
            return None, None
        alt_m = None
        speed_ms = None
        alt_match = _re.search(
            r'Altitude[^<]*</[^>]+>\s*<span>([\d.]+)\s*ft</span>', text, _re.IGNORECASE
        )
        if alt_match:
            ft = _safe_float(alt_match.group(1))
            if ft is not None:
                alt_m = ft / 3.28084
        speed_match = _re.search(
            r'Speed[^<]*</[^>]+>\s*<span>([\d.]+)\s*kt</span>', text, _re.IGNORECASE
        )
        if speed_match:
            kt = _safe_float(speed_match.group(1))
            if kt is not None:
                speed_ms = kt * 0.514444
        return alt_m, speed_ms

    # Points are (lat, lon, coord_alt, ts, desc_alt, speed).
    # desc_alt wins over coord_alt when present (FR24 reports ft in description;
    # coord alt may be 0 for all points). speed is None for non-FR24 sources.

    # Strategy 1: gx:Track (Google Earth track exports).
    # Direct children alternate between <when> and <gx:coord> in order.
    for track in root.iter(f'{GX_NS}Track'):
        children = list(track)
        whens = [el.text for el in children
                 if el.tag == _ns('when') or el.tag == 'when']
        coords = [el.text for el in children if el.tag == f'{GX_NS}coord']
        for w, c in zip(whens, coords):
            if not c:
                continue
            parsed = _parse_kml_coord(c)
            if parsed:
                points.append((*parsed, _parse_timestamp(w) if w else None, None, None))

    # Strategy 2: <Placemark><Point><coordinates> with optional <TimeStamp><when>.
    # FlightRadar24 uses this format with speed/altitude in the <description> CDATA.
    if not points:
        for pm in _iter_either(root, 'Placemark'):
            ts = None
            for ts_el in _iter_either(pm, 'when'):
                ts = _parse_timestamp(ts_el.text)
                break
            desc_alt, speed = None, None
            for desc_el in _iter_either(pm, 'description'):
                desc_alt, speed = _parse_fr24_description(desc_el.text or '')
                break
            for pt in _iter_either(pm, 'Point'):
                for coord_el in _iter_either(pt, 'coordinates'):
                    if coord_el.text:
                        parsed = _parse_kml_coord(coord_el.text.strip())
                        if parsed:
                            points.append((*parsed, ts, desc_alt, speed))

    # Strategy 3: <LineString><coordinates> — space-separated lon,lat[,alt] tuples,
    # no per-point timestamps. Spread across LineStrings (e.g. route exports).
    if not points:
        for ls in _iter_either(root, 'LineString'):
            for coord_el in _iter_either(ls, 'coordinates'):
                if not coord_el.text:
                    continue
                for token in _re.split(r'\s+', coord_el.text.strip()):
                    token = token.strip()
                    if not token:
                        continue
                    parsed = _parse_kml_coord(token)
                    if parsed:
                        points.append((*parsed, None, None, None))

    if not points:
        return JsonResponse(
            {"error": "No track points found in KML file", "imported": 0, "errors": 0},
            status=400,
        )

    count = 0
    errors = 0
    first_error = None

    for lat, lon, coord_alt, ts, desc_alt, speed in points:
        # Prefer description altitude (ft→m) over the coordinate field, which
        # FlightRadar24 leaves as 0 for every point even during cruise.
        alt = desc_alt if desc_alt is not None else coord_alt
        try:
            Location.objects.get_or_create(
                device=device, latitude=lat, longitude=lon,
                timestamp=ts or timezone.now(),
                defaults={'altitude': alt, 'speed': speed},
            )
            count += 1
        except Exception as e:
            errors += 1
            if first_error is None:
                first_error = str(e)
            logger.warning(f"KML import error: {e}")

    if count:
        ensure_auto_geocode(request.user.id)

    result = {"status": "ok", "imported": count, "errors": errors}
    if first_error and errors > 0:
        result["first_error"] = first_error
    return JsonResponse(result)


# ---------------------------------------------------------------------------
# API Keys Management
# ---------------------------------------------------------------------------

@login_required
@csrf_exempt
@require_http_methods(["POST"])
def create_api_key(request):
    name = request.POST.get('name', 'My Device')
    api_key = APIKey(user=request.user, name=name)
    api_key.save()
    _log_action(request, 'api_key_create', description=name[:200])
    return JsonResponse({"status": "ok", "key": api_key.key, "name": api_key.name, "id": api_key.id})


@login_required
@csrf_exempt
@require_http_methods(["GET", "POST"])
def app_api_key(request):
    """Return the caller's single app tracking key, creating one only if absent.

    Idempotent by design: the mobile app calls this to obtain the one durable
    key it pushes locations with. Logging in repeatedly therefore never spawns
    duplicate keys — there is just one, and it works forever (keys don't expire).
    Reuses the oldest active key if any already exist (e.g. created via the web)."""
    api_key = (
        APIKey.objects.filter(user=request.user, is_active=True)
        .order_by('created_at')
        .first()
    )
    if api_key is None:
        api_key = APIKey(user=request.user, name="Roamly Android")
        api_key.save()
    return JsonResponse({"status": "ok", "key": api_key.key, "name": api_key.name, "id": api_key.id})


@login_required
@csrf_exempt
@require_http_methods(["POST", "DELETE"])
def delete_api_key(request, key_id):
    api_key = get_object_or_404(APIKey, id=key_id, user=request.user)
    key_name = api_key.name
    api_key.delete()
    _log_action(request, 'api_key_delete', description=(key_name or '')[:200])
    return JsonResponse({"status": "ok"})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def rename_api_key(request, key_id):
    api_key = get_object_or_404(APIKey, id=key_id, user=request.user)
    name = (request.POST.get('name') or '').strip()
    if not name:
        return JsonResponse({"error": "Name cannot be empty"}, status=400)
    api_key.name = name[:100]
    api_key.save(update_fields=['name'])
    return JsonResponse({"status": "ok", "name": api_key.name})


# ---------------------------------------------------------------------------
# Devices Management
# ---------------------------------------------------------------------------

@login_required
def devices_api(request):
    """Get list of user's devices."""
    devices = Device.objects.filter(user=request.user).order_by('-created_at')
    return JsonResponse({
        "devices": [
            {
                "id": d.id,
                "device_id": d.device_id,
                "name": d.name or d.device_id,
            }
            for d in devices
        ]
    })


# ---------------------------------------------------------------------------
# Account / Danger Zone
# ---------------------------------------------------------------------------

@login_required
@require_http_methods(["POST"])
def delete_location_data(request):
    """Delete location data for the authenticated user within a time range."""
    try:
        body = json.loads(request.body)
    except (json.JSONDecodeError, ValueError):
        return JsonResponse({"error": "Invalid JSON"}, status=400)

    range_val = body.get("range", "all")
    locations = Location.objects.filter(device__user=request.user)

    if range_val != "all":
        try:
            days = int(range_val)
        except (ValueError, TypeError):
            return JsonResponse({"error": "Invalid range"}, status=400)
        cutoff = timezone.now() - timedelta(days=days)
        locations = locations.filter(timestamp__gte=cutoff)

    count, _ = locations.delete()
    _log_action(request, 'delete_data', description=f"range={range_val}, deleted={count}")
    return JsonResponse({"status": "ok", "deleted": count})


@login_required
@csrf_exempt
@require_http_methods(["DELETE"])
def delete_location(request, location_id):
    """Delete a single location point."""
    deleted, _ = Location.objects.filter(
        id=location_id, device__user=request.user
    ).delete()
    if not deleted:
        return JsonResponse({"error": "Not found"}, status=404)
    _bust_user_cache(request.user.id)
    return JsonResponse({"status": "ok"})


@login_required
@require_http_methods(["POST"])
def delete_account(request):
    """Delete the user's account and all associated data."""
    user = request.user
    username = user.username
    # Log with user=None (the row must outlive the account) before deletion, so
    # the async writer doesn't insert a now-dangling FK after the user is gone.
    _log_action(request, 'delete_account', description=f"username={username}", user=False)
    logout(request)
    user.delete()
    return JsonResponse({"status": "ok"})


# ---------------------------------------------------------------------------
# Search
# ---------------------------------------------------------------------------

_CLUSTER_MAX_DEG = 1.0  # ~111 km — POIs within this spread share one bbox query


def _cluster_pois(pois):
    """Group POIs into geographic clusters so each cluster fits within _CLUSTER_MAX_DEG degrees.

    Reduces the number of Location DB queries from one-per-POI down to one-per-cluster.
    """
    clusters = []
    for poi in sorted(pois, key=lambda p: (p.latitude, p.longitude)):
        for cluster in clusters:
            lats = [p.latitude for p in cluster]
            lngs = [p.longitude for p in cluster]
            if (max(max(lats), poi.latitude) - min(min(lats), poi.latitude) <= _CLUSTER_MAX_DEG
                    and max(max(lngs), poi.longitude) - min(min(lngs), poi.longitude) <= _CLUSTER_MAX_DEG):
                cluster.append(poi)
                break
        else:
            clusters.append([poi])
    return clusters


def _search_local_pois(query, user_locations_qs, radius_m=150):
    """Search POIs against user history. Uses pre-computed Visit table when
    available (fast); falls back to on-the-fly proximity scan otherwise."""
    from rapidfuzz import fuzz as _fuzz
    from django.db.models import Q

    query = query.strip()
    if not query:
        return []
    q_lower = query.lower()
    _FUZZY_THRESHOLD = 65

    uid_row = user_locations_qs.values('device__user_id').first()
    if not uid_row:
        return []
    user_id = uid_row['device__user_id']

    # --- Fast path: pre-computed Visit table ---
    from .models import Visit
    words = [w for w in query.split() if len(w) >= 4]
    vfilter = Q(poi__name__icontains=query)
    for w in words:
        vfilter |= Q(poi__name__icontains=w[:4])

    visits = list(
        Visit.objects.filter(vfilter, device__user_id=user_id, poi__isnull=False)
        .select_related('poi')
        .order_by('poi_id', 'start_time')
    )

    has_any_visits = visits or Visit.objects.filter(device__user_id=user_id).exists()

    if visits:
        # Score POI names with fuzzy matching
        poi_scores = {}
        for v in visits:
            if v.poi_id not in poi_scores:
                sc = _fuzz.token_set_ratio(q_lower, v.poi.name.lower())
                poi_scores[v.poi_id] = (sc, v.poi)

        matching_ids = {pid for pid, (sc, _) in poi_scores.items() if sc >= _FUZZY_THRESHOLD}
        if matching_ids:
            from collections import defaultdict
            poi_days = defaultdict(lambda: defaultdict(lambda: {'count': 0, 'time_spent': 0.0}))
            for v in visits:
                if v.poi_id not in matching_ids:
                    continue
                date_str = v.start_time.date().isoformat()
                dur = (v.end_time - v.start_time).total_seconds()
                poi_days[v.poi_id][date_str]['count'] += v.point_count
                poi_days[v.poi_id][date_str]['time_spent'] += dur

            results = []
            for poi_id, days in poi_days.items():
                sc, poi = poi_scores[poi_id]
                day_list = [
                    {'date': d, 'count': s['count'], 'time_spent': int(s['time_spent'])}
                    for d, s in days.items() if s['time_spent'] >= 300
                ]
                if not day_list:
                    continue
                day_list.sort(key=lambda d: d['date'], reverse=True)
                total_time = sum(d['time_spent'] for d in day_list)
                total_pts = sum(d['count'] for d in day_list)
                display = f"{poi.name}, {poi.address}" if poi.address else poi.name
                rank = 0 if (sc == 100 and poi.name.lower() == q_lower) else (1 if sc >= 85 else 2)
                results.append({
                    'place_name': display,
                    'lat': poi.latitude,
                    'lng': poi.longitude,
                    'days': day_list,
                    'total_points': total_pts,
                    '_sort': (rank, -total_time, -total_pts, display),
                })
            results.sort(key=lambda r: r['_sort'])
            for r in results:
                r.pop('_sort', None)
            return results

    # --- Fallback: on-the-fly proximity scan (used before visit computation runs) ---
    if has_any_visits:
        # Visit computation has run but returned nothing for this query.
        return []

    # No visits at all yet — scan location history directly.
    cfilter = Q(name__icontains=query)
    for w in words:
        cfilter |= Q(name__icontains=w[:4])
    candidates = list(POI.objects.filter(cfilter).distinct()[:400])
    if not candidates:
        return []

    scored = [(s, p) for p in candidates
              for s in [_fuzz.token_set_ratio(q_lower, p.name.lower())]
              if s >= _FUZZY_THRESHOLD]
    if not scored:
        return []
    scored.sort(key=lambda x: -x[0])
    matching_pois = [p for _, p in scored]
    for sc, p in scored:
        p.match_rank = 0 if (sc == 100 and p.name.lower() == q_lower) else (1 if sc >= 85 else 2)

    if not matching_pois:
        return []

    delta = radius_m / 111000.0
    results = []

    for cluster in _cluster_pois(matching_pois):
        min_lat = min(p.latitude for p in cluster) - delta
        max_lat = max(p.latitude for p in cluster) + delta
        min_lng = min(p.longitude for p in cluster) - delta
        max_lng = max(p.longitude for p in cluster) + delta

        # One query per cluster instead of one per POI.
        bbox_locs = list(
            user_locations_qs.filter(
                latitude__gte=min_lat, latitude__lte=max_lat,
                longitude__gte=min_lng, longitude__lte=max_lng,
            ).order_by('timestamp').values(
                'timestamp', 'latitude', 'longitude', 'city', 'state', 'device__name'
            )
        )

        if not bbox_locs:
            continue

        for poi in cluster:
            nearby = [
                loc for loc in bbox_locs
                if abs(loc['latitude'] - poi.latitude) <= delta
                and abs(loc['longitude'] - poi.longitude) <= delta
            ]
            if not nearby:
                continue

            day_data = _group_by_day_dicts(nearby)
            day_data = [d for d in day_data if d['time_spent'] >= 300]
            if not day_data:
                continue

            total_points = sum(d['count'] for d in day_data)
            total_time_spent = sum(d['time_spent'] for d in day_data)
            display = f"{poi.name}, {poi.address}" if poi.address else poi.name
            results.append({
                'place_name': display,
                'lat': poi.latitude,
                'lng': poi.longitude,
                'days': day_data,
                'total_points': total_points,
                'total_time_spent': total_time_spent,
                'match_rank': poi.match_rank,
            })

    results.sort(
        key=lambda r: (r['match_rank'], -r['total_time_spent'], -r['total_points'], r['place_name'])
    )
    for r in results:
        r.pop('total_time_spent', None)
        r.pop('match_rank', None)
    return results


def _find_nearby_locations(base_qs, lat, lng, radius_m):
    """Filter a Location queryset to points within radius_m of (lat, lng)."""
    if HAS_POSTGIS and Point:
        from django.contrib.gis.measure import D
        ref = Point(lng, lat, srid=4326)
        return base_qs.filter(location__distance_lte=(ref, D(m=radius_m)))
    else:
        delta = radius_m / 111000.0
        return base_qs.filter(
            latitude__gte=lat - delta, latitude__lte=lat + delta,
            longitude__gte=lng - delta, longitude__lte=lng + delta,
        )


def _calc_dwell_time(qs, max_gap=600):
    """Calculate dwell time from a queryset, only summing gaps under max_gap seconds.

    If consecutive points are more than max_gap apart, that gap is excluded
    (the user likely left and came back). Default max_gap is 10 minutes.
    """
    timestamps = list(qs.order_by('timestamp').values_list('timestamp', flat=True))
    if len(timestamps) < 2:
        return 0
    total = 0
    for i in range(1, len(timestamps)):
        gap = (timestamps[i] - timestamps[i - 1]).total_seconds()
        if gap <= max_gap:
            total += gap
    return int(total)


def _group_by_day(qs):
    """Group a Location queryset by date, returning summary dicts."""
    by_day = {}

    rows = qs.order_by('timestamp').values_list(
        'timestamp', 'city', 'state', 'device__name'
    )

    for ts, city, state, device_name in rows.iterator(chunk_size=5000):
        day_str = ts.date().isoformat()
        day = by_day.get(day_str)
        if day is None:
            day = {
                'date': day_str,
                'count': 0,
                'first_ts': ts,
                'last_ts': ts,
                'time_spent': 0,
                'cities_set': set(),
                'cities': [],
                'devices_set': set(),
                'devices': [],
                'prev_ts': None,
            }
            by_day[day_str] = day

        day['count'] += 1
        day['last_ts'] = ts

        if day['prev_ts'] is not None:
            gap = (ts - day['prev_ts']).total_seconds()
            if gap <= 600:
                day['time_spent'] += int(gap)
        day['prev_ts'] = ts

        city_key = (city or '', state or '')
        if city and city_key not in day['cities_set'] and len(day['cities']) < 5:
            day['cities_set'].add(city_key)
            day['cities'].append({'city': city, 'state': state or ''})

        if device_name and device_name not in day['devices_set'] and len(day['devices']) < 5:
            day['devices_set'].add(device_name)
            day['devices'].append(device_name)

    result = []
    for day_str in sorted(by_day.keys(), reverse=True)[:100]:
        day = by_day[day_str]
        result.append({
            'date': day['date'],
            'count': day['count'],
            'first_ts': day['first_ts'].isoformat() if day['first_ts'] else None,
            'last_ts': day['last_ts'].isoformat() if day['last_ts'] else None,
            'time_spent': day['time_spent'],
            'cities': day['cities'],
            'devices': day['devices'],
        })
    return result


def _compute_day_detail(user, date_str, tz_offset=0):
    """Everything recorded on one local calendar day: every city/state/country,
    named POIs, custom places entered, distance, and the active time range.
    Read-only; used by the AI `get_day_detail` tool so it can name specific stops
    rather than only a state-level match. ``tz_offset`` (browser minutes) bounds
    the day to the user's local midnight rather than the server's UTC day."""
    try:
        d = date.fromisoformat(str(date_str))
    except (ValueError, TypeError):
        return {"error": "date must be YYYY-MM-DD"}

    start, end = _journal_day_bounds(d, tz_offset)
    base = Location.objects.filter(device__user=user, timestamp__gte=start, timestamp__lte=end)
    total = base.count()
    if not total:
        return {"date": str(date_str), "point_count": 0,
                "note": "No location points were recorded that day."}

    first = base.order_by('timestamp').values_list('timestamp', flat=True).first()
    last = base.order_by('-timestamp').values_list('timestamp', flat=True).first()

    # order_by() on each: Location's Meta.ordering ['-timestamp'] otherwise leaks
    # into these DISTINCTs (timestamp forced into the SELECT), breaking them — the
    # .distinct()[:50] ones in particular would return duplicate, incomplete rows.
    cities = [
        {"city": r['city'], "state": r['state'], "country": r['country']}
        for r in base.exclude(city='').order_by().values('city', 'state', 'country').distinct()[:50]
    ]
    states = sorted({s for s in base.exclude(state='').order_by().values_list('state', flat=True).distinct()})[:50]
    countries = sorted({c for c in base.exclude(country='').order_by().values_list('country', flat=True).distinct()})
    pois = [p for p in base.filter(poi__isnull=False).order_by().values_list('poi__name', flat=True).distinct()[:50] if p]

    # Custom places whose geofence contains any of the day's points.
    custom = [p.name for p in CustomPlace.objects.filter(user=user)
              if _find_nearby_locations(base, p.latitude, p.longitude, p.radius_m).exists()]

    distance = _compute_distance_from_qs(base)
    return {
        "date": str(date_str),
        "point_count": total,
        "first_ts": first.isoformat() if first else None,
        "last_ts": last.isoformat() if last else None,
        "distance_km": distance.get("total_km", 0),
        "cities": cities,
        "states": states,
        "countries": countries,
        "pois": pois,
        "custom_places": custom,
    }


def _group_by_day_dicts(locs):
    """Like _group_by_day but takes a pre-fetched list of dicts (sorted by timestamp).

    Used by _search_local_pois so no extra DB query is needed per POI.
    """
    by_day = {}
    for loc in locs:
        ts = loc['timestamp']
        day_str = ts.date().isoformat()
        day = by_day.get(day_str)
        if day is None:
            day = {
                'date': day_str,
                'count': 0,
                'first_ts': ts,
                'last_ts': ts,
                'time_spent': 0,
                'cities_set': set(),
                'cities': [],
                'devices_set': set(),
                'devices': [],
                'prev_ts': None,
            }
            by_day[day_str] = day

        day['count'] += 1
        day['last_ts'] = ts

        if day['prev_ts'] is not None:
            gap = (ts - day['prev_ts']).total_seconds()
            if gap <= 600:
                day['time_spent'] += int(gap)
        day['prev_ts'] = ts

        city = loc.get('city') or ''
        state = loc.get('state') or ''
        device_name = loc.get('device__name') or ''

        city_key = (city, state)
        if city and city_key not in day['cities_set'] and len(day['cities']) < 5:
            day['cities_set'].add(city_key)
            day['cities'].append({'city': city, 'state': state})

        if device_name and device_name not in day['devices_set'] and len(day['devices']) < 5:
            day['devices_set'].add(device_name)
            day['devices'].append(device_name)

    result = []
    for day_str in sorted(by_day.keys(), reverse=True)[:100]:
        day = by_day[day_str]
        result.append({
            'date': day['date'],
            'count': day['count'],
            'first_ts': day['first_ts'].isoformat() if day['first_ts'] else None,
            'last_ts': day['last_ts'].isoformat() if day['last_ts'] else None,
            'time_spent': day['time_spent'],
            'cities': day['cities'],
            'devices': day['devices'],
        })
    return result


def _parse_date_search_query(query):
    """Parse common date query formats into [start, end) datetimes."""
    text = (query or '').strip()
    if not text:
        return None

    lower = text.lower()
    now = timezone.now()

    if lower == 'today':
        start = now.replace(hour=0, minute=0, second=0, microsecond=0)
        end = start + timedelta(days=1)
        return {'start': start, 'end': end, 'kind': 'day'}

    if lower == 'yesterday':
        end = now.replace(hour=0, minute=0, second=0, microsecond=0)
        start = end - timedelta(days=1)
        return {'start': start, 'end': end, 'kind': 'day'}

    day_formats = [
        '%Y-%m-%d',
        '%Y/%m/%d',
        '%b %d %Y',
        '%B %d %Y',
    ]
    month_formats = [
        '%Y-%m',
        '%Y/%m',
        '%b %Y',
        '%B %Y',
    ]

    for fmt in day_formats:
        try:
            d = datetime.strptime(text, fmt)
            start = timezone.make_aware(datetime(d.year, d.month, d.day, 0, 0, 0))
            end = start + timedelta(days=1)
            return {'start': start, 'end': end, 'kind': 'day'}
        except ValueError:
            continue

    for fmt in month_formats:
        try:
            d = datetime.strptime(text, fmt)
            start = timezone.make_aware(datetime(d.year, d.month, 1, 0, 0, 0))
            if d.month == 12:
                end = timezone.make_aware(datetime(d.year + 1, 1, 1, 0, 0, 0))
            else:
                end = timezone.make_aware(datetime(d.year, d.month + 1, 1, 0, 0, 0))
            return {'start': start, 'end': end, 'kind': 'month'}
        except ValueError:
            continue

    if text.isdigit() and len(text) == 4:
        year = int(text)
        if 1900 <= year <= 2100:
            start = timezone.make_aware(datetime(year, 1, 1, 0, 0, 0))
            end = timezone.make_aware(datetime(year + 1, 1, 1, 0, 0, 0))
            return {'start': start, 'end': end, 'kind': 'year'}

    return None


@login_required
def search_view(request):
    return render(request, 'tracker/search.html')


@login_required
def search_api(request):
    """Search location history by text, current location, or place name."""
    mode = request.GET.get('mode', 'text')
    q = request.GET.get('q', '').strip()
    locations = Location.objects.filter(device__user=request.user)
    gen = cache.get(f"cache_gen:{request.user.id}", 0)

    if mode == 'here':
        try:
            lat = float(request.GET['lat'])
            lng = float(request.GET['lng'])
        except (KeyError, ValueError):
            return JsonResponse({'error': 'lat and lng required'}, status=400)
        radius_m = float(request.GET.get('radius', 100))

        cache_key = (
            f"search:{request.user.id}:{gen}:here:"
            f"{round(lat, 5)}:{round(lng, 5)}:{int(radius_m)}"
        )
        cached = cache.get(cache_key)
        if cached is not None:
            return JsonResponse(cached)

        nearby = _find_nearby_locations(locations, lat, lng, radius_m)
        days = _group_by_day(nearby)
        payload = {
            'mode': 'here',
            'results': days,
            'total_days': len(days),
            'total_points': sum(d['count'] for d in days),
        }
        cache.set(cache_key, payload, timeout=120)
        return JsonResponse(payload)

    if not q:
        return JsonResponse({'error': 'q parameter required'}, status=400)

    cache_key = f"search:{request.user.id}:{gen}:text:{q.lower()}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

    payload = _run_history_search(request.user, q)
    cache.set(cache_key, payload, timeout=120)
    return JsonResponse(payload)


def _run_history_search(user, q):
    """Core text/date history search shared by the search_api view and the AI
    `search_history` tool. Returns the payload dict search_api serializes for
    text/date queries (the `mode == 'here'` proximity branch stays in the view).
    Caller is responsible for caching."""
    locations = Location.objects.filter(device__user=user)
    parsed_date = _parse_date_search_query(q)

    # Location name results (city/state/place_name matches)
    if parsed_date:
        filtered = locations.filter(
            timestamp__gte=parsed_date['start'],
            timestamp__lt=parsed_date['end'],
        )
    else:
        filtered = locations.filter(
            Q(city__icontains=q) | Q(state__icontains=q) | Q(place_name__icontains=q)
        )
    days = _group_by_day(filtered)

    # For text-based town/state/place searches, show the searched term in the location column
    # so each result row reflects the query rather than listing all nearby cities for that day.
    if not parsed_date:
        for d in days:
            d['matched_query'] = q
            d['cities'] = [{'city': q, 'state': ''}]

    # Place results (from local POI database)
    place_results = []
    needs_download = False
    places_checked = 0
    if not parsed_date and POI.objects.count() > 0:
        place_results = _search_local_pois(q, locations)
        places_checked = len(place_results)
    elif not parsed_date:
        needs_download = True

    # User-defined custom places matching the query rank above OSM POIs.
    if not parsed_date:
        custom_matches = []
        for p in CustomPlace.objects.filter(user=user, name__icontains=q):
            nearby = _find_nearby_locations(locations, p.latitude, p.longitude, p.radius_m)
            p_days = _group_by_day(nearby)
            if not p_days:
                continue
            custom_matches.append({
                'place_name': p.name,
                'lat': _jf(p.latitude),
                'lng': _jf(p.longitude),
                'total_points': sum(d['count'] for d in p_days),
                'days': p_days,
                'is_custom': True,
            })
        place_results = custom_matches + place_results
        places_checked = len(place_results)

    return {
        'query': q,
        'query_type': 'date' if parsed_date else 'text',
        'location_results': days,
        'total_days': len(days),
        'total_points': sum(d['count'] for d in days),
        'place_results': place_results,
        'places_checked': places_checked,
        'needs_download': needs_download,
    }


# ---------------------------------------------------------------------------
# Custom Places (user-defined named geofences)
# ---------------------------------------------------------------------------

# Auto-assigned accent colors, cycled by the user's place count (Field Journal palette).
_PLACE_COLORS = ['#e8763d', '#7f9b6d', '#7ba3b8', '#a888a0', '#cf9a44']


def _serialize_place(place):
    return {
        'id': place.id,
        'name': place.name,
        'lat': _jf(place.latitude),
        'lng': _jf(place.longitude),
        'radius_m': _jf(place.radius_m),
        'color': place.color or _PLACE_COLORS[0],
        'notes': place.notes or '',
    }


def _place_membership(places, lat, lng):
    """Return the nearest CustomPlace (from a small in-memory list) that contains
    (lat, lng), or None. Used to label data-table rows without a spatial join."""
    best = None
    best_dist = None
    for p in places:
        # bbox prefilter, then exact haversine
        delta = (p.radius_m / 111000.0) + 1e-6
        if abs(p.latitude - lat) > delta or abs(p.longitude - lng) > delta:
            continue
        dist_m = _haversine_km(lat, lng, p.latitude, p.longitude) * 1000.0
        if dist_m <= p.radius_m and (best_dist is None or dist_m < best_dist):
            best, best_dist = p, dist_m
    return best


@login_required
@csrf_exempt
@require_http_methods(["GET", "POST"])
def places_api(request):
    """List the user's custom places (with light stats) or create a new one."""
    if request.method == "POST":
        try:
            data = json.loads(request.body or '{}')
        except (ValueError, TypeError):
            return JsonResponse({'error': 'invalid JSON'}, status=400)
        name = (data.get('name') or '').strip()
        if not name:
            return JsonResponse({'error': 'name required'}, status=400)
        try:
            lat = float(data['lat'])
            lng = float(data['lng'])
            radius_m = float(data.get('radius_m', 150))
        except (KeyError, ValueError, TypeError):
            return JsonResponse({'error': 'lat, lng required'}, status=400)
        radius_m = max(10.0, min(radius_m, 50000.0))
        count = CustomPlace.objects.filter(user=request.user).count()
        place = CustomPlace.objects.create(
            user=request.user, name=name[:200], latitude=lat, longitude=lng,
            radius_m=radius_m, color=_PLACE_COLORS[count % len(_PLACE_COLORS)],
        )
        _bust_user_cache(request.user.id)
        # The new place may cover a suggested cluster — drop it from the queue now
        # rather than leaving a confirmed place sitting there for the TTL.
        cache.delete(f"suggestions:{request.user.id}")
        threading.Thread(target=_refresh_places_snapshot, args=(request.user,), daemon=True).start()
        return JsonResponse(_serialize_place(place))

    # GET list → serve the precomputed snapshot when available.
    snap = _get_snapshot_or_kick(request.user)
    if snap:
        return _snapshot_response(snap, 'places_json')

    gen = cache.get(f"cache_gen:{request.user.id}", 0)
    cache_key = f"places:{request.user.id}:{gen}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

    payload = _compute_places_payload(request.user)
    cache.set(cache_key, payload, timeout=300)
    return JsonResponse(payload)


def _compute_places_payload(user):
    """Custom-places list with per-place point_count + last_seen."""
    base = Location.objects.filter(device__user=user)
    places = []
    for p in CustomPlace.objects.filter(user=user):
        nearby = _find_nearby_locations(base, p.latitude, p.longitude, p.radius_m)
        agg = nearby.aggregate(n=Count('id'), last=Max('timestamp'))
        item = _serialize_place(p)
        item['point_count'] = agg['n'] or 0
        item['last_seen'] = agg['last'].isoformat() if agg['last'] else None
        places.append(item)
    return {'places': places}


def _refresh_places_snapshot(user):
    """Keep the places list fresh right after a place is added/edited/deleted.
    The snapshot is otherwise only refreshed nightly, but a just-created place
    must appear immediately — and recomputing just the places list is cheap."""
    snap = StatsSnapshot.objects.filter(user=user).first()
    if snap and snap.status == 'done':
        snap.places_json = _compute_places_payload(user)
        snap.save(update_fields=['places_json'])


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def stats_recompute_api(request):
    """Kick an on-demand recompute of the user's stats snapshot (the per-page
    'recalculate now' button and Settings → Background Jobs)."""
    from .stats_tasks import start_stats_compute, get_status
    start_stats_compute(request.user.id)
    return JsonResponse(get_status(request.user.id))


@login_required
def stats_recompute_status_api(request):
    """Snapshot status + last-computed time for the recalculate UI."""
    from .stats_tasks import get_status
    return JsonResponse(get_status(request.user.id))


@login_required
def place_detail_api(request, place_id):
    """Full stats for one custom place: points, days, time spent, cities, sample track."""
    place = get_object_or_404(CustomPlace, id=place_id, user=request.user)
    return JsonResponse(_compute_place_detail(request.user, place))


def _compute_place_detail(user, place):
    """Stats for one custom place: points, days, time spent, cities.

    Deliberately lightweight so the detail modal pops open fast even for a place
    that holds hundreds of thousands of points. Cities come from a DB `GROUP BY`
    (only the top handful transfer), and the per-day counts + dwell time from a
    **timestamps-only** ordered scan — one narrow column, no lat/lon/city rows.
    The map's point sample is no longer built here: it streams separately from
    `place_points_api`, off the modal's critical path. Shared by the
    `place_detail_api` view and the AI `get_custom_place_detail` tool."""
    base = Location.objects.filter(device__user=user)
    nearby = _find_nearby_locations(base, place.latitude, place.longitude, place.radius_m)

    # Cities: aggregate in the DB — only the top 12 rows come back, no Python
    # pass over every inside point.
    cities = [
        {'city': r['city'], 'state': r['state'] or '', 'count': r['n']}
        for r in (
            nearby.exclude(city='')
            .values('city', 'state')
            .annotate(n=Count('id'))
            .order_by('-n')[:12]
        )
    ]

    # Per-day counts + dwell from a single timestamps-only scan.
    by_day = {}
    total = 0
    total_dwell = 0
    first_ts = last_ts = None
    prev_ts = None
    for (ts,) in nearby.order_by('timestamp').values_list('timestamp').iterator(chunk_size=10000):
        total += 1
        if first_ts is None:
            first_ts = ts
        last_ts = ts
        d = ts.date().isoformat()
        day = by_day.get(d)
        if day is None:
            day = {'date': d, 'count': 0, 'time_spent': 0, 'prev': None}
            by_day[d] = day
        day['count'] += 1
        if day['prev'] is not None:
            g = (ts - day['prev']).total_seconds()
            if 0 < g <= 600:
                day['time_spent'] += int(g)
        day['prev'] = ts
        if prev_ts is not None:
            g = (ts - prev_ts).total_seconds()
            if 0 < g <= 600:
                total_dwell += int(g)
        prev_ts = ts

    days = sorted(
        ({'date': v['date'], 'count': v['count'], 'time_spent': v['time_spent']} for v in by_day.values()),
        key=lambda x: x['date'], reverse=True,
    )[:100]

    data = _serialize_place(place)
    data.update({
        'point_count': total,
        'first_seen': first_ts.isoformat() if first_ts else None,
        'last_seen': last_ts.isoformat() if last_ts else None,
        'time_spent': total_dwell,
        'days': days,
        'cities': cities,
    })
    return data


@login_required
def place_points_api(request, place_id):
    """Stream the detail map's decimated point sample as newline-delimited JSON.

    A big place can hold hundreds of thousands of inside points; building the
    whole sample before responding is what used to make the modal's map lag. So
    this does one ordered scan, decimates to ~3000 points (stride from the total
    count), and yields them in small chunks as they're read — the client paints
    each chunk onto the map as it arrives, so points fill in progressively
    instead of appearing all at once after a long wait."""
    place = get_object_or_404(CustomPlace, id=place_id, user=request.user)
    base = Location.objects.filter(device__user=request.user)
    nearby = _find_nearby_locations(base, place.latitude, place.longitude, place.radius_m)

    total = nearby.count()
    stride = max(1, total // 3000)

    def gen():
        chunk = []
        for i, (lat, lon) in enumerate(
            nearby.order_by('timestamp')
            .values_list('latitude', 'longitude')
            .iterator(chunk_size=10000)
        ):
            if i % stride:
                continue
            chunk.append([_jf(lon), _jf(lat)])
            if len(chunk) >= 400:
                yield json.dumps(chunk) + '\n'
                chunk = []
        if chunk:
            yield json.dumps(chunk) + '\n'

    resp = StreamingHttpResponse(gen(), content_type='application/x-ndjson')
    resp['Cache-Control'] = 'no-store'
    resp['X-Accel-Buffering'] = 'no'  # let chunks flush past nginx buffering
    return resp


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def place_update(request, place_id):
    place = get_object_or_404(CustomPlace, id=place_id, user=request.user)
    try:
        data = json.loads(request.body or '{}')
    except (ValueError, TypeError):
        return JsonResponse({'error': 'invalid JSON'}, status=400)
    if 'name' in data:
        name = (data.get('name') or '').strip()
        if not name:
            return JsonResponse({'error': 'name required'}, status=400)
        place.name = name[:200]
    if 'lat' in data and 'lng' in data:
        try:
            place.latitude = float(data['lat'])
            place.longitude = float(data['lng'])
        except (ValueError, TypeError):
            return JsonResponse({'error': 'invalid lat/lng'}, status=400)
    if 'radius_m' in data:
        try:
            place.radius_m = max(10.0, min(float(data['radius_m']), 50000.0))
        except (ValueError, TypeError):
            return JsonResponse({'error': 'invalid radius'}, status=400)
    if 'notes' in data:
        place.notes = (data.get('notes') or '')[:10000]
    place.save()
    _bust_user_cache(request.user.id)
    # Only the geometry/name affect the cards + snapshot list; a notes-only
    # autosave must not trigger the heavy per-place recompute.
    if any(k in data for k in ('name', 'lat', 'lng', 'radius_m')):
        # Moving or resizing changes which clusters this place covers.
        cache.delete(f"suggestions:{request.user.id}")
        threading.Thread(target=_refresh_places_snapshot, args=(request.user,), daemon=True).start()
    return JsonResponse(_serialize_place(place))


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def place_delete(request, place_id):
    place = get_object_or_404(CustomPlace, id=place_id, user=request.user)
    place.delete()
    _bust_user_cache(request.user.id)
    # Deleting a place can re-expose the cluster it covered as a suggestion.
    cache.delete(f"suggestions:{request.user.id}")
    threading.Thread(target=_refresh_places_snapshot, args=(request.user,), daemon=True).start()
    return JsonResponse({'deleted': True})


# ---------------------------------------------------------------------------
# Place suggestions — recurring stays the user hasn't named yet
# ---------------------------------------------------------------------------

# Repeat visits to one spot land within a stone's throw of each other (the Visit
# worker already clusters each stay at 150m), so merge candidates at the same
# radius it used.
_SUGGEST_RADIUS_M = 150.0
_SUGGEST_CELL_DEG = 0.004        # ~440m; a 3×3 neighbourhood covers the radius
_SUGGEST_MIN_VISITS = 3          # a spot you stopped at 3 separate times…
_SUGGEST_MIN_HOURS = 1.0         # …for an hour all told, is somewhere that matters
_SUGGEST_LIMIT = 30


def _cluster_visits(rows):
    """Greedily merge Visit rows into place candidates within _SUGGEST_RADIUS_M.

    Grid-indexed so this stays linear-ish rather than comparing every visit to
    every cluster. `rows` are dicts of latitude/longitude/start_time/end_time/
    place_name/city.
    """
    grid = defaultdict(list)     # cell -> [cluster]
    clusters = []

    for r in rows:
        lat, lon = r['latitude'], r['longitude']
        cy, cx = int(lat / _SUGGEST_CELL_DEG), int(lon / _SUGGEST_CELL_DEG)
        best, best_d = None, _SUGGEST_RADIUS_M
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for c in grid.get((cy + dy, cx + dx), ()):
                    d = _haversine_km(c['lat'], c['lon'], lat, lon) * 1000.0
                    if d <= best_d:
                        best_d, best = d, c
        hours = max(0.0, (r['end_time'] - r['start_time']).total_seconds() / 3600.0)
        if best is None:
            c = {
                'lat': lat, 'lon': lon, 'lat_sum': lat, 'lon_sum': lon, 'n': 1,
                'hours': hours, 'last_seen': r['end_time'],
                'names': Counter(), 'cities': Counter(),
            }
            clusters.append(c)
            grid[(cy, cx)].append(c)
            best = c
        else:
            best['lat_sum'] += lat
            best['lon_sum'] += lon
            best['n'] += 1
            best['hours'] += hours
            best['lat'] = best['lat_sum'] / best['n']
            best['lon'] = best['lon_sum'] / best['n']
            if r['end_time'] > best['last_seen']:
                best['last_seen'] = r['end_time']
        if r.get('place_name'):
            best['names'][r['place_name']] += 1
        if r.get('city'):
            best['cities'][r['city']] += 1

    return clusters


@login_required
def place_suggestions_api(request):
    """Recurring stays that aren't inside a custom place yet.

    Suggestions are clustered from Visit rows on the fly rather than stored:
    confirming one just creates a CustomPlace, which then covers the cluster and
    drops it from this list on its own, so there is no confirm state to keep in
    sync. Only dismissals need persisting.
    """
    cache_key = f"suggestions:{request.user.id}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

    rows = list(
        Visit.objects.filter(device__user=request.user)
        .order_by()
        .values('latitude', 'longitude', 'start_time', 'end_time', 'place_name', 'city')
    )
    clusters = _cluster_visits(rows)

    places = list(CustomPlace.objects.filter(user=request.user)
                  .values('latitude', 'longitude', 'radius_m'))
    dismissed = list(request.user.dismissed_suggestions.values('latitude', 'longitude'))

    out = []
    for c in clusters:
        if c['n'] < _SUGGEST_MIN_VISITS or c['hours'] < _SUGGEST_MIN_HOURS:
            continue
        # Already named — the place covering it is the confirmation.
        if any(_haversine_km(p['latitude'], p['longitude'], c['lat'], c['lon']) * 1000.0 <= p['radius_m']
               for p in places):
            continue
        if any(_haversine_km(d['latitude'], d['longitude'], c['lat'], c['lon']) * 1000.0 <= _SUGGEST_RADIUS_M
               for d in dismissed):
            continue
        name = c['names'].most_common(1)[0][0] if c['names'] else ''
        city = c['cities'].most_common(1)[0][0] if c['cities'] else ''
        out.append({
            'lat': round(c['lat'], 6),
            'lng': round(c['lon'], 6),
            'visits': c['n'],
            'hours': round(c['hours'], 1),
            'last_seen': c['last_seen'].isoformat() if c['last_seen'] else None,
            'suggested_name': name,
            'city': city,
            'radius_m': _SUGGEST_RADIUS_M,
        })

    # Most-frequented first — the places worth naming float to the top.
    out.sort(key=lambda s: (-s['visits'], -s['hours']))
    result = {'suggestions': out[:_SUGGEST_LIMIT], 'total': len(out)}
    cache.set(cache_key, result, timeout=1800)
    return JsonResponse(result)


@login_required
@require_http_methods(["POST"])
def place_suggestion_dismiss_api(request):
    """Reject a suggestion so it stops resurfacing.

    Records only that the user doesn't want a place here — the underlying Visit
    rows are untouched, so Stats/Search/AI keep seeing the stays.
    """
    try:
        data = json.loads(request.body or '{}')
        lat = float(data['lat'])
        lng = float(data['lng'])
    except (ValueError, TypeError, KeyError):
        return JsonResponse({'error': 'lat, lng required'}, status=400)

    DismissedSuggestion.objects.create(user=request.user, latitude=lat, longitude=lng)
    cache.delete(f"suggestions:{request.user.id}")
    return JsonResponse({'dismissed': True})


# ---------------------------------------------------------------------------
# POI Download
# ---------------------------------------------------------------------------

@login_required
@require_http_methods(["POST"])
def poi_download_api(request):
    """Start downloading POIs for the user's travel area."""
    job = start_poi_download(request.user.id)
    return JsonResponse({
        'status': job.status,
        'total': job.total,
    })


@login_required
def poi_status_api(request):
    """Check POI download status."""
    return JsonResponse(get_poi_status(request.user.id))


@login_required
@require_http_methods(["POST"])
def poi_stop_api(request):
    """Stop a running POI download."""
    stopped = stop_poi_download(request.user.id)
    return JsonResponse({'stopped': stopped})


@login_required
@require_http_methods(["POST"])
def poi_match_api(request):
    """Start matching the nearest POI to every point (data Place column)."""
    from .poi_match_tasks import start_poi_match
    job = start_poi_match(request.user.id)
    return JsonResponse({'status': job.status, 'total': job.total})


@login_required
def poi_match_status_api(request):
    """Check POI-match progress."""
    from .poi_match_tasks import get_poi_match_status
    return JsonResponse(get_poi_match_status(request.user.id))


@login_required
@require_http_methods(["POST"])
def poi_match_stop_api(request):
    """Stop a running POI-match job."""
    from .poi_match_tasks import stop_poi_match
    return JsonResponse({'stopped': stop_poi_match(request.user.id)})


# ---------------------------------------------------------------------------
# Transport mode detection
# ---------------------------------------------------------------------------

@login_required
@require_http_methods(["POST"])
def transport_detect_api(request):
    """Start labelling every point with how the user was moving."""
    from .transport_tasks import start_transport_detect
    job = start_transport_detect(request.user.id)
    return JsonResponse({'status': job.status, 'total': job.total})


@login_required
def transport_status_api(request):
    """Check transport-detection progress."""
    from .transport_tasks import get_transport_status
    return JsonResponse(get_transport_status(request.user.id))


@login_required
@require_http_methods(["POST"])
def transport_stop_api(request):
    """Stop a running transport-detection job."""
    from .transport_tasks import stop_transport_detect
    return JsonResponse({'stopped': stop_transport_detect(request.user.id)})


@login_required
def transport_breakdown_api(request):
    """Distance and time split by transport mode, for the Stats page.

    Distance reuses the same gated segments as distance_api, so the totals here
    agree with the headline number instead of being a second, rawer estimate.
    """
    device_id = request.GET.get("device_id")
    all_time = request.GET.get("all")
    start_date = request.GET.get("start_date")
    end_date = request.GET.get("end_date")

    # Default all-time view (no filters) → serve the precomputed snapshot instantly.
    if all_time and not device_id and not (start_date and end_date):
        snap = _get_snapshot_or_kick(request.user)
        if snap:
            return _snapshot_response(snap, 'transport_json')

    gen = cache.get(f"cache_gen:{request.user.id}", 0)
    cache_key = f"transport:{request.user.id}:{gen}:{request.GET.urlencode()}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

    qs = Location.objects.filter(device__user=request.user)

    # Same range parsing as distance_api, so the Stats toolbar drives both alike.
    if start_date and end_date:
        try:
            start = datetime.fromisoformat(start_date).replace(hour=0, minute=0, second=0)
            end = datetime.fromisoformat(end_date).replace(hour=23, minute=59, second=59)
            if timezone.is_naive(start):
                start = timezone.make_aware(start)
            if timezone.is_naive(end):
                end = timezone.make_aware(end)
            qs = qs.filter(timestamp__gte=start, timestamp__lte=end)
        except (ValueError, TypeError):
            pass
    elif not all_time:
        try:
            hours = int(request.GET.get("hours", 24))
        except (ValueError, TypeError):
            hours = 24
        qs = qs.filter(timestamp__gte=timezone.now() - timedelta(hours=hours))

    if device_id:
        qs = qs.filter(device__device_id=device_id)

    result = _compute_transport_breakdown_from_qs(qs)
    cache.set(cache_key, result, timeout=1800)
    return JsonResponse(result)


# ---------------------------------------------------------------------------
# Automatic Backups (S3-compatible)
# ---------------------------------------------------------------------------

@login_required
@require_http_methods(["GET", "POST"])
def backup_config_api(request):
    """Get or save S3 backup configuration."""
    if request.method == 'GET':
        try:
            config = BackupConfig.objects.get(user=request.user)
            return JsonResponse({
                'configured': True,
                'endpoint_url': config.endpoint_url,
                'bucket_name': config.bucket_name,
                'access_key': config.access_key,
                'secret_key': '••••••••' if config.secret_key else '',
                'prefix': config.prefix,
                'region': config.region,
                'interval': config.interval,
                'max_backups': config.max_backups,
                'image_backup_enabled': config.image_backup_enabled,
                'image_use_same_creds': config.image_use_same_creds,
                'image_endpoint_url': config.image_endpoint_url,
                'image_bucket_name': config.image_bucket_name,
                'image_access_key': config.image_access_key,
                'image_secret_key': '••••••••' if config.image_secret_key else '',
                'image_prefix': config.image_prefix,
                'image_region': config.image_region,
            })
        except BackupConfig.DoesNotExist:
            return JsonResponse({'configured': False})

    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({'error': 'Invalid JSON'}, status=400)

    endpoint_url = data.get('endpoint_url', '').strip()
    bucket_name = data.get('bucket_name', '').strip()
    access_key = data.get('access_key', '').strip()
    secret_key = data.get('secret_key', '').strip()
    prefix = data.get('prefix', 'roamly-backups/').strip()
    region = data.get('region', 'auto').strip()
    interval = data.get('interval', 'disabled')
    max_backups = max(0, int(data.get('max_backups', 0)))

    if not endpoint_url or not bucket_name or not access_key:
        return JsonResponse({'error': 'Endpoint URL, bucket name, and access key are required'}, status=400)

    if interval not in ('disabled', 'daily', 'weekly', 'monthly'):
        return JsonResponse({'error': 'Invalid interval'}, status=400)

    config, created = BackupConfig.objects.get_or_create(
        user=request.user,
        defaults={
            'endpoint_url': endpoint_url,
            'bucket_name': bucket_name,
            'access_key': access_key,
            'secret_key': secret_key,
            'prefix': prefix,
            'region': region,
            'interval': interval,
            'max_backups': max_backups,
        }
    )

    # Image backup fields
    image_backup_enabled = data.get('image_backup_enabled', False)
    image_use_same_creds = data.get('image_use_same_creds', True)
    image_endpoint_url = data.get('image_endpoint_url', '').strip()
    image_bucket_name = data.get('image_bucket_name', '').strip()
    image_access_key = data.get('image_access_key', '').strip()
    image_secret_key = data.get('image_secret_key', '').strip()
    image_prefix = data.get('image_prefix', 'roamly-media/').strip()
    image_region = data.get('image_region', 'auto').strip()

    if not created:
        config.endpoint_url = endpoint_url
        config.bucket_name = bucket_name
        config.access_key = access_key
        # Only update secret if it's not the masked placeholder
        if secret_key and secret_key != '••••••••':
            config.secret_key = secret_key
        config.prefix = prefix
        config.region = region
        config.interval = interval
        config.max_backups = max_backups
        config.image_backup_enabled = image_backup_enabled
        config.image_use_same_creds = image_use_same_creds
        config.image_endpoint_url = image_endpoint_url
        config.image_bucket_name = image_bucket_name
        config.image_access_key = image_access_key
        if image_secret_key and image_secret_key != '••••••••':
            config.image_secret_key = image_secret_key
        config.image_prefix = image_prefix
        config.image_region = image_region
        config.save()
    else:
        config.image_backup_enabled = image_backup_enabled
        config.image_use_same_creds = image_use_same_creds
        config.image_endpoint_url = image_endpoint_url
        config.image_bucket_name = image_bucket_name
        config.image_access_key = image_access_key
        config.image_secret_key = image_secret_key
        config.image_prefix = image_prefix
        config.image_region = image_region
        config.save()

    return JsonResponse({'status': 'ok'})


@login_required
@require_http_methods(["POST"])
def backup_test_api(request):
    """Test S3 connection with the saved or provided credentials."""
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({'error': 'Invalid JSON'}, status=400)

    # Build a temporary config-like object for testing
    config = BackupConfig(user=request.user)
    config.endpoint_url = data.get('endpoint_url', '').strip()
    config.bucket_name = data.get('bucket_name', '').strip()
    config.access_key = data.get('access_key', '').strip()
    config.prefix = data.get('prefix', 'roamly-backups/').strip()
    config.region = data.get('region', 'auto').strip()

    secret_key = data.get('secret_key', '').strip()
    if secret_key == '••••••••':
        # Use the saved secret key
        try:
            saved = BackupConfig.objects.get(user=request.user)
            config.secret_key = saved.secret_key
        except BackupConfig.DoesNotExist:
            return JsonResponse({'error': 'No saved secret key — please enter one'}, status=400)
    else:
        config.secret_key = secret_key

    if not config.endpoint_url or not config.bucket_name or not config.access_key or not config.secret_key:
        return JsonResponse({'error': 'All connection fields are required'}, status=400)

    success, error = test_s3_connection(config)
    if success:
        return JsonResponse({'status': 'ok'})
    return JsonResponse({'error': error}, status=400)


@login_required
@require_http_methods(["POST"])
def backup_now_api(request):
    """Trigger an immediate backup."""
    try:
        BackupConfig.objects.get(user=request.user)
    except BackupConfig.DoesNotExist:
        return JsonResponse({'error': 'No backup configuration found. Save your S3 settings first.'}, status=400)

    result = run_backup_now(request.user.id)
    return JsonResponse({'status': result})


@login_required
def backup_status_api(request):
    """Get backup status."""
    return JsonResponse(get_backup_status(request.user.id))


@login_required
@require_POST
def backup_stop_api(request):
    """Force-stop a running backup."""
    stopped = stop_backup_now(request.user.id)
    return JsonResponse({'stopped': stopped})


@login_required
@require_POST
def image_backup_now_api(request):
    """Trigger an immediate image backup."""
    try:
        config = BackupConfig.objects.get(user=request.user)
    except BackupConfig.DoesNotExist:
        return JsonResponse({'error': 'No backup configuration found. Save your S3 settings first.'}, status=400)
    result = run_image_backup_now(request.user.id)
    return JsonResponse({'status': result})


@login_required
def image_backup_status_api(request):
    """Get image backup status."""
    return JsonResponse(get_image_backup_status(request.user.id))


# ---------------------------------------------------------------------------
# Profile Picture
# ---------------------------------------------------------------------------

@login_required
@require_http_methods(["POST"])
def upload_profile_picture(request):
    if 'picture' not in request.FILES:
        return JsonResponse({"error": "No file uploaded"}, status=400)
    pic = request.FILES['picture']
    if pic.size > 5 * 1024 * 1024:
        return JsonResponse({"error": "File too large (max 5MB)"}, status=400)
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    if profile.profile_picture:
        profile.profile_picture.delete(save=False)
    if profile.profile_picture_thumbnail:
        profile.profile_picture_thumbnail.delete(save=False)
    full = resize_image(pic, max_size=300)
    pic.seek(0)
    thumb = resize_image(pic, max_size=80)
    profile.profile_picture = full
    profile.profile_picture_thumbnail = thumb
    profile.save()
    return JsonResponse({
        "status": "ok",
        "picture_url": profile.profile_picture.url,
        "thumbnail_url": profile.profile_picture_thumbnail.url,
    })


@login_required
@require_http_methods(["POST"])
def delete_profile_picture(request):
    try:
        profile = request.user.profile
        if profile.profile_picture:
            profile.profile_picture.delete(save=False)
        if profile.profile_picture_thumbnail:
            profile.profile_picture_thumbnail.delete(save=False)
        profile.profile_picture = None
        profile.profile_picture_thumbnail = None
        profile.save()
    except UserProfile.DoesNotExist:
        pass
    return JsonResponse({"status": "ok"})


# ---------------------------------------------------------------------------
# PAL (Shared Group Trips)
# ---------------------------------------------------------------------------

def _get_user_avatar(user):
    """Return avatar data for a user."""
    try:
        profile = user.profile
        if profile.profile_picture_thumbnail:
            return {
                'type': 'image',
                'url': profile.profile_picture_thumbnail.url,
                'full_url': profile.profile_picture.url if profile.profile_picture else None,
            }
    except UserProfile.DoesNotExist:
        pass
    colors = ['#e8763d', '#7f9b6d', '#7ba3b8', '#cf9a44', '#a888a0', '#c0553f', '#6b7fa3', '#4f8a7b']
    color = colors[hash(user.username) % len(colors)]
    initials = user.username[:2].upper()
    return {'type': 'initials', 'initials': initials, 'color': color}


@login_required
def pals_view(request):
    return render(request, 'tracker/pals.html')


@login_required
def pal_detail_view(request, pal_id):
    pal = get_object_or_404(Pal, id=pal_id)
    if not PalMember.objects.filter(pal=pal, user=request.user).exists():
        return redirect('tracker:pals')
    return render(request, 'tracker/pal_detail.html', {
        'pal_id': pal_id,
        'is_public': False,
        'public_slug': '',
    })


def pal_public_view(request, slug):
    pal = get_object_or_404(Pal, public_slug=slug)
    description = pal.description or f'A shared trip on Roamly.'
    return render(request, 'tracker/pal_detail.html', {
        'pal_id': pal.id,
        'pal': pal,
        'is_public': True,
        'public_slug': slug,
        'seo_description': description,
        'seo_canonical': request.build_absolute_uri(),
    })


@login_required
@require_http_methods(["GET", "POST"])
def pals_api(request):
    if request.method == 'GET':
        memberships = PalMember.objects.filter(user=request.user).select_related('pal', 'pal__creator')
        pals = []
        for m in memberships:
            p = m.pal
            pals.append({
                'id': p.id,
                'name': p.name,
                'description': p.description,
                'start_date': p.start_date.isoformat(),
                'end_date': p.end_date.isoformat(),
                'creator': p.creator.username,
                'is_public': bool(p.public_slug),
                'member_count': p.members.count(),
                'role': m.role,
            })
        return JsonResponse({'pals': pals})

    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    name = data.get('name', '').strip()
    if not name:
        return JsonResponse({"error": "Name is required"}, status=400)
    start_date = data.get('start_date')
    end_date = data.get('end_date')
    if not start_date or not end_date:
        return JsonResponse({"error": "Start and end dates required"}, status=400)
    try:
        sd = date.fromisoformat(start_date)
        ed = date.fromisoformat(end_date)
    except ValueError:
        return JsonResponse({"error": "Invalid date format"}, status=400)
    pal = Pal.objects.create(
        name=name,
        description=data.get('description', ''),
        start_date=sd,
        end_date=ed,
        creator=request.user,
    )
    PalMember.objects.create(pal=pal, user=request.user, role='creator')
    return JsonResponse({"status": "ok", "pal_id": pal.id})


@login_required
def pal_detail_api(request, pal_id):
    pal = get_object_or_404(Pal, id=pal_id)
    membership = PalMember.objects.filter(pal=pal, user=request.user).first()
    if not membership:
        return JsonResponse({"error": "Not a member"}, status=403)
    members = []
    for m in pal.members.select_related('user'):
        members.append({
            'user_id': m.user.id,
            'username': m.user.username,
            'role': m.role,
            'joined_at': m.joined_at.isoformat(),
            'avatar': _get_user_avatar(m.user),
        })
    return JsonResponse({
        'id': pal.id,
        'name': pal.name,
        'description': pal.description,
        'start_date': pal.start_date.isoformat(),
        'end_date': pal.end_date.isoformat(),
        'creator': pal.creator.username,
        'is_public': bool(pal.public_slug),
        'public_url': f'/pal/{pal.public_slug}/' if pal.public_slug else None,
        'members': members,
        'role': membership.role,
    })


@login_required
@require_http_methods(["POST"])
def pal_update(request, pal_id):
    pal = get_object_or_404(Pal, id=pal_id, creator=request.user)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    if 'name' in data:
        pal.name = data['name'].strip()
    if 'description' in data:
        pal.description = data['description']
    if 'start_date' in data:
        pal.start_date = date.fromisoformat(data['start_date'])
    if 'end_date' in data:
        pal.end_date = date.fromisoformat(data['end_date'])
    pal.save()
    return JsonResponse({"status": "ok"})


@login_required
@require_http_methods(["POST"])
def pal_delete(request, pal_id):
    pal = get_object_or_404(Pal, id=pal_id, creator=request.user)
    pal.delete()
    return JsonResponse({"status": "ok"})


@login_required
@require_http_methods(["POST"])
def pal_toggle_public(request, pal_id):
    pal = get_object_or_404(Pal, id=pal_id, creator=request.user)
    if pal.public_slug:
        pal.public_slug = None
    else:
        pal.public_slug = uuid.uuid4().hex[:12]
    pal.save()
    return JsonResponse({
        "status": "ok",
        "is_public": bool(pal.public_slug),
        "public_url": f'/pal/{pal.public_slug}/' if pal.public_slug else None,
    })


@login_required
@require_http_methods(["POST"])
def pal_add_member(request, pal_id):
    pal = get_object_or_404(Pal, id=pal_id, creator=request.user)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    username = data.get('username', '').strip()
    if not username:
        return JsonResponse({"error": "Username required"}, status=400)
    from django.contrib.auth.models import User as AuthUser
    try:
        target = AuthUser.objects.get(username=username)
    except AuthUser.DoesNotExist:
        return JsonResponse({"error": "User not found"}, status=404)
    if PalMember.objects.filter(pal=pal, user=target).exists():
        return JsonResponse({"error": "Already a member"}, status=400)
    PalMember.objects.create(pal=pal, user=target, role='member')
    return JsonResponse({
        "status": "ok",
        "member": {
            "user_id": target.id,
            "username": target.username,
            "role": "member",
            "avatar": _get_user_avatar(target),
        }
    })


@login_required
@require_http_methods(["POST"])
def pal_remove_member(request, pal_id, user_id):
    pal = get_object_or_404(Pal, id=pal_id, creator=request.user)
    if user_id == request.user.id:
        return JsonResponse({"error": "Cannot remove yourself"}, status=400)
    membership = get_object_or_404(PalMember, pal=pal, user_id=user_id)
    membership.delete()
    return JsonResponse({"status": "ok"})


@login_required
def pal_locations_api(request, pal_id):
    """Get all member location tracks within PAL date range."""
    pal = get_object_or_404(Pal, id=pal_id)
    if not PalMember.objects.filter(pal=pal, user=request.user).exists():
        return JsonResponse({"error": "Not a member"}, status=403)
    start_dt = timezone.make_aware(datetime.combine(pal.start_date, dt_time.min))
    end_dt = timezone.make_aware(datetime.combine(pal.end_date, dt_time(23, 59, 59)))
    result = {}
    for m in pal.members.select_related('user'):
        devices = Device.objects.filter(user=m.user)
        locations = list(Location.objects.filter(
            device__in=devices,
            timestamp__gte=start_dt,
            timestamp__lte=end_dt,
        ).order_by('timestamp').values_list('latitude', 'longitude', 'timestamp', named=True))
        if len(locations) > 500:
            step = len(locations) // 500
            locations = locations[::step]
        result[m.user.username] = {
            'avatar': _get_user_avatar(m.user),
            'locations': [
                {'lat': _jf(loc.latitude), 'lng': _jf(loc.longitude), 'ts': loc.timestamp.isoformat()}
                for loc in locations
            ]
        }
    return JsonResponse({'members': result})


@login_required
def pal_timeline_api(request, pal_id):
    """Combined blurbs + milestones in chronological order."""
    pal = get_object_or_404(Pal, id=pal_id)
    if not PalMember.objects.filter(pal=pal, user=request.user).exists():
        return JsonResponse({"error": "Not a member"}, status=403)
    page = int(request.GET.get('page', 1))
    per_page = 50
    events = []
    for b in pal.blurbs.select_related('author').prefetch_related('photos', 'comments'):
        events.append({
            'type': 'blurb',
            'id': b.id,
            'author': b.author.username,
            'author_id': b.author.id,
            'avatar': _get_user_avatar(b.author),
            'text': b.text,
            'latitude': b.latitude,
            'longitude': b.longitude,
            'location_name': b.location_name,
            'photos': [{'id': p.id, 'url': p.image.url, 'thumb': p.thumbnail.url if p.thumbnail else p.image.url} for p in b.photos.all()],
            'comment_count': b.comments.count(),
            'created_at': b.created_at.isoformat(),
            'sort_key': b.created_at.isoformat(),
        })
    for m in pal.milestones.select_related('author'):
        events.append({
            'type': 'milestone',
            'id': m.id,
            'author': m.author.username,
            'author_id': m.author.id,
            'title': m.title,
            'description': m.description,
            'emoji': m.emoji,
            'date': m.date.isoformat(),
            'created_at': m.created_at.isoformat(),
            'sort_key': m.date.isoformat(),
        })
    events.sort(key=lambda e: e['sort_key'])
    total = len(events)
    start = (page - 1) * per_page
    events = events[start:start + per_page]
    return JsonResponse({'events': events, 'page': page, 'has_more': start + per_page < total})


@login_required
@require_http_methods(["POST"])
def pal_create_blurb(request, pal_id):
    pal = get_object_or_404(Pal, id=pal_id)
    if not PalMember.objects.filter(pal=pal, user=request.user).exists():
        return JsonResponse({"error": "Not a member"}, status=403)
    text = request.POST.get('text', '').strip()
    if not text:
        return JsonResponse({"error": "Text is required"}, status=400)
    lat = request.POST.get('latitude')
    lng = request.POST.get('longitude')
    if not lat or not lng:
        return JsonResponse({"error": "Location is required"}, status=400)
    blurb = PalBlurb.objects.create(
        pal=pal, author=request.user, text=text,
        latitude=float(lat), longitude=float(lng),
        location_name=request.POST.get('location_name', ''),
    )
    photos = request.FILES.getlist('photos')
    for i, photo_file in enumerate(photos[:5]):
        if photo_file.size > 10 * 1024 * 1024:
            continue
        full_file, thumb_file = resize_photo(photo_file)
        PalBlurbPhoto.objects.create(blurb=blurb, image=full_file, thumbnail=thumb_file, order=i)
    return JsonResponse({"status": "ok", "blurb_id": blurb.id})


@login_required
@require_http_methods(["POST"])
def pal_update_blurb(request, pal_id, blurb_id):
    pal = get_object_or_404(Pal, id=pal_id)
    blurb = get_object_or_404(PalBlurb, id=blurb_id, pal=pal)
    if blurb.author != request.user and pal.creator != request.user:
        return JsonResponse({"error": "Permission denied"}, status=403)
    text = request.POST.get('text', '').strip()
    if text:
        blurb.text = text
    lat = request.POST.get('latitude')
    lng = request.POST.get('longitude')
    if lat and lng:
        blurb.latitude = float(lat)
        blurb.longitude = float(lng)
    location_name = request.POST.get('location_name')
    if location_name is not None:
        blurb.location_name = location_name
    blurb.save()
    return JsonResponse({"status": "ok"})


@login_required
@require_http_methods(["POST"])
def pal_delete_blurb(request, pal_id, blurb_id):
    pal = get_object_or_404(Pal, id=pal_id)
    blurb = get_object_or_404(PalBlurb, id=blurb_id, pal=pal)
    if blurb.author != request.user and pal.creator != request.user:
        return JsonResponse({"error": "Permission denied"}, status=403)
    blurb.delete()
    return JsonResponse({"status": "ok"})


@login_required
@require_http_methods(["POST"])
def pal_create_milestone(request, pal_id):
    pal = get_object_or_404(Pal, id=pal_id)
    if not PalMember.objects.filter(pal=pal, user=request.user).exists():
        return JsonResponse({"error": "Not a member"}, status=403)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    title = data.get('title', '').strip()
    if not title:
        return JsonResponse({"error": "Title required"}, status=400)
    date_str = data.get('date')
    if not date_str:
        return JsonResponse({"error": "Date required"}, status=400)
    milestone_dt = datetime.fromisoformat(date_str.replace('Z', '+00:00'))
    if timezone.is_naive(milestone_dt):
        milestone_dt = timezone.make_aware(milestone_dt)
    milestone = PalMilestone.objects.create(
        pal=pal, author=request.user, title=title,
        description=data.get('description', ''),
        emoji=data.get('emoji', '\U0001f3c1'),
        date=milestone_dt,
    )
    return JsonResponse({"status": "ok", "milestone_id": milestone.id})


@login_required
@require_http_methods(["POST"])
def pal_delete_milestone(request, pal_id, milestone_id):
    pal = get_object_or_404(Pal, id=pal_id)
    milestone = get_object_or_404(PalMilestone, id=milestone_id, pal=pal)
    if milestone.author != request.user and pal.creator != request.user:
        return JsonResponse({"error": "Permission denied"}, status=403)
    milestone.delete()
    return JsonResponse({"status": "ok"})


def _serialize_comment(c):
    """Serialize a PalComment for JSON response."""
    if c.author:
        return {
            'id': c.id,
            'author': c.author.username,
            'author_id': c.author.id,
            'avatar': _get_user_avatar(c.author),
            'is_guest': False,
            'text': c.text,
            'created_at': c.created_at.isoformat(),
        }
    name = c.guest_name or 'guest'
    initial = name[0].upper() if name else 'G'
    return {
        'id': c.id,
        'author': name,
        'author_id': None,
        'avatar': {'type': 'initials', 'initials': initial, 'color': '#6b7280'},
        'is_guest': True,
        'text': c.text,
        'created_at': c.created_at.isoformat(),
    }


@login_required
def pal_blurb_comments(request, pal_id, blurb_id):
    pal = get_object_or_404(Pal, id=pal_id)
    if not PalMember.objects.filter(pal=pal, user=request.user).exists():
        return JsonResponse({"error": "Not a member"}, status=403)
    blurb = get_object_or_404(PalBlurb, id=blurb_id, pal=pal)
    comments = [_serialize_comment(c) for c in blurb.comments.select_related('author')]
    return JsonResponse({'comments': comments})


@login_required
@require_http_methods(["POST"])
def pal_create_comment(request, pal_id, blurb_id):
    pal = get_object_or_404(Pal, id=pal_id)
    if not PalMember.objects.filter(pal=pal, user=request.user).exists():
        return JsonResponse({"error": "Not a member"}, status=403)
    blurb = get_object_or_404(PalBlurb, id=blurb_id, pal=pal)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    text = data.get('text', '').strip()
    if not text:
        return JsonResponse({"error": "Text required"}, status=400)
    comment = PalComment.objects.create(blurb=blurb, author=request.user, text=text)
    return JsonResponse({"status": "ok", "comment": _serialize_comment(comment)})


@login_required
@require_http_methods(["POST"])
def pal_delete_comment(request, pal_id, comment_id):
    pal = get_object_or_404(Pal, id=pal_id)
    comment = get_object_or_404(PalComment, id=comment_id, blurb__pal=pal)
    if comment.author != request.user and pal.creator != request.user:
        return JsonResponse({"error": "Permission denied"}, status=403)
    comment.delete()
    return JsonResponse({"status": "ok"})


# ---------------------------------------------------------------------------
# Public PAL API (no auth required for public PALs)
# ---------------------------------------------------------------------------

def pal_public_detail_api(request, slug):
    pal = get_object_or_404(Pal, public_slug=slug)
    members = []
    for m in pal.members.select_related('user'):
        members.append({
            'user_id': m.user.id,
            'username': m.user.username,
            'role': m.role,
            'avatar': _get_user_avatar(m.user),
        })
    return JsonResponse({
        'id': pal.id,
        'name': pal.name,
        'description': pal.description,
        'start_date': pal.start_date.isoformat(),
        'end_date': pal.end_date.isoformat(),
        'creator': pal.creator.username,
        'is_public': True,
        'members': members,
        'role': 'viewer',
    })


def pal_public_timeline_api(request, slug):
    pal = get_object_or_404(Pal, public_slug=slug)
    page = int(request.GET.get('page', 1))
    per_page = 50
    events = []
    for b in pal.blurbs.select_related('author').prefetch_related('photos', 'comments'):
        events.append({
            'type': 'blurb',
            'id': b.id,
            'author': b.author.username,
            'author_id': b.author.id,
            'avatar': _get_user_avatar(b.author),
            'text': b.text,
            'latitude': b.latitude,
            'longitude': b.longitude,
            'location_name': b.location_name,
            'photos': [{'id': p.id, 'url': p.image.url, 'thumb': p.thumbnail.url if p.thumbnail else p.image.url} for p in b.photos.all()],
            'comment_count': b.comments.count(),
            'created_at': b.created_at.isoformat(),
            'sort_key': b.created_at.isoformat(),
        })
    for m in pal.milestones.select_related('author'):
        events.append({
            'type': 'milestone',
            'id': m.id,
            'author': m.author.username,
            'author_id': m.author.id,
            'title': m.title,
            'description': m.description,
            'emoji': m.emoji,
            'date': m.date.isoformat(),
            'created_at': m.created_at.isoformat(),
            'sort_key': m.date.isoformat(),
        })
    events.sort(key=lambda e: e['sort_key'])
    total = len(events)
    start = (page - 1) * per_page
    events = events[start:start + per_page]
    return JsonResponse({'events': events, 'page': page, 'has_more': start + per_page < total})


def pal_public_locations_api(request, slug):
    pal = get_object_or_404(Pal, public_slug=slug)
    start_dt = timezone.make_aware(datetime.combine(pal.start_date, dt_time.min))
    end_dt = timezone.make_aware(datetime.combine(pal.end_date, dt_time(23, 59, 59)))
    result = {}
    for m in pal.members.select_related('user'):
        devices = Device.objects.filter(user=m.user)
        locations = list(Location.objects.filter(
            device__in=devices,
            timestamp__gte=start_dt,
            timestamp__lte=end_dt,
        ).order_by('timestamp').values_list('latitude', 'longitude', 'timestamp', named=True))
        if len(locations) > 500:
            step = len(locations) // 500
            locations = locations[::step]
        result[m.user.username] = {
            'avatar': _get_user_avatar(m.user),
            'locations': [
                {'lat': _jf(loc.latitude), 'lng': _jf(loc.longitude), 'ts': loc.timestamp.isoformat()}
                for loc in locations
            ]
        }
    return JsonResponse({'members': result})


def pal_public_comments_api(request, slug, blurb_id):
    pal = get_object_or_404(Pal, public_slug=slug)
    blurb = get_object_or_404(PalBlurb, id=blurb_id, pal=pal)
    comments = [_serialize_comment(c) for c in blurb.comments.select_related('author')]
    return JsonResponse({'comments': comments})


@csrf_exempt
@require_http_methods(["POST"])
def pal_public_create_comment(request, slug, blurb_id):
    pal = get_object_or_404(Pal, public_slug=slug)
    blurb = get_object_or_404(PalBlurb, id=blurb_id, pal=pal)
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)
    text = data.get('text', '').strip()
    name = data.get('name', '').strip()
    if not text:
        return JsonResponse({"error": "Text required"}, status=400)
    if not name:
        return JsonResponse({"error": "Name required"}, status=400)
    if len(name) > 100:
        return JsonResponse({"error": "Name too long"}, status=400)
    comment = PalComment.objects.create(blurb=blurb, author=None, guest_name=name, text=text)
    return JsonResponse({"status": "ok", "comment": _serialize_comment(comment)})


# ---------------------------------------------------------------------------
# Journals (DayOne-style daily journaling with an on-this-day map)
# ---------------------------------------------------------------------------

def _journal_parse_date(date_str):
    """Parse an ISO YYYY-MM-DD string into a date, or None."""
    try:
        return datetime.fromisoformat(date_str).date()
    except (ValueError, TypeError):
        return None


def _journal_day_bounds(d, tz_offset=0):
    """Return timezone-aware (start, end) datetimes spanning the user's calendar day.

    ``tz_offset`` is the browser's ``Date.getTimezoneOffset()`` in minutes (UTC −
    local), so local midnight expressed in UTC is ``local_midnight + tz_offset``.
    With the default of 0 the bounds are the UTC calendar day (server TIME_ZONE).
    """
    try:
        tz_offset = int(tz_offset)
    except (TypeError, ValueError):
        tz_offset = 0
    start = datetime.combine(d, dt_time.min) + timedelta(minutes=tz_offset)
    end = datetime.combine(d, dt_time.max) + timedelta(minutes=tz_offset)
    if timezone.is_naive(start):
        start = timezone.make_aware(start)
    if timezone.is_naive(end):
        end = timezone.make_aware(end)
    return start, end


def _journal_serialize_photo(photo):
    return {
        'id': photo.id,
        'url': photo.image.url if photo.image else None,
        'thumbnail': photo.thumbnail.url if photo.thumbnail else (photo.image.url if photo.image else None),
        'caption': photo.caption,
        'order': photo.order,
    }


def _journal_day_track(user, d, tz_offset=0):
    """Build the GPS track + summary stats for a user's calendar day.

    Returns a dict with decimated points, total distance (km), the distinct
    cities/places visited, the bounding centroid, and the raw point count.
    ``tz_offset`` (browser minutes) bounds the day to the user's local midnight.
    """
    # A gap larger than this (or a change of device) breaks the drawn line into
    # a new segment, so we never draw a straight "teleport" across a tracking
    # gap or zig-zag between two devices' interleaved points.
    SEGMENT_GAP_S = 20 * 60

    start, end = _journal_day_bounds(d, tz_offset)
    qs = (Location.objects
          .filter(device__user=user, timestamp__gte=start, timestamp__lte=end,
                  latitude__isnull=False, longitude__isnull=False)
          .order_by('device_id', 'timestamp')
          .values('latitude', 'longitude', 'timestamp', 'city', 'state', 'country', 'device_id', 'accuracy'))

    raw = list(qs)
    total = len(raw)
    if total == 0:
        return {
            'points': [], 'segments': [], 'point_count': 0, 'distance_km': 0.0,
            'places': [], 'cities': [], 'centroid': None,
            'start_ts': None, 'end_ts': None,
        }

    # Walk per-device, time-ordered points; split into contiguous segments and
    # only accumulate distance within a segment (never across a gap/device hop).
    # A teleport outlier (a single wildly-off GPS fix that implies an impossible
    # speed) is dropped outright so the drawn line doesn't spike out to it and
    # back — the cause of the "spaghetti" zig-zags.
    MAX_SPEED_MS = 305.0  # ~1100 km/h; faster than any plausible real travel
    segments = []         # list of lists of raw point dicts
    cur_seg = None
    prev = None
    for p in raw:
        new_segment = (
            prev is None
            or p['device_id'] != prev['device_id']
            or (p['timestamp'] - prev['timestamp']).total_seconds() > SEGMENT_GAP_S
        )
        if not new_segment:
            dt = (p['timestamp'] - prev['timestamp']).total_seconds()
            if dt > 0:
                dist_m = _haversine_km(prev['latitude'], prev['longitude'],
                                       p['latitude'], p['longitude']) * 1000.0
                if dist_m / dt > MAX_SPEED_MS:
                    continue  # teleport outlier — skip, keep prev as the anchor
            cur_seg.append(p)
        else:
            cur_seg = [p]
            segments.append(cur_seg)
        prev = p

    # Jitter-resistant distance: resample+gate each drawn segment the same way as
    # distance_api, so a stationary high-frequency log doesn't inflate the day's
    # mileage. The drawn polyline still uses every point; only this number is gated.
    distance_km = 0.0
    for seg in segments:
        seg_points = ((p['latitude'], p['longitude'], p['timestamp'], p.get('accuracy')) for p in seg)
        for _ts, km in _gated_distance_segments(seg_points):
            distance_km += km

    # Chronological order for start/end markers and centroid (device-agnostic).
    chrono = sorted(raw, key=lambda p: p['timestamp'])

    # Distinct ordered list of place labels visited that day.
    places = []
    seen = set()
    for p in chrono:
        city = (p['city'] or '').strip()
        if not city:
            continue
        label = city
        if p['state']:
            label = f"{city}, {p['state']}"
        key = label.lower()
        if key not in seen:
            seen.add(key)
            places.append({'name': label, 'city': city, 'state': p['state'], 'country': p['country']})

    # Decimate for display — cap at ~1500 points across all segments, keeping
    # each segment's first and last point so the line endpoints stay put.
    MAX_POINTS = 1500
    stride = max(1, total // MAX_POINTS) if total > MAX_POINTS else 1

    def _coords(seg):
        if stride == 1 or len(seg) <= 2:
            kept = seg
        else:
            kept = seg[::stride]
            if kept[-1] is not seg[-1]:
                kept.append(seg[-1])
        return [[_jf(p['longitude']), _jf(p['latitude'])] for p in kept]

    seg_coords = [c for c in (_coords(s) for s in segments) if len(c) >= 2]
    # Flat list of points (kept for back-compat / bounds fitting).
    points = [c for seg in seg_coords for c in seg]

    centroid = [
        sum(p['longitude'] for p in raw) / total,
        sum(p['latitude'] for p in raw) / total,
    ]

    return {
        'points': points,
        'segments': seg_coords,
        'point_count': total,
        'distance_km': round(distance_km, 2),
        'places': places,
        'cities': [pl['name'] for pl in places],
        'centroid': centroid,
        'start': [_jf(chrono[0]['longitude']), _jf(chrono[0]['latitude'])],
        'end': [_jf(chrono[-1]['longitude']), _jf(chrono[-1]['latitude'])],
        'start_ts': int(chrono[0]['timestamp'].timestamp()),
        'end_ts': int(chrono[-1]['timestamp'].timestamp()),
    }


def _journal_compute_streaks(dates):
    """Given a set/list of date objects, compute current + longest streaks and counts."""
    if not dates:
        return {'current': 0, 'longest': 0, 'total': 0}
    dateset = set(dates)
    today = timezone.localdate()

    # Current streak: walk back from today (or yesterday if today not journaled yet).
    current = 0
    cursor = today
    if today not in dateset:
        cursor = today - timedelta(days=1)
    while cursor in dateset:
        current += 1
        cursor -= timedelta(days=1)

    # Longest streak across all entries.
    longest = 0
    for d in dateset:
        if (d - timedelta(days=1)) not in dateset:  # start of a run
            run = 1
            n = d + timedelta(days=1)
            while n in dateset:
                run += 1
                n += timedelta(days=1)
            longest = max(longest, run)

    return {'current': current, 'longest': longest, 'total': len(dateset)}


@login_required
def journals_view(request):
    return render(request, 'tracker/journals.html')


@login_required
def journal_search_api(request):
    q = request.GET.get('q', '').strip()
    if not q:
        return JsonResponse({'entries': [], 'count': 0})

    qs = JournalEntry.objects.filter(user=request.user).prefetch_related('photos')

    today = timezone.localdate()
    q_lower = q.lower()
    date_match = None
    if q_lower == 'today':
        date_match = today
    elif q_lower == 'yesterday':
        date_match = today - timedelta(days=1)
    else:
        for fmt in ('%Y-%m-%d', '%m/%d/%Y', '%d/%m/%Y', '%B %d %Y', '%b %d %Y'):
            try:
                date_match = datetime.strptime(q, fmt).date()
                break
            except ValueError:
                pass

    if date_match:
        qs = qs.filter(date=date_match)
    else:
        qs = qs.filter(
            Q(title__icontains=q) |
            Q(body__icontains=q) |
            Q(mood__icontains=q) |
            Q(weather__icontains=q) |
            Q(location_name__icontains=q)
        )

    qs = qs.order_by('-date')[:50]

    entries = []
    for e in qs:
        photos = list(e.photos.all())
        snippet = ''
        if e.body:
            idx = e.body.lower().find(q.lower())
            if idx >= 0:
                start = max(0, idx - 60)
                end = min(len(e.body), idx + len(q) + 60)
                snippet = ('…' if start > 0 else '') + e.body[start:end] + ('…' if end < len(e.body) else '')
            else:
                snippet = e.body.strip().split('\n')[0][:140]
        entries.append({
            'date': e.date.isoformat(),
            'title': e.title,
            'snippet': snippet,
            'mood': e.mood,
            'weather': e.weather,
            'is_favorite': e.is_favorite,
            'photo_count': len(photos),
            'cover': (photos[0].thumbnail.url if photos[0].thumbnail else photos[0].image.url) if photos else None,
            'location_name': e.location_name,
        })
    return JsonResponse({'entries': entries, 'count': len(entries)})


@login_required
def journals_list_api(request):
    """List journal entries, optionally scoped to a year+month, for calendar rendering."""
    qs = JournalEntry.objects.filter(user=request.user)

    year = request.GET.get('year')
    month = request.GET.get('month')
    if year and month:
        try:
            y, m = int(year), int(month)
            qs = qs.filter(date__year=y, date__month=m)
        except ValueError:
            pass

    qs = qs.prefetch_related('photos').order_by('-date')
    entries = []
    for e in qs:
        photos = list(e.photos.all())
        snippet = e.body.strip().split('\n')[0][:140] if e.body else ''
        entries.append({
            'date': e.date.isoformat(),
            'title': e.title,
            'snippet': snippet,
            'mood': e.mood,
            'weather': e.weather,
            'is_favorite': e.is_favorite,
            'photo_count': len(photos),
            'cover': (photos[0].thumbnail.url if photos[0].thumbnail else photos[0].image.url) if photos else None,
            'word_count': len(e.body.split()) if e.body else 0,
            'location_name': e.location_name,
        })
    return JsonResponse({'entries': entries})


@login_required
def journal_stats_api(request):
    """Streaks + lifetime journaling stats."""
    dates = list(JournalEntry.objects.filter(user=request.user).values_list('date', flat=True))
    streaks = _journal_compute_streaks(dates)
    today = timezone.localdate()
    this_year = sum(1 for d in dates if d.year == today.year)
    this_month = sum(1 for d in dates if d.year == today.year and d.month == today.month)
    words = 0
    for body in JournalEntry.objects.filter(user=request.user).values_list('body', flat=True):
        if body:
            words += len(body.split())
    photos = JournalPhoto.objects.filter(entry__user=request.user).count()
    return JsonResponse({
        'current_streak': streaks['current'],
        'longest_streak': streaks['longest'],
        'total_entries': streaks['total'],
        'this_year': this_year,
        'this_month': this_month,
        'total_words': words,
        'total_photos': photos,
        'entry_dates': [d.isoformat() for d in sorted(dates)],
    })


@login_required
def journal_detail_api(request, date_str):
    """Return one entry (or an empty shell) plus that day's track + map stats."""
    d = _journal_parse_date(date_str)
    if d is None:
        return JsonResponse({'error': 'Invalid date'}, status=400)

    entry = JournalEntry.objects.filter(user=request.user, date=d).prefetch_related('photos').first()
    track = _journal_day_track(request.user, d, request.GET.get('tz', 0))

    if entry:
        entry_data = {
            'exists': True,
            'date': entry.date.isoformat(),
            'title': entry.title,
            'body': entry.body,
            'mood': entry.mood,
            'weather': entry.weather,
            'is_favorite': entry.is_favorite,
            'location_name': entry.location_name,
            'pin': [entry.pin_longitude, entry.pin_latitude] if entry.pin_latitude is not None else None,
            'photos': [_journal_serialize_photo(p) for p in entry.photos.all()],
            'created_at': entry.created_at.isoformat(),
            'updated_at': entry.updated_at.isoformat(),
        }
    else:
        entry_data = {
            'exists': False,
            'date': d.isoformat(),
            'title': '', 'body': '', 'mood': '', 'weather': '',
            'is_favorite': False, 'location_name': '', 'pin': None, 'photos': [],
        }

    return JsonResponse({'entry': entry_data, 'track': track})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def journal_save_api(request, date_str):
    """Create or update the entry for a day (idempotent upsert)."""
    d = _journal_parse_date(date_str)
    if d is None:
        return JsonResponse({'error': 'Invalid date'}, status=400)

    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({'error': 'Invalid JSON'}, status=400)

    entry, _ = JournalEntry.objects.get_or_create(user=request.user, date=d)
    if 'title' in data:
        entry.title = (data.get('title') or '')[:300]
    if 'body' in data:
        entry.body = data.get('body') or ''
    if 'mood' in data:
        entry.mood = (data.get('mood') or '')[:20]
    if 'weather' in data:
        entry.weather = (data.get('weather') or '')[:60]
    if 'is_favorite' in data:
        entry.is_favorite = bool(data.get('is_favorite'))
    if 'location_name' in data:
        entry.location_name = (data.get('location_name') or '')[:300]
    if 'pin' in data:
        pin = data.get('pin')
        if pin and isinstance(pin, (list, tuple)) and len(pin) == 2:
            entry.pin_longitude, entry.pin_latitude = float(pin[0]), float(pin[1])
        else:
            entry.pin_latitude = entry.pin_longitude = None
    entry.save()

    return JsonResponse({'status': 'ok', 'date': entry.date.isoformat()})


@login_required
@csrf_exempt
@require_http_methods(["POST", "DELETE"])
def journal_delete_api(request, date_str):
    d = _journal_parse_date(date_str)
    if d is None:
        return JsonResponse({'error': 'Invalid date'}, status=400)
    JournalEntry.objects.filter(user=request.user, date=d).delete()
    return JsonResponse({'status': 'ok'})


@login_required
@csrf_exempt
@require_http_methods(["POST"])
def journal_photos_api(request, date_str):
    """Upload one or more photos to a day's entry (creates the entry if needed)."""
    d = _journal_parse_date(date_str)
    if d is None:
        return JsonResponse({'error': 'Invalid date'}, status=400)

    entry, _ = JournalEntry.objects.get_or_create(user=request.user, date=d)
    existing = entry.photos.count()
    photos = request.FILES.getlist('photos')
    created = []
    for i, photo_file in enumerate(photos):
        if existing + len(created) >= 20:
            break
        if photo_file.size > 15 * 1024 * 1024:
            continue
        try:
            full_file, thumb_file = resize_photo(photo_file)
        except Exception:
            continue
        p = JournalPhoto.objects.create(
            entry=entry, image=full_file, thumbnail=thumb_file,
            order=existing + i,
        )
        created.append(_journal_serialize_photo(p))
    return JsonResponse({'status': 'ok', 'photos': created})


@login_required
@csrf_exempt
@require_http_methods(["POST", "DELETE"])
def journal_photo_delete_api(request, photo_id):
    photo = get_object_or_404(JournalPhoto, id=photo_id, entry__user=request.user)
    photo.delete()
    return JsonResponse({'status': 'ok'})


# ---------------------------------------------------------------------------
# Admin Panel
# ---------------------------------------------------------------------------

def _require_admin(request):
    """Return a 403 JsonResponse if the requesting user is not an admin, else None."""
    if not request.user.is_authenticated:
        from django.http import HttpResponseForbidden
        return HttpResponseForbidden()
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    if not profile.is_admin:
        return JsonResponse({'error': 'Admin access required.'}, status=403)
    return None


@login_required
def admin_panel_view(request):
    """Instance admin panel — user management and the instance-wide custom JS snippet."""
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    if not profile.is_admin:
        return redirect('tracker:settings')
    site_config = SiteConfig.load()
    return render(request, 'tracker/admin_panel.html', {
        'site_custom_js': site_config.custom_js,
        'site_contact_email': site_config.contact_email,
    })


@login_required
def admin_users_api(request):
    """List all users with activity stats for the admin panel user management section."""
    err = _require_admin(request)
    if err:
        return err

    from django.contrib.auth.models import User as AuthUser
    users = list(
        AuthUser.objects.select_related('profile')
        .prefetch_related('devices')
        .order_by('date_joined')
        .values(
            'id', 'username', 'email', 'date_joined', 'last_login', 'is_active',
        )
    )
    # Annotate with location count and is_admin from profile.
    profiles = {p.user_id: p for p in UserProfile.objects.all()}
    loc_counts = dict(
        Location.objects.values('device__user').annotate(n=Count('id')).values_list('device__user', 'n')
    )
    device_counts = dict(
        Device.objects.values('user').annotate(n=Count('id')).values_list('user', 'n')
    )
    result = []
    for u in users:
        uid = u['id']
        profile = profiles.get(uid)
        result.append({
            'id': uid,
            'username': u['username'],
            'email': u['email'],
            'date_joined': u['date_joined'].isoformat() if u['date_joined'] else None,
            'last_login': u['last_login'].isoformat() if u['last_login'] else None,
            'is_active': u['is_active'],
            'is_admin': profile.is_admin if profile else False,
            'is_self': uid == request.user.id,
            'location_count': loc_counts.get(uid, 0),
            'device_count': device_counts.get(uid, 0),
        })
    return JsonResponse({'users': result}, encoder=DjangoJSONEncoder)


@login_required
@require_POST
def admin_toggle_admin_api(request, user_id):
    """Toggle the is_admin flag on another user. Self-demotion is blocked."""
    err = _require_admin(request)
    if err:
        return err
    if user_id == request.user.id:
        return JsonResponse({'error': 'Cannot change your own admin status.'}, status=400)
    from django.contrib.auth.models import User as AuthUser
    target = get_object_or_404(AuthUser, id=user_id)
    profile, _ = UserProfile.objects.get_or_create(user=target)
    profile.is_admin = not profile.is_admin
    profile.save(update_fields=['is_admin'])
    _log_action(request, 'admin_toggle',
                description=f"{target.username} -> is_admin={profile.is_admin}")
    return JsonResponse({'ok': True, 'is_admin': profile.is_admin})


@login_required
@require_POST
def admin_delete_user_api(request, user_id):
    """Delete another user and all their data. Self-deletion is blocked."""
    err = _require_admin(request)
    if err:
        return err
    if user_id == request.user.id:
        return JsonResponse(
            {'error': 'Cannot delete your own account here. Use Settings → Danger Zone.'},
            status=400,
        )
    from django.contrib.auth.models import User as AuthUser
    target = get_object_or_404(AuthUser, id=user_id)
    username = target.username
    # Log before delete (the acting admin survives; the target's own action rows
    # SET_NULL). Record on the admin's behalf so the audit row keeps a valid FK.
    _log_action(request, 'admin_delete_user', description=f"deleted user {username}")
    # Everything the user owns (devices, locations, …) cascades on delete.
    target.delete()
    return JsonResponse({'ok': True, 'deleted': username})


# ---------------------------------------------------------------------------
# Admin logging & monitoring
#
# Restored + extended from the pre-0036 admin logging. AccessLog is the
# high-volume per-request log (pruned by retention); ActionLog holds the
# low-volume kept-long-term events (logins/signups/errors/admin actions);
# DailyLogRollup preserves per-day aggregates forever so long-range graphs
# survive pruning. All endpoints are admin-only via _require_admin.
# ---------------------------------------------------------------------------

# How far back each dashboard window looks, and whether it buckets by hour or day.
_ADMIN_RANGES = {
    '24h': (timedelta(hours=24), 'hour'),
    '7d': (timedelta(days=7), 'hour'),
    '30d': (timedelta(days=30), 'day'),
    'all': (None, 'day'),
}


def _admin_daily_series(since_date):
    """Per-day {date, requests, unique_ips, unique_users, logins, signups, errors}
    from DailyLogRollup (historical, survives pruning) merged with a live
    aggregation of AccessLog/ActionLog for any recent days not yet rolled up
    (today + anything after the last rollup). since_date=None ⇒ whole history."""
    from django.db.models.functions import TruncDate
    from .models import AccessLog, ActionLog, DailyLogRollup

    rollups = DailyLogRollup.objects.all()
    if since_date:
        rollups = rollups.filter(date__gte=since_date)
    by_date = {
        r.date: {
            'date': r.date.isoformat(),
            'requests': r.requests, 'unique_ips': r.unique_ips,
            'unique_users': r.unique_users, 'logins': r.logins,
            'signups': r.signups, 'errors': r.errors,
        }
        for r in rollups
    }

    # Live-aggregate the not-yet-rolled-up tail (dates strictly after the last
    # rollup, or everything if there are no rollups yet), so today shows current.
    last_rollup = DailyLogRollup.objects.order_by('-date').values_list('date', flat=True).first()
    acc = AccessLog.objects.all()
    act = ActionLog.objects.all()
    if last_rollup is not None:
        live_from = last_rollup + timedelta(days=1)
        naive = datetime.combine(live_from, dt_time.min)
        live_from_dt = timezone.make_aware(naive) if timezone.is_naive(naive) else naive
        acc = acc.filter(timestamp__gte=live_from_dt)
        act = act.filter(timestamp__gte=live_from_dt)
    elif since_date:
        naive = datetime.combine(since_date, dt_time.min)
        since_dt = timezone.make_aware(naive) if timezone.is_naive(naive) else naive
        acc = acc.filter(timestamp__gte=since_dt)
        act = act.filter(timestamp__gte=since_dt)

    req_by_day = dict(
        acc.annotate(d=TruncDate('timestamp')).values('d')
        .annotate(n=Count('id')).values_list('d', 'n')
    )
    ip_by_day = dict(
        acc.exclude(ip_address__isnull=True).annotate(d=TruncDate('timestamp'))
        .values('d').annotate(n=Count('ip_address', distinct=True)).values_list('d', 'n')
    )
    usr_by_day = dict(
        acc.filter(user__isnull=False).annotate(d=TruncDate('timestamp'))
        .values('d').annotate(n=Count('user', distinct=True)).values_list('d', 'n')
    )
    act_by_day = {}
    for row in (act.annotate(d=TruncDate('timestamp')).values('d', 'action')
                .annotate(n=Count('id'))):
        act_by_day.setdefault(row['d'], {}).update({row['action']: row['n']})

    live_dates = set(req_by_day) | set(act_by_day)
    for d in live_dates:
        if since_date and d < since_date:
            continue
        actions = act_by_day.get(d, {})
        by_date[d] = {
            'date': d.isoformat(),
            'requests': req_by_day.get(d, 0),
            'unique_ips': ip_by_day.get(d, 0),
            'unique_users': usr_by_day.get(d, 0),
            'logins': actions.get('login', 0),
            'signups': actions.get('signup', 0),
            'errors': actions.get('error', 0),
        }

    return [by_date[d] for d in sorted(by_date)]


@login_required
def admin_overview_api(request):
    """Monitoring dashboard: stat tiles, a request-volume time series, status /
    top-path / top-IP / top-country breakdowns, and recent events."""
    err = _require_admin(request)
    if err:
        return err

    from django.db.models.functions import TruncHour
    from .models import AccessLog, ActionLog

    rng = request.GET.get('range', '24h')
    if rng not in _ADMIN_RANGES:
        rng = '24h'
    delta, gran = _ADMIN_RANGES[rng]
    now = timezone.now()
    since = (now - delta) if delta else None

    acc = AccessLog.objects.all()
    act = ActionLog.objects.all()
    if since:
        acc = acc.filter(timestamp__gte=since)
        act = act.filter(timestamp__gte=since)

    # Time series.
    if gran == 'hour':
        buckets = list(
            acc.annotate(t=TruncHour('timestamp')).values('t')
            .annotate(n=Count('id')).order_by('t').values_list('t', 'n')
        )
        act_buckets = {}
        for row in (act.annotate(t=TruncHour('timestamp')).values('t', 'action')
                    .annotate(n=Count('id'))):
            act_buckets.setdefault(row['t'], {})[row['action']] = row['n']
        series = [
            {
                't': t.isoformat(), 'requests': n,
                'logins': act_buckets.get(t, {}).get('login', 0),
                'signups': act_buckets.get(t, {}).get('signup', 0),
                'errors': act_buckets.get(t, {}).get('error', 0),
            }
            for t, n in buckets
        ]
    else:
        since_date = timezone.localtime(since).date() if since else None
        series = [
            {
                't': d['date'], 'requests': d['requests'],
                'logins': d['logins'], 'signups': d['signups'], 'errors': d['errors'],
            }
            for d in _admin_daily_series(since_date)
        ]

    # Stat tiles. For hourly windows the raw rows are all present; for day
    # windows (30d/all) totals sum the series so pruned days still count.
    if gran == 'hour':
        total_requests = acc.count()
        unique_ips = acc.exclude(ip_address__isnull=True).values('ip_address').distinct().count()
        unique_users = acc.filter(user__isnull=False).values('user').distinct().count()
        logins = act.filter(action='login').count()
        signups = act.filter(action='signup').count()
        errors = act.filter(action='error').count()
    else:
        total_requests = sum(s['requests'] for s in series)
        logins = sum(s['logins'] for s in series)
        signups = sum(s['signups'] for s in series)
        errors = sum(s['errors'] for s in series)
        # Unique counts don't sum across days; report the best-effort distinct
        # over whatever raw AccessLog rows remain in the window.
        unique_ips = acc.exclude(ip_address__isnull=True).values('ip_address').distinct().count()
        unique_users = acc.filter(user__isnull=False).values('user').distinct().count()

    avg_val = acc.filter(response_ms__isnull=False).aggregate(a=Avg('response_ms'))['a']
    avg_ms = round(avg_val) if avg_val is not None else None

    status_breakdown = dict(
        acc.exclude(status_code__isnull=True).values('status_code')
        .annotate(n=Count('id')).values_list('status_code', 'n')
    )
    top_paths = list(
        acc.values('path').annotate(n=Count('id')).order_by('-n')[:15].values_list('path', 'n')
    )
    top_ips = list(
        acc.exclude(ip_address__isnull=True).values('ip_address')
        .annotate(n=Count('id')).order_by('-n')[:10].values_list('ip_address', 'n')
    )
    ip_counts = list(
        acc.exclude(ip_address__isnull=True).values('ip_address')
        .annotate(n=Count('id')).values_list('ip_address', 'n')
    )
    country_breakdown, unknown_country_count = geoip_utils.country_counts(ip_counts)
    recent_actions = list(
        act.select_related('user').order_by('-timestamp')[:20].values(
            'timestamp', 'action', 'description', 'ip_address', 'user__username'
        )
    )

    return JsonResponse({
        'range': rng,
        'granularity': gran,
        'total_requests': total_requests,
        'unique_ips': unique_ips,
        'unique_users': unique_users,
        'logins': logins,
        'signups': signups,
        'errors': errors,
        'avg_response_ms': avg_ms,
        'series': series,
        'status_breakdown': {str(k): v for k, v in status_breakdown.items()},
        'top_paths': [{'path': p, 'count': n} for p, n in top_paths],
        'top_ips': [{'ip': ip, 'count': n} for ip, n in top_ips],
        'top_countries': country_breakdown[:15],
        'unknown_country_count': unknown_country_count,
        'recent_actions': [
            {
                'timestamp': a['timestamp'].isoformat(),
                'action': a['action'],
                'description': a['description'],
                'ip': a['ip_address'],
                'username': a['user__username'],
            }
            for a in recent_actions
        ],
    }, encoder=DjangoJSONEncoder)


@login_required
def admin_access_logs_api(request):
    """Paginated access-log browse with optional ip/path/user/hours filters."""
    err = _require_admin(request)
    if err:
        return err
    from .models import AccessLog

    page = max(1, int(request.GET.get('page', 1)))
    per_page = min(200, max(1, int(request.GET.get('per_page', 50))))
    ip_filter = request.GET.get('ip', '').strip()
    path_filter = request.GET.get('path', '').strip()
    user_filter = request.GET.get('user', '').strip()
    hours = request.GET.get('hours', '')

    qs = AccessLog.objects.select_related('user').order_by('-timestamp')
    if ip_filter:
        qs = qs.filter(ip_address__icontains=ip_filter)
    if path_filter:
        qs = qs.filter(path__icontains=path_filter)
    if user_filter:
        qs = qs.filter(user__username__icontains=user_filter)
    if hours:
        try:
            qs = qs.filter(timestamp__gte=timezone.now() - timedelta(hours=int(hours)))
        except (ValueError, TypeError):
            pass

    total = qs.count()
    offset = (page - 1) * per_page
    rows = list(qs[offset:offset + per_page].values(
        'id', 'ip_address', 'user__username', 'path', 'method',
        'status_code', 'response_ms', 'timestamp', 'user_agent',
    ))
    return JsonResponse({
        'total': total, 'page': page, 'per_page': per_page,
        'rows': [
            {
                'id': r['id'], 'ip': r['ip_address'], 'user': r['user__username'],
                'path': r['path'], 'method': r['method'], 'status': r['status_code'],
                'ms': r['response_ms'], 'timestamp': r['timestamp'].isoformat(),
                'ua': r['user_agent'],
            }
            for r in rows
        ],
    }, encoder=DjangoJSONEncoder)


@login_required
def admin_action_logs_api(request):
    """Paginated event-log browse (logins/signups/errors/admin actions)."""
    err = _require_admin(request)
    if err:
        return err
    from .models import ActionLog

    page = max(1, int(request.GET.get('page', 1)))
    per_page = min(200, max(1, int(request.GET.get('per_page', 50))))
    action_filter = request.GET.get('action', '').strip()
    hours = request.GET.get('hours', '')

    qs = ActionLog.objects.select_related('user').order_by('-timestamp')
    if action_filter == 'auth':
        qs = qs.filter(action__in=['login', 'logout', 'signup', 'login_fail'])
    elif action_filter == 'admin':
        qs = qs.filter(action__in=['admin_toggle', 'admin_delete_user', 'custom_js_save'])
    elif action_filter:
        qs = qs.filter(action=action_filter)
    if hours:
        try:
            qs = qs.filter(timestamp__gte=timezone.now() - timedelta(hours=int(hours)))
        except (ValueError, TypeError):
            pass

    total = qs.count()
    offset = (page - 1) * per_page
    rows = list(qs[offset:offset + per_page].values(
        'id', 'user__username', 'action', 'description', 'ip_address', 'timestamp'
    ))
    return JsonResponse({
        'total': total, 'page': page, 'per_page': per_page,
        'rows': [
            {
                'id': r['id'], 'user': r['user__username'], 'action': r['action'],
                'description': r['description'], 'ip': r['ip_address'],
                'timestamp': r['timestamp'].isoformat(),
            }
            for r in rows
        ],
    }, encoder=DjangoJSONEncoder)


@login_required
def admin_log_config_api(request):
    """GET/POST the log retention settings on SiteConfig (admins only)."""
    err = _require_admin(request)
    if err:
        return err
    config = SiteConfig.load()
    if request.method == 'POST':
        try:
            data = json.loads(request.body)
        except (json.JSONDecodeError, AttributeError, ValueError):
            data = request.POST
        if 'access_log_retention_days' in data:
            config.access_log_retention_days = max(0, int(data.get('access_log_retention_days') or 0))
        if 'event_log_retention_days' in data:
            config.event_log_retention_days = max(0, int(data.get('event_log_retention_days') or 0))
        config.save(update_fields=['access_log_retention_days', 'event_log_retention_days', 'updated_at'])
    return JsonResponse({
        'access_log_retention_days': config.access_log_retention_days,
        'event_log_retention_days': config.event_log_retention_days,
    })


# ---------------------------------------------------------------------------
# In-app mobile updates
#
# The Android app is sideloaded (no Play Store). CI publishes a signed APK as a
# GitHub Release asset (Roamly<version>.apk) on every `mobile-v*` tag. These two
# endpoints let the app check for and download a newer build while only ever
# talking to its own configured server — the server proxies GitHub.
# ---------------------------------------------------------------------------

_MOBILE_RELEASE_CACHE_KEY = 'mobile_latest_release'
_MOBILE_RELEASE_TTL = 900  # 15m — GitHub unauth API is 60 req/hr/IP; well under.
# Minimum spacing between forced (?refresh=1) GitHub fetches, so the public
# bypass can't be used to burn through GitHub's rate limit.
_MOBILE_RELEASE_REFRESH_COOLDOWN_KEY = 'mobile_latest_release_refresh_lock'
_MOBILE_RELEASE_REFRESH_COOLDOWN = 120  # 2m


def _fetch_latest_mobile_release(force=False):
    """Return parsed metadata for the latest `mobile-v*` release, or None.

    Cached for ``_MOBILE_RELEASE_TTL`` so we never hit GitHub per-request. A
    newly published release is otherwise invisible until the entry expires, so
    ``force=True`` (from ``?refresh=1``) bypasses the read cache and re-fetches
    from GitHub — rate-limited by a short cooldown so it can't be abused.
    Shape: {version_name, tag, asset_name, asset_url, size, release_notes}.
    """
    # Honour a forced refresh only when the cooldown has elapsed; otherwise fall
    # back to the cached value so bursts of ?refresh=1 don't hammer GitHub.
    if force and cache.add(_MOBILE_RELEASE_REFRESH_COOLDOWN_KEY, 1, _MOBILE_RELEASE_REFRESH_COOLDOWN):
        force = True
    else:
        force = False

    if not force:
        cached = cache.get(_MOBILE_RELEASE_CACHE_KEY)
        if cached is not None:
            return cached or None  # cache empty-dict sentinel means "no release"

    repo = getattr(settings, 'MOBILE_UPDATE_REPO', 'waxn/roamly')
    url = f'https://api.github.com/repos/{repo}/releases/latest'
    req = urllib.request.Request(url, headers={
        'User-Agent': 'Roamly-update-check',
        'Accept': 'application/vnd.github+json',
    })
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode('utf-8'))
    except Exception:
        logger.exception('Failed to fetch latest mobile release from GitHub')
        # Cache the miss briefly so a GitHub outage doesn't hammer us.
        cache.set(_MOBILE_RELEASE_CACHE_KEY, {}, 300)
        return None

    tag = data.get('tag_name') or ''
    version_name = tag[len('mobile-v'):] if tag.startswith('mobile-v') else tag.lstrip('v')
    apk = None
    for asset in data.get('assets') or []:
        name = asset.get('name') or ''
        if name.lower().endswith('.apk'):
            apk = asset
            break
    if not version_name or apk is None:
        cache.set(_MOBILE_RELEASE_CACHE_KEY, {}, 300)
        return None

    parsed = {
        'version_name': version_name,
        'tag': tag,
        'asset_name': apk.get('name'),
        'asset_url': apk.get('browser_download_url'),
        'size': apk.get('size') or 0,
        'release_notes': data.get('body') or '',
    }
    cache.set(_MOBILE_RELEASE_CACHE_KEY, parsed, _MOBILE_RELEASE_TTL)
    return parsed


def mobile_version_check(request):
    """Public: latest available Android version + download URL (proxies GitHub).

    ``?refresh=1`` forces a fresh GitHub fetch (bypassing the read cache) so a
    just-published release shows up immediately instead of after the TTL.
    """
    force = request.GET.get('refresh') in ('1', 'true', 'yes')
    release = _fetch_latest_mobile_release(force=force)
    if release is None:
        return JsonResponse({'error': 'No release information available'}, status=503)
    return JsonResponse({
        'version_name': release['version_name'],
        'download_url': '/api/mobile/apk/',
        'release_notes': release['release_notes'],
        'size': release['size'],
    })


def mobile_download_apk(request):
    """Stream the latest signed APK, cached to disk to avoid re-hitting GitHub."""
    release = _fetch_latest_mobile_release()
    if release is None or not release.get('asset_url'):
        return HttpResponse('APK not available', status=503)

    apk_dir = os.path.join(settings.MEDIA_ROOT, 'apk')
    os.makedirs(apk_dir, exist_ok=True)
    # Sanitise the asset name into a safe local filename.
    safe_name = os.path.basename(release['asset_name'] or f"Roamly{release['version_name']}.apk")
    apk_path = os.path.join(apk_dir, safe_name)

    if not os.path.exists(apk_path):
        # First request for this version — pull it from GitHub to disk once.
        tmp_path = apk_path + '.part'
        try:
            req = urllib.request.Request(release['asset_url'], headers={'User-Agent': 'Roamly-update-check'})
            with urllib.request.urlopen(req, timeout=120) as resp, open(tmp_path, 'wb') as out:
                while True:
                    chunk = resp.read(65536)
                    if not chunk:
                        break
                    out.write(chunk)
            os.replace(tmp_path, apk_path)
        except Exception:
            logger.exception('Failed to fetch APK asset from GitHub')
            try:
                os.unlink(tmp_path)
            except OSError:
                pass
            return HttpResponse('Failed to fetch APK', status=502)

    response = FileResponse(
        open(apk_path, 'rb'),
        content_type='application/vnd.android.package-archive',
        as_attachment=True,
        filename=safe_name,
    )
    return response


# ---------------------------------------------------------------------------
# Roads: snap-to-road rendering + tracking-gap filling
#
# Snapping is display-only — nothing here ever writes to Location. The map
# editor writes InferredLocation rows, which no aggregate in the app reads, so
# stats, visits, distance and backups are unaffected by design.
# ---------------------------------------------------------------------------

_SNAP_MAX_IDS = 5000


@login_required
def profile_roads_config_api(request):
    """GET/POST the user's road provider and snapping toggle.

    Mirrors profile_ai_config_api. `resolved` tells the UI which provider the
    "auto" setting actually lands on, so the card can say what will happen
    rather than leaving the user to guess.
    """
    profile, _ = UserProfile.objects.get_or_create(user=request.user)

    if request.method != 'POST':
        return JsonResponse({
            'road_provider': profile.road_provider,
            'resolved': profile.road_provider_resolved,
            'osrm_url': profile.osrm_url,
            'snap_to_roads': profile.snap_to_roads,
            'has_postgis': HAS_POSTGIS,
            'has_local_roads': RoadSegment.objects.exists() if HAS_POSTGIS else False,
            # Surfaced in the card: if snapping or the editor's road routing
            # look inert, an empty or thin road table is the usual reason.
            'total_ways': RoadSegment.objects.count() if HAS_POSTGIS else 0,
            'has_mapbox_token': bool(profile.mapbox_token),
        })

    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        return JsonResponse({'error': 'Invalid request.'}, status=400)

    provider = (data.get('road_provider') or '').strip()
    if provider not in ('', 'local', 'mapbox', 'osrm'):
        return JsonResponse({'error': 'Unknown road provider.'}, status=400)
    profile.road_provider = provider
    profile.osrm_url = (data.get('osrm_url') or '').strip().rstrip('/')[:300]
    profile.snap_to_roads = bool(data.get('snap_to_roads'))

    profile.save(update_fields=[
        'road_provider', 'osrm_url', 'snap_to_roads',
    ])
    return JsonResponse({
        'ok': True,
        'resolved': profile.road_provider_resolved,
        'snap_to_roads': profile.snap_to_roads,
    })


@login_required
@require_http_methods(["POST"])
def roads_snap_api(request):
    """Snap a batch of the user's own points to the road network, for display.

    The client sends **only point ids**; the coordinates are read back from the
    database here. That is deliberate: it enforces ownership, stops a caller
    seeding the snap cache with coordinates it made up, and guarantees the
    timestamp ordering the local smoother depends on for continuity.
    """
    from . import roads as roads_mod

    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    if not profile.snap_to_roads:
        return JsonResponse({'snapped': {}, 'provider': ''})

    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        return JsonResponse({'error': 'Invalid request.'}, status=400)

    raw_ids = data.get('ids') or []
    if not isinstance(raw_ids, list):
        return JsonResponse({'error': 'Invalid request.'}, status=400)
    ids = []
    for v in raw_ids[:_SNAP_MAX_IDS]:
        try:
            ids.append(int(v))
        except (TypeError, ValueError):
            continue
    if not ids:
        return JsonResponse({'snapped': {}, 'provider': profile.road_provider_resolved})

    # speed/transport_mode/timestamp drive the movement gate (roads.snappable):
    # only road journeys get snapped, so walking a garden or a footpath keeps its
    # recorded positions. All three are columns of the same row already being
    # fetched by primary key, so they cost nothing extra.
    pts = [
        {'id': r['id'], 'lat': r['latitude'], 'lng': r['longitude'],
         'accuracy': r['accuracy'], 'speed': r['speed'],
         'transport_mode': r['transport_mode'], 'timestamp': r['timestamp']}
        for r in (Location.objects
                  .filter(id__in=ids, device__user=request.user)
                  .order_by('timestamp', 'id')
                  .values('id', 'latitude', 'longitude', 'accuracy',
                          'speed', 'transport_mode', 'timestamp'))
    ]
    if not pts:
        return JsonResponse({'snapped': {}, 'provider': profile.road_provider_resolved})

    try:
        snapped = roads_mod.snap_points(profile, pts)
    except roads_mod.RoadProviderError as exc:
        return JsonResponse({'error': str(exc), 'snapped': {}}, status=200)

    return JsonResponse({
        'provider': profile.road_provider_resolved,
        'snapped': {str(k): [v[0], v[1]] for k, v in snapped.items()},
    })


# ── Road data download ──────────────────────────────────────────────────────

@login_required
@require_http_methods(["POST"])
def road_download_api(request):
    """Start downloading OSM road geometry for the areas the user has visited."""
    from .road_download_tasks import start_road_download
    job = start_road_download(request.user.id)
    return JsonResponse({'status': job.status, 'total': job.total})


@login_required
def road_download_status_api(request):
    """Check road-download progress."""
    from .road_download_tasks import get_road_download_status
    return JsonResponse(get_road_download_status(request.user.id))


@login_required
@require_http_methods(["POST"])
def road_download_stop_api(request):
    """Stop a running road download."""
    from .road_download_tasks import stop_road_download
    return JsonResponse({'stopped': stop_road_download(request.user.id)})


# ── Editor-generated points ─────────────────────────────────────────────────

def _inferred_filter(request, qs):
    """Apply the map's device + date filters to an InferredLocation queryset.

    Same parameter names and precedence as `locations_api` / `distance_api` so
    the editor layer follows the map toolbar without a second convention.
    """
    device_id = request.GET.get('device_id') or ''
    if device_id:
        qs = qs.filter(device__device_id=device_id)

    start_date = request.GET.get('start_date')
    end_date = request.GET.get('end_date')
    if start_date and end_date:
        try:
            start = datetime.fromisoformat(start_date)
            end = datetime.fromisoformat(end_date)
            if timezone.is_naive(start):
                start = timezone.make_aware(start)
            if timezone.is_naive(end):
                end = timezone.make_aware(end)
            qs = qs.filter(timestamp__gte=start, timestamp__lte=end)
        except (ValueError, TypeError):
            pass
    elif not request.GET.get('all'):
        try:
            hours = int(request.GET.get('hours', 24))
        except (ValueError, TypeError):
            hours = 24
        qs = qs.filter(timestamp__gte=timezone.now() - timedelta(hours=hours))
    return qs


_INFERRED_MAX_POINTS = 20000


@login_required
def inferred_locations_api(request):
    """Editor-generated points, grouped into one polyline per batch.

    Grouped rather than returned flat so the client can draw a dashed line per
    edit without re-segmenting; a batch is a single user action, which is
    exactly the run of points that belongs on one line.
    """
    qs = _inferred_filter(
        request,
        InferredLocation.objects.filter(device__user=request.user, batch__undone=False)
    )

    # Optional viewport bbox — same parameter names the map already sends.
    try:
        min_lng = float(request.GET['min_lng'])
        min_lat = float(request.GET['min_lat'])
        max_lng = float(request.GET['max_lng'])
        max_lat = float(request.GET['max_lat'])
        qs = qs.filter(longitude__gte=min_lng, longitude__lte=max_lng,
                       latitude__gte=min_lat, latitude__lte=max_lat)
    except (KeyError, TypeError, ValueError):
        pass

    rows = list(qs.order_by('batch_id', 'timestamp')
                .values_list('batch_id', 'id', 'longitude', 'latitude', 'timestamp')
                [:_INFERRED_MAX_POINTS])

    by_batch = {}
    for batch_id, pid, lng, lat, ts in rows:
        by_batch.setdefault(batch_id, []).append(
            {'id': pid, 'lng': _jf(lng), 'lat': _jf(lat), 'timestamp': ts.isoformat()})

    # device_id so the map can colour these with the same per-device palette it
    # uses for recorded points — they are meant to read as part of the track.
    meta = {b['id']: b for b in EditBatch.objects.filter(id__in=by_batch.keys())
            .values('id', 'kind', 'device__device_id')}

    out = [{
        'id': batch_id,
        'kind': (meta.get(batch_id) or {}).get('kind', ''),
        'device_id': (meta.get(batch_id) or {}).get('device__device_id', ''),
        'points': pts,
        'coords': [[p['lng'], p['lat']] for p in pts],
    } for batch_id, pts in by_batch.items()]
    return JsonResponse({'batches': out, 'enabled': True})










@login_required
@require_http_methods(["POST"])
def road_data_delete_api(request):
    """Delete all downloaded road geometry.

    RoadSegment is instance-wide reference data (like POI and Boundary — no user
    FK), so this clears it for everyone on the instance; the Settings copy says
    so. Safe to do: it is re-downloadable cache-like data, and nothing else
    references it. Any running download is stopped first so it can't keep
    inserting rows behind the delete.
    """
    from .road_download_tasks import stop_road_download

    stop_road_download(request.user.id)
    deleted = RoadSegment.objects.count()
    # A plain DELETE on a table this size is slow and bloats WAL; TRUNCATE is
    # near-instant and reclaims the space immediately. Nothing references
    # RoadSegment by FK, so there is nothing to cascade.
    if deleted:
        from django.db import connection
        if connection.vendor == 'postgresql':
            with connection.cursor() as cur:
                cur.execute(f'TRUNCATE TABLE {RoadSegment._meta.db_table}')
        else:
            RoadSegment.objects.all().delete()

    # The local provider advertises itself on an EXISTS check that is cached.
    cache.delete(ROADS_AVAILABLE_CACHE_KEY)
    return JsonResponse({'status': 'ok', 'deleted': deleted})




# ---------------------------------------------------------------------------
# Map editor (/editor/)
#
# Replaces automatic gap filling. Every action is deliberate and synchronous —
# there is no background job, because the whole problem with the old feature was
# a job deciding on its own what to change. Output defaults to InferredLocation
# (invisible to every aggregate); "count as real data" writes real Location rows.
# Deleted real points are snapshotted to TrashedLocation first.
# ---------------------------------------------------------------------------

_EDITOR_MAX_IDS = 5000


@login_required
def editor_view(request):
    """The map editor page. Mirrors map_view's context plus standalone chrome."""
    devices = Device.objects.filter(user=request.user)
    return render(request, 'tracker/editor.html', {
        'devices': devices,
        'has_postgis': HAS_POSTGIS,
        # Edge-to-edge viewport: strips the nav and footer, the same lever the
        # public adventure page already uses.
        'standalone': True,
    })


def _editor_ids(data, key='ids'):
    out = []
    for v in (data.get(key) or [])[:_EDITOR_MAX_IDS]:
        try:
            out.append(int(v))
        except (TypeError, ValueError):
            continue
    return out


def _editor_device(user, device_id=None):
    """The device an action applies to. Falls back to the user's only device."""
    qs = Device.objects.filter(user=user)
    if device_id:
        return qs.filter(device_id=device_id).first()
    return qs.first()


def _parse_dt(value):
    if not value:
        return None
    try:
        dt = datetime.fromisoformat(value.replace('Z', '+00:00'))
    except (ValueError, TypeError, AttributeError):
        return None
    return timezone.make_aware(dt) if timezone.is_naive(dt) else dt


@login_required
@require_http_methods(["POST"])
def editor_delete_api(request):
    """Move selected points to the trash (real) or delete them (generated)."""
    from . import editor_tasks
    from .models import EditBatch

    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        return JsonResponse({'error': 'Invalid request.'}, status=400)

    real_ids = _editor_ids(data, 'ids')
    inferred_ids = _editor_ids(data, 'inferred_ids')
    trashed = removed = 0
    batch = None

    if real_ids:
        qs = Location.objects.filter(id__in=real_ids, device__user=request.user)
        device = qs.values_list('device', flat=True).first()
        if device:
            batch = EditBatch.objects.create(
                user=request.user, device_id=device, kind='delete', target='location',
                note=f'{len(real_ids)} point(s)')
            trashed = editor_tasks.trash_locations(request.user, qs, batch=batch)
            batch.point_count = trashed
            batch.save(update_fields=['point_count'])

    if inferred_ids:
        removed, _ = InferredLocation.objects.filter(
            id__in=inferred_ids, device__user=request.user).delete()

    _bust_user_cache(request.user.id)
    return JsonResponse({'status': 'ok', 'trashed': trashed, 'removed': removed,
                         'batch_id': batch.id if batch else None})


# The editor routes from a much wider anchor tolerance than the old automatic
# filler did. That gate exists to stop a background job inventing a leg from a
# point nowhere near a road; here a person has picked both ends deliberately, so
# the sensible behaviour is to project onto the nearest road and say how far it
# moved. Without this, connecting only worked for points that happened to be
# close enough to a road to be snappable in the first place.
_EDITOR_ANCHOR_M = 500.0
_EDITOR_ROUTE_TTL = 900


def _route_anchor_rows(request, ids):
    """The two real points to connect, oldest first, or (None, error)."""
    rows = list(Location.objects.filter(id__in=ids, device__user=request.user)
                .order_by('timestamp')
                .values('id', 'latitude', 'longitude', 'timestamp', 'speed',
                        'device_id', 'transport_mode'))
    if len(rows) != 2:
        return None, 'Select exactly two recorded points to connect.'
    if rows[0]['device_id'] != rows[1]['device_id']:
        return None, 'Both points must be on the same device.'
    if rows[0]['timestamp'] == rows[1]['timestamp']:
        return None, 'Those two points share a timestamp, so there is no gap to fill.'
    return rows, None


@login_required
@require_http_methods(["POST"])
def editor_route_api(request):
    """Offer up to three road routes between two selected points.

    A preview, not a commit: for a lot of gaps there is more than one plausible
    way round and only the person who drove it knows which. The candidates are
    held in the cache and committed by index, so the client never sends geometry
    back and cannot inject a path that was never offered.
    """
    from . import editor_tasks, roads as roads_mod

    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        return JsonResponse({'error': 'Invalid request.'}, status=400)

    rows, err = _route_anchor_rows(request, _editor_ids(data))
    if err:
        return JsonResponse({'error': err}, status=400)

    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    if not profile.road_provider_resolved:
        return JsonResponse({'error': 'No road data provider is configured. '
                                      'Download road data or set a provider in Settings.'}, status=400)

    a = (rows[0]['longitude'], rows[0]['latitude'])
    b = (rows[1]['longitude'], rows[1]['latitude'])
    mode = rows[0]['transport_mode'] or rows[1]['transport_mode'] or ''
    try:
        cands = roads_mod.route_alternatives(profile, a, b, mode, k=3,
                                             max_anchor_m=_EDITOR_ANCHOR_M)
    except roads_mod.RoadProviderError as exc:
        return JsonResponse({'error': str(exc)}, status=400)

    if not cands:
        return JsonResponse({'error': 'No road route found between those two points. '
                                      'The area may not have road data downloaded yet — '
                                      'try Download Roads in Settings.'}, status=400)

    straight = roads_mod._haversine_m(a[0], a[1], b[0], b[1])
    token = secrets.token_hex(8)
    out = []
    for idx, c in enumerate(cands):
        pts, _len = editor_tasks.points_along(
            c['coords'], rows[0]['timestamp'], rows[1]['timestamp'],
            v_start=rows[0]['speed'], v_end=rows[1]['speed'])
        warn = ''
        if straight > 0 and c['length_m'] > max(1500.0, straight * editor_tasks.DETOUR_WARN_FACTOR):
            warn = 'much longer than the direct line — it may be routing around missing road data'
        out.append({
            'index': idx,
            'coords': c['coords'],
            'length_m': _jf(c['length_m']),
            'points': len(pts),
            'warning': warn,
        })

    cache.set(f'editroute:{request.user.id}:{token}',
              {'ids': [r['id'] for r in rows],
               'routes': [c['coords'] for c in cands]},
              _EDITOR_ROUTE_TTL)
    return JsonResponse({'status': 'ok', 'token': token, 'straight_m': _jf(straight),
                         'routes': out})


@login_required
@require_http_methods(["POST"])
def editor_route_commit_api(request):
    """Lay points along the route the user picked."""
    from . import editor_tasks

    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        return JsonResponse({'error': 'Invalid request.'}, status=400)

    held = cache.get(f"editroute:{request.user.id}:{data.get('token') or ''}")
    if not held:
        return JsonResponse({'error': 'That route preview has expired. '
                                      'Select the two points again.'}, status=400)
    try:
        idx = int(data.get('index', 0))
    except (TypeError, ValueError):
        idx = 0
    if not (0 <= idx < len(held['routes'])):
        return JsonResponse({'error': 'Unknown route.'}, status=400)

    rows, err = _route_anchor_rows(request, held['ids'])
    if err:
        return JsonResponse({'error': err}, status=400)

    coords = held['routes'][idx]
    pts, route_len = editor_tasks.points_along(
        coords, rows[0]['timestamp'], rows[1]['timestamp'],
        v_start=rows[0]['speed'], v_end=rows[1]['speed'])
    if not pts:
        return JsonResponse({'error': 'That gap is too short to place any points in.'}, status=400)

    device = Device.objects.get(id=rows[0]['device_id'])
    batch = editor_tasks.create_batch(
        request.user, device, 'route', pts, target='inferred',
        note=f'{route_len / 1000:.1f}km along roads')
    cache.delete(f"editroute:{request.user.id}:{data.get('token')}")
    _bust_user_cache(request.user.id)
    return JsonResponse({'status': 'ok', 'batch_id': batch.id,
                         'points': batch.point_count, 'route_m': _jf(route_len)})


@login_required
@require_http_methods(["POST"])
def editor_add_api(request):
    """Place points by hand: one at a chosen time, or a run from an anchor.

    A run takes its timestamps from the anchor plus a fixed interval, which is
    the natural way to lay a short stretch back in over a hole.
    """
    from . import editor_tasks

    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        return JsonResponse({'error': 'Invalid request.'}, status=400)

    target = 'location' if data.get('as_real') else 'inferred'
    coords = data.get('coords') or []
    if not coords:
        return JsonResponse({'error': 'No positions given.'}, status=400)

    anchor_id = data.get('anchor_id')
    if anchor_id:
        anchor = Location.objects.filter(
            id=anchor_id, device__user=request.user
        ).values('timestamp', 'device_id').first()
        if not anchor:
            return JsonResponse({'error': 'Anchor point not found.'}, status=404)
        try:
            interval = max(1, min(3600, int(data.get('interval_s', 60))))
        except (TypeError, ValueError):
            interval = 60
        device = Device.objects.get(id=anchor['device_id'])
        base = anchor['timestamp']
        pts = [(float(c[0]), float(c[1]), base + timedelta(seconds=interval * (i + 1)))
               for i, c in enumerate(coords)]
        kind = 'run'
    else:
        when = _parse_dt(data.get('timestamp'))
        if not when:
            return JsonResponse({'error': 'A valid timestamp is required.'}, status=400)
        device = _editor_device(request.user, data.get('device_id'))
        if not device:
            return JsonResponse({'error': 'No device to attach the point to.'}, status=400)
        pts = [(float(coords[0][0]), float(coords[0][1]), when)]
        kind = 'manual'

    batch = editor_tasks.create_batch(request.user, device, kind, pts, target=target)
    _bust_user_cache(request.user.id)
    return JsonResponse({'status': 'ok', 'batch_id': batch.id, 'points': batch.point_count})


@login_required
@require_http_methods(["POST"])
def editor_move_api(request, location_id):
    """Reposition or retime a single real point."""
    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        return JsonResponse({'error': 'Invalid request.'}, status=400)

    loc = Location.objects.filter(id=location_id, device__user=request.user).first()
    if not loc:
        return JsonResponse({'error': 'Not found'}, status=404)

    if data.get('lat') is not None and data.get('lng') is not None:
        try:
            loc.latitude = float(data['lat'])
            loc.longitude = float(data['lng'])
        except (TypeError, ValueError):
            return JsonResponse({'error': 'Invalid coordinates.'}, status=400)
    when = _parse_dt(data.get('timestamp'))
    if when:
        loc.timestamp = when

    # .save() (not .update()) so the PostGIS column is re-derived from lat/lon.
    from django.db import IntegrityError
    try:
        loc.save()
    except IntegrityError:
        return JsonResponse({'error': 'Another point on this device already has '
                                      'that exact position and time.'}, status=409)
    _bust_user_cache(request.user.id)
    return JsonResponse({'status': 'ok', 'id': loc.id,
                         'lat': _jf(loc.latitude), 'lng': _jf(loc.longitude),
                         'timestamp': loc.timestamp.isoformat()})


def _copy_window(data):
    src_start = _parse_dt(data.get('src_start'))
    src_end = _parse_dt(data.get('src_end'))
    dest_start = _parse_dt(data.get('dest_start'))
    return src_start, src_end, dest_start


@login_required
@require_http_methods(["POST"])
def editor_copy_preview_api(request):
    """Report what a paste would land on, before anything is written.

    Location's uniqueness rule is (device, lat, lng, timestamp), so pasting onto
    a different date collides with nothing and inserts cleanly — which is exactly
    the hazard: you would silently get two interleaved tracks that every
    aggregate double-counts. Hence an explicit conflict check.
    """
    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        return JsonResponse({'error': 'Invalid request.'}, status=400)

    src_start, src_end, dest_start = _copy_window(data)
    if not (src_start and src_end and dest_start) or src_end <= src_start:
        return JsonResponse({'error': 'Pick a source range and a destination time.'}, status=400)

    device = _editor_device(request.user, data.get('device_id'))
    if not device:
        return JsonResponse({'error': 'No device selected.'}, status=400)

    span = src_end - src_start
    src_count = Location.objects.filter(
        device=device, timestamp__gte=src_start, timestamp__lte=src_end).count()
    dest_count = Location.objects.filter(
        device=device, timestamp__gte=dest_start, timestamp__lte=dest_start + span).count()
    return JsonResponse({
        'status': 'ok', 'source_points': src_count, 'conflicts': dest_count,
        'span_seconds': span.total_seconds(),
        'dest_end': (dest_start + span).isoformat(),
    })


@login_required
@require_http_methods(["POST"])
def editor_copy_api(request):
    """Paste a copied stretch at another time."""
    from . import editor_tasks

    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        return JsonResponse({'error': 'Invalid request.'}, status=400)

    src_start, src_end, dest_start = _copy_window(data)
    if not (src_start and src_end and dest_start) or src_end <= src_start:
        return JsonResponse({'error': 'Pick a source range and a destination time.'}, status=400)

    device = _editor_device(request.user, data.get('device_id'))
    if not device:
        return JsonResponse({'error': 'No device selected.'}, status=400)

    span = src_end - src_start
    offset = dest_start - src_start
    dest_qs = Location.objects.filter(
        device=device, timestamp__gte=dest_start, timestamp__lte=dest_start + span)
    conflicts = dest_qs.count()
    if conflicts and not data.get('replace'):
        return JsonResponse({'error': f'{conflicts} point(s) already exist in that window. '
                                      f'Choose replace, or pick an empty destination.',
                             'conflicts': conflicts}, status=409)

    rows = list(Location.objects.filter(
        device=device, timestamp__gte=src_start, timestamp__lte=src_end)
        .order_by('timestamp').values('latitude', 'longitude', 'timestamp'))
    if not rows:
        return JsonResponse({'error': 'No points in the source range.'}, status=400)

    replaced = 0
    if conflicts:
        replaced = editor_tasks.trash_locations(request.user, dest_qs)

    target = 'location' if data.get('as_real') else 'inferred'
    pts = [(r['longitude'], r['latitude'], r['timestamp'] + offset) for r in rows]
    batch = editor_tasks.create_batch(
        request.user, device, 'copy', pts, target=target,
        note=f"{len(pts)} point(s) from {src_start.date()}")
    _bust_user_cache(request.user.id)
    return JsonResponse({'status': 'ok', 'batch_id': batch.id,
                         'points': batch.point_count, 'replaced': replaced,
                         'as_real': target == 'location'})


@login_required
def editor_batches_api(request):
    """Recent editor actions, newest first, for the undo list."""
    from .models import EditBatch

    rows = (EditBatch.objects.filter(user=request.user)
            .select_related('device').order_by('-created_at')[:50])
    return JsonResponse({'batches': [{
        'id': b.id,
        'kind': b.kind,
        'kind_label': b.get_kind_display(),
        'target': b.target,
        'note': b.note,
        'point_count': b.point_count,
        'undone': b.undone,
        'device': b.device.name or b.device.device_id,
        'created_at': b.created_at.isoformat(),
    } for b in rows]})


@login_required
@require_http_methods(["POST"])
def editor_batch_undo_api(request, batch_id):
    """Reverse one editor action."""
    from . import editor_tasks
    from .models import EditBatch

    batch = EditBatch.objects.filter(id=batch_id, user=request.user, undone=False).first()
    if not batch:
        return JsonResponse({'error': 'Not found'}, status=404)
    affected = editor_tasks.undo_batch(request.user, batch)
    _bust_user_cache(request.user.id)
    return JsonResponse({'status': 'ok', 'affected': affected})


# ── Trash ───────────────────────────────────────────────────────────────────

@login_required
def trash_api(request):
    """Deleted real points, newest first."""
    from .log_cleanup_tasks import trash_retention_days
    from .models import TrashedLocation

    try:
        limit = min(500, max(1, int(request.GET.get('limit', 200))))
    except (TypeError, ValueError):
        limit = 200
    qs = (TrashedLocation.objects.filter(user=request.user)
          .select_related('device').order_by('-deleted_at'))
    total = qs.count()
    return JsonResponse({
        'total': total,
        'retention_days': trash_retention_days(),
        'points': [{
            'id': t.id,
            'lat': _jf(t.latitude), 'lng': _jf(t.longitude),
            'timestamp': t.timestamp.isoformat(),
            'deleted_at': t.deleted_at.isoformat(),
            'device': t.device.name or t.device.device_id,
            'city': t.city, 'state': t.state, 'country': t.country,
        } for t in qs[:limit]],
    })


@login_required
@require_http_methods(["POST"])
def trash_restore_api(request):
    """Put deleted points back. Ids omitted means restore everything."""
    from . import editor_tasks
    from .models import TrashedLocation

    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        data = {}
    ids = _editor_ids(data)
    if not ids:
        ids = list(TrashedLocation.objects.filter(user=request.user)
                   .values_list('id', flat=True)[:_EDITOR_MAX_IDS])
    restored = editor_tasks.restore_trashed(request.user, ids)
    _bust_user_cache(request.user.id)
    return JsonResponse({'status': 'ok', 'restored': restored})


@login_required
@require_http_methods(["POST"])
def trash_empty_api(request):
    """Permanently discard the trash."""
    from .models import TrashedLocation
    deleted, _ = TrashedLocation.objects.filter(user=request.user).delete()
    return JsonResponse({'status': 'ok', 'deleted': deleted})
