package dev.shuchir.hcgateway.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.AndroidEntryPoint
import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.data.repository.HealthConnectRepository
import dev.shuchir.hcgateway.data.repository.SyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class HCGatewayFirebaseService : FirebaseMessagingService() {

    @Inject lateinit var preferencesRepository: PreferencesRepository
    @Inject lateinit var healthConnectRepository: HealthConnectRepository
    @Inject lateinit var syncRepository: SyncRepository
    @Inject lateinit var gson: Gson

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNewToken(token: String) {
        scope.launch {
            preferencesRepository.updateFcmToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val action = message.data["action"] ?: return
        val payload = message.data["data"] ?: return

        scope.launch {
            when (action) {
                "PUSH" -> handlePush(payload)
                "DEL" -> handleDelete(payload)
            }
        }
    }

    private suspend fun handlePush(payload: String) {
        try {
            val records = gson.fromJson<List<Map<String, Any>>>(
                payload,
                object : TypeToken<List<Map<String, Any>>>() {}.type
            )
            // Insert records via Health Connect
            // Note: actual record deserialization would need to map to HC Record types
        } catch (e: Exception) {
            // Post error notification
        }
    }

    private suspend fun handleDelete(payload: String) {
        try {
            val data = gson.fromJson(payload, DeletePayload::class.java)
            syncRepository.deleteRecords(data.recordType, data.uuids)
        } catch (e: Exception) {
            // Post error notification
        }
    }

    private data class DeletePayload(
        val recordType: String,
        val uuids: List<String>,
    )
}
