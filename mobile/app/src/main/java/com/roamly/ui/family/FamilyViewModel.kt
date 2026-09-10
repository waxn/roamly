package com.roamly.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.FamilyCircleDetailResponse
import com.roamly.data.api.FamilyLocationsResponse
import com.roamly.data.repository.FamilyRepository
import com.roamly.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FamilyUiState(
    val loading: Boolean = false,
    /** Every circle the caller belongs to, accepted or still pending an invite. */
    val circles: List<FamilyCircleDetailResponse> = emptyList(),
    val selectedCircleId: Int? = null,
    /** Full detail (members + places) for [selectedCircleId]. */
    val selectedCircle: FamilyCircleDetailResponse? = null,
    val locations: FamilyLocationsResponse? = null,
    val error: String? = null,
)

@HiltViewModel
class FamilyViewModel @Inject constructor(
    private val repo: FamilyRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FamilyUiState())
    val uiState: StateFlow<FamilyUiState> = _state.asStateFlow()

    init { refreshCircles() }

    fun refreshCircles() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = repo.getCircles()) {
                is Result.Success -> {
                    val circles = r.data.circles
                    _state.update { s ->
                        // Keep the current selection if it still exists; otherwise pick
                        // the first circle the caller has actually accepted — a pending
                        // invite has no places/locations to show yet.
                        val stillThere = circles.any { it.id == s.selectedCircleId }
                        val nextSelected = if (stillThere) s.selectedCircleId
                                            else circles.firstOrNull { it.accepted }?.id
                        s.copy(loading = false, circles = circles, selectedCircleId = nextSelected)
                    }
                    _state.value.selectedCircleId?.let { selectCircle(it) }
                }
                is Result.Error -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    fun selectCircle(circleId: Int) {
        _state.update { it.copy(selectedCircleId = circleId, selectedCircle = null, locations = null) }
        viewModelScope.launch {
            when (val r = repo.getCircle(circleId)) {
                is Result.Success -> _state.update { it.copy(selectedCircle = r.data) }
                is Result.Error -> _state.update { it.copy(error = r.message) }
            }
        }
        refreshLocations()
    }

    fun refreshLocations() {
        val circleId = _state.value.selectedCircleId ?: return
        viewModelScope.launch {
            // Best-effort: a live-map hiccup shouldn't blow away the rest of the screen.
            (repo.getLocations(circleId) as? Result.Success)?.let { r ->
                _state.update { it.copy(locations = r.data) }
            }
        }
    }

    fun refreshSelected() {
        _state.value.selectedCircleId?.let { selectCircle(it) }
    }

    fun createCircle(name: String, onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            when (val r = repo.createCircle(name)) {
                is Result.Success -> {
                    refreshCircles()
                    _state.update { it.copy(selectedCircleId = r.data.id) }
                    selectCircle(r.data.id)
                    onDone(true, null)
                }
                is Result.Error -> onDone(false, r.message)
            }
        }
    }

    fun renameCircle(name: String, onDone: (Boolean, String?) -> Unit) {
        val circleId = _state.value.selectedCircleId ?: return
        viewModelScope.launch {
            when (val r = repo.renameCircle(circleId, name)) {
                is Result.Success -> { refreshSelected(); onDone(true, null) }
                is Result.Error -> onDone(false, r.message)
            }
        }
    }

    fun deleteCircle(onDone: (Boolean, String?) -> Unit) {
        val circleId = _state.value.selectedCircleId ?: return
        viewModelScope.launch {
            when (val r = repo.deleteCircle(circleId)) {
                is Result.Success -> { refreshCircles(); onDone(true, null) }
                is Result.Error -> onDone(false, r.message)
            }
        }
    }

    fun leaveCircle(onDone: (Boolean, String?) -> Unit) {
        val circleId = _state.value.selectedCircleId ?: return
        viewModelScope.launch {
            when (val r = repo.leaveCircle(circleId)) {
                is Result.Success -> { refreshCircles(); onDone(true, null) }
                is Result.Error -> onDone(false, r.message)
            }
        }
    }

    fun removeMember(userId: Int, onDone: (Boolean, String?) -> Unit) {
        val circleId = _state.value.selectedCircleId ?: return
        viewModelScope.launch {
            when (val r = repo.removeMember(circleId, userId)) {
                is Result.Success -> { refreshSelected(); onDone(true, null) }
                is Result.Error -> onDone(false, r.message)
            }
        }
    }

    /** Mints (or rotates) the invite link. Callback carries the URL to share. */
    fun invite(rotate: Boolean, onDone: (String?, String?) -> Unit) {
        val circleId = _state.value.selectedCircleId ?: return
        viewModelScope.launch {
            when (val r = repo.invite(circleId, rotate)) {
                is Result.Success -> onDone(r.data.inviteUrl, null)
                is Result.Error -> onDone(null, r.message)
            }
        }
    }

    fun setShareLocation(enabled: Boolean) {
        val circleId = _state.value.selectedCircleId ?: return
        viewModelScope.launch {
            repo.setShareLocation(circleId, enabled)
            refreshSelected()
        }
    }

    fun createPlace(name: String, lat: Double, lng: Double, radiusM: Double, onDone: (Boolean, String?) -> Unit) {
        val circleId = _state.value.selectedCircleId ?: return
        viewModelScope.launch {
            when (val r = repo.createPlace(circleId, name, lat, lng, radiusM)) {
                is Result.Success -> { refreshSelected(); onDone(true, null) }
                is Result.Error -> onDone(false, r.message)
            }
        }
    }

    fun deletePlace(placeId: Int, onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            when (val r = repo.deletePlace(placeId)) {
                is Result.Success -> { refreshSelected(); onDone(true, null) }
                is Result.Error -> onDone(false, r.message)
            }
        }
    }

    suspend fun getAlerts(placeId: Int) = repo.getAlerts(placeId)

    fun setAlerts(placeId: Int, onEnter: Boolean?, onExit: Boolean?, onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            when (val r = repo.setAlerts(placeId, onEnter, onExit)) {
                is Result.Success -> onDone(true, null)
                is Result.Error -> onDone(false, r.message)
            }
        }
    }
}
