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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.roamly.tracker.R
import com.roamly.tracker.databinding.ActivityMainBinding
import com.roamly.tracker.db.AppDatabase
import com.roamly.tracker.service.LocationTrackingService
import com.roamly.tracker.worker.UploadWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var prefs: SharedPreferences
    @Inject lateinit var db: AppDatabase

    private lateinit var binding: ActivityMainBinding
    private var isTracking = false

    // ── Permission launchers ───────────────────────────────────────────────

    private val fineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestBackgroundLocationIfNeeded()
        } else {
            showPermissionRationale()
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestNotificationPermissionIfNeeded()
        }
        // Background location denied is acceptable; tracking still works
        // while the app is in the foreground / service is running
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

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
            Toast.makeText(this, "Sync queued…", Toast.LENGTH_SHORT).show()
        }

        // Observe unsynced count
        db.pointDao().unsyncedCountLive().observe(this) { count ->
            binding.tvUnsyncedCount.text = when {
                count == 0 -> "All points synced ✓"
                count == 1 -> "1 point waiting to sync"
                else       -> "$count points waiting to sync"
            }
        }

        db.pointDao().totalCountLive().observe(this) { count ->
            binding.tvTotalCount.text = "$count points stored locally"
        }

        // Request battery optimisation exemption on first run
        if (!prefs.getBoolean("battery_opt_shown", false)) {
            requestBatteryOptimisationExemption()
        }

        // Schedule periodic background sync (idempotent)
        UploadWorker.schedulePeriodicSync(this)
    }

    override fun onResume() {
        super.onResume()
        isTracking = prefs.getBoolean(LocationTrackingService.PREF_TRACKING_ACTIVE, false)
        updateTrackingUI()
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
                    "Roamly needs 'Allow all the time' location access to track your route " +
                    "while the screen is off.\n\nOn the next screen, select \"Allow all the time\"."
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
                    "To keep tracking running reliably 24/7 (especially overnight), " +
                    "Roamly needs to be excluded from battery optimisation.\n\n" +
                    "This is recommended — without it, Android may pause tracking " +
                    "for up to an hour in deep sleep mode."
                )
                .setPositiveButton("Allow") { _, _ ->
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Skip") { _, _ -> }
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
            .setMessage(
                "Roamly needs precise location access to track your route. " +
                "Please grant the permission in Settings."
            )
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
