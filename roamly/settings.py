import os
import re
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

SECRET_KEY = os.environ.get('SECRET_KEY', 'django-insecure-change-me-in-production')
DEBUG = os.environ.get('DEBUG', 'True').lower() in ('true', '1')
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
    'django.contrib.messages.middleware.MessageMiddleware',
    'django.middleware.clickjacking.XFrameOptionsMiddleware',
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
