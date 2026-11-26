package com.roamly.family

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.roamly.data.prefs.UserPreferences
import com.roamly.data.repository.AuthRepository
import com.roamly.data.repository.FamilyRepository
import com.roamly.data.repository.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "PushRegistrar"

/**
 * Registers the phone's *current* FCM token with the server once signed in.
 *
 * [FamilyPushService.onNewToken] only fires when Firebase issues or rotates a
 * token, which for a fresh install is *before* the user has logged in, so that
 * registration is skipped for lack of a session and never retried: Family alerts
 * then silently never arrive. Calling this on every sign-in and app start makes
 * the registration self-healing. The server upserts by token, so repeating it
 * is harmless. A build without `google-services.json` has no FirebaseApp and
 * this does nothing.
 */
@Singleton
class PushRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val familyRepository: FamilyRepository,
    private val authRepository: AuthRepository,
    private val prefs: UserPreferences,
) {
    private var lastRegistered: String? = null

    suspend fun registerCurrent() {
        if (FirebaseApp.getApps(context).isEmpty()) return
        val token = currentToken() ?: return
        if (token == lastRegistered) return
        if (prefs.apiKey.first().isNullOrBlank() && authRepository.ensureApiKey() !is Result.Success) return
        val label = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        when (val r = familyRepository.registerPushToken(token, label)) {
            is Result.Success -> lastRegistered = token
            is Result.Error -> Log.w(TAG, "Push token registration failed: ${r.message}")
        }
    }

    private suspend fun currentToken(): String? = suspendCancellableCoroutine { cont ->
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                if (cont.isActive) cont.resume(if (t.isSuccessful) t.result else null)
            }
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }
}
