package dev.shuchir.hcgateway.data.remote

import dev.shuchir.hcgateway.data.local.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Skip auth for login and refresh endpoints
        val path = request.url.encodedPath
        if (path.endsWith("/login") || path.endsWith("/refresh")) {
            return chain.proceed(request)
        }

        val token = runBlocking {
            preferencesRepository.settings.first().token
        }

        val authenticatedRequest = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        return chain.proceed(authenticatedRequest)
    }
}
