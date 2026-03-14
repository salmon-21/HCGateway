package dev.shuchir.hcgateway.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<UserSettings> = dataStore.data.map { prefs ->
        UserSettings(
            token = prefs[UserPreferences.TOKEN] ?: "",
            refreshToken = prefs[UserPreferences.REFRESH_TOKEN] ?: "",
            apiBase = prefs[UserPreferences.API_BASE] ?: "",
            username = prefs[UserPreferences.USERNAME] ?: "",
            themeMode = prefs[UserPreferences.THEME_MODE] ?: "system",
            syncInterval = prefs[UserPreferences.SYNC_INTERVAL] ?: 15,
            fullSyncMode = prefs[UserPreferences.FULL_SYNC_MODE] ?: false,
            lastSync = prefs[UserPreferences.LAST_SYNC] ?: 0L,
            changesToken = prefs[UserPreferences.CHANGES_TOKEN] ?: "",
            sentryEnabled = prefs[UserPreferences.SENTRY_ENABLED] ?: true,
            fcmToken = prefs[UserPreferences.FCM_TOKEN] ?: "",
            useHttps = prefs[UserPreferences.USE_HTTPS] ?: true,
            lastSyncResults = prefs[UserPreferences.LAST_SYNC_RESULTS] ?: "",
            startOnBoot = prefs[UserPreferences.START_ON_BOOT] ?: true,
        )
    }

    val isLoggedIn: Flow<Boolean> = dataStore.data.map { prefs ->
        !prefs[UserPreferences.TOKEN].isNullOrBlank()
    }

    val themeMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[UserPreferences.THEME_MODE] ?: "system"
    }

    val onboardingComplete: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[UserPreferences.ONBOARDING_COMPLETE] ?: false
    }

    suspend fun setOnboardingComplete() {
        dataStore.edit { it[UserPreferences.ONBOARDING_COMPLETE] = true }
    }

    suspend fun saveTokens(token: String, refreshToken: String) {
        dataStore.edit { prefs ->
            prefs[UserPreferences.TOKEN] = token
            prefs[UserPreferences.REFRESH_TOKEN] = refreshToken
        }
    }

    suspend fun saveLoginInfo(apiBase: String, username: String, useHttps: Boolean) {
        dataStore.edit { prefs ->
            prefs[UserPreferences.API_BASE] = apiBase
            prefs[UserPreferences.USERNAME] = username
            prefs[UserPreferences.USE_HTTPS] = useHttps
        }
    }

    suspend fun updateThemeMode(mode: String) {
        dataStore.edit { it[UserPreferences.THEME_MODE] = mode }
    }

    suspend fun updateSyncInterval(minutes: Int) {
        dataStore.edit { it[UserPreferences.SYNC_INTERVAL] = minutes }
    }

    suspend fun updateFullSyncMode(enabled: Boolean) {
        dataStore.edit { it[UserPreferences.FULL_SYNC_MODE] = enabled }
    }

    suspend fun updateLastSync(epochMillis: Long) {
        dataStore.edit { it[UserPreferences.LAST_SYNC] = epochMillis }
    }

    suspend fun updateChangesToken(token: String) {
        dataStore.edit { it[UserPreferences.CHANGES_TOKEN] = token }
    }

    suspend fun updateFcmToken(token: String) {
        dataStore.edit { it[UserPreferences.FCM_TOKEN] = token }
    }

    suspend fun updateLastSyncResults(json: String) {
        dataStore.edit { it[UserPreferences.LAST_SYNC_RESULTS] = json }
    }

    suspend fun updateStartOnBoot(enabled: Boolean) {
        dataStore.edit { it[UserPreferences.START_ON_BOOT] = enabled }
    }

    suspend fun clearSession() {
        dataStore.edit { prefs ->
            prefs.remove(UserPreferences.TOKEN)
            prefs.remove(UserPreferences.REFRESH_TOKEN)
            prefs.remove(UserPreferences.LAST_SYNC)
            prefs.remove(UserPreferences.CHANGES_TOKEN)
            // Keep: API_BASE, USERNAME, USE_HTTPS, THEME_MODE, SYNC_INTERVAL, FULL_SYNC_MODE, FCM_TOKEN, SENTRY_ENABLED
        }
    }
}
