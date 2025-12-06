package io.github.gauravyad69.speakershare.ui.screens

import androidx.compose.animation.*
import timber.log.Timber
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Check
import compose.icons.tablericons.DeviceTv
import compose.icons.tablericons.Headphones
import compose.icons.tablericons.InfoCircle
import compose.icons.tablericons.Unlink
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.Search
import compose.icons.tablericons.Volume
import compose.icons.tablericons.Volume2
import compose.icons.tablericons.Volume3
import compose.icons.tablericons.Volume
import compose.icons.tablericons.X
import io.github.gauravyad69.speakershare.data.model.ConnectionStatus
import io.github.gauravyad69.speakershare.data.model.DiscoveryMethod
import io.github.gauravyad69.speakershare.data.model.NetworkInfo
import io.github.gauravyad69.speakershare.network.discovery.DiscoveredHost
import io.github.gauravyad69.speakershare.services.TransferRequest
import io.github.gauravyad69.speakershare.ui.components.DuolingoButton
import io.github.gauravyad69.speakershare.ui.components.ScreenViewer
import io.github.gauravyad69.speakershare.ui.theme.*
import io.github.gauravyad69.speakershare.ui.viewmodels.ClientViewModel

/**
 * Modern Client Screen with Duolingo-inspired Dark UI
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
                        "CLIENT MODE",
                        style = MaterialTheme.typography.labelLarge,
                        color = DuoTextSecondary
                    )
                    connectedHost?.serviceName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.titleMedium,
                            color = DuoTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                if (connectionState == ConnectionStatus.CONNECTED) {
                    IconButton(onClick = { viewModel.disconnect() }) {
                        Icon(
                            TablerIcons.Unlink,
                            contentDescription = "Disconnect",
                            tint = DuoRed,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
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
                    verticalArrangement = Arrangement.spacedBy(24.dp)
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
                    TablerIcons.Refresh,
                    contentDescription = null,
                    tint = DuoPurple,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = { 
                Text(
                    text = "Become Host?",
                    style = MaterialTheme.typography.titleLarge,
                    color = DuoTextPrimary
                )
            },
            text = { 
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "The current host wants to transfer control to you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DuoTextSecondary
                    )
                    
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = DuoSurfaceHighlight
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                TablerIcons.InfoCircle,
                                contentDescription = null,
                                tint = DuoBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "You'll start broadcasting audio to all connected clients.",
                                style = MaterialTheme.typography.bodySmall,
                                color = DuoTextPrimary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                DuolingoButton(
                    text = "ACCEPT",
                    onClick = { viewModel.acceptTransferRequest() },
                    color = DuoGreen,
                    shadowColor = DuoGreenShadow,
                    height = 40.dp
                )
            },
            dismissButton = {
                TextButton(onClick = { viewModel.rejectTransferRequest() }) {
                    Text("DECLINE", color = DuoTextSecondary, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DuoSurface,
            titleContentColor = DuoTextPrimary,
            textContentColor = DuoTextSecondary
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
        ConnectionStatus.CONNECTED -> DuoGreen
        ConnectionStatus.CONNECTING -> DuoOrange
        ConnectionStatus.DISCONNECTED -> DuoTextDisabled
        ConnectionStatus.KICKED -> DuoRed
        ConnectionStatus.ERROR -> DuoRed
    }
    val statusText = when (connectionState) {
        ConnectionStatus.CONNECTED -> "CONNECTED"
        ConnectionStatus.CONNECTING -> "CONNECTING..."
        ConnectionStatus.DISCONNECTED -> "DISCONNECTED"
        ConnectionStatus.KICKED -> "KICKED"
        ConnectionStatus.ERROR -> "ERROR"
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = DuoSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status Icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(statusColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    when (connectionState) {
                        ConnectionStatus.CONNECTED -> TablerIcons.Headphones
                        ConnectionStatus.CONNECTING -> TablerIcons.Refresh
                        ConnectionStatus.KICKED -> TablerIcons.X
                        else -> TablerIcons.Unlink
                    },
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = statusColor
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleLarge,
                color = statusColor
            )
            
            if (connectedHost != null && isConnected) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Surface(
                    color = DuoSurfaceHighlight,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = connectedHost.serviceName,
                            style = MaterialTheme.typography.titleMedium,
                            color = DuoTextPrimary
                        )
                        Text(
                            text = "${connectedHost.localIpAddress}:${connectedHost.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = DuoTextSecondary
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                DuolingoButton(
                    text = "DISCONNECT",
                    onClick = onDisconnect,
                    color = DuoRed,
                    shadowColor = DuoRedShadow,
                    height = 45.dp,
                    modifier = Modifier.fillMaxWidth(0.7f)
                )
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = DuoSurface
        ),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AUDIO CONTROLS",
                    style = MaterialTheme.typography.labelLarge,
                    color = DuoTextSecondary
                )
                
                // Mute button
                IconButton(
                    onClick = onMuteToggle,
                    modifier = Modifier
                        .background(
                            if (isMuted) DuoRed.copy(alpha = 0.2f) else DuoBlue.copy(alpha = 0.2f),
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    Icon(
                        if (isMuted) TablerIcons.Volume else TablerIcons.Volume,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = if (isMuted) DuoRed else DuoBlue
                    )
                }
            }
            
            // Volume Slider
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "VOLUME",
                        style = MaterialTheme.typography.labelMedium,
                        color = DuoTextSecondary
                    )
                    Text(
                        text = "${(volume * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = DuoBlue
                    )
                }
                
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    enabled = !isMuted,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        activeTrackColor = DuoBlue,
                        thumbColor = DuoBlue,
                        inactiveTrackColor = DuoSurfaceHighlight
                    )
                )
            }
            
            // Audio Visualizer
            ModernAudioVisualizer(
                level = if (isMuted) 0f else audioLevel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            )
        }
    }
}

@Composable
private fun ModernAudioVisualizer(
    level: Float,
    modifier: Modifier = Modifier
) {
    val primaryColor = DuoGreen
    val surfaceColor = DuoSurfaceHighlight
    
    Canvas(modifier = modifier.clip(RoundedCornerShape(12.dp))) {
        val barCount = 30
        val gap = 4.dp.toPx()
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val maxBarHeight = size.height
        
        for (i in 0 until barCount) {
            val center = barCount / 2f
            val dist = kotlin.math.abs(i - center) / center
            val waveScale = 1f - dist * 0.5f
            
            val randomFactor = 0.7f + kotlin.random.Random.nextFloat() * 0.6f
            val targetHeight = maxBarHeight * level * waveScale * randomFactor
            val barHeight = targetHeight.coerceIn(6.dp.toPx(), maxBarHeight)
            
            val alpha = if (level > 0.01f) 1f else 0.3f
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
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2)
            )
        }
    }
}

@Composable
private fun ConnectingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = DuoSurface
        ),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
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
                strokeWidth = 4.dp,
                color = DuoOrange
            )
            
            Column {
                Text(
                    text = "CONNECTING...",
                    style = MaterialTheme.typography.titleMedium,
                    color = DuoTextPrimary
                )
                Text(
                    text = "Please wait",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DuoTextSecondary
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = DuoSurface
        ),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                TablerIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = DuoTextDisabled
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "NO HOST SELECTED",
                style = MaterialTheme.typography.titleLarge,
                color = DuoTextPrimary
            )
            
            Text(
                text = "Go back and select a host from the discovery screen",
                style = MaterialTheme.typography.bodyMedium,
                color = DuoTextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            DuolingoButton(
                text = "FIND HOSTS",
                onClick = onNavigateBack,
                color = DuoBlue,
                shadowColor = DuoBlueShadow,
                icon = TablerIcons.Search
            )
        }
    }
}
