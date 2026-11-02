"""Repair Ask replies saved while the stream was decoded as Latin-1.

The streamed reply was read with requests' decode_unicode, which falls back to
ISO-8859-1 when an event stream names no charset, so UTF-8 emoji and dashes were
stored as "ð…" / "â…". Latin-1 maps every byte to one character, so that is
exactly reversible: encode as Latin-1 and decode as UTF-8. Only text where that
round trip succeeds and changes something is rewritten — plain ASCII is left
alone, and genuine Latin-1 text like "café" is not valid UTF-8 once encoded, so
it is skipped too.
"""
from django.db import migrations


def _repair(text):
    try:
        fixed = text.encode('latin-1').decode('utf-8')
    except (UnicodeEncodeError, UnicodeDecodeError):
        return None
    return fixed if fixed != text else None


def forwards(apps, schema_editor):
    AskMessage = apps.get_model('tracker', 'AskMessage')
    for msg in AskMessage.objects.filter(role='assistant').only('id', 'content').iterator():
        fixed = _repair(msg.content or '')
        if fixed is not None:
            AskMessage.objects.filter(pk=msg.pk).update(content=fixed)


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0112_ask_chats'),
    ]

    operations = [
        migrations.RunPython(forwards, migrations.RunPython.noop),
    ]
