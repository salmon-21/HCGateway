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
import kotlinx.coroutines.flow.first

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncRepository: SyncRepository,
    private val preferencesRepository: dev.shuchir.hcgateway.data.local.PreferencesRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Skip if last sync was too recent (prevents duplicate sync on foreground resume)
        val settings = preferencesRepository.settings.first()
        if (settings.lastSync > 0) {
            val elapsed = System.currentTimeMillis() - settings.lastSync
            val minInterval = settings.syncInterval * 60_000L * 3 / 4 // 75% of interval
            if (elapsed < minInterval) {
                android.util.Log.d(TAG, "Skipped: ${elapsed / 1000}s since last sync (min ${minInterval / 1000}s)")
                return Result.success()
            }
        }

        android.util.Log.d(TAG, "Starting sync (interval=${settings.syncInterval}min)")
        createNotificationChannel()
        try {
            setForeground(createProgressInfo("Syncing health data..."))
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to set foreground: ${e.message}")
        }

        return try {
            syncRepository.sync()
            android.util.Log.d(TAG, "Sync completed")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Sync failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val CHANNEL_ID = "hcgateway_sync"
        const val NOTIFICATION_ID = 1
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
