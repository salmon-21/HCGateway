package dev.shuchir.hcgateway.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

data class LoginUiState(
    val serverAddress: String = "",
    val username: String = "",
    val password: String = "",
    val useHttps: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        // Prefill saved login info (not password)
        viewModelScope.launch {
            val settings = preferencesRepository.settings.first()
            _uiState.value = _uiState.value.copy(
                serverAddress = settings.apiBase,
                username = settings.username,
                useHttps = settings.useHttps,
                password = "",
            )
        }
        // Clear password whenever user logs out (token removed)
        viewModelScope.launch {
            preferencesRepository.isLoggedIn.collect { loggedIn ->
                if (!loggedIn) {
                    _uiState.value = _uiState.value.copy(password = "", error = null)
                }
            }
        }
    }

    fun updateServerAddress(value: String) {
        _uiState.value = _uiState.value.copy(serverAddress = value, error = null)
    }

    fun updateUsername(value: String) {
        _uiState.value = _uiState.value.copy(username = value, error = null)
    }

    fun updatePassword(value: String) {
        _uiState.value = _uiState.value.copy(password = value, error = null)
    }

    fun updateUseHttps(value: Boolean) {
        _uiState.value = _uiState.value.copy(useHttps = value, error = null)
    }

    fun login() {
        val state = _uiState.value
        if (state.serverAddress.isBlank() || state.username.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(error = "All fields are required")
            return
        }

        _uiState.value = state.copy(isLoading = true, error = null)

        viewModelScope.launch {
            val fcmToken = try {
                FirebaseMessaging.getInstance().token.await()
            } catch (e: Exception) {
                ""
            }

            val result = authRepository.login(
                apiBase = state.serverAddress,
                useHttps = state.useHttps,
                username = state.username,
                password = state.password,
                fcmToken = fcmToken,
            )

            _uiState.value = if (result.isSuccess) {
                _uiState.value.copy(isLoading = false, password = "")
            } else {
                _uiState.value.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.message ?: "Login failed",
                )
            }
        }
    }
}
