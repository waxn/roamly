package com.roamly.ui.achievements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.AchGame
import com.roamly.data.api.AchievementsResponse
import com.roamly.data.repository.AchievementsRepository
import com.roamly.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AchievementsUiState(
    val loading: Boolean = true,
    val game: AchGame? = null,
    /** True while the account's first scan is still running on the server. */
    val pending: Boolean = false,
    val pct: Int = 0,
    val error: String? = null,
    /** "all" or a badge group id. */
    val filter: String = "all",
    /** Group ids the user has minimised. */
    val folded: Set<String> = emptySet(),
    val computedAt: String? = null,
)

@HiltViewModel
class AchievementsViewModel @Inject constructor(
    private val repo: AchievementsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AchievementsUiState())
    val state: StateFlow<AchievementsUiState> = _state.asStateFlow()
    private var poll: Job? = null

    init { load() }

    fun load() {
        viewModelScope.launch {
            when (val r = repo.load()) {
                is Result.Success -> apply(r.data)
                // Keep showing what we have when a refresh fails.
                is Result.Error -> _state.update { it.copy(loading = false, error = if (it.game == null) r.message else null) }
            }
        }
    }

    private fun apply(r: AchievementsResponse) {
        val game = r.game
        if (r.status == "pending" || game == null) {
            _state.update { it.copy(loading = false, pending = true, pct = (r.job?.pct ?: 0.0).toInt(), error = null) }
            if (poll?.isActive != true) {
                poll = viewModelScope.launch { delay(4000); poll = null; load() }
            }
        } else {
            poll?.cancel()
            _state.update { it.copy(loading = false, pending = false, game = game, computedAt = r.computedAt, error = null) }
        }
    }

    fun setFilter(id: String) = _state.update {
        // Picking a category opens it, like the web page does.
        it.copy(filter = id, folded = if (id == "all") it.folded else it.folded - id)
    }

    fun toggleFold(group: String) = _state.update {
        it.copy(folded = if (group in it.folded) it.folded - group else it.folded + group)
    }

    fun setAllFolded(groups: Collection<String>, folded: Boolean) = _state.update {
        it.copy(folded = if (folded) it.folded + groups else it.folded - groups.toSet())
    }
}
