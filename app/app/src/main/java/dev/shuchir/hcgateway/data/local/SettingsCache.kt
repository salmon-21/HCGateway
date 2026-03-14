package dev.shuchir.hcgateway.data.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsCache @Inject constructor(
    preferencesRepository: PreferencesRepository,
) {
    @Volatile var token: String = ""
        private set
    @Volatile var refreshToken: String = ""
        private set
    @Volatile var apiBase: String = ""
        private set
    @Volatile var useHttps: Boolean = true
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            preferencesRepository.settings.collect { s ->
                token = s.token
                refreshToken = s.refreshToken
                apiBase = s.apiBase
                useHttps = s.useHttps
            }
        }
    }
}
