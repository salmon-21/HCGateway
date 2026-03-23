package dev.shuchir.hcgateway.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncRepository: SyncRepository,
    private val preferencesRepository: PreferencesRepository,
) {
    companion object {
        const val CHANNEL_ID = "hcgateway_persistent"
        const val SYNC_RESULT_CHANNEL_ID = "hcgateway_sync_result"
        const val NOTIFICATION_ID = 100
        const val SYNC_RESULT_NOTIFICATION_ID = 101
        const val RESULT_DISMISS_DELAY_MS = 5000L
        const val ACTION_CANCEL_SYNC = "dev.shuchir.hcgateway.CANCEL_SYNC"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun start() {
        createNotificationChannels()
        observeSyncState()
        scope.launch {
            val text = getNextSyncText()
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun observeSyncState() {
        scope.launch {
            var lastNotifyTime = 0L
            syncRepository.syncState.collect { state ->
                when (state) {
                    is SyncState.Idle -> {
                        val text = getNextSyncText()
                        manager.notify(NOTIFICATION_ID, buildNotification(text))
                    }
                    is SyncState.Syncing -> {
                        val now = System.currentTimeMillis()
                        if (now - lastNotifyTime < 1000) return@collect
                        lastNotifyTime = now
                        val text = when {
                            state.recordsSynced > 0 -> "${state.typesCompleted}/${state.totalTypes} types · ${state.recordsSynced} records"
                            state.typesCompleted > 0 -> "${state.typesCompleted}/${state.totalTypes} types"
                            else -> "Starting..."
                        }
                        manager.notify(NOTIFICATION_ID, buildNotification(text, state.typesCompleted, state.totalTypes, showCancel = true))
                    }
                    is SyncState.Done -> {
                        val nextText = getNextSyncText()
                        manager.notify(NOTIFICATION_ID, buildNotification(nextText))
                        val resultText = if (state.failedTypes.isNotEmpty()) {
                            "${state.recordCount} records, ${state.failedTypes.size} failed"
                        } else {
                            "${state.recordCount} records"
                        }
                        showResultNotification("Done", resultText)
                    }
                    is SyncState.Error -> {
                        val nextText = getNextSyncText()
                        manager.notify(NOTIFICATION_ID, buildNotification(nextText))
                        showResultNotification("Failed", state.message)
                    }
                    is SyncState.Cancelled -> {
                        val nextText = getNextSyncText()
                        manager.notify(NOTIFICATION_ID, buildNotification(nextText))
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

    fun buildNotification(
        text: String,
        progress: Int = 0,
        max: Int = 0,
        showCancel: Boolean = false,
    ) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle("HCGateway")
        .setContentText(text)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .apply {
            if (max > 0) setProgress(max, progress, false)
            if (showCancel) {
                val cancelIntent = Intent(ACTION_CANCEL_SYNC).apply {
                    setPackage(context.packageName)
                }
                val cancelPending = PendingIntent.getBroadcast(
                    context, 1, cancelIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                addAction(0, "Cancel", cancelPending)
            }
        }
        .build()

    private fun showResultNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(context, SYNC_RESULT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setTimeoutAfter(RESULT_DISMISS_DELAY_MS)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

        manager.notify(SYNC_RESULT_NOTIFICATION_ID, notification)
        scope.launch {
            delay(RESULT_DISMISS_DELAY_MS)
            manager.cancel(SYNC_RESULT_NOTIFICATION_ID)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

    fun dismiss() {
        manager.cancel(NOTIFICATION_ID)
    }
}

@AndroidEntryPoint
class CancelSyncReceiver : BroadcastReceiver() {
    @Inject lateinit var syncRepository: SyncRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SyncNotificationManager.ACTION_CANCEL_SYNC) {
            syncRepository.cancel()
        }
    }
}
