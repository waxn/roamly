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
from django.contrib.staticfiles import finders
from django.core.mail import EmailMultiAlternatives
from django.utils.html import escape

logger = logging.getLogger(__name__)


class _InlineImageEmail(EmailMultiAlternatives):
    """``EmailMultiAlternatives`` that embeds inline images by Content-ID.

    Django 6.0 rewrote ``django.core.mail`` on top of Python's modern
    ``email.message.EmailMessage`` and dropped the undocumented
    ``mixed_subtype`` attribute we previously abused to nest the logo into a
    ``multipart/related`` part (setting it now raises). Instead we let Django
    build the message, then attach each related image to the ``text/html`` part
    via ``add_related``, yielding the RFC 2387 structure clients expect:
    ``alternative[text/plain, related[text/html, image]]``.

    ``related`` is a list of ``(cid, bytes, subtype)`` tuples; ``cid`` includes
    the angle brackets (e.g. ``"<roamlylogo>"``), matching the ``cid:roamlylogo``
    reference in the HTML.
    """

    def __init__(self, *args, related=None, **kwargs):
        self._related = related or []
        super().__init__(*args, **kwargs)

    def message(self, **kwargs):
        msg = super().message(**kwargs)
        if self._related:
            html = next(
                (p for p in msg.walk() if p.get_content_type() == 'text/html'),
                None,
            )
            if html is not None:
                for cid, data, subtype in self._related:
                    html.add_related(
                        data, maintype='image', subtype=subtype, cid=cid
                    )
        return msg

# Inline brand logo (the trail-blaze mark + "Roamly" in Fraunces) embedded via a
# Content-ID reference so the real lockup renders instead of a plain-text word.
_LOGO_CID = 'roamlylogo'
_LOGO_STATIC = 'tracker/email/logo.png'
# Display dimensions in the email (asset is 280x84, rendered at 2x for retina).
_LOGO_W, _LOGO_H = 140, 42
_logo_bytes_cache = None  # None = not looked up yet; False = looked up, missing


def _logo_bytes():
    """The committed logo PNG bytes, or None if the asset can't be found.

    Cached across sends. Reading fails soft — a missing asset just falls back to
    the plain-text wordmark (the ``<img>`` alt), never breaks a send.
    """
    global _logo_bytes_cache
    if _logo_bytes_cache is None:
        _logo_bytes_cache = False
        try:
            path = finders.find(_LOGO_STATIC)
            if path:
                with open(path, 'rb') as fh:
                    _logo_bytes_cache = fh.read()
        except Exception as exc:  # noqa: BLE001 — asset is optional
            logger.warning("Could not load email logo asset: %s", exc)
    return _logo_bytes_cache or None

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
    <table role="presentation" width="480" cellpadding="0" cellspacing="0" style="max-width:480px;width:100%;background:{_CARD};border:1px solid {_BORDER};border-radius:0;">
      <tr><td style="padding:28px 32px 8px 32px;">
        <img src="cid:{_LOGO_CID}" width="{_LOGO_W}" height="{_LOGO_H}" alt="Roamly" style="display:block;border:0;outline:none;text-decoration:none;height:{_LOGO_H}px;width:{_LOGO_W}px;" />
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
        f'border-radius:0;text-align:center;font-family:{_MONO};font-size:30px;font-weight:bold;'
        f'letter-spacing:10px;color:{_TEXT};">{escape(code)}</div>'
    )


def _button(label, url):
    return (
        '<table role="presentation" cellspacing="0" cellpadding="0" border="0" style="margin:22px 0;">'
        f'<tr><td align="center" bgcolor="{_PRIMARY}" style="border-radius:0;">'
        f'<a href="{escape(url)}" target="_blank" style="display:inline-block;padding:13px 28px;'
        f'font-family:{_SANS};font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:0;">'
        f'{escape(label)}</a></td></tr></table>'
        f'<p style="margin:0 0 8px 0;font-family:{_SANS};font-size:12px;color:{_DIM};word-break:break-all;">'
        f'Or paste this link: {escape(url)}</p>'
    )


def _ensure_unsub_token(profile):
    """Create the recap-email unsubscribe token if missing; return it.

    Generated lazily on first send (not at profile creation) so a bulk
    migration can never hand every existing user the same default token —
    same reasoning as Adventure.invite_token / _ensure_invite_token.

    Uses lowercase hex rather than base64url: mail scanners and click-trackers
    that rewrite links have been known to alter the ``-``/``_`` in a urlsafe
    token or case-fold the whole URL, either of which would break the exact
    match in email_unsubscribe_view. Hex has neither hazard. 32 hex chars
    (128 bits) fits the field and stays well beyond guessing.
    """
    if not profile.email_unsub_token:
        profile.email_unsub_token = secrets.token_hex(16)
        profile.save(update_fields=['email_unsub_token'])
    return profile.email_unsub_token


def _link(label, url):
    """A plain inline text link — lighter than _button, for a secondary CTA
    sitting alongside a boxed primary one."""
    return (
        f'<a href="{escape(url)}" target="_blank" style="color:{_PRIMARY};'
        f'font-family:{_SANS};font-size:14px;font-weight:600;text-decoration:none;">{escape(label)}</a>'
    )


def _stat_grid(rows):
    """A responsive-ish 2-column grid of headline stat tiles.

    ``rows`` is a list of ``(label, value)`` pairs — value is the big monospaced
    figure (e.g. "412 km"), label the small caption (e.g. "Distance"). Both are
    escaped. Built as a nested table so it survives email clients.
    """
    if not rows:
        return ""
    cells = []
    for label, value in rows:
        cells.append(
            f'<td width="50%" style="padding:6px;" valign="top">'
            f'<div style="background:{_BG};border:1px solid {_BORDER};padding:14px 16px;">'
            f'<div style="font-family:{_MONO};font-size:24px;font-weight:bold;color:{_TEXT};line-height:1.1;">{escape(str(value))}</div>'
            f'<div style="font-family:{_SANS};font-size:12px;color:{_MUTED};margin-top:4px;">{escape(str(label))}</div>'
            f'</div></td>'
        )
    # Pair cells two-per-row; pad an odd trailing cell with an empty column.
    row_html = []
    for i in range(0, len(cells), 2):
        pair = cells[i:i + 2]
        if len(pair) == 1:
            pair.append('<td width="50%" style="padding:6px;"></td>')
        row_html.append('<tr>' + ''.join(pair) + '</tr>')
    return (
        '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" '
        'style="margin:8px 0 4px 0;border-collapse:separate;">'
        + ''.join(row_html) + '</table>'
    )


def _ranked_list(heading, rows):
    """A numbered list section — e.g. top 5 cities/places by time spent.

    ``rows`` is a list of ``(label, detail)`` pairs — ``label`` the place name,
    ``detail`` a short right-aligned figure (e.g. "3h 20m"). Both escaped.
    """
    if not rows:
        return ""
    items = []
    last = len(rows) - 1
    for i, (label, detail) in enumerate(rows):
        border = "none" if i == last else f"1px solid {_BORDER}"
        items.append(
            f'<tr>'
            f'<td style="padding:8px 0;border-bottom:{border};font-family:{_MONO};font-size:13px;'
            f'color:{_DIM};width:20px;" valign="top">{i + 1}</td>'
            f'<td style="padding:8px 8px 8px 4px;border-bottom:{border};font-family:{_SANS};'
            f'font-size:14px;color:{_TEXT};" valign="top">{escape(str(label))}</td>'
            f'<td style="padding:8px 0;border-bottom:{border};font-family:{_MONO};font-size:12px;'
            f'color:{_MUTED};text-align:right;white-space:nowrap;" valign="top">{escape(str(detail))}</td>'
            f'</tr>'
        )
    return (
        f'<div style="margin:22px 0 6px 0;font-family:{_SANS};font-size:12px;font-weight:700;'
        f'letter-spacing:0.04em;text-transform:uppercase;color:{_MUTED};">{escape(heading)}</div>'
        '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" '
        'style="border-collapse:collapse;">' + ''.join(items) + '</table>'
    )


def _send(subject, text_body, html_body, to_email, reply_to=None):
    """Send one multipart email, swallowing errors (returns True on success).

    Never raises — a mail outage must not 500 a signup/login request. ``reply_to``
    (a single address) is used by the contact form so a reply reaches the sender.
    """
    if not email_enabled() or not to_email:
        return False
    try:
        # Embed the lockup as a related inline image the HTML references by CID
        # (Content-ID ``<roamlylogo>`` ↔ ``cid:roamlylogo``), so the real lockup
        # renders in Gmail/Apple/Outlook instead of the plain-text alt fallback.
        logo = _logo_bytes()
        related = [(f'<{_LOGO_CID}>', logo, 'png')] if logo else []
        msg = _InlineImageEmail(
            subject, text_body, settings.DEFAULT_FROM_EMAIL, [to_email],
            reply_to=[reply_to] if reply_to else None,
            related=related,
        )
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


def send_summary_email(to_email, *, period_label, heading, narrative_paras,
                       stat_rows, top_cities=None, top_places=None,
                       map_url=None, stats_url=None, unsubscribe_url=None):
    """Send an AI-generated travel recap for a period.

    ``period_label`` is a short phrase like "day", "week", "month", "year" (used
    in the subject). ``heading`` is the card headline. ``narrative_paras`` is a
    list of plain-text paragraphs from the LLM (escaped here). ``stat_rows`` is a
    list of ``(label, value)`` headline stats rendered as tiles. ``top_cities``/
    ``top_places`` are ``(label, detail)`` lists rendered as ranked sections.
    ``map_url``/``stats_url``, when given, deep-link into the app pre-filtered to
    this period (map gets the boxed primary button, stats a lighter text link).
    ``unsubscribe_url``, when given, renders a muted "manage email preferences"
    line — deliberately scoped to this recap email rather than the shared
    _shell() footer, since verification/reset/invite emails aren't opt-out mail.
    """
    subject = f'Your Roamly {period_label} recap'
    paras = [p for p in (narrative_paras or []) if p and p.strip()]
    body = ''.join(_p(escape(p.strip())) for p in paras)
    body += _stat_grid(stat_rows)
    body += _ranked_list('Top cities', top_cities or [])
    body += _ranked_list('Top places', top_places or [])
    if map_url:
        body += _button('View on map', map_url)
    if stats_url:
        body += (
            f'<p style="margin:{"-10px" if map_url else "18px"} 0 4px 0;">'
            f'{_link("View in Stats →", stats_url)}</p>'
        )
    if unsubscribe_url:
        body += (
            f'<p style="margin:18px 0 0 0;font-family:{_SANS};font-size:12px;color:{_DIM};">'
            f'Don\'t want these? <a href="{escape(unsubscribe_url)}" target="_blank" '
            f'style="color:{_DIM};text-decoration:underline;">Manage email preferences</a></p>'
        )
    preheader = paras[0][:120] if paras else f'Your Roamly {period_label} recap'
    text_lines = [f'Your Roamly {period_label} recap', '']
    text_lines += paras
    if stat_rows:
        text_lines.append('')
        text_lines += [f'{label}: {value}' for label, value in stat_rows]
    if top_cities:
        text_lines += ['', 'Top cities:'] + [f'  {label} — {detail}' for label, detail in top_cities]
    if top_places:
        text_lines += ['', 'Top places:'] + [f'  {label} — {detail}' for label, detail in top_places]
    if map_url:
        text_lines += ['', f'View on map: {map_url}']
    if stats_url:
        text_lines += ['', f'View in Stats: {stats_url}']
    if unsubscribe_url:
        text_lines += ['', f'Manage email preferences: {unsubscribe_url}']
    text = '\n'.join(text_lines)
    html = _shell(heading, body, preheader=preheader)
    return _send(subject, text, html, to_email)


def send_no_data_alert_email(to_email, *, silent_for, last_point_at=None,
                            device_name='', threshold_label='', map_url=None,
                            settings_url=None, unsubscribe_url=None,
                            is_test=False):
    """Alert the user that no device has reported a location fix in a while.

    ``silent_for`` is a pre-formatted human duration ("4h 20m"), ``threshold_label``
    the configured trigger ("3 hours"), ``last_point_at`` a formatted local
    timestamp of the newest fix (None when the account has never logged one).
    ``device_name`` names the device that reported it. ``is_test`` re-words the
    lead so a preview send from Settings is never mistaken for a real outage.

    Unlike the recap emails this carries no AI narrative and no stats — it is an
    operational notice, and the useful content is entirely "when did tracking
    stop, and on which device".
    """
    subject = ('Roamly test alert: tracking check'
               if is_test else 'Roamly alert: no location data received')
    heading = 'Tracking check' if is_test else 'Tracking may have stopped'
    if is_test:
        lead = (
            'This is a <b style="color:%s;">test</b> of your Roamly tracking alert. '
            'Here\'s what a real one would look like, using your current data.' % _TEXT
        )
        text_lead = ("This is a test of your Roamly tracking alert, using your "
                     "current data.")
    else:
        lead = (
            'Roamly hasn\'t received any location data from your devices for '
            f'<b style="color:{_TEXT};">{escape(silent_for)}</b>. If you expected to be '
            'tracking, it\'s worth checking that the app is still running and has '
            'background location permission.'
        )
        text_lead = (f"Roamly hasn't received any location data from your devices "
                     f"for {silent_for}. If you expected to be tracking, check that "
                     f"the app is still running and has background location permission.")

    rows = [("Silent for", silent_for)]
    if threshold_label:
        rows.append(("Alerts after", threshold_label))

    body = _p(lead) + _stat_grid(rows)
    if last_point_at:
        detail = f'Last fix: <b style="color:{_TEXT};">{escape(last_point_at)}</b>'
        if device_name:
            detail += f' from {escape(device_name)}'
        body += _p(detail)
    else:
        body += _p('No location fixes have ever been recorded on this account.')
    if map_url:
        body += _button('Open your map', map_url)
    if settings_url:
        body += (
            f'<p style="margin:{"-10px" if map_url else "18px"} 0 4px 0;">'
            f'{_link("Change alert settings \u2192", settings_url)}</p>'
        )
    if unsubscribe_url:
        body += (
            f'<p style="margin:18px 0 0 0;font-family:{_SANS};font-size:12px;color:{_DIM};">'
            f'Don\'t want these? <a href="{escape(unsubscribe_url)}" target="_blank" '
            f'style="color:{_DIM};text-decoration:underline;">Manage email preferences</a></p>'
        )

    text_lines = [heading, '', text_lead, '']
    text_lines += [f'{label}: {value}' for label, value in rows]
    if last_point_at:
        text_lines.append('Last fix: ' + last_point_at
                          + (f' from {device_name}' if device_name else ''))
    else:
        text_lines.append('No location fixes have ever been recorded on this account.')
    if map_url:
        text_lines += ['', f'Open your map: {map_url}']
    if settings_url:
        text_lines += ['', f'Change alert settings: {settings_url}']
    if unsubscribe_url:
        text_lines += ['', f'Manage email preferences: {unsubscribe_url}']
    text = '\n'.join(text_lines)
    html = _shell(heading, body, preheader=(
        'Test of your Roamly tracking alert' if is_test
        else f'No location data for {silent_for}'))
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


def send_contact_email(to_email, sender_name, sender_email, message):
    """Deliver a public contact-form submission to the instance contact address.

    Sent from ``DEFAULT_FROM_EMAIL`` (the instance's own address) with the
    sender's address as Reply-To, so hitting reply answers the person who wrote
    in. Returns True on success.
    """
    name = (sender_name or '').strip() or 'Someone'
    subject = f'Roamly contact: {name}'
    text = (
        f"New message from the Roamly contact form.\n\n"
        f"From: {name} <{sender_email}>\n\n"
        f"{message}"
    )
    body_html = (
        _p(f'New message from the Roamly contact form.') +
        _p(f'<b style="color:{_TEXT};">From:</b> {escape(name)} '
           f'&lt;{escape(sender_email)}&gt;') +
        f'<div style="margin:16px 0;padding:16px;background:{_BG};border:1px solid {_BORDER};'
        f'font-family:{_SANS};font-size:15px;line-height:1.6;color:{_TEXT};white-space:pre-wrap;">'
        f'{escape(message)}</div>'
    )
    html = _shell('New contact message', body_html,
                  preheader=f'From {name}')
    return _send(subject, text, html, to_email, reply_to=sender_email or None)
