package dev.shuchir.hcgateway.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToLicenses: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settingsNullable by viewModel.settings.collectAsState()
    val settings = settingsNullable ?: return
    val context = LocalContext.current
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // --- Sync section ---
            SectionLabel("Sync")

            // Sync mode
            SettingsItem(
                title = "Sync mode",
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !settings.fullSyncMode,
                        onClick = { viewModel.updateFullSyncMode(false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Incremental") }
                    SegmentedButton(
                        selected = settings.fullSyncMode,
                        onClick = { viewModel.updateFullSyncMode(true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Full 30-day") }
                }
                Text(
                    if (settings.fullSyncMode) "Re-reads all data from the past 30 days every sync"
                    else "Only syncs changes since last sync",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // Start on boot
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Start on boot", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    Text(
                        "Resume background sync after device restart",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.startOnBoot,
                    onCheckedChange = viewModel::updateStartOnBoot,
                )
            }

            // Auto sync toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto sync", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    Text(
                        "Periodically sync in the background",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.autoSyncEnabled,
                    onCheckedChange = viewModel::updateAutoSyncEnabled,
                )
            }

            // Sync interval (shown only when auto sync is enabled)
            val autoSyncSpatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
            val autoSyncEffects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            AnimatedVisibility(
                visible = settings.autoSyncEnabled,
                enter = expandVertically(autoSyncSpatial) + fadeIn(autoSyncEffects),
                exit = shrinkVertically(autoSyncSpatial) + fadeOut(autoSyncEffects),
            ) {
            SettingsItem(title = "Auto-sync interval") {
                SyncIntervalPicker(
                    currentMinutes = settings.syncInterval,
                    onIntervalChange = viewModel::updateSyncInterval,
                )
            }
            }


            // --- Privacy section ---
            SectionLabel("Privacy")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Error reporting", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    Text(
                        "Send crash reports via Sentry",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.sentryEnabled,
                    onCheckedChange = viewModel::updateSentryEnabled,
                )
            }

            // --- Appearance section ---
            SectionLabel("Appearance")

            SettingsItem(title = "Theme") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = settings.themeMode == value,
                            onClick = { viewModel.updateThemeMode(value) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                        ) { Text(label) }
                    }
                }
            }


            // --- About section ---
            SectionLabel("About")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("App version", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    Text("Version ${dev.shuchir.hcgateway.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
                        try { context.startActivity(intent) } catch (_: Exception) {
                            // Fallback: open Health Connect app
                            val fallback = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.healthdata")
                            fallback?.let { context.startActivity(it) }
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(dev.shuchir.hcgateway.R.drawable.ic_health_connect),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    colorFilter = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant) else null,
                )
                Spacer(Modifier.width(12.dp))
                Text("Health Connect", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ShuchirJ/HCGateway"))
                        context.startActivity(intent)
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(dev.shuchir.hcgateway.R.drawable.ic_github),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text("Source code", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ShuchirJ/HCGateway/issues"))
                        context.startActivity(intent)
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text("Report a bug", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToLicenses() }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text("Open source licenses", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
            }

            // --- Account section ---
            SectionLabel("Account")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showLogoutConfirm = true }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(12.dp))
                Text("Logout", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Logout") },
            text = { Text("Are you sure you want to logout?") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    viewModel.logout()
                }) { Text("Logout", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsItem(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(8.dp))
        content()
    }
}

// --- Sync interval picker (moved from HomeScreen) ---

private val INTERVAL_PRESETS = listOf(15, 30, 60, 120, 360, 1440)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SyncIntervalPicker(
    currentMinutes: Int,
    onIntervalChange: (Int) -> Unit,
) {
    // Local state for immediate UI update (avoids DataStore round-trip flash)
    var localMinutes by remember { mutableIntStateOf(currentMinutes) }
    LaunchedEffect(currentMinutes) { localMinutes = currentMinutes }

    val isCustom = localMinutes !in INTERVAL_PRESETS
    var showCustomInput by remember { mutableStateOf(isCustom) }
    var customText by remember(localMinutes) {
        mutableStateOf(if (isCustom) formatIntervalInput(localMinutes) else "")
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        INTERVAL_PRESETS.forEach { minutes ->
            val isSelected = localMinutes == minutes && !showCustomInput
            FilterChip(
                modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec()),
                selected = isSelected,
                onClick = {
                    localMinutes = minutes
                    showCustomInput = false
                    onIntervalChange(minutes)
                },
                label = { Text(formatInterval(minutes), maxLines = 1, softWrap = false) },
                leadingIcon = {
                    Box(Modifier.animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())) {
                        if (isSelected) {
                            Icon(Icons.Filled.Done, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                        }
                    }
                },
            )
        }
        val customSelected = showCustomInput || isCustom
        FilterChip(
            modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec()),
            selected = customSelected,
            onClick = { showCustomInput = true },
            label = { Text("Custom", maxLines = 1, softWrap = false) },
            leadingIcon = {
                Box(Modifier.animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())) {
                    if (customSelected) {
                        Icon(Icons.Filled.Done, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                    }
                }
            },
        )
    }

    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedVisibility(
        visible = showCustomInput || isCustom,
        enter = expandVertically(spatialSpec) + fadeIn(effectsSpec),
        exit = shrinkVertically(spatialSpec) + fadeOut(effectsSpec),
    ) {
        Column {
            Spacer(Modifier.height(8.dp))
            val parsed = parseIntervalInput(customText)
            val isError = customText.isNotBlank() && parsed == null
            OutlinedTextField(
                value = customText,
                onValueChange = { newValue ->
                    customText = newValue
                    parseIntervalInput(newValue)?.let { onIntervalChange(it) }
                },
                label = { Text("Interval") },
                placeholder = { Text("e.g. 2h, 30m, 1d") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = isError,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (parsed != null) showCustomInput = false
                    }
                ),
                supportingText = {
                    Text(
                        when {
                            isError -> "Invalid format. Use e.g. 30m, 2h, 1d (min 1m, max 7d)"
                            parsed != null -> "Set to ${formatInterval(parsed)}"
                            else -> "Use m (minutes), h (hours), d (days)"
                        }
                    )
                },
            )
        }
    }
}

private fun formatInterval(minutes: Int): String = when {
    minutes >= 1440 && minutes % 1440 == 0 -> "${minutes / 1440}d"
    minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes}m"
}

private fun formatIntervalInput(minutes: Int): String = when {
    minutes >= 1440 && minutes % 1440 == 0 -> "${minutes / 1440}d"
    minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60}h"
    minutes >= 60 -> "${"%.1f".format(minutes / 60.0)}h"
    else -> "${minutes}m"
}

private fun parseIntervalInput(input: String): Int? {
    val trimmed = input.trim().lowercase()
    if (trimmed.isEmpty()) return null

    val unit = trimmed.lastOrNull()
    if (unit !in listOf('m', 'h', 'd')) return null

    val number = trimmed.dropLast(1).toDoubleOrNull() ?: return null
    if (number <= 0) return null

    val minutes = when (unit) {
        'm' -> number.toInt()
        'h' -> (number * 60).toInt()
        'd' -> (number * 1440).toInt()
        else -> return null
    }

    if (minutes < 1 || minutes > 10080) return null
    return minutes
}
