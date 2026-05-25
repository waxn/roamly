package com.roamly.tracker.ui

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.roamly.tracker.R
import com.roamly.tracker.db.AppDatabase
import com.roamly.tracker.service.LocationTrackingService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * All user-configurable settings, backed by [SharedPreferences] via AndroidX Preference.
 *
 * Keys must match the constants in [LocationTrackingService] and [UploadWorker].
 */
@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {

    @Inject lateinit var db: AppDatabase

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // "Clear all cached data" action
        findPreference<Preference>("clear_cache")?.setOnPreferenceClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Clear cached points?")
                .setMessage("This will delete all locally stored points (synced and unsynced). Points already on the server are not affected.")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        db.pointDao().deleteAll()
                        Toast.makeText(requireContext(), "Cache cleared", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }
}
