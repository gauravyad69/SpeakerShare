package io.github.gauravyad69.speakershare.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import io.github.gauravyad69.speakershare.data.model.ClientConnection
import io.github.gauravyad69.speakershare.ui.viewmodels.ClientsViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay

/**
 * Clients Management Screen for monitoring and controlling connected clients
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen(
    onNavigateBack: () -> Unit,
    viewModel: ClientsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showKickDialog by remember { mutableStateOf<ClientConnection?>(null) }
    var showBulkActions by remember { mutableStateOf(false) }
    var selectedClients by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        viewModel.startMonitoring()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text("Connected Clients (${uiState.clients.size})")
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.clients.isNotEmpty()) {
                        IconButton(onClick = { showBulkActions = !showBulkActions }) {
                            Icon(
                                if (showBulkActions) Icons.Default.Close else Icons.Default.SelectAll,
                                contentDescription = if (showBulkActions) "Cancel selection" else "Select multiple"
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (showBulkActions && selectedClients.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        viewModel.kickClients(selectedClients.toList())
                        selectedClients = emptySet()
                        showBulkActions = false
                    },
                    icon = { Icon(Icons.Default.PersonRemove, contentDescription = null) },
                    text = { Text("Kick Selected (${selectedClients.size})") },
                    containerColor = MaterialTheme.colorScheme.error
                )
            } else if (uiState.clients.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { viewModel.refreshClients() }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Session Overview Card
            SessionOverviewCard(
                sessionName = uiState.sessionName,
                totalClients = uiState.clients.size,
                maxClients = uiState.maxClients,
                isActive = uiState.isSessionActive,
                totalBandwidth = uiState.totalBandwidth,
                onStopSession = { viewModel.stopSession() }
            )

            // Quick Actions Card
            if (uiState.clients.isNotEmpty()) {
                QuickActionsCard(
                    onKickAll = { viewModel.kickAllClients() },
                    onMuteAll = { viewModel.muteAllClients() },
                    onUnmuteAll = { viewModel.unmuteAllClients() },
                    showBulkActions = showBulkActions,
                    onToggleBulkActions = { showBulkActions = !showBulkActions }
                )
            }

            // Clients List
            if (uiState.clients.isEmpty() && !uiState.isLoading) {
                EmptyClientsCard()
            } else {
                ClientsList(
                    clients = uiState.clients,
                    isSelectionMode = showBulkActions,
                    selectedClients = selectedClients,
                    onClientSelected = { clientId ->
                        selectedClients = if (selectedClients.contains(clientId)) {
                            selectedClients - clientId
                        } else {
                            selectedClients + clientId
                        }
                    },
                    onKickClient = { client -> showKickDialog = client },
                    onMuteClient = { viewModel.muteClient(it.clientId) },
                    onUnmuteClient = { viewModel.unmuteClient(it.clientId) }
                )
            }
        }
    }

    // Kick Confirmation Dialog
    showKickDialog?.let { client ->
        KickClientDialog(
            client = client,
            onConfirm = {
                viewModel.kickClient(client.clientId)
                showKickDialog = null
            },
            onDismiss = { showKickDialog = null }
        )
    }

    // Handle errors
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            // Show error message (could be a snackbar)
            delay(3000)
            viewModel.clearError()
        }
    }
}

@Composable
private fun SessionOverviewCard(
    sessionName: String,
    totalClients: Int,
    maxClients: Int,
    isActive: Boolean,
    totalBandwidth: Float,
    onStopSession: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
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
                Column {
                    Text(
                        text = sessionName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isActive) "Broadcasting Active" else "Session Stopped",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isActive) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                
                if (isActive) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    shape = CircleShape
                                )
                        )
                        Text(
                            text = "LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Session Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SessionStat(
                    label = "Connected",
                    value = "$totalClients${if (maxClients > 0) "/$maxClients" else ""}"
                )
                SessionStat(
                    label = "Bandwidth",
                    value = "${String.format("%.1f", totalBandwidth)} Mbps"
                )
                SessionStat(
                    label = "Status",
                    value = if (isActive) "Active" else "Inactive"
                )
            }

            // Action Button
            if (isActive) {
                Button(
                    onClick = onStopSession,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop Broadcasting")
                }
            }
        }
    }
}

@Composable
private fun SessionStat(
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QuickActionsCard(
    onKickAll: () -> Unit,
    onMuteAll: () -> Unit,
    onUnmuteAll: () -> Unit,
    showBulkActions: Boolean,
    onToggleBulkActions: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Quick Actions",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onMuteAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.VolumeOff, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Mute All")
                }
                
                OutlinedButton(
                    onClick = onUnmuteAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Unmute All")
                }
                
                Button(
                    onClick = onKickAll,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.PersonRemove, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Kick All")
                }
            }
        }
    }
}

@Composable
private fun ClientsList(
    clients: List<ClientConnection>,
    isSelectionMode: Boolean,
    selectedClients: Set<String>,
    onClientSelected: (String) -> Unit,
    onKickClient: (ClientConnection) -> Unit,
    onMuteClient: (ClientConnection) -> Unit,
    onUnmuteClient: (ClientConnection) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(clients, key = { it.clientId }) { client ->
            ClientCard(
                client = client,
                isSelectionMode = isSelectionMode,
                isSelected = selectedClients.contains(client.clientId),
                onSelected = { onClientSelected(client.clientId) },
                onKick = { onKickClient(client) },
                onMute = { onMuteClient(client) },
                onUnmute = { onUnmuteClient(client) }
            )
        }
    }
}

@Composable
private fun ClientCard(
    client: ClientConnection,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onSelected: () -> Unit,
    onKick: () -> Unit,
    onMute: () -> Unit,
    onUnmute: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { modifier ->
                if (isSelectionMode) {
                    modifier.clickable { onSelected() }
                } else {
                    modifier
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Client Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isSelectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onSelected() }
                        )
                    }

                    Column {
                        Text(
                            text = client.ipAddress,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = client.ipAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Connection quality indicator (simplified)
                    // TODO: Implement ConnectionQualityIndicator based on networkMetrics
                    
                    // Transport protocol chip
                    AssistChip(
                        onClick = {},
                        label = { 
                            Text(
                                "Unknown", // TODO: Add transport info to ClientConnection
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                        )
                    )
                }
            }

            // Client Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ClientStat(
                    icon = Icons.Default.AccessTime,
                    label = "Connected",
                    value = formatDuration(System.currentTimeMillis() - client.connectionTime)
                )
                ClientStat(
                    icon = Icons.Default.NetworkCheck,
                    label = "Bandwidth",
                    value = "${String.format("%.1f", client.networkMetrics.bandwidth / 1000f)} KB/s"
                )
                ClientStat(
                    icon = Icons.Default.SignalWifi4Bar,
                    label = "Latency",
                    value = "${client.networkMetrics.latency}ms"
                )
                ClientStat(
                    icon = if (client.audioSettings.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    label = "Audio",
                    value = if (client.audioSettings.isMuted) "Muted" else "Active"
                )
            }

            // Client Actions (only show if not in selection mode)
            if (!isSelectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = if (client.audioSettings.isMuted) onUnmute else onMute,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            if (client.audioSettings.isMuted) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (client.audioSettings.isMuted) "Unmute" else "Mute")
                    }
                    
                    Button(
                        onClick = onKick,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.PersonRemove, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Kick")
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientStat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConnectionQualityIndicator(quality: String) {
    val (icon, color) = when (quality.uppercase()) {
        "EXCELLENT" -> Pair(Icons.Default.SignalWifi4Bar, MaterialTheme.colorScheme.primary)
        "GOOD" -> Pair(Icons.Default.Wifi, MaterialTheme.colorScheme.primary)
        "FAIR" -> Pair(Icons.Default.SignalWifiStatusbar4Bar, MaterialTheme.colorScheme.tertiary)
        "POOR" -> Pair(Icons.Default.WifiOff, MaterialTheme.colorScheme.error)
        else -> Pair(Icons.Default.SignalWifiOff, MaterialTheme.colorScheme.error)
    }
    
    Icon(
        icon,
        contentDescription = "Connection quality: $quality",
        modifier = Modifier.size(20.dp),
        tint = color
    )
}

@Composable
private fun EmptyClientsCard() {
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
                Icons.Default.PeopleOutline,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Text(
                text = "No Connected Clients",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Text(
                text = "Clients will appear here once they connect to your broadcast session.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun KickClientDialog(
    client: ClientConnection,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kick Client") },
        text = {
            Text("Are you sure you want to kick \"${client.ipAddress}\" from the session?")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Kick")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// Helper function
private fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / (1000 * 60)) % 60
    val hours = durationMs / (1000 * 60 * 60)
    
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}