package dev.shuchir.hcgateway

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.worker.PersistentSyncService
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class HCGatewayApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var preferencesRepository: PreferencesRepository

    override fun onCreate() {
        super.onCreate()
        initSentry()
        initThemeMode()
        startServiceIfLoggedIn()
    }

    private fun initThemeMode() {
        CoroutineScope(Dispatchers.IO).launch {
            val mode = preferencesRepository.themeMode.first()
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                dev.shuchir.hcgateway.ui.theme.themeModeToNightMode(mode)
            )
        }
    }

    private fun initSentry() {
        CoroutineScope(Dispatchers.IO).launch {
            val enabled = preferencesRepository.settings.first().sentryEnabled
            val dsn = applicationInfo.metaData?.getString("io.sentry.dsn")
            if (enabled && !dsn.isNullOrBlank()) {
                SentryAndroid.init(this@HCGatewayApp) { options ->
                    options.dsn = dsn
                    options.tracesSampleRate = 1.0
                }
            }
        }
    }

    private fun startServiceIfLoggedIn() {
        CoroutineScope(Dispatchers.IO).launch {
            val settings = preferencesRepository.settings.first()
            if (settings.token.isNotBlank()) {
                PersistentSyncService.start(this@HCGatewayApp)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
