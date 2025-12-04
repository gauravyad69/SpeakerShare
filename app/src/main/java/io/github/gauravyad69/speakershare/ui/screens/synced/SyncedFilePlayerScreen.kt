package io.github.gauravyad69.speakershare.ui.screens.synced

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.gauravyad69.speakershare.data.model.NetworkInfo
import io.github.gauravyad69.speakershare.media.sync.SyncedMediaFile
import io.github.gauravyad69.speakershare.media.sync.SyncSessionState
import io.github.gauravyad69.speakershare.media.sync.TransferProgress
import io.github.gauravyad69.speakershare.ui.viewmodels.SyncedFilePlayerViewModel
import io.github.gauravyad69.speakershare.ui.viewmodels.SyncedPlayerUiState
import androidx.compose.runtime.saveable.rememberSaveable
import java.util.concurrent.TimeUnit

/**
 * Composable screen for synchronized file playback
 * Supports both host (sharing) and client (receiving) modes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncedFilePlayerScreen(
    mediaType: String = "audio", // "audio" or "video"
    onNavigateBack: () -> Unit,
    viewModel: SyncedFilePlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val selectedFiles by viewModel.selectedFiles.collectAsState()
    val discoveredHosts by viewModel.discoveredHosts.collectAsState()
    
    // Use rememberSaveable to survive configuration changes (rotation)
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    
    // Auto-derive mode from session state - if we're in a session, show that mode
    val effectiveTabIndex = when (uiState.sessionState) {
        is SyncSessionState.HostActive -> 0
        is SyncSessionState.ClientJoining, is SyncSessionState.ClientReady -> 1
        else -> selectedTabIndex
    }
    
    val tabs = listOf("Host", "Client")
    
    // File picker launcher for multiple files
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.addFiles(uris)
        }
    }
    
    // Initialize player when screen is created
    LaunchedEffect(Unit) {
        viewModel.initializePlayer()
    }
    
    // Always run discovery in background to find available hosts
    LaunchedEffect(Unit) {
        viewModel.startDiscovery()
    }
    
    // Clean up when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopSession()
        }
    }
    
    // Show error toast
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (mediaType == "video") "Synced Video" else "Synced Audio",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Mode selector - only show when not in active session
            val isInSession = uiState.sessionState is SyncSessionState.HostActive ||
                              uiState.sessionState is SyncSessionState.ClientJoining ||
                              uiState.sessionState is SyncSessionState.ClientReady
            
            if (!isInSession) {
                // Segmented button style mode selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tabs.forEachIndexed { index, title ->
                        val isSelected = effectiveTabIndex == index
                        FilledTonalButton(
                            onClick = { selectedTabIndex = index },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isSelected) 
                                    MaterialTheme.colorScheme.primaryContainer 
                                else 
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Icon(
                                imageVector = if (index == 0) Icons.Filled.Podcasts else Icons.Filled.Headphones,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(title)
                        }
                    }
                }
            }
            
            // Show available hosts count when in host mode setup
            if (!isInSession && effectiveTabIndex == 0 && discoveredHosts.isNotEmpty()) {
                Text(
                    text = "${discoveredHosts.size} host(s) available on network",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            
            // Content based on selected tab (use effectiveTabIndex for rotation survival)
            AnimatedContent(
                targetState = effectiveTabIndex,
                transitionSpec = {
                    fadeIn() + slideInHorizontally() togetherWith fadeOut() + slideOutHorizontally()
                },
                label = "TabContent"
            ) { tabIndex ->
                when (tabIndex) {
                    0 -> HostModeContent(
                        uiState = uiState,
                        selectedFiles = selectedFiles,
                        mediaType = mediaType,
                        onSelectFiles = {
                            val mimeType = if (mediaType == "video") "video/*" else "audio/*"
                            filePickerLauncher.launch(mimeType)
                        },
                        onRemoveFile = { index -> viewModel.removeFile(index) },
                        onPlay = { viewModel.play() },
                        onPause = { viewModel.pause() },
                        onSeek = { viewModel.seekTo(it) },
                        onNextTrack = { viewModel.nextTrack() },
                        onPreviousTrack = { viewModel.previousTrack() },
                        onStartHosting = { viewModel.startHostSession() },
                        onStopHosting = { viewModel.stopSession() },
                        context = context
                    )
                    1 -> ClientModeContent(
                        uiState = uiState,
                        mediaType = mediaType,
                        discoveredHosts = discoveredHosts,
                        onJoinSession = { host, sessionId, files -> 
                            viewModel.joinSession(host, sessionId, files)
                        },
                        onLeaveSession = { viewModel.stopSession() },
                        onRefreshDiscovery = { viewModel.startDiscovery() },
                        context = context
                    )
                }
            }
        }
    }
}

@Composable
private fun HostModeContent(
    uiState: SyncedPlayerUiState,
    selectedFiles: List<SyncedMediaFile>,
    mediaType: String,
    onSelectFiles: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onStartHosting: () -> Unit,
    onStopHosting: () -> Unit,
    context: Context
) {
    val isHostActive = uiState.sessionState is SyncSessionState.HostActive
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status Banner
        item {
            StatusBanner(
                isActive = isHostActive,
                activeText = "Hosting • Session: ${uiState.sessionId ?: ""}",
                inactiveText = "Not hosting",
                activeColor = MaterialTheme.colorScheme.primary
            )
        }
        
        // File Selection Card
        item {
            FileSelectionCard(
                filesCount = selectedFiles.size,
                onSelectFiles = onSelectFiles,
                isHosting = isHostActive
            )
        }
        
        // Selected Files List
        if (selectedFiles.isNotEmpty()) {
            item {
                Text(
                    text = "Selected Files (${selectedFiles.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            itemsIndexed(selectedFiles) { index, file ->
                FileItemCard(
                    file = file,
                    isCurrentFile = uiState.currentFile?.uri == file.uri,
                    onRemove = { onRemoveFile(index) },
                    canRemove = !isHostActive
                )
            }
        }
        
        // Media Player Card (if files selected and hosting)
        if (isHostActive && uiState.currentFile != null) {
            item {
                if (mediaType == "video") {
                    VideoPlayerCard(
                        file = uiState.currentFile!!,
                        isPlaying = uiState.isPlaying,
                        isBuffering = uiState.isBuffering,
                        currentPosition = uiState.currentPositionMs,
                        duration = uiState.durationMs,
                        onPlay = onPlay,
                        onPause = onPause,
                        onSeek = onSeek,
                        onNext = onNextTrack,
                        onPrevious = onPreviousTrack,
                        hasNext = true,
                        hasPrevious = true,
                        context = context
                    )
                } else {
                    AudioPlayerCard(
                        file = uiState.currentFile!!,
                        isPlaying = uiState.isPlaying,
                        isBuffering = uiState.isBuffering,
                        currentPosition = uiState.currentPositionMs,
                        duration = uiState.durationMs,
                        onPlay = onPlay,
                        onPause = onPause,
                        onSeek = onSeek,
                        onNext = onNextTrack,
                        onPrevious = onPreviousTrack,
                        hasNext = true,
                        hasPrevious = true
                    )
                }
            }
        }
        
        // Host Control Card
        item {
            HostControlCard(
                isHosting = isHostActive,
                hasFiles = selectedFiles.isNotEmpty(),
                isLoading = uiState.isLoading,
                onStartHosting = onStartHosting,
                onStopHosting = onStopHosting
            )
        }
        
        // Drift indicator - always show when hosting
        if (isHostActive) {
            item {
                DriftIndicatorCard(driftMs = uiState.driftMs)
            }
        }
        
        // Sync Stats Card (always show when hosting)
        if (isHostActive) {
            item {
                SyncStatsCard(
                    isHost = true,
                    clockOffsetMs = uiState.clockOffsetMs,
                    driftMs = uiState.driftMs,
                    connectedClientsCount = uiState.connectedClientsCount,
                    isPlaying = uiState.isPlaying,
                    currentPositionMs = uiState.currentPositionMs,
                    durationMs = uiState.durationMs
                )
            }
        }
        
        // Instructions Card
        item {
            InstructionsCard(
                title = "How to Host",
                instructions = listOf(
                    "Select ${if (mediaType == "video") "video" else "audio"} file(s) from your device",
                    "Tap 'Start Hosting' to begin the session",
                    "Share the session ID with others on the same network",
                    "All connected clients will play the same file in sync"
                )
            )
        }
    }
}

@Composable
private fun ClientModeContent(
    uiState: SyncedPlayerUiState,
    mediaType: String,
    discoveredHosts: List<NetworkInfo>,
    onJoinSession: (String, String, List<SyncedMediaFile>) -> Unit,
    onLeaveSession: () -> Unit,
    onRefreshDiscovery: () -> Unit,
    context: Context
) {
    var hostAddress by remember { mutableStateOf("") }
    var sessionId by remember { mutableStateOf("") }
    var showManualEntry by remember { mutableStateOf(false) }
    
    val isConnected = uiState.sessionState is SyncSessionState.ClientReady ||
                      uiState.sessionState is SyncSessionState.ClientJoining
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status Banner
        item {
            val statusText = when (uiState.sessionState) {
                is SyncSessionState.ClientJoining -> {
                    val state = uiState.sessionState as SyncSessionState.ClientJoining
                    "Joining... (${state.cachedFiles}/${state.totalFiles} files ready)"
                }
                is SyncSessionState.ClientReady -> "Connected and ready"
                else -> "Not connected"
            }
            
            StatusBanner(
                isActive = isConnected,
                activeText = statusText,
                inactiveText = "Searching for hosts...",
                activeColor = MaterialTheme.colorScheme.secondary
            )
        }
        
        // Discovered Hosts Section
        if (!isConnected) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
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
                                text = "Available Hosts",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            
                            IconButton(onClick = onRefreshDiscovery) {
                                if (uiState.isDiscovering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                                }
                            }
                        }
                        
                        if (discoveredHosts.isEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (uiState.isDiscovering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Searching for hosts on the network...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Icon(
                                        Icons.Outlined.WifiFind,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "No hosts found",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // List of discovered hosts
            items(discoveredHosts) { host ->
                DiscoveredHostCard(
                    host = host,
                    onClick = {
                        // Auto-fill and connect
                        hostAddress = host.localIpAddress
                        sessionId = host.serviceName ?: "default"
                        onJoinSession(host.localIpAddress, sessionId, emptyList())
                    },
                    isLoading = uiState.isLoading
                )
            }
            
            // Manual entry toggle
            item {
                TextButton(
                    onClick = { showManualEntry = !showManualEntry },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        if (showManualEntry) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (showManualEntry) "Hide Manual Entry" else "Enter Host Address Manually")
                }
            }
            
            // Manual Connection Card (expandable)
            if (showManualEntry) {
                item {
                    ConnectionCard(
                        hostAddress = hostAddress,
                        sessionId = sessionId,
                        onHostAddressChange = { hostAddress = it },
                        onSessionIdChange = { sessionId = it },
                        isConnected = isConnected,
                        isLoading = uiState.isLoading,
                        onConnect = {
                            if (hostAddress.isNotBlank()) {
                                onJoinSession(hostAddress, sessionId.ifBlank { "default" }, emptyList())
                            } else {
                                Toast.makeText(context, "Enter host address", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDisconnect = onLeaveSession
                    )
                }
            }
        }
        
        // Transfer Progress Card (when joining)
        if (uiState.sessionState is SyncSessionState.ClientJoining) {
            item {
                TransferProgressCard(
                    progress = uiState.transferProgress
                )
            }
        }
        
        // Playback Status Card (when ready)
        if (uiState.sessionState is SyncSessionState.ClientReady && uiState.currentFile != null) {
            item {
                if (mediaType == "video") {
                    VideoPlayerCard(
                        file = uiState.currentFile!!,
                        isPlaying = uiState.isPlaying,
                        isBuffering = uiState.isBuffering,
                        currentPosition = uiState.currentPositionMs,
                        duration = uiState.durationMs,
                        onPlay = { },
                        onPause = { },
                        onSeek = { },
                        onNext = { },
                        onPrevious = { },
                        hasNext = false,
                        hasPrevious = false,
                        context = context,
                        isReadOnly = true
                    )
                } else {
                    AudioPlayerCard(
                        file = uiState.currentFile!!,
                        isPlaying = uiState.isPlaying,
                        isBuffering = uiState.isBuffering,
                        currentPosition = uiState.currentPositionMs,
                        duration = uiState.durationMs,
                        onPlay = { },
                        onPause = { },
                        onSeek = { },
                        onNext = { },
                        onPrevious = { },
                        hasNext = false,
                        hasPrevious = false,
                        isReadOnly = true
                    )
                }
            }
            
            // Drift indicator for client - always show when connected
            item {
                DriftIndicatorCard(driftMs = uiState.driftMs)
            }
            
            // Sync Stats Card for client
            item {
                SyncStatsCard(
                    isHost = false,
                    clockOffsetMs = uiState.clockOffsetMs,
                    driftMs = uiState.driftMs,
                    connectedClientsCount = 0,
                    isPlaying = uiState.isPlaying,
                    currentPositionMs = uiState.currentPositionMs,
                    durationMs = uiState.durationMs
                )
            }
            
            // Disconnect button
            item {
                Button(
                    onClick = onLeaveSession,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.LinkOff, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disconnect")
                }
            }
        }
        
        // Instructions Card
        if (!isConnected) {
            item {
                InstructionsCard(
                    title = "How to Join",
                    instructions = listOf(
                        "Make sure you're on the same WiFi network as the host",
                        "Wait for hosts to appear automatically, or enter the IP manually",
                        "Tap on a host to connect",
                        "Files will be downloaded/verified automatically"
                    )
                )
            }
        }
    }
}

@Composable
private fun DiscoveredHostCard(
    host: NetworkInfo,
    onClick: () -> Unit,
    isLoading: Boolean
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        ),
        enabled = !isLoading
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Podcasts,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = host.serviceName ?: "SpeakerShare Host",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${host.localIpAddress}:${host.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "Connect",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ==================== UI Components ====================

@Composable
private fun StatusBanner(
    isActive: Boolean,
    activeText: String,
    inactiveText: String,
    activeColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) activeColor.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isActive) activeColor else MaterialTheme.colorScheme.outline)
            )
            Text(
                text = if (isActive) activeText else inactiveText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isActive) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FileSelectionCard(
    filesCount: Int,
    onSelectFiles: () -> Unit,
    isHosting: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Media Files",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            if (filesCount > 0) {
                Text(
                    text = "$filesCount file(s) selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = "No files selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Button(
                onClick = onSelectFiles,
                enabled = !isHosting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (filesCount > 0) "Add More Files" else "Select Files")
            }
        }
    }
}

@Composable
private fun FileItemCard(
    file: SyncedMediaFile,
    isCurrentFile: Boolean,
    onRemove: () -> Unit,
    canRemove: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentFile) 
                MaterialTheme.colorScheme.primaryContainer 
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (file.isVideo) Icons.Filled.VideoFile else Icons.Filled.AudioFile,
                contentDescription = null,
                tint = if (isCurrentFile) 
                    MaterialTheme.colorScheme.onPrimaryContainer 
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatFileSize(file.sizeBytes)} • ${formatDuration(file.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            if (isCurrentFile) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Now Playing",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun AudioPlayerCard(
    file: SyncedMediaFile,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentPosition: Long,
    duration: Long,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    hasNext: Boolean,
    hasPrevious: Boolean,
    isReadOnly: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Album art placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            // File name
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            
            // Progress bar
            Column {
                Slider(
                    value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                    onValueChange = { if (!isReadOnly) onSeek((it * duration).toLong()) },
                    enabled = !isReadOnly,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDuration(currentPosition),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = formatDuration(duration),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            // Playback controls
            if (!isReadOnly) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onPrevious,
                        enabled = hasPrevious
                    ) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                    }
                    
                    IconButton(onClick = { onSeek(maxOf(0, currentPosition - 10000)) }) {
                        Icon(Icons.Filled.Replay10, contentDescription = "Rewind 10s")
                    }
                    
                    Box(contentAlignment = Alignment.Center) {
                        FilledIconButton(
                            onClick = { if (isPlaying) onPause() else onPlay() },
                            modifier = Modifier.size(64.dp)
                        ) {
                            if (isBuffering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                    
                    IconButton(onClick = { onSeek(currentPosition + 10000) }) {
                        Icon(Icons.Filled.Forward10, contentDescription = "Forward 10s")
                    }
                    
                    IconButton(
                        onClick = onNext,
                        enabled = hasNext
                    ) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                    }
                }
            } else {
                // Sync indicator for clients
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Sync,
                        contentDescription = null,
                        tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isPlaying) "Synced with host" else "Waiting for host",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoPlayerCard(
    file: SyncedMediaFile,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentPosition: Long,
    duration: Long,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    hasNext: Boolean,
    hasPrevious: Boolean,
    context: Context,
    isReadOnly: Boolean = false
) {
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    
    // Create ExoPlayer
    DisposableEffect(file.uri) {
        val player = ExoPlayer.Builder(context).build()
        exoPlayer = player
        
        val mediaItem = MediaItem.fromUri(file.localUri ?: file.uri)
        player.setMediaItem(mediaItem)
        player.prepare()
        
        onDispose {
            player.release()
        }
    }
    
    // Sync playback state
    LaunchedEffect(isPlaying, exoPlayer) {
        exoPlayer?.let { player ->
            if (isPlaying && !player.isPlaying) {
                player.play()
            } else if (!isPlaying && player.isPlaying) {
                player.pause()
            }
        }
    }
    
    // Sync position
    LaunchedEffect(currentPosition, exoPlayer) {
        exoPlayer?.let { player ->
            val diff = kotlin.math.abs(player.currentPosition - currentPosition)
            if (diff > 500) {
                player.seekTo(currentPosition)
            }
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Video player view with fullscreen support
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isFullscreen) Modifier.fillMaxHeight()
                        else Modifier.aspectRatio(16f / 9f)
                    )
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
            ) {
                exoPlayer?.let { player ->
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                useController = false
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                // Buffering indicator
                if (isBuffering) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
                
                // Fullscreen toggle button overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.TopEnd
                ) {
                    IconButton(
                        onClick = { isFullscreen = !isFullscreen },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.5f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen"
                        )
                    }
                }
            }
            
            // Hide controls in fullscreen mode
            if (!isFullscreen) {
                Column {
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                        onValueChange = { if (!isReadOnly) onSeek((it * duration).toLong()) },
                        enabled = !isReadOnly
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatDuration(currentPosition), style = MaterialTheme.typography.bodySmall)
                        Text(formatDuration(duration), style = MaterialTheme.typography.bodySmall)
                    }
                }
            
                // Playback controls
                if (!isReadOnly) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onPrevious, enabled = hasPrevious) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                        }
                        
                        IconButton(onClick = { onSeek(maxOf(0, currentPosition - 10000)) }) {
                            Icon(Icons.Filled.Replay10, contentDescription = "Rewind 10s")
                        }
                        
                        FilledIconButton(
                            onClick = { if (isPlaying) onPause() else onPlay() },
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        
                        IconButton(onClick = { onSeek(currentPosition + 10000) }) {
                            Icon(Icons.Filled.Forward10, contentDescription = "Forward 10s")
                        }
                        
                        IconButton(onClick = onNext, enabled = hasNext) {
                            Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                        }
                    }
                } else {
                    // Sync indicator
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Sync,
                            contentDescription = null,
                            tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isPlaying) "Synced with host" else "Waiting for host",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } // End of !isFullscreen
        }
    }
}

@Composable
private fun HostControlCard(
    isHosting: Boolean,
    hasFiles: Boolean,
    isLoading: Boolean,
    onStartHosting: () -> Unit,
    onStopHosting: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Hosting Control",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            if (isHosting) {
                Button(
                    onClick = onStopHosting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop Hosting")
                }
            } else {
                Button(
                    onClick = onStartHosting,
                    enabled = hasFiles && !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Starting...")
                    } else {
                        Icon(Icons.Filled.Podcasts, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start Hosting")
                    }
                }
                
                if (!hasFiles) {
                    Text(
                        text = "Select files first to start hosting",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionCard(
    hostAddress: String,
    sessionId: String,
    onHostAddressChange: (String) -> Unit,
    onSessionIdChange: (String) -> Unit,
    isConnected: Boolean,
    isLoading: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Connect to Host",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            OutlinedTextField(
                value = hostAddress,
                onValueChange = onHostAddressChange,
                label = { Text("Host IP Address") },
                placeholder = { Text("192.168.1.x") },
                enabled = !isConnected,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            OutlinedTextField(
                value = sessionId,
                onValueChange = onSessionIdChange,
                label = { Text("Session ID") },
                enabled = !isConnected,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            if (isConnected) {
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.LinkOff, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disconnect")
                }
            } else {
                Button(
                    onClick = onConnect,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connecting...")
                    } else {
                        Icon(Icons.Filled.Link, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connect")
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferProgressCard(
    progress: Map<String, TransferProgress>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Downloading Files",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            if (progress.isEmpty()) {
                Text(
                    text = "Verifying local files...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                progress.forEach { (fileName, transfer) ->
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = fileName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${(transfer.progressPercent * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        LinearProgressIndicator(
                            progress = { transfer.progressPercent },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DriftIndicatorCard(driftMs: Long) {
    val absDrift = kotlin.math.abs(driftMs)
    val isMeasuring = absDrift == 0L
    
    val driftColor = when {
        isMeasuring -> MaterialTheme.colorScheme.outline
        absDrift < 30 -> MaterialTheme.colorScheme.primary
        absDrift < 70 -> Color(0xFFFF9800) // Orange
        else -> MaterialTheme.colorScheme.error
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = driftColor.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                if (isMeasuring) Icons.Filled.Sync else Icons.Filled.Timer,
                contentDescription = null,
                tint = driftColor
            )
            Column {
                Text(
                    text = if (isMeasuring) "Sync Drift: Measuring..." else "Sync Drift: ${absDrift}ms",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = driftColor
                )
                Text(
                    text = when {
                        isMeasuring -> "Waiting for sync data..."
                        absDrift < 30 -> "Excellent sync (< 30ms)"
                        absDrift < 70 -> "Good sync (< 70ms)"
                        absDrift < 150 -> "Acceptable sync"
                        else -> "High drift - try reconnecting"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SyncStatsCard(
    isHost: Boolean,
    clockOffsetMs: Long,
    driftMs: Long,
    connectedClientsCount: Int,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Analytics,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Sync Statistics",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left column
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatRow(
                        label = "Role",
                        value = if (isHost) "Host" else "Client"
                    )
                    StatRow(
                        label = "Clock Offset",
                        value = "${clockOffsetMs}ms",
                        valueColor = when {
                            kotlin.math.abs(clockOffsetMs) < 50 -> MaterialTheme.colorScheme.primary
                            kotlin.math.abs(clockOffsetMs) < 200 -> Color(0xFFFF9800)
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                    if (!isHost) {
                        val absDrift = kotlin.math.abs(driftMs)
                        StatRow(
                            label = "Drift",
                            value = if (absDrift == 0L) "..." else "${absDrift}ms",
                            valueColor = when {
                                absDrift == 0L -> MaterialTheme.colorScheme.outline
                                absDrift < 30 -> MaterialTheme.colorScheme.primary
                                absDrift < 70 -> Color(0xFFFF9800)
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
                
                // Right column
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatRow(
                        label = "Status",
                        value = if (isPlaying) "Playing" else "Paused",
                        valueColor = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isHost) {
                        StatRow(
                            label = "Clients",
                            value = connectedClientsCount.toString()
                        )
                    }
                    StatRow(
                        label = "Progress",
                        value = "${(currentPositionMs * 100 / maxOf(durationMs, 1)).toInt()}%"
                    )
                }
            }
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

@Composable
private fun InstructionsCard(
    title: String,
    instructions: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            instructions.forEachIndexed { index, instruction ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = instruction,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ==================== Utility Functions ====================

private fun formatDuration(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> String.format("%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format("%.2f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format("%.2f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
