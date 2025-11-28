package io.github.gauravyad69.speakershare.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import compose.icons.TablerIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.gauravyad69.speakershare.ui.theme.SpeakerShareTheme
import io.github.gauravyad69.speakershare.ui.viewmodels.HostViewModel
import io.github.gauravyad69.speakershare.ui.viewmodels.TransferStatus
import io.github.gauravyad69.speakershare.data.model.AudioSource
import io.github.gauravyad69.speakershare.data.model.ClientConnection

/**
 * Host Screen - Main screen for host mode with broadcasting controls
 * Implements T047: Host screen with controls
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(
    onNavigateToClients: () -> Unit,
    onNavigateBack: () -> Unit,
    onBecomeClient: (ip: String, port: Int, hostName: String) -> Unit = { _, _, _ -> },
    autoStart: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: HostViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val transferStatus by viewModel.transferStatus.collectAsStateWithLifecycle()
    val pendingTransferClientId by viewModel.pendingTransferClientId.collectAsStateWithLifecycle()
    
    // State for showing client action menu
    var showClientMenu by remember { mutableStateOf<String?>(null) }
    var showTransferConfirmDialog by remember { mutableStateOf<ClientConnection?>(null) }
    
    // Auto-start hosting if this is a transfer recipient
    LaunchedEffect(autoStart) {
        if (autoStart && !uiState.isHosting) {
            android.util.Log.d("HostScreen", "Auto-starting hosting after transfer acceptance")
            viewModel.startHosting(hostName = android.os.Build.MODEL)
        }
    }
    
    // Handle becoming a client after transfer
    LaunchedEffect(transferStatus) {
        if (transferStatus is TransferStatus.BecomeClient) {
            val status = transferStatus as TransferStatus.BecomeClient
            android.util.Log.d("HostScreen", "Becoming client of new host: ${status.newHostIp}:${status.newHostPort}")
            onBecomeClient(status.newHostIp, status.newHostPort, status.newHostName)
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top App Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.Default.ArrowBack, 
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                
                Text(
                    text = "Host Mode",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                
                IconButton(onClick = onNavigateToClients) {
                    Icon(
                        Icons.Default.People, 
                        contentDescription = "View Clients",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Broadcasting Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (uiState.isHosting) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (uiState.isHosting) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = "Status",
                        tint = if (uiState.isHosting) Color.Green else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = if (uiState.isHosting) "Broadcasting" else "Not Broadcasting",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                if (uiState.isHosting) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${uiState.connectedClients.size} clients connected",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Controls Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Start/Stop Broadcasting Button
            Button(
                onClick = { 
                    if (uiState.isHosting) {
                        viewModel.stopHosting()
                    } else {
                        viewModel.startHosting(
                        hostName = android.os.Build.MODEL
                    )
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isHosting) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            ) {
                Icon(
                    if (uiState.isHosting) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (uiState.isHosting) "Stop" else "Start",
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            // Mute/Unmute Button
            Button(
                onClick = { viewModel.toggleMute() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isMuted) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                ),
                enabled = uiState.isHosting
            ) {
                Icon(
                    if (uiState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (uiState.isMuted) "Unmute" else "Mute",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Audio Source Selection
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Audio Source",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        onClick = { viewModel.switchAudioSource(AudioSource.MICROPHONE) },
                        label = { Text("Microphone") },
                        selected = uiState.audioSource == AudioSource.MICROPHONE,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    
                    FilterChip(
                        onClick = { viewModel.switchAudioSource(AudioSource.SYSTEM_AUDIO) },
                        label = { Text("System Audio") },
                        selected = uiState.audioSource == AudioSource.SYSTEM_AUDIO,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Computer,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Network Information Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Network Information",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "IP Address:",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = uiState.networkInfo?.localIpAddress ?: "Unknown",
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Port:",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = uiState.networkInfo?.port?.toString() ?: "8080",
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Connected Clients Preview
        if (uiState.connectedClients.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Connected Clients",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        
                        TextButton(onClick = onNavigateToClients) {
                            Text("View All")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    uiState.connectedClients.take(3).forEach { client ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                
                                Spacer(modifier = Modifier.width(8.dp))
                                
                                Text(
                                    text = client.clientName,
                                    fontSize = 14.sp
                                )
                            }
                            
                            // Client action menu
                            Box {
                                IconButton(
                                    onClick = { showClientMenu = client.clientId },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "Client options",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                
                                DropdownMenu(
                                    expanded = showClientMenu == client.clientId,
                                    onDismissRequest = { showClientMenu = null }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Make Host") },
                                        onClick = {
                                            showClientMenu = null
                                            showTransferConfirmDialog = client
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.SwapHoriz, contentDescription = null)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Kick") },
                                        onClick = {
                                            showClientMenu = null
                                            viewModel.kickClient(client.clientId)
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.PersonRemove, contentDescription = null)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    
                    if (uiState.connectedClients.size > 3) {
                        Text(
                            text = "and ${uiState.connectedClients.size - 3} more...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 24.dp, top = 4.dp)
                        )
                    }
                }
            }
        }
        
        // Transfer Status Indicator
        if (transferStatus !is TransferStatus.Idle) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (transferStatus) {
                        is TransferStatus.Completed -> MaterialTheme.colorScheme.primaryContainer
                        is TransferStatus.Failed, is TransferStatus.Rejected -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (transferStatus) {
                        is TransferStatus.Requesting, is TransferStatus.WaitingForResponse, is TransferStatus.Completing -> {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        is TransferStatus.Completed -> {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        is TransferStatus.Failed, is TransferStatus.Rejected -> {
                            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                        else -> {}
                    }
                    
                    Text(
                        text = when (transferStatus) {
                            is TransferStatus.Requesting -> "Sending transfer request..."
                            is TransferStatus.WaitingForResponse -> "Waiting for client to accept..."
                            is TransferStatus.Completing -> "Completing transfer..."
                            is TransferStatus.Completed -> "Transfer complete!"
                            is TransferStatus.Rejected -> "Client rejected the transfer"
                            is TransferStatus.Failed -> "Transfer failed: ${(transferStatus as TransferStatus.Failed).reason}"
                            else -> ""
                        },
                        fontSize = 14.sp
                    )
                    
                    if (transferStatus is TransferStatus.WaitingForResponse) {
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.cancelTransferRequest() }) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
    
    // Transfer Confirmation Dialog
    showTransferConfirmDialog?.let { client ->
        AlertDialog(
            onDismissRequest = { showTransferConfirmDialog = null },
            title = { Text("Transfer Host Role") },
            text = { 
                Text("Are you sure you want to make ${client.clientName} the new host? You will stop broadcasting and become a client.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showTransferConfirmDialog = null
                        viewModel.requestTransferHost(client.clientId)
                    }
                ) {
                    Text("Transfer")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showTransferConfirmDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun HostScreenPreview() {
    SpeakerShareTheme {
        // Note: Preview will show without ViewModel data
        // Actual implementation will use HostViewModel
        Surface {
            Text(
                text = "Host Screen Preview\n(Requires ViewModel)",
                modifier = Modifier.padding(16.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}