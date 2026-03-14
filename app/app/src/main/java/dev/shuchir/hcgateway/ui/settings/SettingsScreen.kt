package dev.shuchir.hcgateway.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
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

            // Sync interval
            SettingsItem(title = "Auto-sync interval") {
                SyncIntervalPicker(
                    currentMinutes = settings.syncInterval,
                    onIntervalChange = viewModel::updateSyncInterval,
                )
            }


            // --- Appearance section ---
            SectionLabel("Appearance")

            SettingsItem(title = "Theme") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("light" to "Light", "dark" to "Dark", "system" to "System").forEachIndexed { index, (value, label) ->
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
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/shuchir/HCGateway"))
                        context.startActivity(intent)
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Source code", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/shuchir/HCGateway/issues"))
                        context.startActivity(intent)
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Report a bug", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
            }


            // --- Account section ---
            SectionLabel("Account")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showLogoutConfirm = true }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Logout", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.error)
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
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
                }) { Text("Logout") }
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
        else -> trimmed.toIntOrNull()?.coerceIn(15, 10080)
    }
}
