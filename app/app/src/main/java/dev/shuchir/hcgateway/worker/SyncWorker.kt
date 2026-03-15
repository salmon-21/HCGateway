package dev.shuchir.hcgateway.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.shuchir.hcgateway.R
import dev.shuchir.hcgateway.data.repository.SyncRepository
import dev.shuchir.hcgateway.domain.model.SyncState

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncRepository: SyncRepository,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "hcgateway_sync"
        const val NOTIFICATION_ID = 1
        const val RESULT_NOTIFICATION_ID = 2
    }

    override suspend fun doWork(): Result {
        createNotificationChannel()
        try {
            setForeground(createProgressInfo("Syncing health data..."))
        } catch (_: Exception) {
            // Foreground may fail if another foreground service is running
        }

        return try {
            syncRepository.sync()

            when (val finalState = syncRepository.syncState.value) {
                is SyncState.Done -> showResultNotification("Sync complete", "${finalState.recordCount} records synced")
                is SyncState.Error -> showResultNotification("Sync failed", finalState.message)
                else -> {}
            }

            Result.success()
        } catch (e: Exception) {
            showResultNotification("Sync failed", e.message ?: "Unknown error")
            Result.retry()
        }
    }

    private fun createProgressInfo(text: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("HCGateway")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun showResultNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .build()

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sync",
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
