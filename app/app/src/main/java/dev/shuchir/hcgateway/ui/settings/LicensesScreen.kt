package dev.shuchir.hcgateway.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val libs = remember {
        try {
            Libs.Builder().withContext(context).build()
        } catch (_: Exception) {
            null
        }
    }
    var expandedLibrary by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open source licenses") },
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
        if (libs == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text("No license data available", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(libs.libraries.sortedBy { it.name.lowercase() }) { library ->
                    val isExpanded = expandedLibrary == library.uniqueId
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            expandedLibrary = if (isExpanded) null else library.uniqueId
                        },
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                library.name,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "${library.artifactVersion ?: ""} • ${library.licenses.joinToString { it.name }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (isExpanded) {
                                val licenseContent = library.licenses.firstOrNull()?.licenseContent
                                if (!licenseContent.isNullOrBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        licenseContent,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
