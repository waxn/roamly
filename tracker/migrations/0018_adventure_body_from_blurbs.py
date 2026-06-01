from django.db import migrations


def blurbs_to_body(apps, schema_editor):
    Adventure = apps.get_model('tracker', 'Adventure')
    AdventureBlurb = apps.get_model('tracker', 'AdventureBlurb')

    blurbs_by_adventure = {}
    for blurb in AdventureBlurb.objects.order_by('adventure_id', 'created_at'):
        blurbs_by_adventure.setdefault(blurb.adventure_id, []).append(blurb)

    adventures_to_update = []
    for adventure in Adventure.objects.filter(id__in=blurbs_by_adventure.keys()):
        blurbs = blurbs_by_adventure.get(adventure.id, [])
        if not blurbs:
            continue
        body = [{"type": "heading", "content": {"level": 2, "text": "journey notes"}}]
        for blurb in blurbs:
            block = {"type": "paragraph", "content": {"text": blurb.text}}
            if blurb.location_name:
                block["content"]["_location_name"] = blurb.location_name
            body.append(block)
        adventure.body = body
        adventures_to_update.append(adventure)

    Adventure.objects.bulk_update(adventures_to_update, ['body'])


def reverse_blurbs_to_body(apps, schema_editor):
    Adventure = apps.get_model('tracker', 'Adventure')
    Adventure.objects.update(body=[])


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0017_adventure_body_cover_subtitle_indexes'),
    ]

    operations = [
        migrations.RunPython(blurbs_to_body, reverse_blurbs_to_body),
    ]
