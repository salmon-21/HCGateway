package dev.shuchir.hcgateway.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import dev.shuchir.hcgateway.domain.model.SyncState
import dev.shuchir.hcgateway.ui.components.FilledCard
import dev.shuchir.hcgateway.ui.components.SyncWarningDialog
import dev.shuchir.hcgateway.ui.theme.ExtendedTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val hasPermissions by viewModel.hasPermissions.collectAsState()
    val serverReachable by viewModel.serverReachable.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }
    var showSyncWarning by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) {
        viewModel.onPermissionsResult()
    }

    // Check permissions on first composition
    LaunchedEffect(Unit) {
        viewModel.checkPermissions()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HCGateway") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    IconButton(onClick = { showLogoutConfirm = true }) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Logout")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Status card — reflects actual connection state
            val statusColor = when (serverReachable) {
                true -> ExtendedTheme.colors.successContainer
                false -> MaterialTheme.colorScheme.errorContainer
                null -> MaterialTheme.colorScheme.surfaceContainerLow // checking
            }
            val statusContentColor = when (serverReachable) {
                true -> ExtendedTheme.colors.onSuccessContainer
                false -> MaterialTheme.colorScheme.onErrorContainer
                null -> MaterialTheme.colorScheme.onSurface
            }
            val statusLabel = when (serverReachable) {
                true -> "Connected to ${settings.apiBase}"
                false -> "Cannot reach ${settings.apiBase}"
                null -> "Connecting to ${settings.apiBase}..."
            }

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (serverReachable == false) viewModel.checkServerConnection()
                },
                colors = CardDefaults.elevatedCardColors(
                    containerColor = statusColor,
                ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.CloudSync,
                        contentDescription = null,
                        tint = statusContentColor,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            statusLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = statusContentColor,
                        )
                        Text(
                            "User: ${settings.username}",
                            style = MaterialTheme.typography.bodySmall,
                            color = statusContentColor.copy(alpha = 0.7f),
                        )
                        if (settings.lastSync > 0) {
                            val lastSyncTime = Instant.ofEpochMilli(settings.lastSync)
                                .atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                            Text(
                                "Last sync: $lastSyncTime",
                                style = MaterialTheme.typography.bodySmall,
                                color = statusContentColor.copy(alpha = 0.7f),
                            )
                        }
                        if (serverReachable == false) {
                            Text(
                                "Tap to retry",
                                style = MaterialTheme.typography.labelSmall,
                                color = statusContentColor.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }

            // Permissions card
            if (hasPermissions == false) {
                FilledCard(tonalElevation = true) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Health Connect permissions required",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "Grant permissions to read and write health data",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            permissionLauncher.launch(viewModel.getRequiredPermissions())
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Grant Permissions")
                    }
                }
            }

            // Sync card
            FilledCard(tonalElevation = true) {
                Text(
                    "Sync",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                when (val state = syncState) {
                    is SyncState.Idle -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = {
                                    if (!settings.fullSyncMode && settings.lastSync == 0L) {
                                        showSyncWarning = true
                                    } else {
                                        viewModel.syncNow()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = hasPermissions == true,
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Sync")
                            }
                            OutlinedButton(
                                onClick = { showDatePicker = true },
                                modifier = Modifier.weight(1f),
                                enabled = hasPermissions == true,
                            ) {
                                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Force Sync")
                            }
                        }
                    }
                    is SyncState.Syncing -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(
                                progress = { state.typesCompleted.toFloat() / state.totalTypes },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                if (state.currentType.isNotBlank()) {
                                    "Syncing ${state.currentType}... (${state.typesCompleted}/${state.totalTypes})"
                                } else {
                                    "Starting sync..."
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    is SyncState.Done -> {
                        Text(
                            "Sync complete: ${state.recordCount} records",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = viewModel::resetSyncState) {
                            Text("OK")
                        }
                    }
                    is SyncState.Error -> {
                        Text(
                            "Error: ${state.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = viewModel::resetSyncState) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            // Settings card
            FilledCard {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                // Theme
                Text("Theme", style = MaterialTheme.typography.labelMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("light" to "Light", "dark" to "Dark", "system" to "System").forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = settings.themeMode == value,
                            onClick = { viewModel.updateThemeMode(value) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                        ) { Text(label) }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Sync interval
                Text("Auto-sync interval", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                SyncIntervalPicker(
                    currentMinutes = settings.syncInterval,
                    onIntervalChange = viewModel::updateSyncInterval,
                )

                Spacer(Modifier.height(16.dp))

                // Sync mode
                Text("Sync mode", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
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

            if (!viewModel.isHealthConnectAvailable) {
                FilledCard(tonalElevation = true) {
                    Text(
                        "Health Connect is not available on this device",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    // Force Sync Date Range Picker
    if (showDatePicker) {
        val datePickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val start = datePickerState.selectedStartDateMillis
                    val end = datePickerState.selectedEndDateMillis
                    if (start != null && end != null) {
                        val startDate = Instant.ofEpochMilli(start).atZone(ZoneId.of("UTC")).toLocalDate()
                        val endDate = Instant.ofEpochMilli(end).atZone(ZoneId.of("UTC")).toLocalDate()
                        viewModel.syncRange(startDate, endDate)
                    }
                    showDatePicker = false
                }) { Text("Force Sync") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DateRangePicker(
                state = datePickerState,
                title = { Text("Force sync date range", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
                headline = { Text("Select range to re-sync", modifier = Modifier.padding(start = 24.dp)) },
                modifier = Modifier.weight(1f),
            )
        }
    }

    // Sync warning dialog
    if (showSyncWarning) {
        SyncWarningDialog(
            onConfirm = {
                showSyncWarning = false
                viewModel.syncNow()
            },
            onDismiss = { showSyncWarning = false },
        )
    }

    // Logout confirmation
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Logout") },
            text = { Text("Are you sure you want to logout?") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    viewModel.logout()
                }) { Text("Logout") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

private val INTERVAL_PRESETS = listOf(15, 30, 60, 120, 360, 1440)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncIntervalPicker(
    currentMinutes: Int,
    onIntervalChange: (Int) -> Unit,
) {
    val isCustom = currentMinutes !in INTERVAL_PRESETS
    var showCustomInput by remember { mutableStateOf(isCustom) }
    var customText by remember(currentMinutes) {
        mutableStateOf(if (isCustom) formatIntervalInput(currentMinutes) else "")
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        INTERVAL_PRESETS.forEach { minutes ->
            FilterChip(
                selected = currentMinutes == minutes && !showCustomInput,
                onClick = {
                    showCustomInput = false
                    onIntervalChange(minutes)
                },
                label = { Text(formatInterval(minutes)) },
            )
        }
        FilterChip(
            selected = showCustomInput || isCustom,
            onClick = { showCustomInput = true },
            label = { Text("Custom") },
        )
    }

    if (showCustomInput || isCustom) {
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = customText,
                onValueChange = { customText = it },
                label = { Text("Interval") },
                placeholder = { Text("e.g. 2h, 30m, 1d") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        parseIntervalInput(customText)?.let {
                            onIntervalChange(it)
                            showCustomInput = false
                        }
                    }
                ),
                supportingText = { Text("Use m (minutes), h (hours), d (days)") },
            )
            FilledTonalButton(
                onClick = {
                    parseIntervalInput(customText)?.let {
                        onIntervalChange(it)
                        showCustomInput = false
                    }
                },
            ) {
                Text("Set")
            }
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

    val number = trimmed.dropLast(1).toDoubleOrNull()
    val unit = trimmed.lastOrNull()

    return when (unit) {
        'm' -> number?.toInt()?.coerceIn(15, 10080)
        'h' -> number?.let { (it * 60).toInt().coerceIn(15, 10080) }
        'd' -> number?.let { (it * 1440).toInt().coerceIn(15, 10080) }
        else -> {
            // Try parsing as plain number (assume minutes)
            trimmed.toIntOrNull()?.coerceIn(15, 10080)
        }
    }
}
