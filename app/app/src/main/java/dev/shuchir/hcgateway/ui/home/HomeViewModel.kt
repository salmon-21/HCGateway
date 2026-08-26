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
import dev.shuchir.hcgateway.data.repository.SystemSettings
import dev.shuchir.hcgateway.domain.model.RECORD_TYPES
import dev.shuchir.hcgateway.domain.model.ServerStatus
import dev.shuchir.hcgateway.domain.model.SyncState
import dev.shuchir.hcgateway.domain.model.TypeSyncResult
import kotlinx.coroutines.Job
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
    private val systemSettings: SystemSettings,
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

    private var connectionCheck: Job? = null

    private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Checking)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    private val _hasPermissions = MutableStateFlow<Boolean?>(null)
    val hasPermissions: StateFlow<Boolean?> = _hasPermissions.asStateFlow()

    private val _batteryOptimized = MutableStateFlow(false)
    val batteryOptimized: StateFlow<Boolean> = _batteryOptimized.asStateFlow()

    init {
        checkServerConnection()
        // Re-check on sync completion
        viewModelScope.launch {
            syncRepository.syncState.collect { state ->
                when (state) {
                    is SyncState.Done -> _serverStatus.value = ServerStatus.Connected
                    is SyncState.Error -> checkServerConnection()
                    else -> {}
                }
            }
        }
        // Re-check when app returns to foreground
        lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkServerConnection()
                checkPermissions()
                checkBatteryOptimization()
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        // Re-check when network state changes
        viewModelScope.launch {
            networkMonitor.isConnected.collect { connected ->
                if (connected) {
                    // The monitor has already evicted stale pooled sockets, so this
                    // check runs over fresh connections.
                    checkServerConnection()
                } else {
                    _serverStatus.value = ServerStatus.Unreachable
                }
            }
        }
    }

    fun checkServerConnection() {
        // Startup, ON_RESUME and a network change all fire this at once; without
        // this guard each one re-probes /health and /refresh in parallel.
        if (connectionCheck?.isActive == true) return
        connectionCheck = viewModelScope.launch {
            _serverStatus.value = ServerStatus.Checking
            val settings = preferencesRepository.settings.first()

            // Reachability first, and on its own: /health needs no credentials, so
            // a failure here is genuinely the network rather than the session.
            val reachable = try {
                withTimeout(5000) { apiService.health().isSuccessful }
            } catch (_: Exception) {
                false
            }
            if (!reachable) {
                _serverStatus.value = ServerStatus.Unreachable
                return@launch
            }

            if (settings.refreshToken.isBlank()) {
                _serverStatus.value = ServerStatus.Unauthenticated
                return@launch
            }

            // The server is up; now find out whether our session still is.
            val authenticated = try {
                withTimeout(5000) {
                    val response = apiService.refresh(RefreshRequest(settings.refreshToken))
                    val body = response.body()
                    if (response.isSuccessful && body != null) {
                        preferencesRepository.saveTokens(body.token, body.refresh)
                        true
                    } else {
                        false
                    }
                }
            } catch (_: Exception) {
                false
            }

            _serverStatus.value =
                if (authenticated) ServerStatus.Connected else ServerStatus.Unauthenticated
            if (authenticated) {
                loadServerCounts()
                loadPendingCounts()
            }
        }
    }

    fun checkBatteryOptimization() {
        _batteryOptimized.value = systemSettings.isBatteryOptimized()
    }

    fun requestIgnoreBatteryOptimizations() = systemSettings.requestIgnoreBatteryOptimizations()

    fun getRequiredPermissions(): Set<String> = healthConnectRepository.permissions

    // Pending (new) record counts since last sync via Changes API
    private val _pendingCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pendingCounts: StateFlow<Map<String, Int>> = _pendingCounts.asStateFlow()

    fun loadPendingCounts(consumeChanges: Boolean = false) {
        viewModelScope.launch {
            val settings = preferencesRepository.settings.first()
            if (settings.changesToken.isBlank()) {
                _pendingCounts.value = emptyMap()
                return@launch
            }
            try {
                val result = healthConnectRepository.getChanges(settings.changesToken)
                if (result.tokenExpired) {
                    _pendingCounts.value = emptyMap()
                    return@launch
                }
                if (consumeChanges && result.nextToken.isNotBlank()) {
                    // After sync completion, advance the token so these changes
                    // don't appear as New. They were already uploaded by the sync.
                    preferencesRepository.updateChangesToken(result.nextToken)
                    _pendingCounts.value = emptyMap()
                } else {
                    _pendingCounts.value = result.upsertedRecords.mapValues { it.value.size }
                }
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

    fun syncNow() = launchSync { syncRepository.sync() }

    fun syncRange(startDate: LocalDate, endDate: LocalDate) = launchSync {
        syncRepository.sync(startDate, endDate)
    }

    private fun launchSync(block: suspend () -> Unit) {
        val job = viewModelScope.launch { block() }
        syncRepository.setSyncJob(job)
    }

    fun cancelSync() {
        syncRepository.cancel()
    }

    fun resetSyncState() {
        syncRepository.resetState()
    }
}
