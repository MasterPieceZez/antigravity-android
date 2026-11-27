package com.termux.app

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface HubPhase {
    data object Initializing : HubPhase
    data object SetupPermissions : HubPhase
    data object Onboarding : HubPhase
    data object Booting : HubPhase
    data class Running(val hubUrl: String) : HubPhase
    data class Error(val message: String) : HubPhase
}

data class HubUiState(
    val phase: HubPhase = HubPhase.Initializing,
    val bootstrapComplete: Boolean = false,
    val setupDismissed: Boolean = false,
    val currentHubUrl: String? = null,
    val isBootInFlight: Boolean = false,
    val isHubReconnectInFlight: Boolean = false,
    val isSwitchingAccount: Boolean = false,
    val lastQuotaTriggerAt: Long = 0L,
    val renderCrashCount: Int = 0,
    val lastRenderCrashMs: Long = 0L
)

/**
 * Consolidates AntigravityMainActivity lifecycle, boot, reconnect, and account-switch
 * state into an atomic StateFlow that survives configuration changes.
 */
class AntigravityHubViewModel : ViewModel() {
    private val stateLock = Any()
    private val _uiState = MutableStateFlow(HubUiState())
    val uiState: StateFlow<HubUiState> = _uiState.asStateFlow()

    val currentState: HubUiState get() = _uiState.value

    fun setSetupDismissed(dismissed: Boolean) {
        synchronized(stateLock) {
            _uiState.update { it.copy(setupDismissed = dismissed) }
        }
    }

    fun markBootstrapComplete() {
        synchronized(stateLock) {
            _uiState.update { it.copy(bootstrapComplete = true) }
        }
    }

    fun showSetup() {
        synchronized(stateLock) {
            _uiState.update { it.copy(phase = HubPhase.SetupPermissions) }
        }
    }

    fun showOnboarding() {
        synchronized(stateLock) {
            _uiState.update { it.copy(phase = HubPhase.Onboarding) }
        }
    }

    fun tryBeginBoot(): Boolean = synchronized(stateLock) {
        if (_uiState.value.isBootInFlight) return false
        _uiState.update { it.copy(isBootInFlight = true, phase = HubPhase.Booting) }
        true
    }

    fun finishBoot(hubUrl: String?) {
        synchronized(stateLock) {
            _uiState.update { state ->
                if (hubUrl != null) {
                    state.copy(
                        isBootInFlight = false,
                        currentHubUrl = hubUrl,
                        phase = HubPhase.Running(hubUrl)
                    )
                } else {
                    state.copy(
                        isBootInFlight = false,
                        phase = HubPhase.Error("Hub startup timed out")
                    )
                }
            }
        }
    }

    fun tryBeginReconnect(): Boolean = synchronized(stateLock) {
        val s = _uiState.value
        if (!s.bootstrapComplete || s.isHubReconnectInFlight || s.isBootInFlight || s.isSwitchingAccount) {
            return false
        }
        _uiState.update { it.copy(isHubReconnectInFlight = true) }
        true
    }

    fun finishReconnect(hubUrl: String?) {
        synchronized(stateLock) {
            _uiState.update { state ->
                state.copy(
                    isHubReconnectInFlight = false,
                    currentHubUrl = hubUrl ?: state.currentHubUrl,
                    phase = if (hubUrl != null) HubPhase.Running(hubUrl) else state.phase
                )
            }
        }
    }

    fun tryBeginAccountSwitch(): Boolean = synchronized(stateLock) {
        if (_uiState.value.isSwitchingAccount) return false
        _uiState.update { it.copy(isSwitchingAccount = true) }
        true
    }

    fun tryBeginQuotaFailover(nowMs: Long, minIntervalMs: Long = 15_000L): Boolean = synchronized(stateLock) {
        val s = _uiState.value
        if (s.isSwitchingAccount || nowMs - s.lastQuotaTriggerAt < minIntervalMs) {
            return false
        }
        _uiState.update { it.copy(isSwitchingAccount = true, lastQuotaTriggerAt = nowMs) }
        true
    }

    fun finishAccountSwitch(newUrl: String?) {
        synchronized(stateLock) {
            _uiState.update { state ->
                state.copy(
                    isSwitchingAccount = false,
                    currentHubUrl = newUrl ?: state.currentHubUrl,
                    phase = if (newUrl != null) HubPhase.Running(newUrl) else state.phase
                )
            }
        }
    }

    fun setCurrentHubUrl(url: String?) {
        synchronized(stateLock) {
            _uiState.update {
                it.copy(
                    currentHubUrl = url,
                    phase = if (url != null) HubPhase.Running(url) else it.phase
                )
            }
        }
    }

    fun recordRenderCrash(nowMs: Long): Int = synchronized(stateLock) {
        val prev = _uiState.value
        val nextCount = if (nowMs - prev.lastRenderCrashMs < 15_000L) prev.renderCrashCount + 1 else 1
        _uiState.update {
            it.copy(renderCrashCount = nextCount, lastRenderCrashMs = nowMs)
        }
        nextCount
    }
}
