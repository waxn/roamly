from django.template.loaders import app_directories, cached

from .state import is_beta

_PREFIX = 'tracker/'
_BETA = 'tracker/beta/'


class BetaAppDirsLoader(app_directories.Loader):
    """App-directory loader that, during a /beta/ request, looks for a parallel
    ``tracker/beta/<name>`` first and falls back to the classic template.

    That fallback is what makes the beta incremental: a page nobody has forked
    yet still renders (extending the beta base, since ``{% extends
    'tracker/base.html' %}`` is remapped like any other name)."""

    def get_template_sources(self, template_name):
        if is_beta() and template_name.startswith(_PREFIX) and not template_name.startswith(_BETA):
            yield from super().get_template_sources(_BETA + template_name[len(_PREFIX):])
        yield from super().get_template_sources(template_name)


class BetaCachedLoader(cached.Loader):
    """The stock cached loader keys on the template name alone, so a name that
    resolves differently under /beta/ must carry that in its cache key."""

    def cache_key(self, template_name, skip=None):
        key = super().cache_key(template_name, skip)
        return key + '|beta' if is_beta() else key
