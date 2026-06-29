import re

from django.db import migrations, models


PIN_RE = re.compile(r'\[\^pin:(\d+)\]')


def places_to_blurbs(apps, schema_editor):
    """Convert every AdventurePlace into an AdventureBlurb (POI) and rewrite the
    inline pin references and location_card place_ids stored in each adventure's
    body JSON so they point at the new blurb ids."""
    Adventure = apps.get_model('tracker', 'Adventure')
    AdventurePlace = apps.get_model('tracker', 'AdventurePlace')
    AdventureBlurb = apps.get_model('tracker', 'AdventureBlurb')

    for adv in Adventure.objects.all():
        author = adv.creator_id
        if author is None:
            author = adv.device.user_id

        id_map = {}
        for place in adv.places.all():
            blurb = AdventureBlurb.objects.create(
                adventure=adv,
                author_id=author,
                title=(place.name or '')[:200],
                text=place.notes or '',
                latitude=place.latitude,
                longitude=place.longitude,
                location_name='',
            )
            # created_at is auto_now_add; preserve the original time explicitly.
            AdventureBlurb.objects.filter(pk=blurb.pk).update(created_at=place.created_at)
            id_map[place.id] = blurb.id

        if not id_map:
            continue

        body = adv.body or []
        changed = False
        for block in body:
            if not isinstance(block, dict):
                continue
            content = block.get('content') or {}
            btype = block.get('type')
            if btype in ('paragraph', 'heading', 'callout'):
                text = content.get('text')
                if text and '[^pin:' in text:
                    new_text = PIN_RE.sub(
                        lambda m: '[^pin:%d]' % id_map.get(int(m.group(1)), int(m.group(1))),
                        text,
                    )
                    if new_text != text:
                        content['text'] = new_text
                        changed = True
            elif btype == 'location_card':
                pid = content.get('place_id')
                if pid in id_map:
                    content['place_id'] = id_map[pid]
                    changed = True
        if changed:
            adv.body = body
            adv.save(update_fields=['body'])


def noop(apps, schema_editor):
    # AdventurePlace is dropped; this migration is one-way.
    pass


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0036_remove_admin_logging'),
    ]

    operations = [
        migrations.AddField(
            model_name='adventureblurb',
            name='title',
            field=models.CharField(blank=True, default='', max_length=200),
        ),
        migrations.AlterField(
            model_name='adventureblurb',
            name='text',
            field=models.TextField(blank=True),
        ),
        migrations.RunPython(places_to_blurbs, noop),
        migrations.DeleteModel(name='AdventurePlace'),
    ]
