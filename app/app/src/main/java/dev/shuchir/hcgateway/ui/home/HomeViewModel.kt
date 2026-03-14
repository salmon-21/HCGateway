package dev.shuchir.hcgateway.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.data.local.UserSettings
import dev.shuchir.hcgateway.data.remote.ApiService
import dev.shuchir.hcgateway.data.remote.RefreshRequest
import dev.shuchir.hcgateway.data.repository.HealthConnectRepository
import dev.shuchir.hcgateway.data.repository.NetworkMonitor
import dev.shuchir.hcgateway.data.repository.SyncRepository
import dev.shuchir.hcgateway.domain.model.RECORD_TYPES
import dev.shuchir.hcgateway.domain.model.SyncState
import dev.shuchir.hcgateway.domain.model.TypeSyncResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val syncRepository: SyncRepository,
    private val healthConnectRepository: HealthConnectRepository,
    private val apiService: ApiService,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    val settings: StateFlow<UserSettings> = preferencesRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    val syncState: StateFlow<SyncState> = syncRepository.syncState

    val isHealthConnectAvailable: Boolean get() = healthConnectRepository.isAvailable

    // null = checking, true = reachable, false = unreachable
    private val _serverReachable = MutableStateFlow<Boolean?>(null)
    val serverReachable: StateFlow<Boolean?> = _serverReachable.asStateFlow()

    init {
        checkServerConnection()
        // Re-check on sync completion
        viewModelScope.launch {
            syncRepository.syncState.collect { state ->
                when (state) {
                    is SyncState.Done -> _serverReachable.value = true
                    is SyncState.Error -> checkServerConnection()
                    else -> {}
                }
            }
        }
        // Re-check when app returns to foreground
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkServerConnection()
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        // Re-check when network state changes
        viewModelScope.launch {
            networkMonitor.isConnected.collect { connected ->
                if (connected) {
                    checkServerConnection()
                } else {
                    _serverReachable.value = false
                }
            }
        }
    }

    fun checkServerConnection() {
        viewModelScope.launch {
            _serverReachable.value = null
            val settings = preferencesRepository.settings.first()
            if (settings.refreshToken.isBlank()) {
                _serverReachable.value = false
                return@launch
            }
            _serverReachable.value = try {
                withTimeout(5000) {
                    val response = apiService.refresh(RefreshRequest(settings.refreshToken))
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        preferencesRepository.saveTokens(body.token, body.refresh)
                        true
                    } else {
                        false
                    }
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private val _hasPermissions = MutableStateFlow<Boolean?>(null)
    val hasPermissions: StateFlow<Boolean?> = _hasPermissions.asStateFlow()

    fun getRequiredPermissions(): Set<String> = healthConnectRepository.buildPermissions()

    // Health Connect record counts (for comparison with synced data)
    private val _hcRecordCounts = MutableStateFlow<List<TypeSyncResult>>(emptyList())
    val hcRecordCounts: StateFlow<List<TypeSyncResult>> = _hcRecordCounts.asStateFlow()

    fun loadHcRecordCounts() {
        viewModelScope.launch {
            val counts = RECORD_TYPES.mapNotNull { type ->
                try {
                    val records = healthConnectRepository.readRecords(
                        type.recordClass,
                        java.time.Instant.EPOCH,
                        java.time.Instant.now(),
                    )
                    if (records.isNotEmpty()) TypeSyncResult(type.name, records.size) else null
                } catch (_: Exception) {
                    null
                }
            }
            _hcRecordCounts.value = counts
        }
    }

    // Server record counts
    private val _serverCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val serverCounts: StateFlow<Map<String, Int>> = _serverCounts.asStateFlow()

    fun loadServerCounts() {
        viewModelScope.launch {
            try {
                val response = apiService.getCounts()
                if (response.isSuccessful && response.body() != null) {
                    _serverCounts.value = response.body()!!
                }
            } catch (_: Exception) { }
        }
    }

    fun checkPermissions() {
        viewModelScope.launch {
            _hasPermissions.value = healthConnectRepository.hasAllPermissions()
        }
    }

    fun onPermissionsResult() {
        checkPermissions()
    }

    fun syncNow() {
        val job = viewModelScope.launch {
            syncRepository.sync()
        }
        syncRepository.setSyncJob(job)
    }

    fun syncRange(startDate: LocalDate, endDate: LocalDate) {
        val job = viewModelScope.launch {
            syncRepository.sync(startDate, endDate)
        }
        syncRepository.setSyncJob(job)
    }

    fun cancelSync() {
        syncRepository.cancel()
    }

    fun resetSyncState() {
        syncRepository.resetState()
    }
}
