package com.pocketmartian.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketmartian.app.data.local.SettingsRepository
import com.pocketmartian.app.data.repository.RealtimeTrainsRepository
import com.pocketmartian.app.data.repository.RttCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrainsSettingsUiState(
    /** What is in the field, saved or not. */
    val token: String = "",
    val saved: Boolean = false,
    val checking: Boolean = false,
    /** Result of the last Save, Check or Clear, and whether it was good news. */
    val message: String? = null,
    val ok: Boolean = true
)

/** The user's own Realtime Trains token: enter, save, check, clear. */
@HiltViewModel
class TrainsSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val trains: RealtimeTrainsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TrainsSettingsUiState())
    val state: StateFlow<TrainsSettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.rttToken.collect { saved ->
                _state.value = _state.value.copy(
                    token = if (_state.value.token.isBlank()) saved.orEmpty() else _state.value.token,
                    saved = saved != null
                )
            }
        }
    }

    fun updateToken(text: String) {
        _state.value = _state.value.copy(token = text, message = null)
    }

    /** Save, then check, so a mistyped token is caught straight away. */
    fun save() {
        val token = _state.value.token.trim()
        if (token.isEmpty()) return
        viewModelScope.launch {
            settings.saveRttToken(token)
            check()
        }
    }

    fun check() {
        if (_state.value.checking) return
        val token = _state.value.token.trim().takeIf { it.isNotEmpty() }
        _state.value = _state.value.copy(checking = true, message = null)
        viewModelScope.launch {
            val result = trains.check(token)
            _state.value = _state.value.copy(
                checking = false,
                message = when (result) {
                    is RttCheck.Valid -> "${result.summary}. Live UK train times are on."
                    is RttCheck.Invalid -> result.message
                },
                ok = result is RttCheck.Valid
            )
        }
    }

    fun clear() {
        viewModelScope.launch {
            settings.clearRttToken()
            _state.value = TrainsSettingsUiState(message = "Token removed. Train times will come from web search.")
        }
    }
}
