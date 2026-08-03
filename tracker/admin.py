from django.contrib import admin
from .models import (
    APIKey, Device, Location, Adventure,
    UserProfile,
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


@admin.register(UserProfile)
class UserProfileAdmin(admin.ModelAdmin):
    list_display = ('user',)
