package dev.shuchir.hcgateway.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.google.gson.Gson
import com.google.gson.JsonElement
import dev.shuchir.hcgateway.domain.model.RECORD_TYPES
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

@Singleton
class HealthConnectRepository @Inject constructor(
    private val healthConnectClient: HealthConnectClient?,
    private val gson: Gson,
) {
    val isAvailable: Boolean get() = healthConnectClient != null

    fun buildPermissions(): Set<String> {
        return RECORD_TYPES.flatMap { type ->
            listOf(
                HealthPermission.getReadPermission(type.recordClass),
                HealthPermission.getWritePermission(type.recordClass),
            )
        }.toSet()
    }

    suspend fun hasAllPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        return buildPermissions().all { it in granted }
    }

    suspend fun readRecords(
        recordClass: KClass<out Record>,
        startTime: Instant,
        endTime: Instant,
    ): List<Record> {
        val client = healthConnectClient ?: return emptyList()
        val allRecords = mutableListOf<Record>()
        var pageToken: String? = null

        do {
            val request = ReadRecordsRequest(
                recordType = recordClass,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                pageToken = pageToken,
            )
            val response = client.readRecords(request)
            allRecords.addAll(response.records)
            pageToken = response.pageToken
        } while (pageToken != null)

        return allRecords
    }

    fun recordsToJson(records: List<Record>): JsonElement {
        return RecordSerializer.serializeRecords(records)
    }

    suspend fun getChangesToken(): String {
        val client = healthConnectClient ?: throw IllegalStateException("Health Connect not available")
        val request = ChangesTokenRequest(
            recordTypes = RECORD_TYPES.map { it.recordClass }.toSet(),
        )
        return client.getChangesToken(request)
    }

    data class ChangeResult(
        val upsertedRecords: Map<String, List<Record>>,
        val deletedIds: Map<String, List<String>>,
        val nextToken: String,
        val hasMore: Boolean,
        val tokenExpired: Boolean,
    )

    suspend fun getChanges(token: String): ChangeResult {
        val client = healthConnectClient ?: throw IllegalStateException("Health Connect not available")
        val upserted = mutableMapOf<String, MutableList<Record>>()
        val deleted = mutableMapOf<String, MutableList<String>>()

        var currentToken = token
        var hasMore = true

        try {
            while (hasMore) {
                val response = client.getChanges(currentToken)
                for (change in response.changes) {
                    when (change) {
                        is UpsertionChange -> {
                            val record = change.record
                            val typeName = recordClassName(record::class)
                            upserted.getOrPut(typeName) { mutableListOf() }.add(record)
                        }
                        is DeletionChange -> {
                            // DeletionChange doesn't carry type info — skip for now
                        }
                    }
                }
                hasMore = response.hasMore
                currentToken = response.nextChangesToken
            }
        } catch (e: Exception) {
            if (e.message?.contains("token", ignoreCase = true) == true) {
                return ChangeResult(emptyMap(), emptyMap(), "", false, tokenExpired = true)
            }
            throw e
        }

        return ChangeResult(upserted, deleted, currentToken, false, tokenExpired = false)
    }

    suspend fun insertRecords(records: List<Record>) {
        healthConnectClient?.insertRecords(records)
    }

    suspend fun deleteRecordsByIds(recordClass: KClass<out Record>, ids: List<String>) {
        healthConnectClient?.deleteRecords(
            recordType = recordClass,
            recordIdsList = ids,
            clientRecordIdsList = ids,
        )
    }

    private fun recordClassName(kClass: KClass<out Record>): String {
        return RECORD_TYPES.find { it.recordClass == kClass }?.name ?: kClass.simpleName ?: "Unknown"
    }
}
