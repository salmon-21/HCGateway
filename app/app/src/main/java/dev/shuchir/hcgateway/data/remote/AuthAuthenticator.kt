package dev.shuchir.hcgateway.data.remote

import dev.shuchir.hcgateway.data.local.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject

class AuthAuthenticator @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val apiServiceProvider: dagger.Lazy<ApiService>,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Only retry once
        if (response.request.header("X-Retry") != null) return null

        val refreshToken = runBlocking {
            preferencesRepository.settings.first().refreshToken
        }

        if (refreshToken.isBlank()) return null

        val refreshResponse = runBlocking {
            try {
                val result = apiServiceProvider.get().refresh(RefreshRequest(refreshToken))
                if (result.isSuccessful) result.body() else null
            } catch (e: Exception) {
                null
            }
        }

        return if (refreshResponse != null) {
            runBlocking {
                preferencesRepository.saveTokens(refreshResponse.token, refreshResponse.refresh)
            }
            response.request.newBuilder()
                .header("Authorization", "Bearer ${refreshResponse.token}")
                .header("X-Retry", "true")
                .build()
        } else {
            null
        }
    }
}
