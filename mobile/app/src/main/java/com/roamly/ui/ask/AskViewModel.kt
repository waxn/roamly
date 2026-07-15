package com.roamly.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.AskMessage
import com.roamly.data.prefs.UserPreferences
import com.roamly.data.repository.AskRepository
import com.roamly.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.TimeZone
import javax.inject.Inject

data class ChatMessage(val role: String, val content: String)

data class AskUiState(
    /** Cached from the server — gates whether the Ask tab is shown at all. */
    val askEnabled: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val sending: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AskViewModel @Inject constructor(
    private val repo: AskRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(AskUiState())
    val state: StateFlow<AskUiState> = _state

    init {
        // Paint from the cached flag immediately, then refresh from the server
        // so a config change made on the web shows up here too.
        viewModelScope.launch {
            userPreferences.askEnabled.collect { enabled ->
                _state.update { it.copy(askEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            userPreferences.serverUrl.first { !it.isNullOrBlank() }
            when (val result = repo.getConfig()) {
                is Result.Success -> userPreferences.setAskEnabled(result.data.configured)
                is Result.Error -> Unit
            }
        }
    }

    fun setInput(text: String) {
        _state.update { it.copy(input = text, error = null) }
    }

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.sending) return

        val history = _state.value.messages + ChatMessage("user", text)
        _state.update { it.copy(messages = history, input = "", sending = true, error = null) }

        viewModelScope.launch {
            val payload = history.map { AskMessage(it.role, it.content) }
            when (val result = repo.sendMessage(payload, localTzOffsetMinutes())) {
                is Result.Success -> {
                    val reply = result.data.reply
                    if (reply != null) {
                        _state.update { it.copy(messages = it.messages + ChatMessage("assistant", reply), sending = false) }
                    } else {
                        _state.update { it.copy(sending = false, error = result.data.error ?: "Something went wrong.") }
                    }
                }
                is Result.Error -> _state.update { it.copy(sending = false, error = result.message) }
            }
        }
    }

    /** Matches JS `Date.getTimezoneOffset()` convention (UTC minus local, in minutes). */
    private fun localTzOffsetMinutes(): Int {
        val offsetMs = TimeZone.getDefault().getOffset(System.currentTimeMillis())
        return -(offsetMs / 60000)
    }
}
