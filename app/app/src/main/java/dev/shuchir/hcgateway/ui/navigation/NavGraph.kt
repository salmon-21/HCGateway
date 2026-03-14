package dev.shuchir.hcgateway.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.ui.home.HomeScreen
import dev.shuchir.hcgateway.ui.login.LoginScreen
import dev.shuchir.hcgateway.ui.theme.HCGatewayTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class NavViewModel @Inject constructor(
    preferencesRepository: PreferencesRepository,
) : ViewModel() {

    val isLoggedIn: StateFlow<Boolean?> = preferencesRepository.isLoggedIn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val themeMode: StateFlow<String> = preferencesRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")
}

@Composable
fun NavGraph(
    viewModel: NavViewModel = hiltViewModel(),
) {
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()

    HCGatewayTheme(themeMode = themeMode) {
        when (isLoggedIn) {
            null -> { /* Loading — splash screen still showing */ }
            false -> LoginScreen()
            true -> HomeScreen()
        }
    }
}
