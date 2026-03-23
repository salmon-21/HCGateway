package dev.shuchir.hcgateway.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.shuchir.hcgateway.data.local.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var preferencesRepository: PreferencesRepository
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var syncNotificationManager: SyncNotificationManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = preferencesRepository.settings.first()
                if (settings.token.isNotBlank() && settings.startOnBoot) {
                    if (settings.autoSyncEnabled) {
                        syncScheduler.schedule(settings.syncInterval)
                    }
                    syncNotificationManager.start()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
