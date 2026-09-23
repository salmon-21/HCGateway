package dev.shuchir.hcgateway.data.remote

import dev.shuchir.hcgateway.data.local.SettingsCache
import dev.shuchir.hcgateway.data.repository.AuthRepository
import dev.shuchir.hcgateway.data.repository.RefreshResult
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(
    private val settingsCache: SettingsCache,
    private val authRepositoryProvider: dagger.Lazy<AuthRepository>,
) : Interceptor {

    private val refreshLock = Any()

    // The access token the last refresh replaced, and its replacement (null if the
    // refresh token was rejected). SettingsCache trails DataStore, so a request
    // queued behind that refresh may still see the old values; this answers it
    // without another round trip. Guarded by refreshLock.
    private var lastRefresh: Pair<String, String?>? = null

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

            val newToken = synchronized(refreshLock) { refreshLocked(token) }
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

    private fun refreshLocked(staleToken: String): String? {
        lastRefresh?.let { (replaced, replacement) -> if (replaced == staleToken) return replacement }

        // Refreshed elsewhere (e.g. HomeViewModel) since this request went out.
        settingsCache.token.let { if (it != staleToken && it.isNotBlank()) return it }

        // A rejected refresh token is discarded from storage, so blank covers it.
        val refreshToken = settingsCache.refreshToken
        if (refreshToken.isBlank()) return null

        return when (val result = runBlocking { authRepositoryProvider.get().refreshSession(refreshToken) }) {
            is RefreshResult.Refreshed -> result.token.also { lastRefresh = staleToken to it }
            RefreshResult.Rejected -> null.also { lastRefresh = staleToken to null }
            RefreshResult.Failed -> null
        }
    }
}
