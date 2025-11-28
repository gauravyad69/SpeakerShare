package io.github.gauravyad69.speakershare.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.gauravyad69.speakershare.data.model.NetworkInfo
import io.github.gauravyad69.speakershare.network.discovery.DiscoveredHost
import io.github.gauravyad69.speakershare.ui.viewmodels.ClientViewModel

import io.github.gauravyad69.speakershare.data.model.DiscoveryMethod

/**
 * Client Screen for connecting to hosts and controlling playback
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(
    onNavigateBack: () -> Unit,
    initialHostIp: String? = null,
    initialHostPort: Int? = null,
    initialHostName: String? = null,
    viewModel: ClientViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedHost by viewModel.connectedHost.collectAsState()
    val volume by viewModel.volume.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(initialHostIp, initialHostPort, initialHostName) {
        if (initialHostIp != null && initialHostPort != null && initialHostName != null) {
            if (connectionState == io.github.gauravyad69.speakershare.data.model.ConnectionStatus.DISCONNECTED) {
                viewModel.connectToHost(
                    NetworkInfo(
                        localIpAddress = initialHostIp,
                        port = initialHostPort,
                        networkInterface = "wlan0",
                        isHotspot = false,
                        discoveryMethod = DiscoveryMethod.MDNS, // Default
                        serviceName = initialHostName
                    )
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SpeakerShare Client") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { /* TODO: Implement refresh discovery */ },
                        enabled = !uiState.isLoading
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
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
            // Connection Status Card
            ConnectionStatusCard(
                isConnected = connectionState == io.github.gauravyad69.speakershare.data.model.ConnectionStatus.CONNECTED,
                connectedHost = connectedHost,
                connectionStatus = connectionState.toString(),
                onDisconnect = { viewModel.disconnect() }
            )

            // Audio Controls (shown when connected)
            if (connectionState == io.github.gauravyad69.speakershare.data.model.ConnectionStatus.CONNECTED) {
                AudioControlsCard(
                    volume = volume,
                    isMuted = isMuted,
                    onVolumeChange = { viewModel.setVolume(it) },
                    onMuteToggle = { viewModel.toggleMute() }
                )

                // Audio Visualizer
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Audio Visualizer",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AudioVisualizer(level = audioLevel)
                    }
                }

                // Audio Visualizer
                AudioVisualizer(level = volume)
            }

            // Discovery Section
            if (connectionState != io.github.gauravyad69.speakershare.data.model.ConnectionStatus.CONNECTED) {
                DiscoverySection(
                    isDiscovering = uiState.isLoading,
                    discoveredHosts = emptyList(), // TODO: Integrate with DiscoveryViewModel
                    onConnectToHost = { host -> 
                        // TODO: Convert DiscoveredHost to NetworkInfo or change ClientViewModel.connectToHost signature
                        // viewModel.connectToHost(host)
                    },
                    onRefreshDiscovery = { /* TODO: Implement refresh discovery */ }
                )
            }

            // Connection Statistics (when connected)
            // TODO: Add connection statistics when available
            // if (connectionState == io.github.gauravyad69.speakershare.data.model.ConnectionStatus.CONNECTED) {
            //     ConnectionStatsCard(stats = connectionStats)
            // }
        }
    }

    // Handle connection errors
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            // Show error snackbar or dialog
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    isConnected: Boolean,
    connectedHost: NetworkInfo?,
    connectionStatus: String,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Connection Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                if (isConnected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Connected",
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = "Disconnected",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Text(
                text = connectionStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (connectedHost != null) {
                Divider()
                
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Service: ${connectedHost.serviceName}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "IP: ${connectedHost.localIpAddress}:${connectedHost.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Discovery: ${connectedHost.discoveryMethod}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
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
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Audio Controls",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconButton(
                    onClick = onMuteToggle,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surface,
                        contentColor = if (isMuted) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (isMuted) "Unmute" else "Mute"
                    )
                }

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Volume: ${(volume * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Slider(
                        value = volume,
                        onValueChange = onVolumeChange,
                        enabled = !isMuted,
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoverySection(
    isDiscovering: Boolean,
    discoveredHosts: List<DiscoveredHost>,
    onConnectToHost: (DiscoveredHost) -> Unit,
    onRefreshDiscovery: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Available Hosts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (isDiscovering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            if (discoveredHosts.isEmpty() && !isDiscovering) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No hosts found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onRefreshDiscovery) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Again")
                    }
                }
            } else if (discoveredHosts.isNotEmpty()) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    items(discoveredHosts) { host ->
                        HostListItem(
                            host = host,
                            onConnect = { onConnectToHost(host) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HostListItem(
    host: DiscoveredHost,
    onConnect: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = host.hostName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (host.discoveryMethod.contains("WEBRTC", ignoreCase = true)) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = "WebRTC",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (host.discoveryMethod.contains("UDP", ignoreCase = true)) {
                        Icon(
                            Icons.Default.Router,
                            contentDescription = "UDP",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${host.ipAddress}:${host.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Clients: ${host.connectedClients}/${if (host.maxClients > 0) host.maxClients else "∞"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = onConnect,
                    enabled = host.isAcceptingClients,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Connect")
                }
            }

            if (!host.isAcceptingClients) {
                Text(
                    text = "Not accepting new clients",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatsCard(
    stats: Map<String, Any>
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Connection Statistics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            stats.entries.forEach { (key, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun AudioVisualizer(
    level: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxWidth().height(60.dp)) {
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