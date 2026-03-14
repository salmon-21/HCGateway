package dev.shuchir.hcgateway

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.sentry.android.core.SentryAndroid
import javax.inject.Inject

@HiltAndroidApp
class HCGatewayApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        initSentry()
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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
