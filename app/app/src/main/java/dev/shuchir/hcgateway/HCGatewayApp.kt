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
                when (mode) {
                    "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }

    private fun initSentry() {
        val dsn = applicationInfo.metaData?.getString("io.sentry.dsn")
        if (!dsn.isNullOrBlank()) {
            SentryAndroid.init(this) { options ->
                options.dsn = dsn
                options.tracesSampleRate = 1.0
            }
        }
    }

    private fun startServiceIfLoggedIn() {
        CoroutineScope(Dispatchers.IO).launch {
            val loggedIn = preferencesRepository.isLoggedIn.first()
            if (loggedIn) {
                PersistentSyncService.start(this@HCGatewayApp)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
