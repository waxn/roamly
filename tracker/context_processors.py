from django.conf import settings


def custom_js_snippet(request):
    return {'CUSTOM_JS_SNIPPET': getattr(settings, 'CUSTOM_JS_SNIPPET', '')}
