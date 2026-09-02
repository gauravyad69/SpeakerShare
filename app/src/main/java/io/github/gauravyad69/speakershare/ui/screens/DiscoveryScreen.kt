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
                    .statusBarsPadding()
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

            // Error message (previously never displayed)
            error?.let { errorMessage ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DuoRed.copy(alpha = 0.12f)),
                    border = androidx.compose.foundation.BorderStroke(2.dp, DuoRed.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            TablerIcons.Ban,
                            contentDescription = null,
                            tint = DuoRed,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = DuoTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("DISMISS", color = DuoTextSecondary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Discovered Hosts List
            if (discoveredHosts.isEmpty() && !isScanning) {
                EmptyStateCard(
                    onScanAgain = { viewModel.refreshDiscovery() },
                    onManualConnect = { showManualConnect = true }
                )
            } else {
                DiscoveredHostsList(
                    hosts = discoveredHosts,
                    onHostSelected = onHostSelected
                )
            }
        }
    }

    // Manual Connect Dialog
    if (showManualConnect) {
        ManualConnectDialog(
            onDismiss = { showManualConnect = false },
            onConnect = { ip, port ->
                // Navigate to the client screen with the manual address.
                // Previously this dialog was a dead end (TODO stub).
                onHostSelected(
                    DiscoveredHost(
                        hostId = "manual-$ip:$port",
                        hostName = "Host $ip",
                        ipAddress = ip,
                        port = port,
                        serviceName = "Host $ip",
                        discoveryMethod = "MANUAL_IP",
                        lastSeen = System.currentTimeMillis(),
                        audioSource = "",
                        quality = "",
                        connectedClients = 0,
                        maxClients = 0,
                        isAcceptingClients = true
                    )
                )
                showManualConnect = false
            }
        )
    }

    // Error state is displayed inline above the host list
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
                    text = "DISCOVERY STATUS",
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
private fun DiscoveredHostsList(
    hosts: List<DiscoveredHost>,
    onHostSelected: (DiscoveredHost) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(hosts, key = { it.hostId }) { host ->
            DiscoveredHostCard(
                host = host,
                onSelect = { onHostSelected(host) }
            )
        }
    }
}

@Composable
private fun DiscoveredHostCard(
    host: DiscoveredHost,
    onSelect: () -> Unit
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
                    if (host.discoveryMethod.contains("MANUAL", ignoreCase = true)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DuoPurple
                        ) {
                            Text(
                                text = "Manual",
                                style = MaterialTheme.typography.labelSmall,
                                color = DuoTextPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                DuolingoButton(
                    text = "CONNECT",
                    onClick = onSelect,
                    icon = TablerIcons.PlayerPlay,
                    color = if (host.isAcceptingClients) DuoGreen else DuoTextDisabled,
                    shadowColor = if (host.isAcceptingClients) DuoGreenShadow else DuoOutline,
                    modifier = Modifier.fillMaxWidth(),
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

