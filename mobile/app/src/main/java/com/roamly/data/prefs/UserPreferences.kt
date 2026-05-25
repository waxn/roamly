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

        // Tracking state
        private val KEY_TRACKING_ACTIVE        = booleanPreferencesKey("tracking_active")
        private val KEY_TRACKING_INTERVAL_SECS = intPreferencesKey("tracking_interval_secs")
        private val KEY_MAX_ACCURACY_M         = intPreferencesKey("max_accuracy_m")

        // Last sync result (written by UploadWorker after every run)
        private val KEY_LAST_SYNC_TIME    = longPreferencesKey("last_sync_time")
        private val KEY_LAST_SYNC_SUCCESS = booleanPreferencesKey("last_sync_success")
        private val KEY_LAST_SYNC_COUNT   = intPreferencesKey("last_sync_count")
        private val KEY_LAST_SYNC_ERROR   = stringPreferencesKey("last_sync_error")
    }

    // ── Auth / connection ──────────────────────────────────────────────────

    val serverUrl:  Flow<String?>  = context.dataStore.data.map { it[KEY_SERVER_URL] }
    val sessionId:  Flow<String?>  = context.dataStore.data.map { it[KEY_SESSION_ID] }
    val apiKey:     Flow<String?>  = context.dataStore.data.map { it[KEY_API_KEY] }
    val username:   Flow<String?>  = context.dataStore.data.map { it[KEY_USERNAME] }
    val deviceId:   Flow<String?>  = context.dataStore.data.map { it[KEY_DEVICE_ID] }
    val darkMode:   Flow<Boolean?> = context.dataStore.data.map { it[KEY_DARK_MODE] }

    // ── Tracking ───────────────────────────────────────────────────────────

    val isTrackingActive:      Flow<Boolean> = context.dataStore.data.map { it[KEY_TRACKING_ACTIVE] ?: false }
    val trackingIntervalSecs:  Flow<Int>     = context.dataStore.data.map { it[KEY_TRACKING_INTERVAL_SECS] ?: 30 }
    val maxAccuracyM:          Flow<Int>     = context.dataStore.data.map { it[KEY_MAX_ACCURACY_M] ?: 100 }

    // ── Last sync result ───────────────────────────────────────────────────

    val lastSyncTime:    Flow<Long>    = context.dataStore.data.map { it[KEY_LAST_SYNC_TIME] ?: 0L }
    val lastSyncSuccess: Flow<Boolean> = context.dataStore.data.map { it[KEY_LAST_SYNC_SUCCESS] ?: false }
    val lastSyncCount:   Flow<Int>     = context.dataStore.data.map { it[KEY_LAST_SYNC_COUNT] ?: 0 }
    val lastSyncError:   Flow<String>  = context.dataStore.data.map { it[KEY_LAST_SYNC_ERROR] ?: "" }

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

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DARK_MODE] = enabled }
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { it[KEY_API_KEY] = key }
    }

    suspend fun setTrackingActive(active: Boolean) {
        context.dataStore.edit { it[KEY_TRACKING_ACTIVE] = active }
    }

    suspend fun setTrackingIntervalSecs(secs: Int) {
        context.dataStore.edit { it[KEY_TRACKING_INTERVAL_SECS] = secs }
    }

    suspend fun setMaxAccuracyM(metres: Int) {
        context.dataStore.edit { it[KEY_MAX_ACCURACY_M] = metres }
    }

    suspend fun setSyncResult(time: Long, success: Boolean, count: Int, error: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_SYNC_TIME]    = time
            prefs[KEY_LAST_SYNC_SUCCESS] = success
            prefs[KEY_LAST_SYNC_COUNT]   = count
            prefs[KEY_LAST_SYNC_ERROR]   = error
        }
    }

    suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_SERVER_URL)
            prefs.remove(KEY_SESSION_ID)
            prefs.remove(KEY_USERNAME)
            // Keep API key, device ID, dark mode, and tracking prefs across logout
            // so re-login reuses the same key and device name
        }
    }
}
