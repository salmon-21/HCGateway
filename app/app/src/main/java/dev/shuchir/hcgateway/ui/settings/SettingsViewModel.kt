package dev.shuchir.hcgateway.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.data.local.UserSettings
import dev.shuchir.hcgateway.data.repository.AuthRepository
import dev.shuchir.hcgateway.worker.SyncScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val authRepository: AuthRepository,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    val settings: StateFlow<UserSettings> = preferencesRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    fun updateThemeMode(mode: String) {
        viewModelScope.launch { preferencesRepository.updateThemeMode(mode) }
    }

    fun updateSyncInterval(minutes: Int) {
        viewModelScope.launch {
            preferencesRepository.updateSyncInterval(minutes)
            syncScheduler.schedule(minutes)
        }
    }

    fun updateFullSyncMode(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.updateFullSyncMode(enabled) }
    }

    fun updateStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.updateStartOnBoot(enabled) }
    }

    fun updateAutoSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updateAutoSyncEnabled(enabled)
            if (enabled) {
                val interval = preferencesRepository.settings.first().syncInterval
                syncScheduler.schedule(interval)
            } else {
                syncScheduler.cancel()
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            syncScheduler.cancel()
            authRepository.logout()
        }
    }
}
