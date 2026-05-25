package com.roamly.tracker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.roamly.tracker.R
import com.roamly.tracker.databinding.ActivityMainBinding
import com.roamly.tracker.db.AppDatabase
import com.roamly.tracker.service.LocationTrackingService
import com.roamly.tracker.worker.UploadWorker
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var prefs: SharedPreferences
    @Inject lateinit var db: AppDatabase

    private lateinit var binding: ActivityMainBinding
    private var isTracking = false

    // Listener so the UI refreshes immediately when UploadWorker writes results
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in listOf(
                UploadWorker.PREF_LAST_SYNC_TIME,
                UploadWorker.PREF_LAST_SYNC_SUCCESS,
                UploadWorker.PREF_LAST_SYNC_COUNT,
                UploadWorker.PREF_LAST_SYNC_ERROR
            )
        ) {
            refreshSyncStatus()
        }
    }

    // ── Permission launchers ───────────────────────────────────────────────

    private val fineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestBackgroundLocationIfNeeded() else showPermissionRationale()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { requestNotificationPermissionIfNeeded() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startTracking() }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        isTracking = prefs.getBoolean(LocationTrackingService.PREF_TRACKING_ACTIVE, false)
        updateTrackingUI()

        binding.btnToggleTracking.setOnClickListener {
            if (isTracking) stopTracking() else checkPermissionsAndStart()
        }

        binding.btnSyncNow.setOnClickListener {
            UploadWorker.scheduleNow(this)
        }

        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Live DB counts
        db.pointDao().unsyncedCountLive().observe(this) { count ->
            binding.tvUnsyncedCount.text = when {
                count == 0 -> "All points synced ✓"
                count == 1 -> "1 point pending upload"
                else       -> "$count points pending upload"
            }
        }

        db.pointDao().totalCountLive().observe(this) { count ->
            binding.tvTotalCount.text = "$count points in local cache"
        }

        // Observe WorkManager so "Sync Now" shows a spinner while running
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(UploadWorker.ONETIME_WORK_TAG)
            .observe(this) { infos ->
                val running = infos?.any {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                } == true
                binding.syncProgress.visibility = if (running) View.VISIBLE else View.GONE
                binding.btnSyncNow.isEnabled = !running
                binding.btnSyncNow.text = if (running) "Syncing…" else "Sync Now"
                if (!running) refreshSyncStatus()
            }

        // Battery optimisation on first run
        if (!prefs.getBoolean("battery_opt_shown", false)) {
            requestBatteryOptimisationExemption()
        }

        UploadWorker.schedulePeriodicSync(this)
    }

    override fun onResume() {
        super.onResume()
        isTracking = prefs.getBoolean(LocationTrackingService.PREF_TRACKING_ACTIVE, false)
        updateTrackingUI()
        refreshConfigWarning()
        refreshSyncStatus()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    // ── Menu ───────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Status display ─────────────────────────────────────────────────────

    /**
     * Show a banner if the user hasn't configured server URL / API key / device ID yet.
     * Called on every resume so it disappears once they fill in Settings.
     */
    private fun refreshConfigWarning() {
        val url      = prefs.getString(UploadWorker.PREF_SERVER_URL, "") ?: ""
        val key      = prefs.getString(UploadWorker.PREF_API_KEY, "") ?: ""
        val deviceId = prefs.getString(UploadWorker.PREF_DEVICE_ID, "") ?: ""

        val misconfigured = url.isBlank() || key.isBlank() || deviceId.isBlank()
        binding.cardConfigWarning.visibility = if (misconfigured) View.VISIBLE else View.GONE
    }

    /**
     * Read last sync result from prefs (written by [UploadWorker]) and update the status line.
     */
    private fun refreshSyncStatus() {
        val lastTime    = prefs.getLong(UploadWorker.PREF_LAST_SYNC_TIME, 0L)
        val lastSuccess = prefs.getBoolean(UploadWorker.PREF_LAST_SYNC_SUCCESS, false)
        val lastCount   = prefs.getInt(UploadWorker.PREF_LAST_SYNC_COUNT, 0)
        val lastError   = prefs.getString(UploadWorker.PREF_LAST_SYNC_ERROR, "") ?: ""

        if (lastTime == 0L) {
            binding.tvLastSync.text = "Never synced"
            binding.tvLastSync.setTextColor(getColor(R.color.text_muted))
            return
        }

        val timeStr = formatRelativeTime(lastTime)

        if (lastSuccess) {
            val pointsStr = when (lastCount) {
                0    -> "nothing to send"
                1    -> "1 point sent"
                else -> "$lastCount points sent"
            }
            binding.tvLastSync.text = "Last sync: $timeStr ($pointsStr)"
            binding.tvLastSync.setTextColor(getColor(R.color.sync_ok))
        } else {
            val errorStr = if (lastError.isNotBlank()) " — $lastError" else ""
            binding.tvLastSync.text = "Last sync failed: $timeStr$errorStr"
            binding.tvLastSync.setTextColor(getColor(R.color.sync_error))
        }
    }

    private fun formatRelativeTime(epochMs: Long): String {
        val diffMs = System.currentTimeMillis() - epochMs
        return when {
            diffMs < 60_000L          -> "just now"
            diffMs < 3_600_000L       -> "${diffMs / 60_000}m ago"
            diffMs < 86_400_000L      -> "${diffMs / 3_600_000}h ago"
            else                      -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                                            .format(Date(epochMs))
        }
    }

    // ── Tracking controls ──────────────────────────────────────────────────

    private fun checkPermissionsAndStart() {
        when {
            hasFineLocation() -> requestBackgroundLocationIfNeeded()
            else              -> fineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation()) {
            AlertDialog.Builder(this)
                .setTitle("Background Location")
                .setMessage(
                    "Roamly needs 'Allow all the time' location access to track while the screen is off.\n\n" +
                    "On the next screen, select \"Allow all the time\"."
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton("Not now") { _, _ -> startTracking() }
                .show()
        } else {
            requestNotificationPermissionIfNeeded()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startTracking()
        }
    }

    private fun startTracking() {
        LocationTrackingService.start(this)
        isTracking = true
        updateTrackingUI()
    }

    private fun stopTracking() {
        LocationTrackingService.stop(this)
        isTracking = false
        updateTrackingUI()
    }

    private fun updateTrackingUI() {
        if (isTracking) {
            binding.btnToggleTracking.text = "Stop Tracking"
            binding.btnToggleTracking.setIconResource(R.drawable.ic_stop)
            binding.statusIndicator.setImageResource(R.drawable.ic_tracking_active)
            binding.tvTrackingStatus.text = "Tracking active"
        } else {
            binding.btnToggleTracking.text = "Start Tracking"
            binding.btnToggleTracking.setIconResource(R.drawable.ic_play)
            binding.statusIndicator.setImageResource(R.drawable.ic_tracking_inactive)
            binding.tvTrackingStatus.text = "Not tracking"
        }
    }

    // ── Battery optimisation ───────────────────────────────────────────────

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimisationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Improve Tracking Reliability")
                .setMessage(
                    "To track reliably 24/7 (especially overnight), Roamly needs to be " +
                    "excluded from battery optimisation.\n\n" +
                    "Without it, Android may pause tracking for up to an hour while the screen is off."
                )
                .setPositiveButton("Allow") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                }
                .setNegativeButton("Skip", null)
                .show()
        }
        prefs.edit().putBoolean("battery_opt_shown", true).apply()
    }

    // ── Permission helpers ─────────────────────────────────────────────────

    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocation() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("Location Permission Required")
            .setMessage("Roamly needs precise location access to track your route. Please grant it in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
