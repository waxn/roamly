"""Data for the Hearth layout's card home (/home/). Placeholder."""
from django.contrib.auth.decorators import login_required
from django.http import JsonResponse
from django.views.decorators.http import require_GET


@login_required
@require_GET
def home_api(request):
    return JsonResponse({})
