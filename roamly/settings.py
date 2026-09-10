import os
import re
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

# DEBUG defaults OFF. This image is published to Docker Hub and deployed by
# copying .env.example, so the *code* default is what a self-hoster who misses a
# variable actually gets — and a debug default means full tracebacks (with
# settings and SQL) on every 500, plus ALLOWED_HOSTS being ignored entirely.
# It is also what makes the custom error pages in tracker/views.py reachable.
DEBUG = os.environ.get('DEBUG', 'False').lower() in ('true', '1')

# No fallback SECRET_KEY in production. The old default was a hardcoded literal
# in a public image, so every instance whose operator missed the variable shared
# one publicly-known key — enough to forge session cookies and password-reset
# tokens. Fail loudly at boot instead of silently running forgeable.
SECRET_KEY = os.environ.get('SECRET_KEY', '')
if not SECRET_KEY:
    if DEBUG:
        SECRET_KEY = 'django-insecure-dev-only-not-for-production'
    else:
        from django.core.exceptions import ImproperlyConfigured
        raise ImproperlyConfigured(
            'SECRET_KEY must be set when DEBUG is off. Generate one with:\n'
            '  python -c "import secrets; print(secrets.token_urlsafe(64))"'
        )

ALLOWED_HOSTS = os.environ.get('ALLOWED_HOSTS', 'localhost,127.0.0.1').split(',')
CSRF_TRUSTED_ORIGINS = os.environ.get('CSRF_TRUSTED_ORIGINS', 'http://localhost,http://127.0.0.1').split(',')

INSTALLED_APPS = [
    'django.contrib.admin',
    'django.contrib.auth',
    'django.contrib.contenttypes',
    'django.contrib.sessions',
    'django.contrib.messages',
    'django.contrib.staticfiles',
    'django.contrib.sitemaps',
    'tracker',
]

# Add GeoDjango when using PostGIS
DATABASE_URL = os.environ.get('DATABASE_URL')
if DATABASE_URL:
    INSTALLED_APPS.insert(-1, 'django.contrib.gis')
    INSTALLED_APPS.insert(-1, 'django.contrib.postgres')

MIDDLEWARE = [
    'django.middleware.security.SecurityMiddleware',
    'django.middleware.gzip.GZipMiddleware',
    'whitenoise.middleware.WhiteNoiseMiddleware',
    'django.contrib.sessions.middleware.SessionMiddleware',
    'django.middleware.common.CommonMiddleware',
    'django.middleware.csrf.CsrfViewMiddleware',
    'django.contrib.auth.middleware.AuthenticationMiddleware',
    'tracker.middleware.ApiKeyAuthMiddleware',
    # After ApiKeyAuthMiddleware so request.user is resolved before we log it.
    'tracker.middleware.RequestLoggingMiddleware',
    # Also after ApiKeyAuthMiddleware: activates the user's local timezone,
    # derived from their latest fix, so date ranges and day buckets are local.
    'tracker.middleware.UserTimezoneMiddleware',
    'django.contrib.messages.middleware.MessageMiddleware',
    'django.middleware.clickjacking.XFrameOptionsMiddleware',
    # Last, so it sees the final response and can set headers on everything
    # (including responses short-circuited by the middlewares above it).
    'tracker.middleware.SecurityHeadersMiddleware',
]

ROOT_URLCONF = 'roamly.urls'

TEMPLATES = [
    {
        'BACKEND': 'django.template.backends.django.DjangoTemplates',
        'DIRS': [],
        'APP_DIRS': True,
        'OPTIONS': {
            'context_processors': [
                'django.template.context_processors.request',
                'django.contrib.auth.context_processors.auth',
                'django.contrib.messages.context_processors.messages',
                'tracker.context_processors.custom_js_snippet',
            ],
        },
    },
]

WSGI_APPLICATION = 'roamly.wsgi.application'

# Database
if DATABASE_URL:
    import dj_database_url
    DATABASES = {'default': dj_database_url.parse(DATABASE_URL, conn_max_age=600)}
    DATABASES['default']['ENGINE'] = 'django.contrib.gis.db.backends.postgis'
else:
    DATABASES = {
        'default': {
            'ENGINE': 'django.db.backends.sqlite3',
            'NAME': BASE_DIR / 'db.sqlite3',
        }
    }

AUTH_PASSWORD_VALIDATORS = [
    {'NAME': 'django.contrib.auth.password_validation.UserAttributeSimilarityValidator'},
    {'NAME': 'django.contrib.auth.password_validation.MinimumLengthValidator'},
    {'NAME': 'django.contrib.auth.password_validation.CommonPasswordValidator'},
    {'NAME': 'django.contrib.auth.password_validation.NumericPasswordValidator'},
]

LANGUAGE_CODE = 'en-us'
TIME_ZONE = 'UTC'
USE_I18N = True
USE_TZ = True

STATIC_URL = 'static/'
STATIC_ROOT = BASE_DIR / 'staticfiles'
STORAGES = {
    "default": {"BACKEND": "django.core.files.storage.FileSystemStorage"},
    "staticfiles": {"BACKEND": "whitenoise.storage.CompressedManifestStaticFilesStorage"},
}

MEDIA_URL = '/media/'
MEDIA_ROOT = BASE_DIR / 'media'

DEFAULT_AUTO_FIELD = 'django.db.models.BigAutoField'

REDIS_URL = os.environ.get('REDIS_URL', '')
if REDIS_URL:
    CACHES = {
        'default': {
            'BACKEND': 'django_redis.cache.RedisCache',
            'LOCATION': REDIS_URL,
            'OPTIONS': {'CLIENT_CLASS': 'django_redis.client.DefaultClient'},
        }
    }
else:
    CACHES = {'default': {'BACKEND': 'django.core.cache.backends.locmem.LocMemCache'}}

# How many reverse proxies sit in front of this app. X-Forwarded-For is
# append-only, so the client controls everything BEFORE the entries our own
# proxies wrote — _client_ip counts this many back from the end of the header
# and falls back to REMOTE_ADDR when the header is shorter than that.
#
#   1  a single nginx / Caddy / Traefik in front of the container (the default)
#   2  Cloudflare (or another CDN) in front of that proxy
#   0  the app is exposed directly, trust nothing but REMOTE_ADDR
#
# Setting this too HIGH is the dangerous direction: it starts trusting entries
# the client wrote, which is exactly what defeats every rate limit in the app.
TRUSTED_PROXY_COUNT = int(os.environ.get('TRUSTED_PROXY_COUNT', '1'))

SITE_URL = os.environ.get('SITE_URL', 'http://localhost:8000')

# GitHub repo (owner/name) whose latest `mobile-v*` release the in-app updater
# checks. Self-hosters / forks can repoint this to their own release repo.
MOBILE_UPDATE_REPO = os.environ.get('MOBILE_UPDATE_REPO', 'waxn/roamly')

# Overpass endpoint pool for the road/subway/POI downloaders. Requests try
# these in order, advancing only when an endpoint is unreachable or too slow to
# answer — never on an HTTP error, which means the server was reached and
# answered. See tracker/overpass.py, which is the only place that talks to
# Overpass and which remembers the endpoint that last worked.
#
# The default pool deliberately omits the official instance (overpass-api.de):
# it enforces a fair-use policy and refuses connections outright from an IP it
# has judged abusive, so on such a network it is a guaranteed failed attempt on
# every request. Put it back at the top of the list if this server can reach it.
_OVERPASS_DEFAULT_POOL = [
    'https://overpass.kumi.systems/api/interpreter',
    'https://overpass.private.coffee/api/interpreter',
    'https://maps.mail.ru/osm/tools/overpass/api/interpreter',
    'https://overpass.osm.ch/api/interpreter',
]

# OVERPASS_URL (singular) is the legacy setting and is still honoured: an
# instance that set it because the official endpoint refuses its network gets
# that endpoint tried FIRST, and now gets the rest of the pool as a free
# fallback rather than having a single point of failure.
_overpass_primary = os.environ.get('OVERPASS_URL', '').strip()
_overpass_pool = [u for u in re.split(r'[,\s]+', os.environ.get('OVERPASS_URLS', '')) if u]
if not _overpass_pool:
    _overpass_pool = list(_OVERPASS_DEFAULT_POOL)
if _overpass_primary:
    _overpass_pool = [_overpass_primary] + _overpass_pool

# Order-preserving dedupe.
OVERPASS_URLS = list(dict.fromkeys(_overpass_pool))
# Back-compat for anything still reading the singular name.
OVERPASS_URL = OVERPASS_URLS[0]

# Secret key that, when entered on the signup form's "admin account" section,
# creates an instance-admin account. Leave unset to disable admin signups.
ADMIN_SIGNUP_KEY = os.environ.get('ADMIN_SIGNUP_KEY', '')

# ── Email / SMTP ────────────────────────────────────────────────────────────
# All email features (signup verification, new-device login codes, invite
# emails) are GATED on EMAIL_HOST being set. With no SMTP configured, the app
# behaves exactly as before: no verification, invites shown as copyable links.
EMAIL_HOST = os.environ.get('EMAIL_HOST', '')
EMAIL_PORT = int(os.environ.get('EMAIL_PORT', '587'))
EMAIL_HOST_USER = os.environ.get('EMAIL_HOST_USER', '')
EMAIL_HOST_PASSWORD = os.environ.get('EMAIL_HOST_PASSWORD', '')
EMAIL_USE_TLS = os.environ.get('EMAIL_USE_TLS', 'True').lower() in ('true', '1')
EMAIL_USE_SSL = os.environ.get('EMAIL_USE_SSL', 'False').lower() in ('true', '1')
DEFAULT_FROM_EMAIL = os.environ.get('DEFAULT_FROM_EMAIL', EMAIL_HOST_USER or 'roamly@localhost')
EMAIL_TIMEOUT = int(os.environ.get('EMAIL_TIMEOUT', '10'))
# True when the instance operator has configured outbound mail.
EMAIL_ENABLED = bool(EMAIL_HOST)
if EMAIL_ENABLED:
    EMAIL_BACKEND = 'django.core.mail.backends.smtp.EmailBackend'
else:
    # No SMTP: keep a harmless backend so any stray send_mail call is a no-op-ish
    # console write rather than an error.
    EMAIL_BACKEND = 'django.core.mail.backends.console.EmailBackend'

# ── Transport / cookie security ─────────────────────────────────────────────
# All gated on DEBUG so a local run over plain HTTP still works.
#
# SECURE_PROXY_SSL_HEADER is the one with visible symptoms: Roamly normally runs
# behind a reverse proxy that terminates TLS, and without this request.is_secure()
# is always False and request.scheme is "http" — so every absolute URL built from
# build_absolute_uri came out as http://, including the canonical link, og:url,
# the robots.txt Sitemap line and every sitemap.xml entry.
SECURE_REFERRER_POLICY = 'strict-origin-when-cross-origin'
SESSION_COOKIE_AGE = 60 * 60 * 24 * 30          # 30 days
SESSION_SAVE_EVERY_REQUEST = True                # sliding expiry, not fixed
if not DEBUG:
    SECURE_PROXY_SSL_HEADER = ('HTTP_X_FORWARDED_PROTO', 'https')
    SESSION_COOKIE_SECURE = True
    CSRF_COOKIE_SECURE = True
    SESSION_COOKIE_SAMESITE = 'Lax'
    CSRF_COOKIE_SAMESITE = 'Lax'
    # HSTS and the HTTPS redirect stay OPT-IN. A self-hoster running on a LAN
    # over plain http:// would otherwise lock themselves out of their own
    # instance — and HSTS in particular is remembered by the browser for as long
    # as it says, so getting it wrong is not something a redeploy undoes.
    SECURE_HSTS_SECONDS = int(os.environ.get('HSTS_SECONDS', '0'))
    SECURE_HSTS_INCLUDE_SUBDOMAINS = SECURE_HSTS_SECONDS > 0
    SECURE_HSTS_PRELOAD = SECURE_HSTS_SECONDS > 0
    SECURE_SSL_REDIRECT = os.environ.get('SSL_REDIRECT', 'False').lower() in ('true', '1')

# ── Content-Security-Policy ─────────────────────────────────────────────────
# Enforced by tracker.middleware.SecurityHeadersMiddleware.
#
# Report-only by default. The app has a great deal of deliberately inline CSS and
# JS (base.html alone is a 47KB <style> block), so 'unsafe-inline' cannot be
# dropped without a much larger refactor — the value here is locking down
# connect-src, img-src, object-src and frame-ancestors, which is what limits
# what an injected script could actually do. Flip CSP_ENFORCE=1 once you have
# browsed every page with the console open and seen no violations.
CSP_ENFORCE = os.environ.get('CSP_ENFORCE', 'False').lower() in ('true', '1')
CSP_EXTRA_HOSTS = [h for h in re.split(r'[,\s]+', os.environ.get('CSP_EXTRA_HOSTS', '')) if h]

LOGIN_URL = '/login/'
LOGIN_REDIRECT_URL = '/map/'

# File uploads (for GPX/CSV import)
DATA_UPLOAD_MAX_MEMORY_SIZE = 104857600  # 100MB

# Logging: capture unhandled 500s into the admin panel's ActionLog (with
# traceback) via a custom handler on Django's 'django.request' logger, which
# fires on every 5xx response. disable_existing_loggers=False keeps Django's
# default console logging intact; the handler is best-effort and never raises.
LOGGING = {
    'version': 1,
    'disable_existing_loggers': False,
    'handlers': {
        'action_log_errors': {
            'level': 'ERROR',
            'class': 'tracker.error_log_handler.ActionLogErrorHandler',
        },
    },
    'loggers': {
        'django.request': {
            'handlers': ['action_log_errors'],
            'level': 'ERROR',
            'propagate': True,
        },
    },
}
