package dev.shuchir.hcgateway.data.repository

import com.google.gson.Gson
import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.data.remote.ApiService
import dev.shuchir.hcgateway.data.remote.DeleteRequest
import dev.shuchir.hcgateway.data.remote.SyncRequest
import dev.shuchir.hcgateway.domain.model.RECORD_TYPES
import dev.shuchir.hcgateway.domain.model.SyncState
import dev.shuchir.hcgateway.domain.model.TypeSyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val gson: Gson,
) {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var syncJob: Job? = null

    val isSyncing: Boolean get() = syncJob?.isActive == true

    suspend fun sync(customStartDate: LocalDate? = null, customEndDate: LocalDate? = null) {
        if (isSyncing) return
        performSync(customStartDate, customEndDate)
    }

    fun cancel() {
        syncJob?.cancel()
        syncJob = null
        _syncState.value = SyncState.Idle
    }

    fun setSyncJob(job: Job) {
        syncJob = job
    }

    private suspend fun performSync(customStartDate: LocalDate?, customEndDate: LocalDate?) {
        _syncState.value = SyncState.Syncing("", 0, RECORD_TYPES.size)
        val typeResults = mutableListOf<TypeSyncResult>()
        var totalRecords = 0

        try {
            val settings = preferencesRepository.settings.first()

            if (customStartDate != null) {
                val startTime = customStartDate.atStartOfDay(ZoneId.of("UTC")).toInstant()
                val endTime = (customEndDate ?: LocalDate.now()).plusDays(1)
                    .atStartOfDay(ZoneId.of("UTC")).toInstant()
                totalRecords = fullSync(startTime, endTime, typeResults)
            } else if (settings.fullSyncMode) {
                val startTime = Instant.now().minusSeconds(29 * 24 * 60 * 60L)
                totalRecords = fullSync(startTime, Instant.now(), typeResults)
            } else if (settings.changesToken.isNotBlank()) {
                totalRecords = deltaSync(settings.changesToken, typeResults)
            } else {
                val startTime = Instant.now().minusSeconds(29 * 24 * 60 * 60L)
                totalRecords = fullSync(startTime, Instant.now(), typeResults)
            }

            preferencesRepository.updateLastSync(System.currentTimeMillis())
            if (typeResults.isNotEmpty()) {
                preferencesRepository.updateLastSyncResults(gson.toJson(typeResults))
            }
            _syncState.value = SyncState.Done(totalRecords, typeResults)
        } catch (e: CancellationException) {
            _syncState.value = SyncState.Idle
            throw e
        } catch (e: Exception) {
            _syncState.value = SyncState.Error(e.message ?: "Sync failed")
        }
    }

    private suspend fun fullSync(
        startTime: Instant,
        endTime: Instant,
        typeResults: MutableList<TypeSyncResult>,
    ): Int {
        var totalRecords = 0

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

        for ((index, pair) in results.withIndex()) {
            val (typeName, records) = pair
            _syncState.value = SyncState.Syncing(typeName, index + 1, RECORD_TYPES.size)

            if (records.isNotEmpty()) {
                try {
                    val json = healthConnectRepository.recordsToJson(records)
                    apiService.syncRecords(typeName, SyncRequest(json))
                    totalRecords += records.size
                    typeResults.add(TypeSyncResult(typeName, records.size))
                } catch (e: Exception) {
                    // Continue with next type
                }
            }
        }

        try {
            val token = healthConnectRepository.getChangesToken()
            preferencesRepository.updateChangesToken(token)
        } catch (_: Exception) { }

        return totalRecords
    }

    private suspend fun deltaSync(
        changesToken: String,
        typeResults: MutableList<TypeSyncResult>,
    ): Int {
        var totalRecords = 0

        val result = healthConnectRepository.getChanges(changesToken)

        if (result.tokenExpired) {
            preferencesRepository.updateChangesToken("")
            val startTime = Instant.now().minusSeconds(29 * 24 * 60 * 60L)
            return fullSync(startTime, Instant.now(), typeResults)
        }

        var typesProcessed = 0
        for ((typeName, records) in result.upsertedRecords) {
            typesProcessed++
            _syncState.value = SyncState.Syncing(typeName, typesProcessed, result.upsertedRecords.size)

            if (records.isNotEmpty()) {
                try {
                    val json = healthConnectRepository.recordsToJson(records)
                    apiService.syncRecords(typeName, SyncRequest(json))
                    totalRecords += records.size
                    typeResults.add(TypeSyncResult(typeName, records.size))
                } catch (_: Exception) { }
            }
        }

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
