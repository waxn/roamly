from django.contrib import admin
from django.urls import path, include, re_path
from django.conf import settings
from django.views.static import serve
from django.contrib.sitemaps.views import sitemap
from tracker.sitemaps import StaticViewSitemap, AdventureSitemap

sitemaps = {
    'static': StaticViewSitemap,
    'adventures': AdventureSitemap,
}

urlpatterns = [
    path('admin/', admin.site.urls),
    path('sitemap.xml', sitemap, {'sitemaps': sitemaps}, name='django.contrib.sitemaps.views.sitemap'),
    path('', include('tracker.urls')),
]

handler400 = 'tracker.views.error_400'
handler403 = 'tracker.views.error_403'
handler404 = 'tracker.views.error_404'
handler500 = 'tracker.views.error_500'

# Serve media files (profile pictures, blurb photos) in all environments
urlpatterns += [
    re_path(r'^media/(?P<path>.*)$', serve, {'document_root': settings.MEDIA_ROOT}),
]
