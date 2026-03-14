package dev.shuchir.hcgateway.ui.onboarding

import android.Manifest
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.MaterialTheme
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EnergySavingsLeaf
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shuchir.hcgateway.R
import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.data.repository.HealthConnectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PermissionOnboardingViewModel @Inject constructor(
    private val healthConnectRepository: HealthConnectRepository,
    private val preferencesRepository: PreferencesRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _healthGranted = MutableStateFlow(false)
    val healthGranted: StateFlow<Boolean> = _healthGranted.asStateFlow()

    private val _batteryOptimized = MutableStateFlow(true)
    val batteryOptimized: StateFlow<Boolean> = _batteryOptimized.asStateFlow()

    fun getPermissions(): Set<String> = healthConnectRepository.buildPermissions()

    fun checkAll() {
        viewModelScope.launch {
            _healthGranted.value = healthConnectRepository.hasAllPermissions()
        }
        val pm = appContext.getSystemService(PowerManager::class.java)
        _batteryOptimized.value = !pm.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            preferencesRepository.setOnboardingComplete()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PermissionOnboardingScreen(
    onNext: () -> Unit,
    viewModel: PermissionOnboardingViewModel = hiltViewModel(),
) {
    val healthGranted by viewModel.healthGranted.collectAsState()
    val batteryOptimized by viewModel.batteryOptimized.collectAsState()
    val context = LocalContext.current

    val healthLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { viewModel.checkAll() }

    var notificationGranted by remember { mutableStateOf(true) }
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationGranted = granted }

    // Re-check on resume (user may return from battery settings)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.checkAll()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { viewModel.checkAll() }

    val allDone = healthGranted && !batteryOptimized && notificationGranted

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            Text(
                "Setup",
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "HCGateway needs the following permissions to sync your health data reliably.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))

            // Health Connect
            PermissionRow(
                icon = {
                    Image(
                        painter = painterResource(R.drawable.ic_health_connect),
                    colorFilter = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
                        androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                    else null,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                },
                title = "Health Connect",
                description = "Read and write health data",
                granted = healthGranted,
                onRequest = { healthLauncher.launch(viewModel.getPermissions()) },
            )

            Spacer(Modifier.height(16.dp))

            // Notifications
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionRow(
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    title = "Notifications",
                    description = "Show sync progress and errors",
                    granted = notificationGranted,
                    onRequest = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
                Spacer(Modifier.height(16.dp))
            }

            // Battery optimization
            PermissionRow(
                icon = { Icon(Icons.Default.EnergySavingsLeaf, contentDescription = null, modifier = Modifier.size(24.dp)) },
                title = "Unrestricted battery",
                description = "Keep background sync running reliably",
                granted = !batteryOptimized,
                onRequest = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        // Fallback to battery settings page
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                },
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    viewModel.completeOnboarding()
                    onNext()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = allDone,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("Next")
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    viewModel.completeOnboarding()
                    onNext()
                },
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("Skip")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PermissionRow(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        icon()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            Icon(Icons.Default.Check, contentDescription = "Granted", tint = MaterialTheme.colorScheme.primary)
        } else {
            FilledTonalButton(onClick = onRequest, shapes = ButtonDefaults.shapes()) {
                Text("Grant")
            }
        }
    }
}
