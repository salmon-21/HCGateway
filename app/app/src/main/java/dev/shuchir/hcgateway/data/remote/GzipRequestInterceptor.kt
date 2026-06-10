package dev.shuchir.hcgateway.data.remote

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.GzipSink
import okio.buffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gzips outgoing request bodies. Sync pages are ~100 KB of JSON that compress
 * several-fold, which matters on mobile data; the API inflates them in
 * GzipRequestMiddleware (api/main.py) — deploy the server before shipping this.
 *
 * Bodies under [MIN_GZIP_BYTES] are sent as-is: gzip overhead can grow tiny
 * payloads (login, refresh) and saves nothing.
 */
@Singleton
class GzipRequestInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val body = original.body
        if (body == null ||
            original.header("Content-Encoding") != null ||
            (body.contentLength() in 0 until MIN_GZIP_BYTES)
        ) {
            return chain.proceed(original)
        }
        return chain.proceed(original.newBuilder()
            .header("Content-Encoding", "gzip")
            .method(original.method, gzipped(original))
            .build())
    }

    // Buffer the compressed bytes so Content-Length stays known (no chunked
    // transfer encoding). Compressed pages are tens of KB — bounded memory.
    private fun gzipped(request: Request): okhttp3.RequestBody {
        val body = request.body!!
        val buffer = Buffer()
        GzipSink(buffer).buffer().use { body.writeTo(it) }
        return buffer.readByteArray().toRequestBody(body.contentType())
    }

    private companion object {
        const val MIN_GZIP_BYTES = 1024L
    }
}
