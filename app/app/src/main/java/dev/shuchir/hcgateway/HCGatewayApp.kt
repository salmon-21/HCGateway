package dev.shuchir.hcgateway

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.worker.SyncNotificationManager
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class HCGatewayApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var preferencesRepository: PreferencesRepository
    @Inject lateinit var syncNotificationManager: SyncNotificationManager

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("App process started")
        initSentry()
        initThemeMode()
        startNotificationIfLoggedIn()
    }

    private fun initThemeMode() {
        CoroutineScope(Dispatchers.IO).launch {
            val mode = preferencesRepository.themeMode.first()
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                dev.shuchir.hcgateway.ui.theme.themeModeToNightMode(mode)
            )
        }
    }

    // Sentry auto-init is disabled in the manifest; we init here only when the
    // user opts in, so no telemetry (errors or sessions) leaves the device
    // unless reporting is enabled. DSN and other defaults come from the manifest.
    private fun initSentry() {
        CoroutineScope(Dispatchers.IO).launch {
            if (!preferencesRepository.settings.first().sentryEnabled) return@launch
            SentryAndroid.init(this@HCGatewayApp)
        }
    }

    private fun startNotificationIfLoggedIn() {
        CoroutineScope(Dispatchers.IO).launch {
            val settings = preferencesRepository.settings.first()
            if (settings.token.isNotBlank()) {
                syncNotificationManager.start()
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
