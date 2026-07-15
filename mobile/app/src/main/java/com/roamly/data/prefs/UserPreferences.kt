package com.roamly.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "roamly_prefs")

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Auth / connection
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_SESSION_ID = stringPreferencesKey("session_id")
        private val KEY_API_KEY    = stringPreferencesKey("api_key")
        private val KEY_USERNAME   = stringPreferencesKey("username")
        private val KEY_DEVICE_ID  = stringPreferencesKey("device_id")
        private val KEY_DARK_MODE  = booleanPreferencesKey("dark_mode")
        // Optional Mapbox token, mirrored from the server so the map can use the
        // same Mapbox basemap the user configured on the web. Cached here so the
        // map still gets it offline / before the refresh completes.
        private val KEY_MAPBOX_TOKEN = stringPreferencesKey("mapbox_token")
        // Selected map basemap (label shared with the web map, e.g. "Streets",
        // "Satellite", "Dark", "Mapbox Streets"). Per-device UI choice.
        private val KEY_MAP_BASEMAP = stringPreferencesKey("map_basemap")

        // Tracking state
        private val KEY_TRACKING_ACTIVE        = booleanPreferencesKey("tracking_active")
        private val KEY_TRACKING_ENABLED       = booleanPreferencesKey("tracking_enabled")
        private val KEY_TRACKING_INTERVAL_SECS = intPreferencesKey("tracking_interval_secs")
        private val KEY_MAX_ACCURACY_M         = intPreferencesKey("max_accuracy_m")
        private val KEY_MIN_DISTANCE_M         = intPreferencesKey("min_distance_m")
        private val KEY_LOCATION_PRIORITY      = stringPreferencesKey("location_priority")
        private val KEY_AUTO_START_TRACKING    = booleanPreferencesKey("auto_start_tracking")
        private val KEY_SYNC_ON_MOBILE_DATA    = booleanPreferencesKey("sync_on_mobile_data")
        private val KEY_SUPPRESS_DRIFT         = booleanPreferencesKey("suppress_stationary_drift")

        // Last sync result (written by UploadWorker after every run)
        private val KEY_LAST_SYNC_TIME    = longPreferencesKey("last_sync_time")
        private val KEY_LAST_SYNC_SUCCESS = booleanPreferencesKey("last_sync_success")
        private val KEY_LAST_SYNC_COUNT   = intPreferencesKey("last_sync_count")
        private val KEY_LAST_SYNC_ERROR   = stringPreferencesKey("last_sync_error")

        // In-app update: epoch millis of the last on-launch update check (throttle).
        private val KEY_LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
    }

    // ── Auth / connection ──────────────────────────────────────────────────

    val serverUrl:  Flow<String?>  = context.dataStore.data.map { it[KEY_SERVER_URL] }
    val sessionId:  Flow<String?>  = context.dataStore.data.map { it[KEY_SESSION_ID] }
    val apiKey:     Flow<String?>  = context.dataStore.data.map { it[KEY_API_KEY] }
    val username:   Flow<String?>  = context.dataStore.data.map { it[KEY_USERNAME] }
    val deviceId:   Flow<String?>  = context.dataStore.data.map { it[KEY_DEVICE_ID] }
    val darkMode:   Flow<Boolean?> = context.dataStore.data.map { it[KEY_DARK_MODE] }
    val mapboxToken: Flow<String>  = context.dataStore.data.map { it[KEY_MAPBOX_TOKEN] ?: "" }
    val mapBasemap:  Flow<String>  = context.dataStore.data.map { it[KEY_MAP_BASEMAP] ?: "Streets" }

    // ── Tracking ───────────────────────────────────────────────────────────

    /** Runtime status — is the foreground service currently running. */
    val isTrackingActive:      Flow<Boolean> = context.dataStore.data.map { it[KEY_TRACKING_ACTIVE] ?: false }
    /** User intent — tracking should be on (survives reboots and OS kills). */
    val trackingEnabled:       Flow<Boolean> = context.dataStore.data.map { it[KEY_TRACKING_ENABLED] ?: false }
    val trackingIntervalSecs:  Flow<Int>     = context.dataStore.data.map { it[KEY_TRACKING_INTERVAL_SECS] ?: 30 }
    val maxAccuracyM:          Flow<Int>     = context.dataStore.data.map { it[KEY_MAX_ACCURACY_M] ?: 100 }
    /** Minimum metres of movement before a new point is recorded (0 = record every fix). */
    val minDistanceM:          Flow<Int>     = context.dataStore.data.map { it[KEY_MIN_DISTANCE_M] ?: 10 }
    /** "auto" | "high" | "balanced" | "low" — GPS power/accuracy tradeoff. */
    val locationPriority:      Flow<String>  = context.dataStore.data.map { it[KEY_LOCATION_PRIORITY] ?: "auto" }
    val autoStartTracking:     Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_START_TRACKING] ?: true }
    val syncOnMobileData:      Flow<Boolean> = context.dataStore.data.map { it[KEY_SYNC_ON_MOBILE_DATA] ?: true }
    /** Snap wandering GPS fixes to a stable anchor while parked (suppresses stationary drift). */
    val suppressStationaryDrift: Flow<Boolean> = context.dataStore.data.map { it[KEY_SUPPRESS_DRIFT] ?: true }

    // ── Last sync result ───────────────────────────────────────────────────

    val lastSyncTime:    Flow<Long>    = context.dataStore.data.map { it[KEY_LAST_SYNC_TIME] ?: 0L }
    val lastSyncSuccess: Flow<Boolean> = context.dataStore.data.map { it[KEY_LAST_SYNC_SUCCESS] ?: false }
    val lastSyncCount:   Flow<Int>     = context.dataStore.data.map { it[KEY_LAST_SYNC_COUNT] ?: 0 }
    val lastSyncError:   Flow<String>  = context.dataStore.data.map { it[KEY_LAST_SYNC_ERROR] ?: "" }

    // ── In-app update ──────────────────────────────────────────────────────

    /** Epoch millis of the last on-launch update check (used to throttle to ~24h). */
    val lastUpdateCheck: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_UPDATE_CHECK] ?: 0L }

    // ── Writers ────────────────────────────────────────────────────────────

    suspend fun save(serverUrl: String, sessionId: String, apiKey: String, username: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = serverUrl
            prefs[KEY_SESSION_ID] = sessionId
            prefs[KEY_API_KEY]    = apiKey
            prefs[KEY_USERNAME]   = username
        }
    }

    suspend fun setDeviceId(deviceId: String) {
        context.dataStore.edit { it[KEY_DEVICE_ID] = deviceId }
    }

    /** Set a device id only if one isn't already stored. Used to make the
     *  tracker work out of the box right after login (uploads need a device id). */
    suspend fun setDeviceIdIfUnset(deviceId: String) {
        context.dataStore.edit { prefs ->
            if (prefs[KEY_DEVICE_ID].isNullOrBlank()) prefs[KEY_DEVICE_ID] = deviceId
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DARK_MODE] = enabled }
    }

    suspend fun setMapboxToken(token: String) {
        context.dataStore.edit { it[KEY_MAPBOX_TOKEN] = token }
    }

    suspend fun setMapBasemap(name: String) {
        context.dataStore.edit { it[KEY_MAP_BASEMAP] = name }
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { it[KEY_API_KEY] = key }
    }

    suspend fun setTrackingActive(active: Boolean) {
        context.dataStore.edit { it[KEY_TRACKING_ACTIVE] = active }
    }

    suspend fun setTrackingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_TRACKING_ENABLED] = enabled }
    }

    suspend fun setTrackingIntervalSecs(secs: Int) {
        context.dataStore.edit { it[KEY_TRACKING_INTERVAL_SECS] = secs }
    }

    suspend fun setMaxAccuracyM(metres: Int) {
        context.dataStore.edit { it[KEY_MAX_ACCURACY_M] = metres }
    }

    suspend fun setMinDistanceM(metres: Int) {
        context.dataStore.edit { it[KEY_MIN_DISTANCE_M] = metres }
    }

    suspend fun setLocationPriority(priority: String) {
        context.dataStore.edit { it[KEY_LOCATION_PRIORITY] = priority }
    }

    suspend fun setAutoStartTracking(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_START_TRACKING] = enabled }
    }

    suspend fun setSyncOnMobileData(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SYNC_ON_MOBILE_DATA] = enabled }
    }

    suspend fun setSuppressStationaryDrift(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SUPPRESS_DRIFT] = enabled }
    }

    suspend fun setLastUpdateCheck(time: Long) {
        context.dataStore.edit { it[KEY_LAST_UPDATE_CHECK] = time }
    }

    suspend fun setSyncResult(time: Long, success: Boolean, count: Int, error: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_SYNC_TIME]    = time
            prefs[KEY_LAST_SYNC_SUCCESS] = success
            prefs[KEY_LAST_SYNC_COUNT]   = count
            prefs[KEY_LAST_SYNC_ERROR]   = error
        }
    }

    /**
     * Full sign-out. Removes the credentials that gate "logged in" — including the
     * API key, which is now the durable credential (the session cookie is optional
     * because the server authenticates the Bearer key on every endpoint). Tracking
     * preferences, device id and dark mode are kept so a re-login feels seamless.
     */
    suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_SERVER_URL)
            prefs.remove(KEY_SESSION_ID)
            prefs.remove(KEY_API_KEY)
            prefs.remove(KEY_USERNAME)
            prefs.remove(KEY_MAPBOX_TOKEN)
        }
    }
}
