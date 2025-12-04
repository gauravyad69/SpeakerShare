package io.github.gauravyad69.speakershare.ui.screens

import androidx.compose.animation.*
import timber.log.Timber
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.gauravyad69.speakershare.data.model.ConnectionStatus
import io.github.gauravyad69.speakershare.data.model.DiscoveryMethod
import io.github.gauravyad69.speakershare.data.model.NetworkInfo
import io.github.gauravyad69.speakershare.network.discovery.DiscoveredHost
import io.github.gauravyad69.speakershare.services.TransferRequest
import io.github.gauravyad69.speakershare.ui.components.ScreenViewer
import io.github.gauravyad69.speakershare.ui.theme.*
import io.github.gauravyad69.speakershare.ui.viewmodels.ClientViewModel

/**
 * Modern Client Screen with improved UI/UX
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(
    onNavigateBack: () -> Unit,
    onBecomeHost: () -> Unit = {},
    initialHostIp: String? = null,
    initialHostPort: Int? = null,
    initialHostName: String? = null,
    isTransferReconnect: Boolean = false,
    viewModel: ClientViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedHost by viewModel.connectedHost.collectAsState()
    val volume by viewModel.volume.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()
    val pendingTransferRequest by viewModel.pendingTransferRequest.collectAsState()
    
    // Screen streaming state
    val isScreenStreaming by viewModel.isScreenStreaming.collectAsState()
    val isScreenShareAvailable by viewModel.isScreenShareAvailable.collectAsState()
    val currentScreenFrame by viewModel.currentScreenFrame.collectAsState()
    
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Set up callback for when user becomes host
    LaunchedEffect(Unit) {
        viewModel.setOnBecomeHostListener { fromHostAddress ->
            Timber.d("Becoming host, redirecting from: $fromHostAddress")
            onBecomeHost()
        }
    }

    LaunchedEffect(initialHostIp, initialHostPort, initialHostName) {
        if (initialHostIp != null && initialHostPort != null && initialHostName != null) {
            if (connectionState == ConnectionStatus.DISCONNECTED) {
                viewModel.connectToHost(
                    NetworkInfo(
                        localIpAddress = initialHostIp,
                        port = initialHostPort,
                        networkInterface = "wlan0",
                        isHotspot = false,
                        discoveryMethod = DiscoveryMethod.MDNS,
                        serviceName = initialHostName
                    ),
                    retryOnFailure = isTransferReconnect
                )
            }
        }
    }
    
    // Check screen share availability when connected
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionStatus.CONNECTED) {
            while (true) {
                viewModel.checkScreenShareAvailable()
                kotlinx.coroutines.delay(5000)
            }
        } else {
            viewModel.stopScreenViewing()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(
                            "Client Mode",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        connectedHost?.serviceName?.let {
                            Text(
                                it,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                    if (connectionState == ConnectionStatus.CONNECTED) {
                        IconButton(onClick = { viewModel.disconnect() }) {
                            Icon(
                                Icons.Outlined.LinkOff,
                                contentDescription = "Disconnect",
                                tint = MaterialTheme.colorScheme.error
                            )
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
            // Connection Status Card
            ConnectionStatusCard(
                connectionState = connectionState,
                connectedHost = connectedHost,
                onDisconnect = { viewModel.disconnect() }
            )
            
            // Audio Controls (shown when connected)
            AnimatedVisibility(
                visible = connectionState == ConnectionStatus.CONNECTED,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Audio Controls
                    AudioControlsCard(
                        volume = volume,
                        isMuted = isMuted,
                        audioLevel = audioLevel,
                        onVolumeChange = { viewModel.setVolume(it) },
                        onMuteToggle = { viewModel.toggleMute() }
                    )
                    
                    // Screen Viewer
                    ScreenViewer(
                        screenFrame = currentScreenFrame,
                        isScreenAvailable = isScreenShareAvailable,
                        isStreaming = isScreenStreaming,
                        onStartViewing = { viewModel.startScreenViewing() },
                        onStopViewing = { viewModel.stopScreenViewing() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            
            // Connecting state
            AnimatedVisibility(
                visible = connectionState == ConnectionStatus.CONNECTING,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ConnectingCard()
            }
            
            // Disconnected state with discovery hint
            AnimatedVisibility(
                visible = connectionState == ConnectionStatus.DISCONNECTED && initialHostIp == null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                NoHostCard(onNavigateBack = onNavigateBack)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Handle connection errors
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            // Could show snackbar here
        }
    }
    
    // Host Transfer Request Dialog
    pendingTransferRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.rejectTransferRequest() },
            icon = {
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = { 
                Text(
                    text = "Become Host?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = { 
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("The current host wants to transfer control to you.")
                    
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "You'll start broadcasting audio to all connected clients.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.acceptTransferRequest() },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Accept")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.rejectTransferRequest() },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Decline")
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
private fun ConnectionStatusCard(
    connectionState: ConnectionStatus,
    connectedHost: NetworkInfo?,
    onDisconnect: () -> Unit
) {
    val isConnected = connectionState == ConnectionStatus.CONNECTED
    val statusColor = when (connectionState) {
        ConnectionStatus.CONNECTED -> Success
        ConnectionStatus.CONNECTING -> Warning
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionStatus.KICKED -> Error
        ConnectionStatus.ERROR -> Error
    }
    val statusText = when (connectionState) {
        ConnectionStatus.CONNECTED -> "Connected"
        ConnectionStatus.CONNECTING -> "Connecting..."
        ConnectionStatus.DISCONNECTED -> "Disconnected"
        ConnectionStatus.KICKED -> "Kicked from Session"
        ConnectionStatus.ERROR -> "Connection Error"
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) {
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
            // Status Icon
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(statusColor, statusColor.copy(alpha = 0.3f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    when (connectionState) {
                        ConnectionStatus.CONNECTED -> Icons.Filled.Headphones
                        ConnectionStatus.CONNECTING -> Icons.Outlined.Sync
                        ConnectionStatus.KICKED -> Icons.Filled.Block
                        else -> Icons.Outlined.HeadsetOff
                    },
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = if (isConnected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = statusText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            
            if (connectedHost != null && isConnected) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = connectedHost.serviceName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${connectedHost.localIpAddress}:${connectedHost.port}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedButton(
                    onClick = onDisconnect,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Error
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Error)
                ) {
                    Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disconnect")
                }
            }
        }
    }
}

@Composable
private fun AudioControlsCard(
    volume: Float,
    isMuted: Boolean,
    audioLevel: Float,
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Audio Controls",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
                
                // Mute button
                FilledTonalIconButton(
                    onClick = onMuteToggle,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isMuted) Error.copy(alpha = 0.15f) else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (isMuted) Error else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = if (isMuted) "Unmute" else "Mute"
                    )
                }
            }
            
            // Volume Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Volume",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${(volume * 100).toInt()}%",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    enabled = !isMuted,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        thumbColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
            
            // Audio Visualizer
            ModernAudioVisualizer(
                level = if (isMuted) 0f else audioLevel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            )
        }
    }
}

@Composable
private fun ModernAudioVisualizer(
    level: Float,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    
    Canvas(modifier = modifier.clip(RoundedCornerShape(8.dp))) {
        val barCount = 40
        val gap = 3.dp.toPx()
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val maxBarHeight = size.height
        
        for (i in 0 until barCount) {
            val center = barCount / 2f
            val dist = kotlin.math.abs(i - center) / center
            val waveScale = 1f - dist * 0.5f
            
            val randomFactor = 0.7f + kotlin.random.Random.nextFloat() * 0.6f
            val targetHeight = maxBarHeight * level * waveScale * randomFactor
            val barHeight = targetHeight.coerceIn(4.dp.toPx(), maxBarHeight)
            
            val alpha = if (level > 0.01f) 0.8f else 0.3f
            val color = if (level > 0.01f) {
                primaryColor.copy(alpha = alpha)
            } else {
                surfaceColor
            }
            
            val x = i * (barWidth + gap)
            val y = (maxBarHeight - barHeight) / 2
            
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
            )
        }
    }
}

@Composable
private fun ConnectingCard() {
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
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 3.dp
            )
            
            Column {
                Text(
                    text = "Connecting to host...",
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
                Text(
                    text = "Please wait",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NoHostCard(
    onNavigateBack: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Outlined.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "No Host Selected",
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
            
            Text(
                text = "Go back and select a host from the discovery screen",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = onNavigateBack,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Find Hosts")
            }
        }
    }
}
