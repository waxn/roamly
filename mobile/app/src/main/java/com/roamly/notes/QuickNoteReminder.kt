package com.roamly.notes

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.roamly.MainActivity
import com.roamly.R
import com.roamly.data.prefs.UserPreferences
import com.roamly.receiver.QuickNoteReminderReceiver
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime

private const val TAG = "QuickNoteReminder"

/**
 * The evening "what did you do today?" nudge.
 *
 * One inexact alarm a day (setAndAllowWhileIdle: no exact-alarm permission,
 * still fires in Doze), re-armed for tomorrow every time it fires, on app start
 * and on boot. When it fires, [QuickNoteReminderReceiver] posts the
 * notification only if today has no note yet, so writing one earlier in the day
 * means you are never nagged. Tapping it opens the app straight into the note
 * sheet on the map (see [QuickNoteLaunch]).
 */
object QuickNoteReminder {
    const val CHANNEL = "roamly_daily_note"
    const val ACTION_FIRE = "com.roamly.DAILY_NOTE_REMINDER"
    const val EXTRA_OPEN_QUICK_NOTE = "open_quick_note"
    private const val ALARM_REQUEST = 4711
    private const val OPEN_REQUEST = 4712
    private const val NOTIFICATION_ID = 4713

    /** 7:30 pm: late enough that the day has happened, early enough to be awake for it. */
    const val DEFAULT_MINUTE_OF_DAY = 19 * 60 + 30

    /** Arm the next reminder, or cancel it when [enabled] is false. */
    fun schedule(context: Context, enabled: Boolean, minuteOfDay: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(context)
        am.cancel(pi)
        if (!enabled) return
        val at = nextTrigger(ZonedDateTime.now(), minuteOfDay)
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pi)
        }.onFailure { Log.w(TAG, "Could not arm the daily note reminder", it) }
    }

    /** [schedule] from the saved setting. Signed out means nothing to remind
     *  about, and Simple Mode is a phone that is tracked rather than kept, so
     *  neither gets one. */
    suspend fun scheduleFromPrefs(context: Context, prefs: UserPreferences) {
        schedule(context, isWanted(prefs), prefs.noteReminderMinute.first())
    }

    suspend fun isWanted(prefs: UserPreferences): Boolean =
        !prefs.sessionId.first().isNullOrBlank() &&
            prefs.noteReminderEnabled.first() &&
            !prefs.simpleModeEnabled.first()

    /** The next [minuteOfDay] strictly after [now], in [now]'s zone (DST-safe via atZone). */
    internal fun nextTrigger(now: ZonedDateTime, minuteOfDay: Int): ZonedDateTime {
        val m = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        val today = now.toLocalDate().atTime(m / 60, m % 60).atZone(now.zone)
        return if (today.isAfter(now)) today
        else now.toLocalDate().plusDays(1).atTime(m / 60, m % 60).atZone(now.zone)
    }

    fun createChannel(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Daily note reminder", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "An evening nudge to jot down what you did today"
            }
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun show(context: Context) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_roamly_mark)
            .setContentTitle("What did you do today?")
            .setContentText("Jot it down in a line before it blurs into tomorrow.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Could not post the daily note reminder", it) }
    }

    /** Clear a posted reminder, e.g. once the note has been written. */
    fun dismiss(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, ALARM_REQUEST,
        Intent(context, QuickNoteReminderReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, OPEN_REQUEST,
        Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_QUICK_NOTE, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
