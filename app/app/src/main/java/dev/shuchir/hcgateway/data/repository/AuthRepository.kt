package dev.shuchir.hcgateway.data.repository

import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.data.remote.ApiService
import dev.shuchir.hcgateway.data.remote.LoginRequest
import dev.shuchir.hcgateway.data.remote.RefreshRequest
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
    ): Result<Unit> = try {
        // Save API base temporarily so DynamicBaseUrlInterceptor can route the request
        preferencesRepository.saveLoginInfo(apiBase, username, useHttps)

        val response = apiService.login(LoginRequest(username, password))
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

    /**
     * Trade [refreshToken] for a fresh session, persisting the new tokens. A
     * token the server rejects (403: unknown to it) is discarded from storage,
     * so nothing retries it — across process restarts too — until a new login.
     */
    suspend fun refreshSession(refreshToken: String): RefreshResult = try {
        val response = apiService.refresh(RefreshRequest(refreshToken))
        val body = response.body()
        when {
            response.isSuccessful && body != null -> {
                preferencesRepository.saveTokens(body.token, body.refresh)
                RefreshResult.Refreshed(body.token)
            }
            response.code() == 403 -> {
                preferencesRepository.discardRefreshToken(refreshToken)
                RefreshResult.Rejected
            }
            else -> RefreshResult.Failed
        }
    } catch (e: Exception) {
        RefreshResult.Failed
    }

    suspend fun logout() {
        preferencesRepository.clearSession()
    }
}

sealed interface RefreshResult {
    data class Refreshed(val token: String) : RefreshResult
    /** The server doesn't know the refresh token; only a new login helps. */
    data object Rejected : RefreshResult
    /** Transient (network, 5xx); worth retrying later. */
    data object Failed : RefreshResult
}
