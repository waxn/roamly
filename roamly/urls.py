from django.contrib import admin
from django.urls import path, include, re_path
from tracker.views import serve_media
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

# Serve media files (profile pictures, blurb photos) in all environments.
# Goes through tracker.views.serve_media rather than django.views.static.serve
# directly: that view picks Content-Type from the filename extension, so a file
# stored under an attacker-chosen name is rendered as whatever it claims to be,
# on this origin. serve_media forces anything outside a small inline allowlist
# to application/octet-stream + attachment, and always sends nosniff.
urlpatterns += [
    re_path(r'^media/(?P<path>.*)$', serve_media),
]
