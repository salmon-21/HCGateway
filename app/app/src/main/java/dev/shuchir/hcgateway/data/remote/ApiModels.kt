package dev.shuchir.hcgateway.data.remote

import com.google.gson.JsonElement

data class LoginRequest(
    val username: String,
    val password: String,
    val fcm: String = "",
)

data class LoginResponse(
    val token: String,
    val refresh: String,
)

data class RefreshRequest(
    val refresh: String,
)

data class RefreshResponse(
    val token: String,
    val refresh: String,
)

data class SyncRequest(
    val data: JsonElement,
)

data class DeleteRequest(
    val uuid: List<String>,
)
