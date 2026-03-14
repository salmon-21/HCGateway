package dev.shuchir.hcgateway.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.ui.home.HomeScreen
import dev.shuchir.hcgateway.ui.login.LoginScreen
import dev.shuchir.hcgateway.ui.onboarding.PermissionOnboardingScreen
import dev.shuchir.hcgateway.ui.settings.SettingsScreen
import dev.shuchir.hcgateway.ui.theme.HCGatewayTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class AuthState { Loading, Onboarding, LoggedOut, LoggedIn }

@HiltViewModel
class NavViewModel @Inject constructor(
    preferencesRepository: PreferencesRepository,
) : ViewModel() {

    val authState: StateFlow<AuthState> = combine(
        preferencesRepository.isLoggedIn,
        preferencesRepository.onboardingComplete,
    ) { loggedIn, onboarded ->
        when {
            loggedIn -> AuthState.LoggedIn
            !onboarded -> AuthState.Onboarding
            else -> AuthState.LoggedOut
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AuthState.Loading)

    val themeMode: StateFlow<String> = preferencesRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")
}

@Composable
fun NavGraph(
    viewModel: NavViewModel = hiltViewModel(),
) {
    val authState by viewModel.authState.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()

    HCGatewayTheme(themeMode = themeMode) {
        AnimatedContent(
            targetState = authState,
            transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(300))
            },
            label = "auth",
        ) { state ->
            when (state) {
                AuthState.Loading -> { /* Splash */ }
                AuthState.Onboarding -> PermissionOnboardingScreen(onNext = { /* state updates automatically */ })
                AuthState.LoggedOut -> LoginScreen()
                AuthState.LoggedIn -> AuthenticatedNavGraph()
            }
        }
    }
}

private val enterTransition: EnterTransition =
    slideInHorizontally(tween(300)) { it / 4 } + fadeIn(tween(300))
private val exitTransition: ExitTransition =
    slideOutHorizontally(tween(300)) { -it / 4 } + fadeOut(tween(300))
private val popEnterTransition: EnterTransition =
    slideInHorizontally(tween(300)) { -it / 4 } + fadeIn(tween(300))
private val popExitTransition: ExitTransition =
    slideOutHorizontally(tween(300)) { it / 4 } + fadeOut(tween(300))

@Composable
private fun AuthenticatedNavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home",
        enterTransition = { enterTransition },
        exitTransition = { exitTransition },
        popEnterTransition = { popEnterTransition },
        popExitTransition = { popExitTransition },
    ) {
        composable("home") {
            HomeScreen(
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToPermissions = { navController.navigate("permissions") },
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable("permissions") {
            PermissionOnboardingScreen(
                onNext = { navController.popBackStack() },
            )
        }
    }
}
