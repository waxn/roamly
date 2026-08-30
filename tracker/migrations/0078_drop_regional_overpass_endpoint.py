from django.db import migrations

# The endpoint being removed, matched on host so a trailing-slash or path
# variant of the same mirror is caught too.
_REGIONAL_HOSTS = ('overpass.osm.ch',)


def _strip_regional_endpoints(apps, schema_editor):
    """Drop known regional extracts from an admin-saved Overpass endpoint list.

    `overpass.osm.ch` is a Switzerland-only instance. It does not fail for the
    rest of the world — it answers HTTP 200 with zero elements, which a download
    cannot tell apart from "this cell genuinely has no subway/road here". The run
    then stores nothing, reports no failures, and marks every cell it asked about
    as covered in DownloadedRegion, so re-running never re-asks.

    Removing it from `overpass.DEFAULT_POOL` is not enough on its own: the admin
    panel renders the *effective* pool and any save writes the full explicit list
    into SiteConfig.overpass_urls, which then wins outright over the defaults. So
    an instance whose admin ever pressed Save is still pinned to it.

    Same shape as 0072's reset of profiles that had explicitly picked Valhalla:
    a stored choice that is no longer a valid one has to be cleared, not just
    removed from the menu. A list that ends up empty is left empty, which means
    "use the built-in pool".
    """
    SiteConfig = apps.get_model('tracker', 'SiteConfig')
    for cfg in SiteConfig.objects.exclude(overpass_urls='').iterator():
        lines = [ln for ln in (cfg.overpass_urls or '').splitlines()]
        kept = [ln for ln in lines
                if not any(h in ln.lower() for h in _REGIONAL_HOSTS)]
        if len(kept) != len(lines):
            cfg.overpass_urls = '\n'.join(kept).strip()
            cfg.save(update_fields=['overpass_urls'])


class Migration(migrations.Migration):
    """Remove the Switzerland-only Overpass mirror from saved endpoint lists.

    See tracker/overpass.py's DEFAULT_POOL comment for why a regional extract in
    the pool is worse than a dead one.
    """

    dependencies = [
        ('tracker', '0077_download_job_last_error'),
    ]

    operations = [
        migrations.RunPython(_strip_regional_endpoints, migrations.RunPython.noop),
    ]
