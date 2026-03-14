package dev.shuchir.hcgateway.data.remote

import dev.shuchir.hcgateway.data.local.SettingsCache
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class DynamicBaseUrlInterceptor @Inject constructor(
    private val settingsCache: SettingsCache,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        val apiBase = settingsCache.apiBase
        if (apiBase.isBlank()) return chain.proceed(original)

        val scheme = if (settingsCache.useHttps) "https" else "http"
        val baseUrl = "$scheme://$apiBase".toHttpUrlOrNull() ?: return chain.proceed(original)

        val newUrl = original.url.newBuilder()
            .scheme(baseUrl.scheme)
            .host(baseUrl.host)
            .port(baseUrl.port)
            .build()

        return chain.proceed(original.newBuilder().url(newUrl).build())
    }
}
