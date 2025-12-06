package io.github.gauravyad69.speakershare.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import compose.icons.TablerIcons
import compose.icons.tablericons.Activity
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Check
import compose.icons.tablericons.Clock
import compose.icons.tablericons.DeviceSpeaker
import compose.icons.tablericons.InfoCircle
import compose.icons.tablericons.PlayerStop
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.Trash
import compose.icons.tablericons.UserOff
import compose.icons.tablericons.UserX
import compose.icons.tablericons.Users
import compose.icons.tablericons.Volume
import compose.icons.tablericons.Volume
import compose.icons.tablericons.Wifi
import compose.icons.tablericons.WifiOff
import io.github.gauravyad69.speakershare.data.model.ClientConnection
import io.github.gauravyad69.speakershare.ui.components.DuolingoButton
import io.github.gauravyad69.speakershare.ui.theme.*
import io.github.gauravyad69.speakershare.ui.viewmodels.ClientsViewModel
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
                        "CLIENTS MANAGEMENT",
                        style = MaterialTheme.typography.labelLarge,
                        color = DuoTextSecondary
                    )
                    Text(
                        "CONNECTED (${uiState.clients.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = DuoTextPrimary
                    )
                }
                
                if (uiState.clients.isNotEmpty()) {
                    IconButton(onClick = { showBulkActions = !showBulkActions }) {
                        Icon(
                            if (showBulkActions) TablerIcons.Check else TablerIcons.Users,
                            contentDescription = if (showBulkActions) "Cancel selection" else "Select multiple",
                            tint = if (showBulkActions) DuoGreen else DuoBlue,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (showBulkActions && selectedClients.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        viewModel.kickClients(selectedClients.toList())
                        selectedClients = emptySet()
                        showBulkActions = false
                    },
                    icon = { Icon(TablerIcons.UserX, contentDescription = null, tint = DuoTextPrimary) },
                    text = { Text("KICK SELECTED (${selectedClients.size})", color = DuoTextPrimary, fontWeight = FontWeight.Bold) },
                    containerColor = DuoRed
                )
            } else if (uiState.clients.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { viewModel.refreshClients() },
                    containerColor = DuoBlue,
                    contentColor = DuoTextPrimary
                ) {
                    Icon(TablerIcons.Refresh, contentDescription = "Refresh")
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
                Column {
                    Text(
                        text = sessionName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DuoTextPrimary
                    )
                    Text(
                        text = if (isActive) "BROADCASTING ACTIVE" else "SESSION STOPPED",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isActive) DuoGreen else DuoTextSecondary
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
                                    DuoGreen,
                                    shape = CircleShape
                                )
                        )
                        Text(
                            text = "LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = DuoGreen
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
                DuolingoButton(
                    text = "STOP BROADCASTING",
                    onClick = onStopSession,
                    icon = TablerIcons.PlayerStop,
                    color = DuoRed,
                    shadowColor = DuoRedShadow,
                    modifier = Modifier.fillMaxWidth()
                )
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
            fontWeight = FontWeight.Bold,
            color = DuoTextPrimary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = DuoTextSecondary
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
                text = "QUICK ACTIONS",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = DuoTextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DuolingoButton(
                    text = "MUTE ALL",
                    onClick = onMuteAll,
                    icon = TablerIcons.Volume,
                    color = DuoSurfaceHighlight,
                    shadowColor = DuoOutline,
                    textColor = DuoTextSecondary,
                    modifier = Modifier.weight(1f),
                    height = 40.dp
                )
                
                DuolingoButton(
                    text = "UNMUTE ALL",
                    onClick = onUnmuteAll,
                    icon = TablerIcons.Volume,
                    color = DuoSurfaceHighlight,
                    shadowColor = DuoOutline,
                    textColor = DuoTextSecondary,
                    modifier = Modifier.weight(1f),
                    height = 40.dp
                )
                
                DuolingoButton(
                    text = "KICK ALL",
                    onClick = onKickAll,
                    icon = TablerIcons.UserX,
                    color = DuoRed,
                    shadowColor = DuoRedShadow,
                    modifier = Modifier.weight(1f),
                    height = 40.dp
                )
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                DuoSurfaceHighlight
            } else {
                DuoSurface
            }
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, DuoBlue)
        } else BorderStroke(2.dp, DuoSurfaceHighlight)
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
                            onCheckedChange = { onSelected() },
                            colors = CheckboxDefaults.colors(
                                checkedColor = DuoBlue,
                                uncheckedColor = DuoTextSecondary
                            )
                        )
                    }

                    Column {
                        Text(
                            text = client.clientName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = DuoTextPrimary
                        )
                        Text(
                            text = client.ipAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = DuoTextSecondary
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Transport protocol chip
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DuoSurfaceHighlight
                    ) {
                        Text(
                            text = "Unknown", // TODO: Add transport info to ClientConnection
                            style = MaterialTheme.typography.labelSmall,
                            color = DuoTextSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Client Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ClientStat(
                    icon = TablerIcons.Clock,
                    label = "Connected",
                    value = formatDuration(System.currentTimeMillis() - client.connectionTime)
                )
                ClientStat(
                    icon = TablerIcons.Wifi,
                    label = "Bandwidth",
                    value = "${String.format("%.1f", client.networkMetrics.bandwidth / 1000f)} KB/s"
                )
                ClientStat(
                    icon = TablerIcons.DeviceSpeaker,
                    label = "Latency",
                    value = "${client.networkMetrics.latency}ms"
                )
                ClientStat(
                    icon = if (client.audioSettings.isMuted) TablerIcons.Volume else TablerIcons.Volume,
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
                    DuolingoButton(
                        text = if (client.audioSettings.isMuted) "UNMUTE" else "MUTE",
                        onClick = if (client.audioSettings.isMuted) onUnmute else onMute,
                        icon = if (client.audioSettings.isMuted) TablerIcons.Volume else TablerIcons.Volume,
                        color = DuoSurfaceHighlight,
                        shadowColor = DuoOutline,
                        textColor = DuoTextSecondary,
                        modifier = Modifier.weight(1f),
                        height = 40.dp
                    )
                    
                    DuolingoButton(
                        text = "KICK",
                        onClick = onKick,
                        icon = TablerIcons.UserX,
                        color = DuoRed,
                        shadowColor = DuoRedShadow,
                        modifier = Modifier.weight(1f),
                        height = 40.dp
                    )
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
            tint = DuoTextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = DuoTextPrimary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = DuoTextSecondary
        )
    }
}

@Composable
private fun EmptyClientsCard() {
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
                TablerIcons.Users,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = DuoTextDisabled
            )
            
            Text(
                text = "NO CONNECTED CLIENTS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = DuoTextSecondary
            )
            
            Text(
                text = "Clients will appear here once they connect to your broadcast session.",
                style = MaterialTheme.typography.bodyMedium,
                color = DuoTextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
        title = { Text("Kick Client", style = MaterialTheme.typography.titleLarge) },
        text = {
            Text("Are you sure you want to kick \"${client.ipAddress}\" from the session?", style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            DuolingoButton(
                text = "KICK",
                onClick = onConfirm,
                color = DuoRed,
                shadowColor = DuoRedShadow,
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
