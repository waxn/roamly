from django.contrib import admin
from .models import (
    APIKey, Device, Location, Adventure, AdventurePlace,
    UserProfile, Pal, PalMember, PalBlurb, PalBlurbPhoto, PalMilestone, PalComment,
)


@admin.register(APIKey)
class APIKeyAdmin(admin.ModelAdmin):
    list_display = ('name', 'user', 'created_at', 'last_used', 'is_active')
    list_filter = ('is_active',)


@admin.register(Device)
class DeviceAdmin(admin.ModelAdmin):
    list_display = ('name', 'device_id', 'user', 'created_at')
    list_filter = ('user',)


@admin.register(Location)
class LocationAdmin(admin.ModelAdmin):
    list_display = ('device', 'latitude', 'longitude', 'city', 'country', 'timestamp')
    list_filter = ('device', 'country')
    date_hierarchy = 'timestamp'


@admin.register(Adventure)
class AdventureAdmin(admin.ModelAdmin):
    list_display = ('name', 'device', 'start_time', 'end_time')


@admin.register(AdventurePlace)
class AdventurePlaceAdmin(admin.ModelAdmin):
    list_display = ('name', 'adventure', 'latitude', 'longitude')


@admin.register(UserProfile)
class UserProfileAdmin(admin.ModelAdmin):
    list_display = ('user',)


@admin.register(Pal)
class PalAdmin(admin.ModelAdmin):
    list_display = ('name', 'creator', 'start_date', 'end_date', 'public_slug')


@admin.register(PalMember)
class PalMemberAdmin(admin.ModelAdmin):
    list_display = ('pal', 'user', 'role', 'joined_at')


@admin.register(PalBlurb)
class PalBlurbAdmin(admin.ModelAdmin):
    list_display = ('pal', 'author', 'location_name', 'created_at')


@admin.register(PalBlurbPhoto)
class PalBlurbPhotoAdmin(admin.ModelAdmin):
    list_display = ('blurb', 'order', 'created_at')


@admin.register(PalMilestone)
class PalMilestoneAdmin(admin.ModelAdmin):
    list_display = ('pal', 'title', 'date', 'author')


@admin.register(PalComment)
class PalCommentAdmin(admin.ModelAdmin):
    list_display = ('blurb', 'author', 'created_at')
