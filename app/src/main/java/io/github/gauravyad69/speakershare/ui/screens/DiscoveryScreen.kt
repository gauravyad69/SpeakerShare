@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.gauravyad69.speakershare.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Ban
import compose.icons.tablericons.PlayerPlay
import compose.icons.tablericons.Plus
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.Search
import io.github.gauravyad69.speakershare.network.discovery.DiscoveredHost
import io.github.gauravyad69.speakershare.ui.components.DuolingoButton
import io.github.gauravyad69.speakershare.ui.theme.*
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
    val isScanning by viewModel.isDiscovering.collectAsState()
    val discoveredHosts by viewModel.availableHosts.collectAsState()
    val lastScanTime by viewModel.lastDiscoveryTime.collectAsState()
    val error by viewModel.error.collectAsState()
    var showManualConnect by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.startDiscovery()
    }

    Scaffold(
        containerColor = DuoBackground,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        TablerIcons.ArrowLeft,
                        contentDescription = "Back",
                        tint = DuoTextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "FIND HOSTS",
                        style = MaterialTheme.typography.labelLarge,
                        color = DuoTextSecondary
                    )
                    Text(
                        "NETWORK DISCOVERY",
                        style = MaterialTheme.typography.titleMedium,
                        color = DuoTextPrimary
                    )
                }
                
                IconButton(
                    onClick = { viewModel.refreshDiscovery() },
                    enabled = !isScanning
                ) {
                    Icon(
                        TablerIcons.Refresh,
                        contentDescription = "Refresh",
                        tint = if (isScanning) DuoTextDisabled else DuoBlue,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                IconButton(onClick = { showManualConnect = true }) {
                    Icon(
                        TablerIcons.Plus,
                        contentDescription = "Manual Connect",
                        tint = DuoGreen,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.refreshDiscovery() },
                icon = { 
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = DuoTextPrimary
                        )
                    } else {
                        Icon(TablerIcons.Search, contentDescription = null, tint = DuoTextPrimary)
                    }
                },
                text = { Text(if (isScanning) "SCANNING..." else "SCAN NETWORK", color = DuoTextPrimary, fontWeight = FontWeight.Bold) },
                containerColor = DuoBlue
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
                isScanning = isScanning,
                scanProgress = 0.0f, // TODO: Add progress tracking
                hostsFound = discoveredHosts.size,
                lastScanTime = lastScanTime
            )

                        // Filter and Sort Options
            DiscoveryFilters(
                sortBy = "name", // TODO: Add sort state to ViewModel
                filterByTransport = "all", // TODO: Add filter state to ViewModel
                onSortChange = { /* TODO: Implement sorting */ },
                onFilterChange = { /* TODO: Implement filtering */ }
            )

            // Discovered Hosts List
            if (discoveredHosts.isEmpty() && !isScanning) {
                EmptyStateCard(
                    onScanAgain = { viewModel.refreshDiscovery() },
                    onManualConnect = { showManualConnect = true }
                )
            } else {
                DiscoveredHostsList(
                    hosts = discoveredHosts, // Use directly since filtering/sorting is TODO
                    onHostSelected = onHostSelected,
                    onRefreshHost = { /* TODO: Implement host refresh */ }
                )
            }
        }
    }

    // Manual Connect Dialog
    if (showManualConnect) {
        ManualConnectDialog(
            onDismiss = { showManualConnect = false },
            onConnect = { ip, port ->
                // TODO: Set manual address in ViewModel and then connect
                // viewModel.setManualHostAddress("$ip:$port")
                // viewModel.connectToManualHost()
                showManualConnect = false
            }
        )
    }

    // Error handling
    error?.let { errorMessage ->
        LaunchedEffect(errorMessage) {
            // Show error message
            delay(3000)
            // TODO: Add clearError method to ViewModel
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DuoSurface),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
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
                    text = "NETWORK DISCOVERY",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = DuoTextPrimary
                )

                if (isScanning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = DuoBlue
                        )
                        Text(
                            text = "SCANNING...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = DuoBlue,
                            fontWeight = FontWeight.Bold
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
                            color = DuoTextSecondary
                        )
                        Text(
                            text = "${(scanProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = DuoTextSecondary
                        )
                    }
                    LinearProgressIndicator(
                        progress = scanProgress,
                        modifier = Modifier.fillMaxWidth(),
                        color = DuoBlue,
                        trackColor = DuoSurfaceHighlight
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Hosts Found: $hostsFound",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DuoTextPrimary
                )

                lastScanTime?.let { time ->
                    Text(
                        text = "Last scan: ${formatTime(time)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = DuoTextSecondary
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DuoSurface),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "FILTER & SORT",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = DuoTextSecondary
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
                        label = { Text("Sort by", color = DuoTextSecondary) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sortExpanded) },
                        modifier = Modifier.menuAnchor(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = DuoBlue,
                            unfocusedBorderColor = DuoSurfaceHighlight,
                            focusedTextColor = DuoTextPrimary,
                            unfocusedTextColor = DuoTextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false },
                        containerColor = DuoSurface
                    ) {
                        listOf("name", "distance", "clients", "quality").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(formatSortBy(option), color = DuoTextPrimary) },
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
                        label = { Text("Transport", color = DuoTextSecondary) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = filterExpanded) },
                        modifier = Modifier.menuAnchor(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = DuoBlue,
                            unfocusedBorderColor = DuoSurfaceHighlight,
                            focusedTextColor = DuoTextPrimary,
                            unfocusedTextColor = DuoTextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = filterExpanded,
                        onDismissRequest = { filterExpanded = false },
                        containerColor = DuoSurface
                    ) {
                        listOf(null, "WEBRTC", "UDP").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option ?: "All", color = DuoTextPrimary) },
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
        items(hosts, key = { it.hostId }) { host ->
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (host.isAcceptingClients) {
                DuoSurface
            } else {
                DuoSurfaceHighlight
            }
        ),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
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
                        fontWeight = FontWeight.Bold,
                        color = DuoTextPrimary
                    )
                    Text(
                        text = "${host.ipAddress}:${host.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DuoTextSecondary
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (host.discoveryMethod.contains("WEBRTC", ignoreCase = true)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DuoBlue
                        ) {
                            Text(
                                text = "WebRTC",
                                style = MaterialTheme.typography.labelSmall,
                                color = DuoTextPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (host.discoveryMethod.contains("UDP", ignoreCase = true)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DuoGreen
                        ) {
                            Text(
                                text = "UDP",
                                style = MaterialTheme.typography.labelSmall,
                                color = DuoTextPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
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
                        color = DuoTextSecondary
                    )
                    Text(
                        text = "Quality: ${host.quality}",
                        style = MaterialTheme.typography.bodySmall,
                        color = DuoTextSecondary
                    )
                }
                
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Clients: ${host.connectedClients}/${if (host.maxClients > 0) host.maxClients else "∞"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = DuoTextSecondary
                    )
                    Text(
                        text = "Age: ${formatAge(System.currentTimeMillis() - host.lastSeen)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = DuoTextSecondary
                    )
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DuolingoButton(
                    text = "",
                    onClick = onRefresh,
                    icon = TablerIcons.Refresh,
                    color = DuoSurfaceHighlight,
                    shadowColor = DuoOutline,
                    textColor = DuoTextSecondary,
                    modifier = Modifier.weight(0.3f),
                    height = 40.dp
                )

                DuolingoButton(
                    text = "CONNECT",
                    onClick = onSelect,
                    icon = TablerIcons.PlayerPlay,
                    color = if (host.isAcceptingClients) DuoGreen else DuoTextDisabled,
                    shadowColor = if (host.isAcceptingClients) DuoGreenShadow else DuoOutline,
                    modifier = Modifier.weight(0.7f),
                    height = 40.dp
                )
            }

            // Status indicator
            if (!host.isAcceptingClients) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        TablerIcons.Ban,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = DuoRed
                    )
                    Text(
                        text = "Not accepting new clients",
                        style = MaterialTheme.typography.bodySmall,
                        color = DuoRed
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DuoSurface),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                TablerIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = DuoTextDisabled
            )
            
            Text(
                text = "NO HOSTS FOUND",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = DuoTextSecondary
            )
            
            Text(
                text = "Make sure you're connected to the same Wi-Fi network as the host device.",
                style = MaterialTheme.typography.bodyMedium,
                color = DuoTextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DuolingoButton(
                    text = "SCAN AGAIN",
                    onClick = onScanAgain,
                    icon = TablerIcons.Search,
                    color = DuoSurfaceHighlight,
                    shadowColor = DuoOutline,
                    textColor = DuoTextSecondary,
                    height = 40.dp
                )
                
                DuolingoButton(
                    text = "MANUAL",
                    onClick = onManualConnect,
                    icon = TablerIcons.Plus,
                    color = DuoBlue,
                    shadowColor = DuoBlueShadow,
                    height = 40.dp
                )
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
        title = { Text("Manual Connection", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it },
                    label = { Text("IP Address", color = DuoTextSecondary) },
                    placeholder = { Text("192.168.1.100", color = DuoTextDisabled) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DuoBlue,
                        unfocusedBorderColor = DuoSurfaceHighlight,
                        focusedTextColor = DuoTextPrimary,
                        unfocusedTextColor = DuoTextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port", color = DuoTextSecondary) },
                    placeholder = { Text("8080", color = DuoTextDisabled) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DuoBlue,
                        unfocusedBorderColor = DuoSurfaceHighlight,
                        focusedTextColor = DuoTextPrimary,
                        unfocusedTextColor = DuoTextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            DuolingoButton(
                text = "CONNECT",
                onClick = {
                    if (ipAddress.isNotBlank() && port.isNotBlank()) {
                        onConnect(ipAddress.trim(), port.trim().toIntOrNull() ?: 8080)
                    }
                },
                color = if (ipAddress.isNotBlank() && port.isNotBlank()) DuoGreen else DuoTextDisabled,
                shadowColor = if (ipAddress.isNotBlank() && port.isNotBlank()) DuoGreenShadow else DuoOutline,
                height = 40.dp
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = DuoTextSecondary, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = DuoSurface,
        titleContentColor = DuoTextPrimary,
        textContentColor = DuoTextSecondary
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

