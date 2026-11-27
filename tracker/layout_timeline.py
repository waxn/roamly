"""Data for the Chronicle layout's day feed (/timeline/). Placeholder."""
from django.contrib.auth.decorators import login_required
from django.http import JsonResponse
from django.views.decorators.http import require_GET


@login_required
@require_GET
def timeline_api(request):
    return JsonResponse({'days': [], 'next_before': None})
