package dev.shuchir.hcgateway.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.shuchir.hcgateway.MainActivity
import dev.shuchir.hcgateway.R
import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.data.repository.SyncRepository
import dev.shuchir.hcgateway.domain.model.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@AndroidEntryPoint
class PersistentSyncService : Service() {

    @Inject lateinit var syncRepository: SyncRepository
    @Inject lateinit var preferencesRepository: PreferencesRepository

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "hcgateway_persistent"
        const val NOTIFICATION_ID = 100
        const val SYNC_RESULT_NOTIFICATION_ID = 101
        const val RESULT_DISMISS_DELAY_MS = 5000L

        fun start(context: Context) {
            val intent = Intent(context, PersistentSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PersistentSyncService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildPersistentNotification("Idle"))
        observeSyncState()
        // Update with actual schedule after startup
        scope.launch {
            val text = getNextSyncText()
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildPersistentNotification(text))
        }
    }

    private fun observeSyncState() {
        scope.launch {
            syncRepository.syncState.collect { state ->
                val manager = getSystemService(NotificationManager::class.java)
                when (state) {
                    is SyncState.Idle -> {
                        val nextSyncText = getNextSyncText()
                        manager.notify(NOTIFICATION_ID, buildPersistentNotification(nextSyncText))
                    }
                    is SyncState.Syncing -> {
                        val text = if (state.typesCompleted > 0) {
                            "${state.typesCompleted}/${state.totalTypes} types"
                        } else "Starting..."
                        manager.notify(NOTIFICATION_ID, buildPersistentNotification(text, state.typesCompleted, state.totalTypes))
                    }
                    is SyncState.Done -> {
                        val nextSyncText = getNextSyncText()
                        manager.notify(NOTIFICATION_ID, buildPersistentNotification(nextSyncText))
                        showResultNotification("Done", "${state.recordCount} records")
                    }
                    is SyncState.Error -> {
                        val nextSyncText = getNextSyncText()
                        manager.notify(NOTIFICATION_ID, buildPersistentNotification(nextSyncText))
                        showResultNotification("Failed", state.message)
                    }
                    is SyncState.Cancelled -> {
                        val nextSyncText = getNextSyncText()
                        manager.notify(NOTIFICATION_ID, buildPersistentNotification(nextSyncText))
                    }
                }
            }
        }
    }

    private suspend fun getNextSyncText(): String {
        val settings = preferencesRepository.settings.first()
        if (!settings.autoSyncEnabled) return "Auto sync off"
        val baseTime = if (settings.lastSync > 0) settings.lastSync else System.currentTimeMillis()
        var nextMillis = baseTime + settings.syncInterval * 60_000L
        val now = System.currentTimeMillis()
        while (nextMillis < now) {
            nextMillis += settings.syncInterval * 60_000L
        }
        val nextTime = Instant.ofEpochMilli(nextMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("H:mm"))
        return "Next sync: $nextTime"
    }

    private fun buildPersistentNotification(
        text: String,
        progress: Int = 0,
        max: Int = 0,
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("HCGateway")
        .setContentText(text)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .apply {
            if (max > 0) setProgress(max, progress, false)
        }
        .build()

    private fun showResultNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, SYNC_RESULT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setTimeoutAfter(RESULT_DISMISS_DELAY_MS)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

        manager.notify(SYNC_RESULT_NOTIFICATION_ID, notification)

        // Fallback: cancel after delay (setTimeoutAfter not supported on all devices)
        scope.launch {
            delay(RESULT_DISMISS_DELAY_MS)
            manager.cancel(SYNC_RESULT_NOTIFICATION_ID)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sync Status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Persistent sync status"
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(SYNC_RESULT_CHANNEL_ID, "Sync Results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Sync completion and error notifications"
                }
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

private const val SYNC_RESULT_CHANNEL_ID = "hcgateway_sync_result"
