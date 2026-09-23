package dev.shuchir.hcgateway.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import soup.compose.material.motion.animation.materialSharedAxisXIn
import soup.compose.material.motion.animation.materialSharedAxisXOut
import soup.compose.material.motion.animation.rememberSlideDistance
import javax.inject.Inject
import kotlin.math.pow

enum class AuthState { Loading, Onboarding, LoggedOut, LoggedIn }

private const val BACK_DURATION_MS = 300

private fun <T> backTween(easing: Easing): FiniteAnimationSpec<T> = tween(BACK_DURATION_MS, easing = easing)

private val DecelerateCubic = Easing { 1 - (1 - it).pow(3) }

/** Back-gesture easing: runs [curve] to completion within the first [lead] of the gesture. */
private fun backGestureEasing(lead: Float, curve: Easing = DecelerateCubic) =
    Easing { curve.transform((it / lead).coerceAtMost(1f)) }

// Leaving page: eases off the mark (gentle first movement) as it slides, and has
// faded out before it has finished moving.
private val PageLeavingSlide = backGestureEasing(lead = 0.1f, curve = CubicBezierEasing(0.85f, 0f, 0.15f, 1f))
private val PageLeaving = backGestureEasing(lead = 0.1f)
private val PageLeavingFade = backGestureEasing(lead = 0.07f)

// Revealed page: appears and grows almost at once, sliding in a little behind.
private val PageRevealed = backGestureEasing(lead = 0.04f)
private val PageRevealedSlide = backGestureEasing(lead = 0.1f)

// How short of its resting place a page being revealed by a back gesture stays:
// offset left by this fraction of its width, and scaled down by it too. The
// transition can't hold it there — a fully swiped gesture seeks it to the end —
// so RevealablePage applies it separately.
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

    // NavHost itself requires the dispatcher owner, so it is always present here.
    val dispatcher = checkNotNull(LocalNavigationEventDispatcherOwner.current).navigationEventDispatcher
    // Only a gesture's start and end matter, not each progress event.
    val backGesture = remember(dispatcher) {
        dispatcher.transitionState
            .map { it is NavigationEventTransitionState.InProgress }
            .distinctUntilChanged()
    }.collectAsState(false)
    val currentEntry = navController.currentBackStackEntryAsState()

    // Register every destination through this rather than composable(), or it
    // loses the held-back look while a back gesture reveals it. During the
    // gesture the page being left is still the current entry, so any other page
    // on screen is the one being revealed.
    fun NavGraphBuilder.page(route: String, content: @Composable () -> Unit) =
        composable(route) { entry ->
            RevealablePage(
                revealed = backGesture.value && currentEntry.value?.id != entry.id,
                content = content,
            )
        }

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
        // the previous page is shown dimmed and short of its place (see
        // RevealablePage) until the gesture commits or cancels.
        predictivePopEnterTransition = {
            slideInHorizontally(backTween(PageRevealedSlide)) { -it * 15 / 100 } +
                scaleIn(backTween(PageRevealed), initialScale = 0.9f) +
                fadeIn(backTween(PageRevealed))
        },
        predictivePopExitTransition = {
            slideOutHorizontally(backTween(PageLeavingSlide)) { it } +
                scaleOut(backTween(PageLeaving), targetScale = 0.8f) +
                fadeOut(backTween(PageLeavingFade))
        },
    ) {
        page("home") {
            HomeScreen(
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToPermissions = { navController.navigate("permissions") },
            )
        }
        page("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToLicenses = { navController.navigate("licenses") },
            )
        }
        page("licenses") {
            LicensesScreen(onBack = { navController.popBackStack() })
        }
        page("permissions") {
            PermissionOnboardingScreen(
                onNext = { navController.popBackStack() },
            )
        }
    }
}

/**
 * While [revealed] by a back gesture, stays dimmed and [REVEALED_SHORTFALL]
 * short of its place; settles once the gesture commits.
 */
@Composable
private fun RevealablePage(revealed: Boolean, content: @Composable () -> Unit) {
    val scrim by animateFloatAsState(if (revealed) 0.45f else 0f, label = "backScrim")
    // Snap into the held-back position (the page isn't visible yet when the
    // gesture starts); glide home on commit.
    val shortfall by animateFloatAsState(
        targetValue = if (revealed) REVEALED_SHORTFALL else 0f,
        animationSpec = if (revealed) snap() else backTween(FastOutSlowInEasing),
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
