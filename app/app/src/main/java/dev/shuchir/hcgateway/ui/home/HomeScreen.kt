package dev.shuchir.hcgateway.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shuchir.hcgateway.domain.model.TypeSyncResult
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import dev.shuchir.hcgateway.domain.model.SyncState
import dev.shuchir.hcgateway.ui.components.FilledCard
import dev.shuchir.hcgateway.ui.theme.ExtendedTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToPermissions: (() -> Unit)? = null,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val hasPermissions by viewModel.hasPermissions.collectAsState()
    val serverReachable by viewModel.serverReachable.collectAsState()
    val hcRecordCounts by viewModel.hcRecordCounts.collectAsState()
    val serverCounts by viewModel.serverCounts.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) {
        viewModel.onPermissionsResult()
    }

    LaunchedEffect(Unit) {
        viewModel.checkPermissions()
    }

    // Load HC record counts when permissions are granted, and server counts
    LaunchedEffect(hasPermissions) {
        if (hasPermissions == true) viewModel.loadHcRecordCounts()
        viewModel.loadServerCounts()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HCGateway") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
            // --- Connection status card ---
            val statusColor = when (serverReachable) {
                true -> ExtendedTheme.colors.successContainer
                false -> MaterialTheme.colorScheme.errorContainer
                null -> MaterialTheme.colorScheme.surfaceContainerLow
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
                onClick = { if (serverReachable == false) viewModel.checkServerConnection() },
                colors = CardDefaults.elevatedCardColors(containerColor = statusColor),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.CloudSync, contentDescription = null, tint = statusContentColor)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(statusLabel, style = MaterialTheme.typography.titleSmall, color = statusContentColor)
                        Text("User: ${settings.username}", style = MaterialTheme.typography.bodySmall, color = statusContentColor.copy(alpha = 0.7f))
                        if (serverReachable == false) {
                            Text("Tap to retry", style = MaterialTheme.typography.labelSmall, color = statusContentColor.copy(alpha = 0.5f))
                        }
                    }
                }
            }

            // --- Permissions card ---
            if (hasPermissions == false) {
                FilledCard(tonalElevation = true) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Health Connect permissions required", style = MaterialTheme.typography.titleSmall)
                            Text("Grant permissions to read and write health data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (onNavigateToPermissions != null) onNavigateToPermissions()
                            else permissionLauncher.launch(viewModel.getRequiredPermissions())
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Grant Permissions") }
                }
            }

            // --- Sync card (actions + results) ---
            FilledCard(tonalElevation = true) {
                // Auto-dismiss Done state
                LaunchedEffect(syncState) {
                    when (syncState) {
                        is SyncState.Done, is SyncState.Cancelled -> {
                            viewModel.loadServerCounts()
                            viewModel.loadHcRecordCounts()
                            viewModel.resetSyncState()
                        }
                        else -> {}
                    }
                }

                // Sync status / actions
                when (val state = syncState) {
                    is SyncState.Syncing -> {
                        Text("Syncing...", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.typesCompleted.toFloat() / state.totalTypes },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (state.currentType.isNotBlank()) "${state.currentType} (${state.typesCompleted}/${state.totalTypes})"
                            else "Starting...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = viewModel::cancelSync,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Cancel") }
                    }
                    is SyncState.Done -> { /* auto-dismissed above */ }
                    is SyncState.Cancelled -> { /* auto-dismissed above */ }
                    is SyncState.Error -> {
                        Text("Sync", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                        Text("Error: ${state.message}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = viewModel::resetSyncState) { Text("Dismiss") }
                        }
                    }
                    is SyncState.Idle -> {
                        Text("Sync", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = {
                                    viewModel.syncNow()
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

                        // Show data overview: HC vs Server
                        if (hcRecordCounts.isNotEmpty() || serverCounts.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                            if (settings.lastSync > 0) {
                                val lastSyncTime = Instant.ofEpochMilli(settings.lastSync)
                                    .atZone(ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                                Text(
                                    "Last synced $lastSyncTime",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            DataOverviewTable(hcRecordCounts, serverCounts)
                        }
                    }
                }
            }

            // --- Health Connect unavailable ---
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
                        val startDate = Instant.ofEpochMilli(start).atZone(java.time.ZoneId.of("UTC")).toLocalDate()
                        val endDate = Instant.ofEpochMilli(end).atZone(java.time.ZoneId.of("UTC")).toLocalDate()
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

}

@Composable
private fun DataOverviewTable(
    hcCounts: List<TypeSyncResult>,
    serverCounts: Map<String, Int>,
) {
    val hcMap = hcCounts.associateBy { it.typeName }
    val allTypes = (hcMap.keys + serverCounts.keys).distinct()
        .sortedByDescending { maxOf(hcMap[it]?.recordCount ?: 0, serverCounts[it] ?: 0) }

    if (allTypes.isEmpty()) return

    // Header
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Type", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("Device", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Server", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    allTypes.forEach { typeName ->
        val hcCount = hcMap[typeName]?.recordCount
        val srvCount = serverCounts[typeName]
        val matched = hcCount != null && srvCount != null && srvCount >= hcCount

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(typeName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(
                    "${hcCount ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 40.dp),
                )
                Text(
                    "${srvCount ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (matched) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 40.dp),
                )
            }
        }
    }
}

