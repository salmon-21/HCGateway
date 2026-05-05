package dev.shuchir.hcgateway.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.health.connect.client.HealthConnectClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isBatteryOptimized(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations() {
        tryStart(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
    }

    fun openHealthConnectAppSettings() {
        tryStart(
            Intent(ACTION_MANAGE_HEALTH_PERMISSIONS).apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
            },
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS),
        )
    }

    private fun tryStart(primary: Intent, fallback: Intent) {
        primary.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(primary)
        } catch (_: Exception) {
            try { context.startActivity(fallback) } catch (_: Exception) { }
        }
    }

    companion object {
        private const val ACTION_MANAGE_HEALTH_PERMISSIONS =
            "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS"
    }
}
