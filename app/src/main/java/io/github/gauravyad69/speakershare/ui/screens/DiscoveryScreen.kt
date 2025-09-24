package io.github.gauravyad69.speakershare.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.gauravyad69.speakershare.network.DiscoveredHost
import io.github.gauravyad69.speakershare.ui.viewmodels.DiscoveryViewModel
import kotlinx.coroutines.delay

/**
 * Discovery Screen for finding and selecting available hosts
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    onNavigateBack: () -> Unit,
    onHostSelected: (DiscoveredHost) -> Unit,
    viewModel: DiscoveryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showManualConnect by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.startDiscovery()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find Hosts") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshDiscovery() },
                        enabled = !uiState.isScanning
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    
                    IconButton(onClick = { showManualConnect = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Manual Connect")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.refreshDiscovery() },
                icon = { 
                    if (uiState.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null)
                    }
                },
                text = { Text(if (uiState.isScanning) "Scanning..." else "Scan Network") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Discovery Status Card
            DiscoveryStatusCard(
                isScanning = uiState.isScanning,
                scanProgress = uiState.scanProgress,
                hostsFound = uiState.discoveredHosts.size,
                lastScanTime = uiState.lastScanTime
            )

            // Filter and Sort Options
            DiscoveryFilters(
                sortBy = uiState.sortBy,
                filterByTransport = uiState.filterByTransport,
                onSortChange = { viewModel.setSortBy(it) },
                onFilterChange = { viewModel.setTransportFilter(it) }
            )

            // Discovered Hosts List
            if (uiState.discoveredHosts.isEmpty() && !uiState.isScanning) {
                EmptyStateCard(
                    onScanAgain = { viewModel.refreshDiscovery() },
                    onManualConnect = { showManualConnect = true }
                )
            } else {
                DiscoveredHostsList(
                    hosts = uiState.filteredAndSortedHosts,
                    onHostSelected = onHostSelected,
                    onRefreshHost = { host -> viewModel.refreshHost(host) }
                )
            }
        }
    }

    // Manual Connect Dialog
    if (showManualConnect) {
        ManualConnectDialog(
            onDismiss = { showManualConnect = false },
            onConnect = { ip, port ->
                viewModel.connectToManualHost(ip, port)
                showManualConnect = false
            }
        )
    }

    // Error handling
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            // Show error message
            delay(3000)
            viewModel.clearError()
        }
    }
}

@Composable
private fun DiscoveryStatusCard(
    isScanning: Boolean,
    scanProgress: Float,
    hostsFound: Int,
    lastScanTime: Long?
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Network Discovery",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (isScanning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Scanning...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (isScanning && scanProgress > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Progress",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${(scanProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LinearProgressIndicator(
                        progress = scanProgress,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Hosts Found: $hostsFound",
                    style = MaterialTheme.typography.bodyMedium
                )

                lastScanTime?.let { time ->
                    Text(
                        text = "Last scan: ${formatTime(time)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveryFilters(
    sortBy: String,
    filterByTransport: String?,
    onSortChange: (String) -> Unit,
    onFilterChange: (String?) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Filter & Sort",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Sort by dropdown
                var sortExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = sortExpanded,
                    onExpandedChange = { sortExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = formatSortBy(sortBy),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Sort by") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sortExpanded) },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false }
                    ) {
                        listOf("name", "distance", "clients", "quality").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(formatSortBy(option)) },
                                onClick = {
                                    onSortChange(option)
                                    sortExpanded = false
                                }
                            )
                        }
                    }
                }

                // Transport filter dropdown
                var filterExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = filterExpanded,
                    onExpandedChange = { filterExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = filterByTransport ?: "All",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Transport") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = filterExpanded) },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = filterExpanded,
                        onDismissRequest = { filterExpanded = false }
                    ) {
                        listOf(null, "WEBRTC", "UDP").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option ?: "All") },
                                onClick = {
                                    onFilterChange(option)
                                    filterExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredHostsList(
    hosts: List<DiscoveredHost>,
    onHostSelected: (DiscoveredHost) -> Unit,
    onRefreshHost: (DiscoveredHost) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(hosts, key = { it.sessionId }) { host ->
            DiscoveredHostCard(
                host = host,
                onSelect = { onHostSelected(host) },
                onRefresh = { onRefreshHost(host) }
            )
        }
    }
}

@Composable
private fun DiscoveredHostCard(
    host: DiscoveredHost,
    onSelect: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (host.isAcceptingClients) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Host Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = host.hostName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${host.ipAddress}:${host.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (host.supportsWebRTC()) {
                        AssistChip(
                            onClick = {},
                            label = { Text("WebRTC", style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                    if (host.supportsUDP()) {
                        AssistChip(
                            onClick = {},
                            label = { Text("UDP", style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        )
                    }
                }
            }

            // Host Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Source: ${host.audioSource}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Quality: ${host.quality.bitrate}kbps, ${host.quality.encoding}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Clients: ${host.connectedClients}/${if (host.maxClients > 0) host.maxClients else "∞"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Age: ${formatAge(host.getAge())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(0.3f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Button(
                    onClick = onSelect,
                    enabled = host.isAcceptingClients,
                    modifier = Modifier.weight(0.7f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect")
                }
            }

            // Status indicator
            if (!host.isAcceptingClients) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Block,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Not accepting new clients",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(
    onScanAgain: () -> Unit,
    onManualConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Text(
                text = "No Hosts Found",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Text(
                text = "Make sure you're connected to the same Wi-Fi network as the host device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onScanAgain) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan Again")
                }
                
                Button(onClick = onManualConnect) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Manual Connect")
                }
            }
        }
    }
}

@Composable
private fun ManualConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (String, Int) -> Unit
) {
    var ipAddress by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8080") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual Connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it },
                    label = { Text("IP Address") },
                    placeholder = { Text("192.168.1.100") },
                    singleLine = true
                )
                
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    placeholder = { Text("8080") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (ipAddress.isNotBlank() && port.isNotBlank()) {
                        onConnect(ipAddress.trim(), port.trim().toIntOrNull() ?: 8080)
                    }
                },
                enabled = ipAddress.isNotBlank() && port.isNotBlank()
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// Helper functions
private fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60000 -> "Just now"
        diff < 3600000 -> "${diff / 60000}m ago"
        else -> "${diff / 3600000}h ago"
    }
}

private fun formatAge(ageMs: Long): String {
    return when {
        ageMs < 60000 -> "${ageMs / 1000}s"
        ageMs < 3600000 -> "${ageMs / 60000}m"
        else -> "${ageMs / 3600000}h"
    }
}

private fun formatSortBy(sortBy: String): String {
    return when (sortBy) {
        "name" -> "Host Name"
        "distance" -> "Network Distance"
        "clients" -> "Client Count"
        "quality" -> "Audio Quality"
        else -> sortBy
    }
}