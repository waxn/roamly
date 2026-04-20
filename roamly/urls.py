from django.contrib import admin
from django.urls import path, include, re_path
from django.conf import settings
from django.views.static import serve
from django.contrib.sitemaps.views import sitemap
from tracker.sitemaps import StaticViewSitemap, TripSitemap, PalSitemap

sitemaps = {
    'static': StaticViewSitemap,
    'trips': TripSitemap,
    'pals': PalSitemap,
}

urlpatterns = [
    path('admin/', admin.site.urls),
    path('sitemap.xml', sitemap, {'sitemaps': sitemaps}, name='django.contrib.sitemaps.views.sitemap'),
    path('', include('tracker.urls')),
]

# Serve media files (profile pictures, blurb photos) in all environments
urlpatterns += [
    re_path(r'^media/(?P<path>.*)$', serve, {'document_root': settings.MEDIA_ROOT}),
]
