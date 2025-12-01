package io.github.gauravyad69.speakershare.ui.screens

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.gauravyad69.speakershare.data.model.AudioSource
import io.github.gauravyad69.speakershare.data.model.ClientConnection
import io.github.gauravyad69.speakershare.ui.theme.*
import io.github.gauravyad69.speakershare.ui.viewmodels.HostViewModel
import io.github.gauravyad69.speakershare.ui.viewmodels.TransferStatus

/**
 * Modern Host Screen with improved UI/UX
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
    val context = LocalContext.current
    val mediaProjectionManager = remember { 
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager 
    }
    val scrollState = rememberScrollState()

    // Track which mode was requested when MediaProjection permission is granted
    var pendingAudioSourceMode by remember { mutableStateOf<AudioSource?>(null) }
    var showClientMenu by remember { mutableStateOf<String?>(null) }
    var showTransferConfirmDialog by remember { mutableStateOf<ClientConnection?>(null) }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val requestedMode = pendingAudioSourceMode ?: AudioSource.SYSTEM_AUDIO
            android.util.Log.d("HostScreen", "MediaProjection permission granted for mode: $requestedMode")
            
            val initIntent = io.github.gauravyad69.speakershare.services.AudioForegroundService.initMediaProjection(
                context, result.resultCode, result.data!!
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(initIntent)
            } else {
                context.startService(initIntent)
            }
            
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val switchIntent = when (requestedMode) {
                    AudioSource.SCREEN_AND_AUDIO -> 
                        io.github.gauravyad69.speakershare.services.AudioForegroundService.switchToScreenAndAudio(context)
                    else -> 
                        io.github.gauravyad69.speakershare.services.AudioForegroundService.switchToSystemAudio(context)
                }
                context.startService(switchIntent)
                viewModel.updateAudioSourceState(requestedMode)
            }, 500)
        }
        pendingAudioSourceMode = null
    }
    
    // Auto-start hosting
    LaunchedEffect(autoStart) {
        if (autoStart && !uiState.isHosting) {
            viewModel.startHosting(hostName = android.os.Build.MODEL)
        }
    }
    
    // Handle becoming a client after transfer
    LaunchedEffect(transferStatus) {
        if (transferStatus is TransferStatus.BecomeClient) {
            val status = transferStatus as TransferStatus.BecomeClient
            onBecomeClient(status.newHostIp, status.newHostPort, status.newHostName)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(
                            "Host Mode",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        if (uiState.isHosting) {
                            Text(
                                "${uiState.connectedClients.size} connected",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.connectedClients.isNotEmpty()) {
                        IconButton(onClick = onNavigateToClients) {
                            BadgedBox(
                                badge = {
                                    Badge { 
                                        Text(uiState.connectedClients.size.toString()) 
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.People, contentDescription = "View Clients")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Broadcasting Status Card
            BroadcastingStatusCard(
                isHosting = uiState.isHosting,
                isMuted = uiState.isMuted,
                audioSource = uiState.audioSource,
                onToggleBroadcasting = {
                    if (uiState.isHosting) {
                        viewModel.stopHosting()
                    } else {
                        viewModel.startHosting(hostName = android.os.Build.MODEL)
                    }
                },
                onToggleMute = { viewModel.toggleMute() }
            )
            
            // Audio Source Selection
            AudioSourceSelector(
                selectedSource = uiState.audioSource,
                isHosting = uiState.isHosting,
                onSourceSelected = { source ->
                    when (source) {
                        AudioSource.MICROPHONE -> viewModel.switchAudioSource(source)
                        AudioSource.SYSTEM_AUDIO, AudioSource.SCREEN_AND_AUDIO -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                mediaProjectionManager?.let { mpm ->
                                    pendingAudioSourceMode = source
                                    mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                                } ?: viewModel.switchAudioSource(source)
                            } else {
                                if (source == AudioSource.SCREEN_AND_AUDIO) {
                                    android.widget.Toast.makeText(
                                        context, 
                                        "Screen sharing requires Android 10+", 
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    viewModel.switchAudioSource(source)
                                }
                            }
                        }
                    }
                }
            )
            
            // Network Info Card
            NetworkInfoCard(
                ipAddress = uiState.networkInfo?.localIpAddress ?: "Unknown",
                port = uiState.networkInfo?.port ?: 8080
            )
            
            // Connected Clients Preview
            if (uiState.connectedClients.isNotEmpty()) {
                ConnectedClientsCard(
                    clients = uiState.connectedClients,
                    showClientMenu = showClientMenu,
                    onShowMenu = { showClientMenu = it },
                    onHideMenu = { showClientMenu = null },
                    onMakeHost = { showTransferConfirmDialog = it },
                    onKick = { viewModel.kickClient(it.clientId) },
                    onViewAll = onNavigateToClients
                )
            }
            
            // Transfer Status
            AnimatedVisibility(
                visible = transferStatus !is TransferStatus.Idle,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                TransferStatusCard(
                    status = transferStatus,
                    onCancel = { viewModel.cancelTransferRequest() }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    
    // Transfer Confirmation Dialog
    showTransferConfirmDialog?.let { client ->
        AlertDialog(
            onDismissRequest = { showTransferConfirmDialog = null },
            icon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
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

@Composable
private fun BroadcastingStatusCard(
    isHosting: Boolean,
    isMuted: Boolean,
    audioSource: AudioSource,
    onToggleBroadcasting: () -> Unit,
    onToggleMute: () -> Unit
) {
    val sourceIcon = when (audioSource) {
        AudioSource.MICROPHONE -> Icons.Filled.Mic
        AudioSource.SYSTEM_AUDIO -> Icons.Filled.VolumeUp
        AudioSource.SCREEN_AND_AUDIO -> Icons.Filled.ScreenShare
    }
    val sourceLabel = when (audioSource) {
        AudioSource.MICROPHONE -> "Microphone"
        AudioSource.SYSTEM_AUDIO -> "System Audio"
        AudioSource.SCREEN_AND_AUDIO -> "Screen & Audio"
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHosting) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status Indicator
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        if (isHosting) {
                            Brush.radialGradient(
                                colors = listOf(Success, Success.copy(alpha = 0.3f))
                            )
                        } else {
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                                )
                            )
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isHosting) Icons.Filled.Podcasts else Icons.Outlined.Podcasts,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = if (isHosting) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = if (isHosting) "Broadcasting" else "Ready to Broadcast",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            
            if (isHosting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        sourceIcon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = sourceLabel,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isMuted) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = Error.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "MUTED",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Error,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Main action button
                Button(
                    onClick = onToggleBroadcasting,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isHosting) Error else MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    Icon(
                        if (isHosting) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isHosting) "Stop" else "Start",
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                // Mute button
                if (isHosting) {
                    FilledTonalButton(
                        onClick = onToggleMute,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (isMuted) Error.copy(alpha = 0.2f) else MaterialTheme.colorScheme.secondaryContainer
                        ),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Icon(
                            if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = if (isMuted) "Unmute" else "Mute",
                            tint = if (isMuted) Error else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioSourceSelector(
    selectedSource: AudioSource,
    isHosting: Boolean,
    onSourceSelected: (AudioSource) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Audio Source",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AudioSourceChip(
                    icon = Icons.Filled.Mic,
                    label = "Mic",
                    selected = selectedSource == AudioSource.MICROPHONE,
                    color = MicrophoneColor,
                    onClick = { onSourceSelected(AudioSource.MICROPHONE) },
                    modifier = Modifier.weight(1f)
                )
                
                AudioSourceChip(
                    icon = Icons.Filled.VolumeUp,
                    label = "System",
                    selected = selectedSource == AudioSource.SYSTEM_AUDIO,
                    color = SystemAudioColor,
                    onClick = { onSourceSelected(AudioSource.SYSTEM_AUDIO) },
                    modifier = Modifier.weight(1f)
                )
                
                AudioSourceChip(
                    icon = Icons.Filled.ScreenShare,
                    label = "Screen",
                    selected = selectedSource == AudioSource.SCREEN_AND_AUDIO,
                    color = ScreenAudioColor,
                    onClick = { onSourceSelected(AudioSource.SCREEN_AND_AUDIO) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioSourceChip(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) color.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(2.dp, color)
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun NetworkInfoCard(
    ipAddress: String,
    port: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Outlined.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        text = "Network",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$ipAddress:$port",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
            }
            
            IconButton(onClick = { /* TODO: Copy to clipboard */ }) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Copy",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ConnectedClientsCard(
    clients: List<ClientConnection>,
    showClientMenu: String?,
    onShowMenu: (String) -> Unit,
    onHideMenu: () -> Unit,
    onMakeHost: (ClientConnection) -> Unit,
    onKick: (ClientConnection) -> Unit,
    onViewAll: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
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
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                
                TextButton(onClick = onViewAll) {
                    Text("View All")
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            clients.take(3).forEach { client ->
                ClientListItem(
                    client = client,
                    showMenu = showClientMenu == client.clientId,
                    onShowMenu = { onShowMenu(client.clientId) },
                    onHideMenu = onHideMenu,
                    onMakeHost = { onMakeHost(client) },
                    onKick = { onKick(client) }
                )
            }
            
            if (clients.size > 3) {
                Text(
                    text = "and ${clients.size - 3} more...",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 44.dp, top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ClientListItem(
    client: ClientConnection,
    showMenu: Boolean,
    onShowMenu: () -> Unit,
    onHideMenu: () -> Unit,
    onMakeHost: () -> Unit,
    onKick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            
            Text(
                text = client.clientName,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        Box {
            IconButton(
                onClick = onShowMenu,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Options",
                    modifier = Modifier.size(18.dp)
                )
            }
            
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = onHideMenu
            ) {
                DropdownMenuItem(
                    text = { Text("Make Host") },
                    onClick = {
                        onHideMenu()
                        onMakeHost()
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Kick", color = Error) },
                    onClick = {
                        onHideMenu()
                        onKick()
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.PersonRemove, contentDescription = null, tint = Error)
                    }
                )
            }
        }
    }
}

@Composable
private fun TransferStatusCard(
    status: TransferStatus,
    onCancel: () -> Unit
) {
    val (icon, text, color) = when (status) {
        is TransferStatus.Requesting -> Triple(null, "Sending transfer request...", MaterialTheme.colorScheme.surfaceVariant)
        is TransferStatus.WaitingForResponse -> Triple(null, "Waiting for client to accept...", MaterialTheme.colorScheme.surfaceVariant)
        is TransferStatus.Completing -> Triple(null, "Completing transfer...", MaterialTheme.colorScheme.surfaceVariant)
        is TransferStatus.Completed -> Triple(Icons.Filled.CheckCircle, "Transfer complete!", MaterialTheme.colorScheme.primaryContainer)
        is TransferStatus.Rejected -> Triple(Icons.Filled.Cancel, "Client rejected the transfer", MaterialTheme.colorScheme.errorContainer)
        is TransferStatus.Failed -> Triple(Icons.Filled.Error, "Transfer failed: ${status.reason}", MaterialTheme.colorScheme.errorContainer)
        else -> Triple(null, "", MaterialTheme.colorScheme.surfaceVariant)
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = when (status) {
                        is TransferStatus.Completed -> MaterialTheme.colorScheme.primary
                        else -> Error
                    }
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
            
            Text(
                text = text,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            
            if (status is TransferStatus.WaitingForResponse) {
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    }
}

// Keep the AudioVisualizer for compatibility
@Composable
fun AudioVisualizer(
    level: Float,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Canvas(modifier = modifier.fillMaxWidth().height(60.dp)) {
        val barCount = 30
        val barWidth = size.width / barCount
        val maxBarHeight = size.height
        
        for (i in 0 until barCount) {
            val center = barCount / 2f
            val dist = kotlin.math.abs(i - center) / center
            val scale = 1f - dist * 0.5f
            
            val barHeight = maxBarHeight * level * scale * (0.8f + kotlin.random.Random.nextFloat() * 0.4f)
            val clampedHeight = barHeight.coerceIn(2.dp.toPx(), maxBarHeight)
            
            val color = Color(
                red = 0.2f,
                green = 0.8f + (level * 0.2f),
                blue = 0.4f,
                alpha = 0.8f
            )
            
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(
                    x = i * barWidth,
                    y = (maxBarHeight - clampedHeight) / 2
                ),
                size = androidx.compose.ui.geometry.Size(
                    width = barWidth * 0.8f,
                    height = clampedHeight
                )
            )
        }
    }
}
