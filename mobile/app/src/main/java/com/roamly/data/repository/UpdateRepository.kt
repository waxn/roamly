package com.roamly.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.roamly.data.api.RoamlyApi
import com.roamly.data.api.VersionInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of an update check. */
sealed class UpdateCheck {
    /** A newer build is available on the server. */
    data class Available(val info: VersionInfo, val currentVersion: String) : UpdateCheck()
    /** Already on the latest (or newer) build. */
    object UpToDate : UpdateCheck()
    /** Check failed (offline, server/GitHub error). Handled quietly. */
    object Failed : UpdateCheck()
}

/**
 * In-app updater. The Android app is sideloaded (no Play Store); the server
 * proxies the latest signed APK from its GitHub release. This checks the
 * server's [RoamlyApi.getLatestVersion], downloads the APK via
 * [RoamlyApi.downloadApk], and hands it to the system package installer.
 *
 * Android can't silently install for a non-system app: the OS always shows its
 * install-confirmation screen and first requires "install unknown apps" for us.
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val api: RoamlyApi,
    @ApplicationContext private val context: Context,
) {

    /** Installed app versionName, e.g. "1.11.0". */
    fun currentVersionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /** Ask the server for the latest build and compare against what's installed. */
    suspend fun checkForUpdate(): UpdateCheck = try {
        val resp = api.getLatestVersion()
        val body = resp.body()
        if (!resp.isSuccessful || body == null || body.versionName.isBlank()) {
            UpdateCheck.Failed
        } else {
            val current = currentVersionName()
            if (isNewer(body.versionName, current)) {
                UpdateCheck.Available(body, current)
            } else {
                UpdateCheck.UpToDate
            }
        }
    } catch (e: Exception) {
        UpdateCheck.Failed
    }

    /**
     * Download the APK into cacheDir/updates/, reporting 0..100 progress.
     * Returns the downloaded file, or null on failure.
     */
    suspend fun downloadApk(info: VersionInfo, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val resp = api.downloadApk()
            val body = resp.body()
            if (!resp.isSuccessful || body == null) return@withContext null

            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Clear stale downloads so we never install an old cached APK.
            dir.listFiles()?.forEach { it.delete() }
            val outFile = File(dir, "Roamly${info.versionName}.apk")

            val total = if (info.size > 0) info.size else body.contentLength()
            body.byteStream().use { input ->
                outFile.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            onProgress(100)
            outFile
        } catch (e: Exception) {
            null
        }
    }

    /**
     * True if the app may install packages. On Android O+ the user must have
     * granted "install unknown apps"; below O it's implied by the permission.
     */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Send the user to the system "install unknown apps" screen for this app. */
    fun openUnknownSourcesSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Launch the system package installer for a downloaded APK. */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Semver-ish compare: is [remote] a higher version than [local]? */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = parseVersion(remote)
        val l = parseVersion(local)
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun parseVersion(v: String): List<Int> =
        v.trim().removePrefix("v").split('.', '-')
            .mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
}
