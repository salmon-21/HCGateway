package dev.shuchir.hcgateway.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import soup.compose.material.motion.animation.materialSharedAxisXIn
import soup.compose.material.motion.animation.materialSharedAxisXOut
import soup.compose.material.motion.animation.rememberSlideDistance
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.coroutines.flow.MutableStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.ui.home.HomeScreen
import dev.shuchir.hcgateway.ui.login.LoginScreen
import dev.shuchir.hcgateway.ui.onboarding.PermissionOnboardingScreen
import dev.shuchir.hcgateway.ui.settings.LicensesScreen
import dev.shuchir.hcgateway.ui.settings.SettingsScreen
import dev.shuchir.hcgateway.ui.theme.HCGatewayTheme
import dev.shuchir.hcgateway.worker.SyncNotificationManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.pow

enum class AuthState { Loading, Onboarding, LoggedOut, LoggedIn }

private val DecelerateCubic = Easing { 1 - (1 - it).pow(3) }

/** Back-gesture easing: runs [curve] to completion within the first [lead] of the gesture. */
private fun backGestureEasing(lead: Float, curve: Easing = DecelerateCubic) =
    Easing { curve.transform((it / lead).coerceAtMost(1f)) }

// The leaving page eases off the mark (gentle first movement) as it slides, and
// has faded out well before it has finished moving.
private val PageLeavingSlide = backGestureEasing(lead = 0.1f, curve = CubicBezierEasing(0.85f, 0f, 0.15f, 1f))
private val PageLeaving = backGestureEasing(lead = 0.1f)
private val PageLeavingFade = backGestureEasing(lead = 0.07f)
private val PageRevealed = backGestureEasing(lead = 0.04f)
private val PageRevealedSlide = backGestureEasing(lead = 0.1f)

// How short of its resting place a page being revealed by a back gesture stays:
// offset left by this fraction of its width, and scaled down by it too. The
// transition can't hold it there — a fully swiped gesture seeks it to the end —
// so page() applies it separately.
private const val REVEALED_SHORTFALL = 0.1f

@HiltViewModel
class NavViewModel @Inject constructor(
    preferencesRepository: PreferencesRepository,
    val syncNotificationManager: SyncNotificationManager,
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

}

@Composable
fun NavGraph(
    viewModel: NavViewModel = hiltViewModel(),
) {
    val authState by viewModel.authState.collectAsState()
    val context = LocalContext.current

    // Start/stop notification based on auth state
    LaunchedEffect(authState) {
        when (authState) {
            AuthState.LoggedIn -> viewModel.syncNotificationManager.start()
            AuthState.LoggedOut -> viewModel.syncNotificationManager.dismiss()
            else -> {}
        }
    }

    HCGatewayTheme {
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

@Composable
private fun AuthenticatedNavGraph() {
    val navController = rememberNavController()
    val slideDistance = rememberSlideDistance()

    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = Modifier.background(MaterialTheme.colorScheme.surface),
        enterTransition = { materialSharedAxisXIn(forward = true, slideDistance = slideDistance) },
        exitTransition = { materialSharedAxisXOut(forward = true, slideDistance = slideDistance) },
        popEnterTransition = { materialSharedAxisXIn(forward = false, slideDistance = slideDistance) },
        popExitTransition = { materialSharedAxisXOut(forward = false, slideDistance = slideDistance) },
        // Back gesture: the page shrinks and fades as it slides off to the right
        // (from either edge), while the previous page slides in from the left.
        // The transition plays out in the first sliver of the swipe. Past that
        // the previous page is shown dimmed and short of its place (see page())
        // until the gesture commits or cancels.
        predictivePopEnterTransition = {
            slideInHorizontally(tween(300, easing = PageRevealedSlide)) { -it * 15 / 100 } +
                scaleIn(tween(300, easing = PageRevealed), initialScale = 0.9f) +
                fadeIn(tween(300, easing = PageRevealed))
        },
        predictivePopExitTransition = {
            slideOutHorizontally(tween(300, easing = PageLeavingSlide)) { it } +
                scaleOut(tween(300, easing = PageLeaving), targetScale = 0.8f) +
                fadeOut(tween(300, easing = PageLeavingFade))
        },
    ) {
        page("home", navController) {
            HomeScreen(
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToPermissions = { navController.navigate("permissions") },
            )
        }
        page("settings", navController) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToLicenses = { navController.navigate("licenses") },
            )
        }
        page("licenses", navController) {
            LicensesScreen(onBack = { navController.popBackStack() })
        }
        page("permissions", navController) {
            PermissionOnboardingScreen(
                onNext = { navController.popBackStack() },
            )
        }
    }
}

/**
 * A destination that, while a back gesture is revealing it, stays dimmed and
 * [REVEALED_SHORTFALL] short of its place. During the gesture the page being
 * left is still the current entry, so any other page on screen is the one being
 * revealed; once the gesture commits it becomes current and both settle.
 */
private fun NavGraphBuilder.page(
    route: String,
    navController: NavHostController,
    content: @Composable () -> Unit,
) = composable(route) { entry ->
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val gesture by (dispatcher?.transitionState ?: IdleTransition).collectAsState()
    val current by navController.currentBackStackEntryAsState()
    val revealed = gesture is NavigationEventTransitionState.InProgress && current?.id != entry.id
    val scrim by animateFloatAsState(if (revealed) 0.45f else 0f, label = "backScrim")
    // Snap into the held-back position (the page isn't visible yet when the
    // gesture starts); glide home on commit.
    val shortfall by animateFloatAsState(
        targetValue = if (revealed) REVEALED_SHORTFALL else 0f,
        animationSpec = if (revealed) snap() else tween(300),
        label = "backShortfall",
    )

    Box(
        Modifier
            .graphicsLayer {
                translationX = -size.width * shortfall
                scaleX = 1 - shortfall
                scaleY = 1 - shortfall
            }
            .drawWithContent { drawContent(); drawRect(Color.Black, alpha = scrim) },
    ) {
        content()
    }
}

private val IdleTransition: StateFlow<NavigationEventTransitionState> =
    MutableStateFlow(NavigationEventTransitionState.Idle)
