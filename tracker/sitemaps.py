from django.contrib.sitemaps import Sitemap
from django.urls import reverse
from .models import Adventure, Pal


class StaticViewSitemap(Sitemap):
    priority = 0.8
    changefreq = 'monthly'

    def items(self):
        return ['tracker:landing', 'tracker:docs', 'tracker:terms', 'tracker:privacy']

    def location(self, item):
        return reverse(item)


class TripSitemap(Sitemap):
    changefreq = 'weekly'
    priority = 0.6

    def items(self):
        return Adventure.objects.filter(public_slug__isnull=False).exclude(public_slug='')

    def location(self, obj):
        return reverse('tracker:adventure_public', kwargs={'slug': obj.public_slug})

    def lastmod(self, obj):
        return obj.end_time


class PalSitemap(Sitemap):
    changefreq = 'weekly'
    priority = 0.6

    def items(self):
        return Pal.objects.filter(public_slug__isnull=False).exclude(public_slug='')

    def location(self, obj):
        return reverse('tracker:pal_public', kwargs={'slug': obj.public_slug})
