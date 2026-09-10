from django.contrib.sitemaps import Sitemap
from django.urls import reverse
from .models import Adventure


class StaticViewSitemap(Sitemap):
    priority = 0.8
    changefreq = 'monthly'

    def items(self):
        return ['tracker:landing', 'tracker:docs', 'tracker:terms', 'tracker:privacy']

    def location(self, item):
        return reverse(item)


class AdventureSitemap(Sitemap):
    changefreq = 'weekly'
    priority = 0.6

    def items(self):
        # PIN-protected adventures are deliberately not for the open internet,
        # so their URLs stay out of the sitemap.
        return (Adventure.objects
                .filter(public_slug__isnull=False)
                .exclude(public_slug='')
                .exclude(access_pin__gt=''))

    def location(self, obj):
        return reverse('tracker:adventure_public', kwargs={'slug': obj.public_slug})

    def lastmod(self, obj):
        return obj.end_time
