package dev.shuchir.hcgateway.data.remote

import dev.shuchir.hcgateway.data.local.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class DynamicBaseUrlInterceptor @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val settings = runBlocking { preferencesRepository.settings.first() }

        if (settings.apiBase.isBlank()) return chain.proceed(original)

        val scheme = if (settings.useHttps) "https" else "http"
        val baseUrl = "$scheme://${settings.apiBase}".toHttpUrlOrNull() ?: return chain.proceed(original)

        val newUrl = original.url.newBuilder()
            .scheme(baseUrl.scheme)
            .host(baseUrl.host)
            .port(baseUrl.port)
            .build()

        return chain.proceed(original.newBuilder().url(newUrl).build())
    }
}
