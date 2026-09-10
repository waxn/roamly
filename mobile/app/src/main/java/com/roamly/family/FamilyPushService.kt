package com.roamly.family

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.roamly.MainActivity
import com.roamly.R
import com.roamly.RoamlyApp
import com.roamly.data.prefs.UserPreferences
import com.roamly.data.repository.AuthRepository
import com.roamly.data.repository.FamilyRepository
import com.roamly.data.repository.Result
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "FamilyPushService"

/** Family Circle place-alert push. Harmless when the app has no
 *  google-services.json baked in: FirebaseApp never auto-initializes without
 *  it, so this service is simply never invoked (no crash, no registration) —
 *  see app/build.gradle.kts. */
@AndroidEntryPoint
class FamilyPushService : FirebaseMessagingService() {

    @Inject lateinit var familyRepository: FamilyRepository
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var prefs: UserPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A fresh token — first install, or a periodic FCM-driven rotation.
     *  Registration needs a session, so this is a best-effort no-op before
     *  the user has ever logged in; the next rotation (or a future login)
     *  covers it, matching how AuthRepository.ensureApiKey() is already
     *  used elsewhere on this exact "may not be logged in yet" premise. */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        scope.launch {
            if (prefs.apiKey.first().isNullOrBlank() && authRepository.ensureApiKey() !is Result.Success) {
                Log.d(TAG, "No session yet — skipping push token registration")
                return@launch
            }
            val label = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
            when (val r = familyRepository.registerPushToken(token, label)) {
                is Result.Success -> Log.d(TAG, "Registered family push token")
                is Result.Error -> Log.w(TAG, "Failed to register family push token: ${r.message}")
            }
        }
    }

    /** Data-only messages only (see tracker/push_tasks.py) — this always runs,
     *  regardless of foreground/background/killed state, so notification
     *  building stays consistent instead of the OS tray sometimes
     *  intercepting a mixed notification+data payload directly. */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.data["title"] ?: "Family Circle"
        val body = message.data["body"] ?: return

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, RoamlyApp.CHANNEL_FAMILY_ALERTS)
            .setSmallIcon(R.drawable.ic_roamly_mark)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        runCatching {
            NotificationManagerCompat.from(this)
                .notify(System.currentTimeMillis().toInt(), notification)
        }.onFailure { Log.w(TAG, "Could not post family alert notification", it) }
    }
}
