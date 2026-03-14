package dev.shuchir.hcgateway.data.repository

import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.data.remote.ApiService
import dev.shuchir.hcgateway.data.remote.DeleteRequest
import dev.shuchir.hcgateway.data.remote.SyncRequest
import dev.shuchir.hcgateway.domain.model.RECORD_TYPES
import dev.shuchir.hcgateway.domain.model.SyncState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepository @Inject constructor(
    private val healthConnectRepository: HealthConnectRepository,
    private val apiService: ApiService,
    private val preferencesRepository: PreferencesRepository,
) {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val syncMutex = Mutex()

    suspend fun sync(customStartDate: LocalDate? = null, customEndDate: LocalDate? = null) {
        if (!syncMutex.tryLock()) return
        try {
            performSync(customStartDate, customEndDate)
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun performSync(customStartDate: LocalDate?, customEndDate: LocalDate?) {
        _syncState.value = SyncState.Syncing("", 0, RECORD_TYPES.size)
        var totalRecords = 0

        try {
            val settings = preferencesRepository.settings.first()

            if (customStartDate != null) {
                // Custom range: always full read
                val startTime = customStartDate.atStartOfDay(ZoneId.of("UTC")).toInstant()
                val endTime = (customEndDate ?: LocalDate.now()).plusDays(1)
                    .atStartOfDay(ZoneId.of("UTC")).toInstant()
                totalRecords = fullSync(startTime, endTime)
            } else if (settings.fullSyncMode) {
                // Full 30-day sync mode enabled
                val startTime = Instant.now().minusSeconds(29 * 24 * 60 * 60L)
                val endTime = Instant.now()
                totalRecords = fullSync(startTime, endTime)
            } else if (settings.changesToken.isNotBlank()) {
                // Delta sync using Changes API
                totalRecords = deltaSync(settings.changesToken)
            } else {
                // First sync: full 30-day read
                val startTime = Instant.now().minusSeconds(29 * 24 * 60 * 60L)
                val endTime = Instant.now()
                totalRecords = fullSync(startTime, endTime)
            }

            preferencesRepository.updateLastSync(System.currentTimeMillis())
            _syncState.value = SyncState.Done(totalRecords)
        } catch (e: Exception) {
            _syncState.value = SyncState.Error(e.message ?: "Sync failed")
        }
    }

    private suspend fun fullSync(startTime: Instant, endTime: Instant): Int {
        var totalRecords = 0

        // Parallel read all record types
        val results = coroutineScope {
            RECORD_TYPES.map { type ->
                async {
                    try {
                        val records = healthConnectRepository.readRecords(
                            type.recordClass, startTime, endTime
                        )
                        type.name to records
                    } catch (e: Exception) {
                        type.name to emptyList()
                    }
                }
            }.awaitAll()
        }

        // Upload results
        for ((index, pair) in results.withIndex()) {
            val (typeName, records) = pair
            _syncState.value = SyncState.Syncing(typeName, index + 1, RECORD_TYPES.size)

            if (records.isNotEmpty()) {
                try {
                    val json = healthConnectRepository.recordsToJson(records)
                    apiService.syncRecords(typeName, SyncRequest(json))
                    totalRecords += records.size
                } catch (e: Exception) {
                    // Continue with next type
                }
            }
        }

        // Get a fresh changes token after full sync
        try {
            val token = healthConnectRepository.getChangesToken()
            preferencesRepository.updateChangesToken(token)
        } catch (_: Exception) { }

        return totalRecords
    }

    private suspend fun deltaSync(changesToken: String): Int {
        var totalRecords = 0

        val result = healthConnectRepository.getChanges(changesToken)

        if (result.tokenExpired) {
            // Fall back to full sync
            preferencesRepository.updateChangesToken("")
            val startTime = Instant.now().minusSeconds(29 * 24 * 60 * 60L)
            return fullSync(startTime, Instant.now())
        }

        // Upload upserted records
        var typesProcessed = 0
        for ((typeName, records) in result.upsertedRecords) {
            typesProcessed++
            _syncState.value = SyncState.Syncing(typeName, typesProcessed, result.upsertedRecords.size)

            if (records.isNotEmpty()) {
                try {
                    val json = healthConnectRepository.recordsToJson(records)
                    apiService.syncRecords(typeName, SyncRequest(json))
                    totalRecords += records.size
                } catch (_: Exception) { }
            }
        }

        // Store new token
        if (result.nextToken.isNotBlank()) {
            preferencesRepository.updateChangesToken(result.nextToken)
        }

        return totalRecords
    }

    suspend fun deleteRecords(recordType: String, uuids: List<String>) {
        try {
            apiService.deleteRecords(recordType, DeleteRequest(uuids))
        } catch (_: Exception) { }

        val typeInfo = RECORD_TYPES.find { it.name == recordType } ?: return
        try {
            healthConnectRepository.deleteRecordsByIds(typeInfo.recordClass, uuids)
        } catch (_: Exception) { }
    }

    fun resetState() {
        _syncState.value = SyncState.Idle
    }
}
