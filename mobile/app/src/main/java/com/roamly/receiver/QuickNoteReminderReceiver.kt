package com.roamly.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.roamly.data.prefs.UserPreferences
import com.roamly.data.repository.JournalRepository
import com.roamly.data.repository.Result
import com.roamly.notes.QuickNoteReminder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import javax.inject.Inject

/** Fires once each evening: re-arms tomorrow, then posts the reminder unless
 *  today already has a note. A failed check posts it anyway: a reminder you
 *  did not need is cheaper than a day you forgot. */
@AndroidEntryPoint
class QuickNoteReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var prefs: UserPreferences
    @Inject lateinit var journals: JournalRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != QuickNoteReminder.ACTION_FIRE) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                QuickNoteReminder.scheduleFromPrefs(appContext, prefs)
                if (!QuickNoteReminder.isWanted(prefs)) return@launch
                val today = LocalDate.now().toString()
                val written = withTimeoutOrNull(8_000) {
                    (journals.quickNote(today) as? Result.Success)?.data?.note?.isNotBlank()
                } ?: false
                if (!written) QuickNoteReminder.show(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
