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
import dev.shuchir.hcgateway.data.repository.SyncRepository
import dev.shuchir.hcgateway.domain.model.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PersistentSyncService : Service() {

    @Inject lateinit var syncRepository: SyncRepository

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "hcgateway_persistent"
        const val NOTIFICATION_ID = 100
        const val SYNC_RESULT_NOTIFICATION_ID = 101

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
    }

    private fun observeSyncState() {
        scope.launch {
            syncRepository.syncState.collect { state ->
                val manager = getSystemService(NotificationManager::class.java)
                when (state) {
                    is SyncState.Idle -> {
                        manager.notify(NOTIFICATION_ID, buildPersistentNotification("Waiting for next sync"))
                    }
                    is SyncState.Syncing -> {
                        val text = if (state.currentType.isNotBlank()) {
                            "Syncing ${state.currentType} (${state.typesCompleted}/${state.totalTypes})"
                        } else "Starting sync..."
                        manager.notify(NOTIFICATION_ID, buildPersistentNotification(text, state.typesCompleted, state.totalTypes))
                    }
                    is SyncState.Done -> {
                        manager.notify(NOTIFICATION_ID, buildPersistentNotification("Waiting for next sync"))
                        showResultNotification("Sync complete", "${state.recordCount} records synced")
                    }
                    is SyncState.Error -> {
                        manager.notify(NOTIFICATION_ID, buildPersistentNotification("Waiting for next sync"))
                        showResultNotification("Sync failed", state.message)
                    }
                }
            }
        }
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
        val notification = NotificationCompat.Builder(this, SYNC_RESULT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(SYNC_RESULT_NOTIFICATION_ID, notification)
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
