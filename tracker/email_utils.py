"""Outbound email helpers.

Everything here is gated on ``settings.EMAIL_ENABLED`` (which is True only when
``EMAIL_HOST`` is configured). Callers should check ``email_enabled()`` before
requiring a verification step so the app degrades gracefully with no SMTP.

Emails are sent as multipart text + branded HTML (the HTML mirrors the site's
"Field Journal" dark look). The plain-text part is the fallback.
"""
import logging
import secrets

from django.conf import settings
from django.core.mail import EmailMultiAlternatives
from django.utils.html import escape

logger = logging.getLogger(__name__)

# Brand palette (matches base.html :root dark theme).
_BG = "#14160f"
_CARD = "#1b1e15"
_BORDER = "#30352a"
_TEXT = "#e9e5d8"
_MUTED = "#a9a794"
_DIM = "#706e5d"
_PRIMARY = "#e8763d"
_SERIF = "Georgia, 'Times New Roman', serif"
_SANS = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"
_MONO = "'SF Mono', 'Roboto Mono', Menlo, Consolas, monospace"


def email_enabled():
    return bool(getattr(settings, 'EMAIL_ENABLED', False))


def gen_code(digits=6):
    """A numeric verification code, zero-padded, cryptographically random."""
    upper = 10 ** digits
    return str(secrets.randbelow(upper)).zfill(digits)


def _shell(heading, body_html, preheader=""):
    """Wrap inner HTML in the branded, email-client-safe (table + inline) layout."""
    pre = (
        f'<div style="display:none;max-height:0;overflow:hidden;opacity:0;">{escape(preheader)}</div>'
        if preheader else ""
    )
    return f"""\
<!DOCTYPE html>
<html><body style="margin:0;padding:0;background:{_BG};">
{pre}
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:{_BG};padding:32px 12px;">
  <tr><td align="center">
    <table role="presentation" width="480" cellpadding="0" cellspacing="0" style="max-width:480px;width:100%;background:{_CARD};border:1px solid {_BORDER};border-radius:12px;">
      <tr><td style="padding:28px 32px 8px 32px;">
        <div style="font-family:{_SERIF};font-size:24px;font-weight:bold;color:{_PRIMARY};letter-spacing:-0.01em;">Roamly</div>
      </td></tr>
      <tr><td style="padding:8px 32px 28px 32px;">
        <h1 style="margin:12px 0 16px 0;font-family:{_SERIF};font-size:22px;font-weight:bold;color:{_TEXT};">{escape(heading)}</h1>
        {body_html}
      </td></tr>
      <tr><td style="padding:0 32px 28px 32px;">
        <div style="border-top:1px solid {_BORDER};padding-top:16px;font-family:{_SANS};font-size:12px;color:{_DIM};line-height:1.5;">
          You're receiving this because someone used this address with Roamly. If it wasn't you, you can safely ignore this email.
        </div>
      </td></tr>
    </table>
  </td></tr>
</table>
</body></html>"""


def _p(text):
    return f'<p style="margin:0 0 14px 0;font-family:{_SANS};font-size:15px;line-height:1.6;color:{_MUTED};">{text}</p>'


def _code_box(code):
    return (
        f'<div style="margin:20px 0;padding:16px;background:{_BG};border:1px solid {_BORDER};'
        f'border-radius:8px;text-align:center;font-family:{_MONO};font-size:30px;font-weight:bold;'
        f'letter-spacing:10px;color:{_TEXT};">{escape(code)}</div>'
    )


def _button(label, url):
    return (
        '<table role="presentation" cellspacing="0" cellpadding="0" border="0" style="margin:22px 0;">'
        f'<tr><td align="center" bgcolor="{_PRIMARY}" style="border-radius:8px;">'
        f'<a href="{escape(url)}" target="_blank" style="display:inline-block;padding:13px 28px;'
        f'font-family:{_SANS};font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:8px;">'
        f'{escape(label)}</a></td></tr></table>'
        f'<p style="margin:0 0 8px 0;font-family:{_SANS};font-size:12px;color:{_DIM};word-break:break-all;">'
        f'Or paste this link: {escape(url)}</p>'
    )


def _send(subject, text_body, html_body, to_email):
    """Send one multipart email, swallowing errors (returns True on success).

    Never raises — a mail outage must not 500 a signup/login request.
    """
    if not email_enabled() or not to_email:
        return False
    try:
        msg = EmailMultiAlternatives(subject, text_body, settings.DEFAULT_FROM_EMAIL, [to_email])
        msg.attach_alternative(html_body, "text/html")
        msg.send()
        return True
    except Exception as exc:  # noqa: BLE001 — deliberately broad; log and move on
        logger.error("Email send failed to %s: %s", to_email, exc)
        return False


def send_code_email(to_email, code, purpose='signup'):
    """Email a verification code. `purpose` in {'signup', 'login'}."""
    if purpose == 'login':
        subject = 'Your Roamly sign-in code'
        heading = 'Sign in from a new device'
        lead = 'Someone signed in to your Roamly account from a new device. Enter this code to finish:'
        text = (
            f"Someone signed in to your Roamly account from a new device.\n\n"
            f"Your verification code is: {code}\n\n"
            f"It expires in 15 minutes. If this wasn't you, change your password."
        )
    else:
        subject = 'Verify your Roamly account'
        heading = 'Welcome to Roamly'
        lead = 'Enter this code to activate your account:'
        text = (
            f"Welcome to Roamly!\n\nYour verification code is: {code}\n\n"
            f"It expires in 15 minutes."
        )
    html = _shell(heading, _p(lead) + _code_box(code) + _p(
        '<span style="color:%s;">This code expires in 15 minutes.</span>' % _DIM), preheader=f'Code: {code}')
    return _send(subject, text, html, to_email)


def send_invite_email(to_email, trip_name, join_url, inviter_name=''):
    """Email a trip invite link."""
    subject = f'{inviter_name or "Someone"} invited you to a Roamly trip'
    heading = "You're invited to a trip"
    who = f"{escape(inviter_name)} invited" if inviter_name else "You've been invited"
    text = (
        f"{who} you to join the trip \"{trip_name}\" on Roamly.\n\n"
        f"Open this link to view the trip and join:\n{join_url}\n\n"
        f"You'll be able to log in or create an account and add your own track."
    )
    html = _shell(heading,
                  _p(f'{who} you to join <b style="color:{_TEXT};">{escape(trip_name)}</b> on Roamly.') +
                  _p('View the trip and join — you can log in or create a free account and add your own track.') +
                  _button('View &amp; join trip', join_url),
                  preheader=f'Join {trip_name} on Roamly')
    return _send(subject, text, html, to_email)


def send_password_reset_email(to_email, reset_url, username=''):
    """Email a password-reset link."""
    subject = 'Reset your Roamly password'
    heading = 'Reset your password'
    text = (
        f"We got a request to reset the password for your Roamly account"
        f"{f' ({username})' if username else ''}.\n\n"
        f"Open this link to choose a new password:\n{reset_url}\n\n"
        f"If you didn't ask for this, you can ignore this email — your password stays the same."
    )
    html = _shell(heading,
                  _p('We got a request to reset the password for your Roamly account'
                     + (f' (<b style="color:%s;">%s</b>)' % (_TEXT, escape(username)) if username else '') + '.') +
                  _p('Choose a new password:') +
                  _button('Reset password', reset_url) +
                  _p('If you didn\'t ask for this, ignore this email — your password won\'t change.'),
                  preheader='Reset your Roamly password')
    return _send(subject, text, html, to_email)
