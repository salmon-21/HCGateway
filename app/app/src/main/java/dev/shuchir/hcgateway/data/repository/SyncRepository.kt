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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger
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
    companion object {
        const val MIN_SYNC_DISPLAY_MS = 1000L
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var syncJob: Job? = null
    private var currentTypeResults = mutableListOf<TypeSyncResult>()
    private var currentRecordCount = 0

    val isSyncing: Boolean get() = syncJob?.isActive == true

    suspend fun sync(customStartDate: LocalDate? = null, customEndDate: LocalDate? = null) {
        if (isSyncing) return
        performSync(customStartDate, customEndDate)
    }

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
        syncJob?.cancel()
        syncJob = null
        _syncState.value = SyncState.Cancelled(currentRecordCount, currentTypeResults.toList())
    }

    fun setSyncJob(job: Job) {
        syncJob = job
    }

    private fun updateSyncState(state: SyncState) {
        if (!cancelled) _syncState.value = state
    }

    private suspend fun performSync(customStartDate: LocalDate?, customEndDate: LocalDate?) {
        cancelled = false
        _syncState.value = SyncState.Syncing("", 0, RECORD_TYPES.size)
        currentTypeResults = mutableListOf()
        currentRecordCount = 0
        val typeResults = currentTypeResults
        val failedTypes = mutableListOf<String>()
        var totalRecords = 0
        val syncStartTime = System.currentTimeMillis()

        try {
            val settings = preferencesRepository.settings.first()

            if (customStartDate != null) {
                val startTime = customStartDate.atStartOfDay(ZoneId.of("UTC")).toInstant()
                val endTime = (customEndDate ?: LocalDate.now()).plusDays(1)
                    .atStartOfDay(ZoneId.of("UTC")).toInstant()
                totalRecords = fullSync(startTime, endTime, typeResults, failedTypes)
            } else if (settings.fullSyncMode) {
                val startTime = Instant.now().minusSeconds(29 * 24 * 60 * 60L)
                totalRecords = fullSync(startTime, Instant.now(), typeResults, failedTypes)
            } else if (settings.changesToken.isNotBlank()) {
                totalRecords = deltaSync(settings.changesToken, typeResults, failedTypes)
            } else {
                val startTime = Instant.now().minusSeconds(29 * 24 * 60 * 60L)
                totalRecords = fullSync(startTime, Instant.now(), typeResults, failedTypes)
            }

            // Ensure minimum display time for progress animation
            val elapsed = System.currentTimeMillis() - syncStartTime
            if (elapsed < MIN_SYNC_DISPLAY_MS) {
                kotlinx.coroutines.delay(MIN_SYNC_DISPLAY_MS - elapsed)
            }

            preferencesRepository.updateLastSync(System.currentTimeMillis())
            if (typeResults.isNotEmpty()) {
                preferencesRepository.updateLastSyncResults(gson.toJson(typeResults))
            }
            _syncState.value = SyncState.Done(totalRecords, typeResults, failedTypes)
        } catch (e: CancellationException) {
            // Cancelled state is set by cancel(), just save partial results
            if (typeResults.isNotEmpty()) {
                preferencesRepository.updateLastSyncResults(gson.toJson(typeResults))
            }
            throw e
        } catch (e: Exception) {
            _syncState.value = SyncState.Error(e.message ?: "Sync failed")
        }
    }

    private suspend fun fullSync(
        startTime: Instant,
        endTime: Instant,
        typeResults: MutableList<TypeSyncResult>,
        failedTypes: MutableList<String> = mutableListOf(),
    ): Int {
        val completed = AtomicInteger(0)
        val totalRecordsAtomic = AtomicInteger(0)

        // Pipeline: for each type, read pages into a channel while a sender coroutine
        // uploads them in parallel. This overlaps HC API reads with API server uploads.
        // All types run concurrently — empty types complete instantly.
        coroutineScope {
            RECORD_TYPES.map { type ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val channel = kotlinx.coroutines.channels.Channel<Pair<com.google.gson.JsonElement, Int>>(1)
                        var typeTotal = 0

                        var readerError: Exception? = null

                        // Producer: read pages from Health Connect
                        val reader = launch {
                            try {
                                healthConnectRepository.readRecordsPaged(
                                    type.recordClass, startTime, endTime,
                                ) { page ->
                                    coroutineContext.ensureActive()
                                    val json = healthConnectRepository.recordsToJson(page)
                                    typeTotal += page.size
                                    channel.send(json to page.size)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                readerError = e
                            } finally {
                                channel.close()
                            }
                        }

                        // Consumer: upload pages to API server
                        for ((json, pageSize) in channel) {
                            coroutineContext.ensureActive()
                            apiService.syncRecords(type.name, SyncRequest(json))
                            val synced = totalRecordsAtomic.addAndGet(pageSize)
                            currentRecordCount = synced
                            updateSyncState(SyncState.Syncing(
                                type.name, completed.get(), RECORD_TYPES.size, synced,
                            ))
                        }

                        reader.join()
                        readerError?.let { throw it }

                        if (typeTotal > 0) {
                            android.util.Log.d("Sync", "${type.name}: $typeTotal records")
                            synchronized(typeResults) {
                                typeResults.add(TypeSyncResult(type.name, typeTotal))
                            }
                        }
                    } catch (e: Exception) {
                        // Skip unsupported types (SecurityException = no permission granted by HC)
                        val isUnsupported = e is SecurityException ||
                            e.cause is SecurityException ||
                            e.message?.contains("SecurityException") == true
                        if (!isUnsupported) {
                            android.util.Log.e("Sync", "${type.name} failed: ${e.message}", e)
                            synchronized(failedTypes) { failedTypes.add(type.name) }
                        } else {
                            android.util.Log.d("Sync", "${type.name}: skipped (unsupported)")
                        }
                    }
                    val done = completed.incrementAndGet()
                    updateSyncState(SyncState.Syncing(
                        type.name, done, RECORD_TYPES.size, totalRecordsAtomic.get(),
                    ))
                }
            }.awaitAll()
        }

        try {
            val token = healthConnectRepository.getChangesToken()
            preferencesRepository.updateChangesToken(token)
        } catch (_: Exception) { }

        return totalRecordsAtomic.get()
    }

    private suspend fun deltaSync(
        changesToken: String,
        typeResults: MutableList<TypeSyncResult>,
        failedTypes: MutableList<String> = mutableListOf(),
    ): Int {
        var totalRecords = 0

        val result = healthConnectRepository.getChanges(changesToken)

        if (result.tokenExpired) {
            preferencesRepository.updateChangesToken("")
            val startTime = Instant.now().minusSeconds(29 * 24 * 60 * 60L)
            return fullSync(startTime, Instant.now(), typeResults, failedTypes)
        }

        val completed = AtomicInteger(0)
        val totalRecordsAtomic = AtomicInteger(0)
        val totalTypes = result.upsertedRecords.size

        coroutineScope {
            result.upsertedRecords.map { (typeName, records) ->
                async {
                    if (records.isNotEmpty()) {
                        try {
                            val json = healthConnectRepository.recordsToJson(records)
                            apiService.syncRecords(typeName, SyncRequest(json))
                            totalRecordsAtomic.addAndGet(records.size)
                            currentRecordCount = totalRecordsAtomic.get()
                            synchronized(typeResults) {
                                typeResults.add(TypeSyncResult(typeName, records.size))
                            }
                        } catch (_: Exception) {
                            synchronized(failedTypes) { failedTypes.add(typeName) }
                        }
                    }
                    val done = completed.incrementAndGet()
                    updateSyncState(SyncState.Syncing(
                        typeName, done, totalTypes, totalRecordsAtomic.get(),
                    ))
                }
            }.awaitAll()
        }

        totalRecords = totalRecordsAtomic.get()

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
