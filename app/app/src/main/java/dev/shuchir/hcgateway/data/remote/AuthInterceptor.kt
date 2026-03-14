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

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Skip auth for login and refresh endpoints
        val path = request.url.encodedPath
        if (path.endsWith("/login") || path.endsWith("/refresh")) {
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

            val refreshToken = settingsCache.refreshToken
            if (refreshToken.isBlank()) return response

            val newToken = runBlocking {
                try {
                    val result = apiServiceProvider.get().refresh(RefreshRequest(refreshToken))
                    if (result.isSuccessful && result.body() != null) {
                        val body = result.body()!!
                        preferencesRepository.saveTokens(body.token, body.refresh)
                        body.token
                    } else null
                } catch (_: Exception) { null }
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
