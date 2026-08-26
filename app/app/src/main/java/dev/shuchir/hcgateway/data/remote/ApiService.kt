package dev.shuchir.hcgateway.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Paths the API serves without a token, mirroring the `before_request` exemption
 * list in api/apiVersions/v2/routes.py. Kept beside the endpoint declarations so
 * adding one here is the same edit as adding the call.
 */
val UNAUTHENTICATED_PATHS = setOf(
    "/api/v2/login",
    "/api/v2/refresh",
    "/api/v2/health",
    "/api/v2/status",
)

interface ApiService {

    /** Unauthenticated liveness probe — reachability, independent of session state. */
    @GET("api/v2/health")
    suspend fun health(): Response<Unit>

    @POST("api/v2/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("api/v2/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<RefreshResponse>

    @POST("api/v2/sync/{recordType}")
    suspend fun syncRecords(
        @Path("recordType") recordType: String,
        @Body request: SyncRequest,
    ): Response<Unit>

    @HTTP(method = "DELETE", path = "api/v2/sync/{recordType}", hasBody = true)
    suspend fun deleteRecords(
        @Path("recordType") recordType: String,
        @Body request: DeleteRequest,
    ): Response<Unit>

    @GET("api/v2/counts")
    suspend fun getCounts(): Response<Map<String, Int>>
}
