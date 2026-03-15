package dev.shuchir.hcgateway.data.repository

import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.data.remote.ApiService
import dev.shuchir.hcgateway.data.remote.LoginRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val apiService: ApiService,
    private val preferencesRepository: PreferencesRepository,
) {
    suspend fun login(
        apiBase: String,
        useHttps: Boolean,
        username: String,
        password: String,
        fcmToken: String,
    ): Result<Unit> = try {
        // Save API base temporarily so DynamicBaseUrlInterceptor can route the request
        preferencesRepository.saveLoginInfo(apiBase, username, useHttps)

        val response = apiService.login(LoginRequest(username, password, fcmToken))
        if (response.isSuccessful && response.body() != null) {
            val body = response.body()!!
            preferencesRepository.saveTokens(body.token, body.refresh)
            Result.success(Unit)
        } else {
            Result.failure(Exception("Login failed: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun logout() {
        preferencesRepository.clearSession()
    }
}
