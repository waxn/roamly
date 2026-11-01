from django.urls import set_script_prefix

from .state import set_beta

PREFIX = '/beta'


class BetaUIMiddleware:
    """Serve ``/beta/<anything>`` as the same page as ``/<anything>``, rendered
    from the parallel ``tracker/beta/`` templates.

    The prefix is stripped from ``path_info`` so the ordinary URLconf resolves
    it, and the script prefix is set so ``{% url %}`` / ``redirect()`` keep
    emitting ``/beta/...`` links. Views are untouched: the template loader
    (``tracker.beta.loader``) swaps in the beta template when the flag set here
    is on. Literal ``/api/...`` calls from page JS stay unprefixed.
    """

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        path = request.path_info
        if path != PREFIX and not path.startswith(PREFIX + '/'):
            request.beta = False
            return self.get_response(request)
        request.beta = True
        request.path_info = path[len(PREFIX):] or '/'
        set_script_prefix(PREFIX + '/')
        set_beta(True)
        try:
            response = self.get_response(request)
        finally:
            set_beta(False)
        response['X-Robots-Tag'] = 'noindex, nofollow'
        return response
