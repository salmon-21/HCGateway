package dev.shuchir.hcgateway.data.remote

import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.data.local.SettingsCache
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(
    private val settingsCache: SettingsCache,
    private val preferencesRepository: PreferencesRepository,
    private val apiServiceProvider: dagger.Lazy<ApiService>,
) : Interceptor {

    private val refreshLock = Any()

    // A refresh token the server has rejected. Retrying it would 403 forever, so
    // we stop until a new one arrives (i.e. the user logs in again). Tracking the
    // *last used* token instead would be wrong: /refresh returns the same refresh
    // token it was given, so a success would permanently block the next refresh.
    @Volatile private var failedRefreshToken: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // /health in particular must stay unauthenticated, so a reachability
        // check doesn't fail merely because the session expired.
        if (request.url.encodedPath in UNAUTHENTICATED_PATHS) {
            return chain.proceed(request)
        }

        val token = settingsCache.token

        val authenticatedRequest = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        val response = chain.proceed(authenticatedRequest)

        // Auto-refresh on 403
        if (response.code == 403 && request.header("X-Retry") == null) {
            response.close()

            val newToken = synchronized(refreshLock) {
                // Check if another thread already refreshed
                val currentToken = settingsCache.token
                if (currentToken != token && currentToken.isNotBlank()) {
                    // Token was already refreshed by another request
                    currentToken
                } else {
                    // We need to refresh
                    val refreshToken = settingsCache.refreshToken
                    if (refreshToken.isBlank() || refreshToken == failedRefreshToken) null
                    else {
                        runBlocking {
                            try {
                                val result = apiServiceProvider.get().refresh(RefreshRequest(refreshToken))
                                val body = result.body()
                                if (result.isSuccessful && body != null) {
                                    failedRefreshToken = null
                                    preferencesRepository.saveTokens(body.token, body.refresh)
                                    body.token
                                } else {
                                    // Rejected, not merely unlucky — don't spin on it.
                                    if (result.code() == 403) failedRefreshToken = refreshToken
                                    null
                                }
                            } catch (_: Exception) { null }
                        }
                    }
                }
            }

            if (newToken != null) {
                val retryRequest = request.newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .header("X-Retry", "true")
                    .build()
                return chain.proceed(retryRequest)
            }
        }

        return response
    }
}
