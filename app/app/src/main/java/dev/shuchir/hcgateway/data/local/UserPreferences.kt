package dev.shuchir.hcgateway.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object UserPreferences {
    val TOKEN = stringPreferencesKey("token")
    val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    val API_BASE = stringPreferencesKey("api_base")
    val USERNAME = stringPreferencesKey("username")
    val THEME_MODE = stringPreferencesKey("theme_mode") // "light", "dark", "system"
    val SYNC_INTERVAL = intPreferencesKey("sync_interval") // minutes
    val FULL_SYNC_MODE = booleanPreferencesKey("full_sync_mode")
    val LAST_SYNC = longPreferencesKey("last_sync") // epoch millis
    val CHANGES_TOKEN = stringPreferencesKey("changes_token")
    val SENTRY_ENABLED = booleanPreferencesKey("sentry_enabled")
    val FCM_TOKEN = stringPreferencesKey("fcm_token")
    val USE_HTTPS = booleanPreferencesKey("use_https")
    val LAST_SYNC_RESULTS = stringPreferencesKey("last_sync_results") // JSON: [{"typeName":"Steps","recordCount":38},...]
    val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
}

data class UserSettings(
    val token: String = "",
    val refreshToken: String = "",
    val apiBase: String = "",
    val username: String = "",
    val themeMode: String = "system",
    val syncInterval: Int = 15,
    val fullSyncMode: Boolean = false,
    val lastSync: Long = 0L,
    val changesToken: String = "",
    val sentryEnabled: Boolean = true,
    val fcmToken: String = "",
    val useHttps: Boolean = true,
    val lastSyncResults: String = "", // JSON
    val startOnBoot: Boolean = true,
)
