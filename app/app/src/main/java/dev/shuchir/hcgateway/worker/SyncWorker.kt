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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber

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
                Timber.i("Skipped: ${elapsed / 1000}s since last sync (min ${minInterval / 1000}s)")
                return Result.success()
            }
        }

        Timber.i("Starting sync (interval=${settings.syncInterval}min)")
        createNotificationChannel()
        try {
            setForeground(createProgressInfo("Syncing health data..."))
        } catch (e: Exception) {
            Timber.w(e, "Failed to set foreground")
        }

        return try {
            if (syncRepository.sync()) {
                Timber.i("Sync completed")
                Result.success()
            } else {
                // sync() swallowed a failure (logged it already) and reported it via
                // syncState — ask WorkManager to retry with backoff.
                Result.retry()
            }
        } catch (e: CancellationException) {
            // Normal control flow (user cancelled or WorkManager stopped the job) —
            // rethrow so the cancellation propagates instead of being reported as a crash.
            Timber.i("Sync cancelled")
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Sync failed")
            Result.retry()
        }
    }

    companion object {
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
