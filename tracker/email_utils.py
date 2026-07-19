"""Outbound email helpers.

Everything here is gated on ``settings.EMAIL_ENABLED`` (which is True only when
``EMAIL_HOST`` is configured). Callers should check ``email_enabled()`` before
requiring a verification step so the app degrades gracefully with no SMTP.
"""
import logging
import secrets

from django.conf import settings
from django.core.mail import send_mail

logger = logging.getLogger(__name__)


def email_enabled():
    return bool(getattr(settings, 'EMAIL_ENABLED', False))


def gen_code(digits=6):
    """A numeric verification code, zero-padded, cryptographically random."""
    upper = 10 ** digits
    return str(secrets.randbelow(upper)).zfill(digits)


def _send(subject, message, to_email):
    """Send one email, swallowing errors (returns True on success).

    Never raises — a mail outage must not 500 a signup/login request.
    """
    if not email_enabled():
        return False
    if not to_email:
        return False
    try:
        send_mail(
            subject,
            message,
            settings.DEFAULT_FROM_EMAIL,
            [to_email],
            fail_silently=False,
        )
        return True
    except Exception as exc:  # noqa: BLE001 — deliberately broad; log and move on
        logger.error("Email send failed to %s: %s", to_email, exc)
        return False


def send_code_email(to_email, code, purpose='signup'):
    """Email a verification code. `purpose` in {'signup', 'login'}."""
    if purpose == 'login':
        subject = 'Your Roamly sign-in code'
        body = (
            f"Someone signed in to your Roamly account from a new device.\n\n"
            f"Your verification code is: {code}\n\n"
            f"Enter it to finish signing in. The code expires in 15 minutes.\n"
            f"If this wasn't you, change your password."
        )
    else:
        subject = 'Verify your Roamly account'
        body = (
            f"Welcome to Roamly!\n\n"
            f"Your verification code is: {code}\n\n"
            f"Enter it to activate your account. The code expires in 15 minutes."
        )
    return _send(subject, body, to_email)


def send_invite_email(to_email, trip_name, join_url, inviter_name=''):
    """Email a trip invite link."""
    who = f"{inviter_name} invited" if inviter_name else "You've been invited"
    subject = f'{inviter_name or "Someone"} invited you to a Roamly trip'
    body = (
        f"{who} you to join the trip \"{trip_name}\" on Roamly.\n\n"
        f"Open this link to view the trip and join:\n{join_url}\n\n"
        f"You'll be able to log in or create an account and add your own track."
    )
    return _send(subject, body, to_email)
