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

    private lateinit var lifecycleObserver: LifecycleEventObserver

    override fun onCleared() {
        super.onCleared()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
    }

    private val _tableHeightPx = MutableStateFlow(0)
    val tableHeightPx: StateFlow<Int> = _tableHeightPx.asStateFlow()

    fun updateTableHeight(px: Int) {
        if (px > 0) _tableHeightPx.value = px
    }

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
        lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkServerConnection()
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
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
            val reachable = try {
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
            _serverReachable.value = reachable
            if (reachable) {
                loadServerCounts()
                loadPendingCounts()
            }
        }
    }

    private val _hasPermissions = MutableStateFlow<Boolean?>(null)
    val hasPermissions: StateFlow<Boolean?> = _hasPermissions.asStateFlow()

    fun getRequiredPermissions(): Set<String> = healthConnectRepository.permissions

    // Pending (new) record counts since last sync via Changes API
    private val _pendingCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pendingCounts: StateFlow<Map<String, Int>> = _pendingCounts.asStateFlow()

    fun loadPendingCounts() {
        viewModelScope.launch {
            val settings = preferencesRepository.settings.first()
            if (settings.changesToken.isBlank()) {
                // No token = never synced, can't determine pending
                _pendingCounts.value = emptyMap()
                return@launch
            }
            try {
                val result = healthConnectRepository.getChanges(settings.changesToken)
                if (result.tokenExpired) {
                    _pendingCounts.value = emptyMap()
                    return@launch
                }
                _pendingCounts.value = result.upsertedRecords.mapValues { it.value.size }
            } catch (_: Exception) {
                _pendingCounts.value = emptyMap()
            }
        }
    }

    // Server record counts (null = not loaded yet)
    private val _serverCounts = MutableStateFlow<Map<String, Int>?>(null)
    val serverCounts: StateFlow<Map<String, Int>?> = _serverCounts.asStateFlow()

    fun resetServerCounts() {
        _serverCounts.value = null
    }

    fun loadServerCounts() {
        viewModelScope.launch {
            try {
                val response = apiService.getCounts()
                _serverCounts.value = if (response.isSuccessful && response.body() != null) {
                    response.body()!!
                } else {
                    _serverCounts.value ?: emptyMap()
                }
            } catch (_: Exception) {
                _serverCounts.value = _serverCounts.value ?: emptyMap()
            }
        }
    }

    suspend fun refreshTable() {
        // Null out to show LoadingIndicator, then fetch both and wait for completion
        _serverCounts.value = null
        loadPendingCounts()
        // Fetch server counts synchronously so we wait for the result
        try {
            val response = apiService.getCounts()
            _serverCounts.value = if (response.isSuccessful && response.body() != null) {
                response.body()!!
            } else {
                emptyMap()
            }
        } catch (_: Exception) {
            _serverCounts.value = emptyMap()
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
