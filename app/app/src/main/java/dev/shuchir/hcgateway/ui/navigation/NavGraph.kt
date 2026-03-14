package dev.shuchir.hcgateway.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import dev.shuchir.hcgateway.ui.settings.LicensesScreen
import dev.shuchir.hcgateway.ui.settings.SettingsScreen
import dev.shuchir.hcgateway.ui.theme.HCGatewayTheme
import dev.shuchir.hcgateway.worker.PersistentSyncService
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
    val context = LocalContext.current

    // Start/stop persistent service based on auth state
    LaunchedEffect(authState) {
        when (authState) {
            AuthState.LoggedIn -> {
                try {
                    PersistentSyncService.start(context)
                } catch (e: Exception) {
                    android.util.Log.e("NavGraph", "Failed to start service", e)
                }
            }
            AuthState.LoggedOut -> PersistentSyncService.stop(context)
            else -> {}
        }
    }

    HCGatewayTheme(themeMode = themeMode) {
        AnimatedContent(
            targetState = authState,
            transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(300))
            },
            label = "auth",
        ) { state ->
            when (state) {
                AuthState.Loading -> {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                }
                AuthState.Onboarding -> PermissionOnboardingScreen(onNext = { /* state updates automatically */ })
                AuthState.LoggedOut -> LoginScreen()
                AuthState.LoggedIn -> AuthenticatedNavGraph()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AuthenticatedNavGraph() {
    val navController = rememberNavController()
    val spatialSpec = MaterialTheme.motionScheme.slowSpatialSpec<IntOffset>()
    val effectsSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    val popSpatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()

    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = Modifier.background(MaterialTheme.colorScheme.surface),
        enterTransition = { slideInHorizontally(spatialSpec) { it / 4 } + fadeIn(effectsSpec) },
        exitTransition = { slideOutHorizontally(spatialSpec) { -it / 4 } + fadeOut(effectsSpec) },
        popEnterTransition = { slideInHorizontally(popSpatialSpec) { -it / 4 } + fadeIn(effectsSpec) },
        popExitTransition = { slideOutHorizontally(popSpatialSpec) { it / 4 } + fadeOut(effectsSpec) },
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
                onNavigateToLicenses = { navController.navigate("licenses") },
            )
        }
        composable("licenses") {
            LicensesScreen(onBack = { navController.popBackStack() })
        }
        composable("permissions") {
            PermissionOnboardingScreen(
                onNext = { navController.popBackStack() },
            )
        }
    }
}
