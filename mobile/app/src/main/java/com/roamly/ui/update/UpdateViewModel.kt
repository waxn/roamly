package com.roamly.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.VersionInfo
import com.roamly.data.prefs.UserPreferences
import com.roamly.data.repository.UpdateCheck
import com.roamly.data.repository.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UpdateUiState(
    val currentVersion: String = "",
    val checking: Boolean = false,
    /** Non-null when a newer build than the installed one is available. */
    val available: VersionInfo? = null,
    /** True after a manual check that found nothing newer (transient UI note). */
    val upToDate: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val error: String? = null,
    /** User closed the "update available" banner for this session. */
    val bannerDismissed: Boolean = false,
    /** Set when install was blocked pending the "install unknown apps" grant. */
    val needsInstallPermission: Boolean = false,
)

private const val CHECK_THROTTLE_MS = 24L * 60 * 60 * 1000  // ~once/day

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repo: UpdateRepository,
    private val prefs: UserPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState(currentVersion = repo.currentVersionName()))
    val state: StateFlow<UpdateUiState> = _state

    /** On-launch check: throttled to ~24h, silent, and a no-op with no server URL. */
    fun checkOnLaunch() {
        viewModelScope.launch {
            if (prefs.serverUrl.first().isNullOrBlank()) return@launch
            val now = System.currentTimeMillis()
            if (now - prefs.lastUpdateCheck.first() < CHECK_THROTTLE_MS) return@launch
            prefs.setLastUpdateCheck(now)
            val r = repo.checkForUpdate()
            if (r is UpdateCheck.Available) {
                _state.update { it.copy(available = r.info, currentVersion = r.currentVersion) }
            }
        }
    }

    /** Manual check from Settings — always runs and reports the outcome. */
    fun checkNow() {
        viewModelScope.launch {
            _state.update { it.copy(checking = true, upToDate = false, error = null) }
            prefs.setLastUpdateCheck(System.currentTimeMillis())
            when (val r = repo.checkForUpdate()) {
                is UpdateCheck.Available ->
                    _state.update { it.copy(checking = false, available = r.info, currentVersion = r.currentVersion, bannerDismissed = false) }
                UpdateCheck.UpToDate ->
                    _state.update { it.copy(checking = false, upToDate = true, available = null) }
                UpdateCheck.Failed ->
                    _state.update { it.copy(checking = false, error = "Couldn't check for updates") }
            }
        }
    }

    /** Download the available APK and launch the system installer. */
    fun downloadAndInstall() {
        val info = _state.value.available ?: return
        if (!repo.canInstall()) {
            _state.update { it.copy(needsInstallPermission = true) }
            repo.openUnknownSourcesSettings()
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(downloading = true, progress = 0, error = null, needsInstallPermission = false) }
            val file = repo.downloadApk(info) { pct -> _state.update { s -> s.copy(progress = pct) } }
            if (file == null) {
                _state.update { it.copy(downloading = false, error = "Download failed") }
            } else {
                _state.update { it.copy(downloading = false) }
                repo.install(file)
            }
        }
    }

    fun dismissBanner() = _state.update { it.copy(bannerDismissed = true) }
}
