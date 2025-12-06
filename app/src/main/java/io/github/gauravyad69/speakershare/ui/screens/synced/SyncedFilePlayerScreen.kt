package io.github.gauravyad69.speakershare.ui.screens.synced

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Headphones
import compose.icons.tablericons.Microphone
import compose.icons.tablericons.PlayerPlay
import compose.icons.tablericons.PlayerPause
import compose.icons.tablericons.ArrowsMaximize
import compose.icons.tablericons.ArrowsMinimize
import compose.icons.tablericons.PlayerSkipBack
import compose.icons.tablericons.PlayerSkipForward
import compose.icons.tablericons.RotateClockwise2
import compose.icons.tablericons.Rotate2
import compose.icons.tablericons.DeviceSpeaker
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.Wifi
import compose.icons.tablericons.Plus
import compose.icons.tablericons.Video
import compose.icons.tablericons.Music
import compose.icons.tablericons.Trash
import compose.icons.tablericons.PlayerStop
import compose.icons.tablericons.Unlink
import compose.icons.tablericons.Link
import compose.icons.tablericons.ChartBar
import compose.icons.tablericons.InfoCircle
import compose.icons.tablericons.Volume
import compose.icons.tablericons.Volume2
import compose.icons.tablericons.Volume3
import compose.icons.tablericons.Clock
import io.github.gauravyad69.speakershare.data.model.NetworkInfo
import io.github.gauravyad69.speakershare.media.sync.SyncedMediaFile
import io.github.gauravyad69.speakershare.media.sync.SyncSessionState
import io.github.gauravyad69.speakershare.media.sync.TransferProgress
import io.github.gauravyad69.speakershare.ui.components.DuolingoButton
import io.github.gauravyad69.speakershare.ui.theme.*
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
    // Default to Client mode (1) instead of Host mode (0)
    var selectedTabIndex by rememberSaveable { mutableStateOf(1) }
    
    // Auto-derive mode from session state - if we're in a session, show that mode
    val effectiveTabIndex = when (uiState.sessionState) {
        is SyncSessionState.HostActive -> 0
        is SyncSessionState.ClientJoining, is SyncSessionState.ClientReady -> 1
        else -> selectedTabIndex
    }
    
    val tabs = listOf("HOST", "CLIENT")
    
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
    
    // Note: We don't call stopSession() in DisposableEffect because it would
    // clear files on rotation. Session cleanup happens in ViewModel.onCleared()
    // when the screen is actually destroyed (navigating away).
    
    // Show error toast
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
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
                        if (mediaType == "video") "SYNCED VIDEO" else "SYNCED AUDIO",
                        style = MaterialTheme.typography.labelLarge,
                        color = DuoTextSecondary
                    )
                    Text(
                        if (uiState.sessionState is SyncSessionState.HostActive) "HOSTING" 
                        else if (uiState.sessionState is SyncSessionState.ClientReady) "CONNECTED"
                        else "PLAYER",
                        style = MaterialTheme.typography.titleMedium,
                        color = DuoTextPrimary
                    )
                }
            }
        },
        bottomBar = {
            // Mode selector - always pinned at bottom
            val isInSession = uiState.sessionState is SyncSessionState.HostActive ||
                              uiState.sessionState is SyncSessionState.ClientJoining ||
                              uiState.sessionState is SyncSessionState.ClientReady
            
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                color = DuoSurface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Session status indicator when in session
                    if (isInSession) {
                        val statusText = when (uiState.sessionState) {
                            is SyncSessionState.HostActive -> "Hosting Session"
                            is SyncSessionState.ClientJoining -> "Joining..."
                            is SyncSessionState.ClientReady -> "Connected"
                            else -> ""
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(DuoGreen)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = DuoGreen
                            )
                        }
                    }
                    
                    // Mode selector buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        tabs.forEachIndexed { index, title ->
                            val isSelected = effectiveTabIndex == index
                            val isActiveSession = when (index) {
                                0 -> uiState.sessionState is SyncSessionState.HostActive
                                1 -> uiState.sessionState is SyncSessionState.ClientJoining ||
                                     uiState.sessionState is SyncSessionState.ClientReady
                                else -> false
                            }
                            
                            DuolingoButton(
                                text = title,
                                onClick = { 
                                    if (!isInSession) {
                                        selectedTabIndex = index 
                                    }
                                },
                                icon = if (index == 0) TablerIcons.DeviceSpeaker else TablerIcons.Headphones,
                                color = if (isActiveSession || isSelected) DuoBlue else DuoSurfaceHighlight,
                                shadowColor = if (isActiveSession || isSelected) DuoBlueShadow else DuoOutline,
                                textColor = if (isActiveSession || isSelected) DuoTextPrimary else DuoTextSecondary,
                                modifier = Modifier.weight(1f),
                                enabled = !isInSession || isActiveSession
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
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
                        onVolumeChange = { viewModel.setVolume(it) },
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
    onVolumeChange: (Float) -> Unit,
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
                activeText = "HOSTING • SESSION: ${uiState.sessionId ?: ""}",
                inactiveText = "NOT HOSTING",
                activeColor = DuoGreen
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
                    text = "SELECTED FILES (${selectedFiles.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = DuoTextSecondary
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
                        volume = uiState.volume,
                        onPlay = onPlay,
                        onPause = onPause,
                        onSeek = onSeek,
                        onVolumeChange = onVolumeChange,
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
                        volume = uiState.volume,
                        onPlay = onPlay,
                        onPause = onPause,
                        onSeek = onSeek,
                        onVolumeChange = onVolumeChange,
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
                title = "HOW TO HOST",
                instructions = listOf(
                    "Select ${if (mediaType == "video") "video" else "audio"} file(s) from your device",
                    "Tap 'START HOSTING' to begin the session",
                    "Share the session ID with others on the same network",
                    "All connected clients will play the same file in sync"
                )
            )
        }
        
        item {
            Spacer(modifier = Modifier.height(80.dp))
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
                    "JOINING... (${state.cachedFiles}/${state.totalFiles} FILES READY)"
                }
                is SyncSessionState.ClientReady -> "CONNECTED AND READY"
                else -> "NOT CONNECTED"
            }
            
            StatusBanner(
                isActive = isConnected,
                activeText = statusText,
                inactiveText = "SEARCHING FOR HOSTS...",
                activeColor = DuoBlue
            )
        }
        
        // Discovered Hosts Section
        if (!isConnected) {
            item {
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
                                text = "AVAILABLE HOSTS",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = DuoTextPrimary
                            )
                            
                            IconButton(onClick = onRefreshDiscovery) {
                                if (uiState.isDiscovering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = DuoBlue
                                    )
                                } else {
                                    Icon(
                                        TablerIcons.Refresh, 
                                        contentDescription = "Refresh",
                                        tint = DuoBlue
                                    )
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
                                        strokeWidth = 2.dp,
                                        color = DuoBlue
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Searching for hosts...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = DuoTextSecondary
                                    )
                                } else {
                                    Icon(
                                        TablerIcons.Wifi,
                                        contentDescription = null,
                                        tint = DuoTextDisabled
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "No hosts found",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = DuoTextSecondary
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
                        if (showManualEntry) TablerIcons.PlayerSkipBack else TablerIcons.PlayerSkipForward, // Using arrows as placeholder
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = DuoBlue
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (showManualEntry) "HIDE MANUAL ENTRY" else "ENTER HOST ADDRESS MANUALLY",
                        color = DuoBlue,
                        fontWeight = FontWeight.Bold
                    )
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
                DuolingoButton(
                    text = "DISCONNECT",
                    onClick = onLeaveSession,
                    color = DuoRed,
                    shadowColor = DuoRedShadow,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        
        // Instructions Card
        if (!isConnected) {
            item {
                InstructionsCard(
                    title = "HOW TO JOIN",
                    instructions = listOf(
                        "Make sure you're on the same WiFi network as the host",
                        "Wait for hosts to appear automatically, or enter the IP manually",
                        "Tap on a host to connect",
                        "Files will be downloaded/verified automatically"
                    )
                )
            }
        }
        
        item {
            Spacer(modifier = Modifier.height(80.dp))
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
            containerColor = DuoSurface
        ),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight),
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(DuoBlue),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    TablerIcons.DeviceSpeaker,
                    contentDescription = null,
                    tint = DuoTextPrimary
                )
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = host.serviceName ?: "SpeakerShare Host",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = DuoTextPrimary
                )
                Text(
                    text = "${host.localIpAddress}:${host.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = DuoTextSecondary
                )
            }
            
            Icon(
                TablerIcons.PlayerPlay,
                contentDescription = "Connect",
                tint = DuoBlue
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
        color = if (isActive) activeColor.copy(alpha = 0.1f) else DuoSurfaceHighlight
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
                    .background(if (isActive) activeColor else DuoTextDisabled)
            )
            Text(
                text = if (isActive) activeText else inactiveText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isActive) activeColor else DuoTextSecondary
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DuoSurface),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "MEDIA FILES",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = DuoTextPrimary
            )
            
            if (filesCount > 0) {
                Text(
                    text = "$filesCount file(s) selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DuoGreen
                )
            } else {
                Text(
                    text = "No files selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DuoTextSecondary
                )
            }
            
            DuolingoButton(
                text = if (filesCount > 0) "ADD MORE FILES" else "SELECT FILES",
                onClick = onSelectFiles,
                icon = TablerIcons.Plus,
                color = DuoBlue,
                shadowColor = DuoBlueShadow,
                enabled = !isHosting,
                modifier = Modifier.fillMaxWidth()
            )
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
                DuoSurfaceHighlight 
            else DuoSurface
        ),
        border = if (isCurrentFile) androidx.compose.foundation.BorderStroke(2.dp, DuoBlue) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (file.isVideo) TablerIcons.Video else TablerIcons.Music,
                contentDescription = null,
                tint = if (isCurrentFile) 
                    DuoBlue 
                else DuoTextSecondary,
                modifier = Modifier.size(32.dp)
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isCurrentFile) DuoBlue else DuoTextPrimary
                )
                Text(
                    text = "${formatFileSize(file.sizeBytes)} • ${formatDuration(file.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = DuoTextSecondary
                )
            }
            
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(
                        TablerIcons.Trash,
                        contentDescription = "Remove",
                        tint = DuoRed
                    )
                }
            }
            
            if (isCurrentFile) {
                Icon(
                    TablerIcons.PlayerPlay,
                    contentDescription = "Now Playing",
                    tint = DuoBlue
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
    volume: Float = 1.0f,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: ((Float) -> Unit)? = null,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    hasNext: Boolean,
    hasPrevious: Boolean,
    isReadOnly: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DuoSurface),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
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
                                DuoBlue.copy(alpha = 0.2f),
                                DuoBlue.copy(alpha = 0.05f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    TablerIcons.Music,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = DuoBlue
                )
            }
            
            // File name
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
                color = DuoTextPrimary
            )
            
            // Progress bar
            Column {
                Slider(
                    value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                    onValueChange = { if (!isReadOnly) onSeek((it * duration).toLong()) },
                    enabled = !isReadOnly,
                    colors = SliderDefaults.colors(
                        thumbColor = DuoBlue,
                        activeTrackColor = DuoBlue,
                        inactiveTrackColor = DuoSurfaceHighlight
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDuration(currentPosition),
                        style = MaterialTheme.typography.bodySmall,
                        color = DuoTextSecondary
                    )
                    Text(
                        text = formatDuration(duration),
                        style = MaterialTheme.typography.bodySmall,
                        color = DuoTextSecondary
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
                        Icon(
                            TablerIcons.PlayerSkipBack, 
                            contentDescription = "Previous",
                            tint = if (hasPrevious) DuoTextPrimary else DuoTextDisabled
                        )
                    }
                    
                    IconButton(onClick = { onSeek(maxOf(0, currentPosition - 10000)) }) {
                        Icon(
                            TablerIcons.PlayerSkipBack, // Using skip back as rewind placeholder
                            contentDescription = "Rewind 10s",
                            tint = DuoTextPrimary
                        )
                    }
                    
                    Box(contentAlignment = Alignment.Center) {
                        FilledIconButton(
                            onClick = { if (isPlaying) onPause() else onPlay() },
                            modifier = Modifier.size(64.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = DuoBlue
                            )
                        ) {
                            if (isBuffering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = DuoTextPrimary
                                )
                            } else {
                                Icon(
                                    imageVector = if (isPlaying) TablerIcons.PlayerPause else TablerIcons.PlayerPlay,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    modifier = Modifier.size(32.dp),
                                    tint = DuoTextPrimary
                                )
                            }
                        }
                    }
                    
                    IconButton(onClick = { onSeek(currentPosition + 10000) }) {
                        Icon(
                            TablerIcons.PlayerSkipForward, // Using skip forward as forward placeholder
                            contentDescription = "Forward 10s",
                            tint = DuoTextPrimary
                        )
                    }
                    
                    IconButton(
                        onClick = onNext,
                        enabled = hasNext
                    ) {
                        Icon(
                            TablerIcons.PlayerSkipForward, 
                            contentDescription = "Next",
                            tint = if (hasNext) DuoTextPrimary else DuoTextDisabled
                        )
                    }
                }
                
                // Volume control (only shown for host)
                if (onVolumeChange != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = TablerIcons.Volume,
                            contentDescription = "Volume",
                            tint = DuoTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Slider(
                            value = volume,
                            onValueChange = onVolumeChange,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = DuoTextSecondary,
                                activeTrackColor = DuoTextSecondary,
                                inactiveTrackColor = DuoSurfaceHighlight
                            )
                        )
                        Text(
                            text = "${(volume * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = DuoTextSecondary,
                            modifier = Modifier.width(40.dp)
                        )
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
                        TablerIcons.Refresh,
                        contentDescription = null,
                        tint = if (isPlaying) DuoGreen else DuoTextDisabled,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isPlaying) "Synced with host" else "Waiting for host",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DuoTextSecondary
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
    volume: Float = 1.0f,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: ((Float) -> Unit)? = null,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    hasNext: Boolean,
    hasPrevious: Boolean,
    context: Context,
    isReadOnly: Boolean = false
) {
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    val view = LocalView.current
    
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
    
    // Fullscreen Dialog
    if (isFullscreen) {
        FullscreenVideoDialog(
            exoPlayer = exoPlayer,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            currentPosition = currentPosition,
            duration = duration,
            volume = volume,
            onPlay = onPlay,
            onPause = onPause,
            onSeek = onSeek,
            onVolumeChange = onVolumeChange,
            onNext = onNext,
            onPrevious = onPrevious,
            hasNext = hasNext,
            hasPrevious = hasPrevious,
            isReadOnly = isReadOnly,
            onExitFullscreen = { isFullscreen = false }
        )
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Video player view
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .clickable { isFullscreen = true }
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
                        onClick = { isFullscreen = true },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.5f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = TablerIcons.ArrowsMaximize,
                            contentDescription = "Fullscreen"
                        )
                    }
                }
            }
            
            // Progress bar
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
                        Icon(TablerIcons.PlayerSkipBack, contentDescription = "Previous")
                    }
                    
                    IconButton(onClick = { onSeek(maxOf(0, currentPosition - 10000)) }) {
                        Icon(TablerIcons.Rotate2, contentDescription = "Rewind 10s")
                    }
                    
                    FilledIconButton(
                        onClick = { if (isPlaying) onPause() else onPlay() },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) TablerIcons.PlayerPause else TablerIcons.PlayerPlay,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    
                    IconButton(onClick = { onSeek(currentPosition + 10000) }) {
                        Icon(TablerIcons.RotateClockwise2, contentDescription = "Forward 10s")
                    }
                    
                    IconButton(onClick = onNext, enabled = hasNext) {
                        Icon(TablerIcons.PlayerSkipForward, contentDescription = "Next")
                    }
                }
                
                // Volume control (only shown for host)
                if (onVolumeChange != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (volume == 0f) TablerIcons.Volume3 
                                         else if (volume < 0.5f) TablerIcons.Volume2 
                                         else TablerIcons.Volume,
                            contentDescription = "Volume",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Slider(
                            value = volume,
                            onValueChange = onVolumeChange,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.secondary,
                                activeTrackColor = MaterialTheme.colorScheme.secondary
                            )
                        )
                        Text(
                            text = "${(volume * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(40.dp)
                        )
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
                        TablerIcons.Refresh,
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
        }
    }
}

/**
 * Fullscreen video dialog with immersive mode
 */
@Composable
private fun FullscreenVideoDialog(
    exoPlayer: ExoPlayer?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentPosition: Long,
    duration: Long,
    volume: Float,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: ((Float) -> Unit)?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    hasNext: Boolean,
    hasPrevious: Boolean,
    isReadOnly: Boolean,
    onExitFullscreen: () -> Unit
) {
    val context = LocalContext.current
    var showControls by remember { mutableStateOf(true) }
    
    // Handle back button to exit fullscreen
    BackHandler { onExitFullscreen() }
    
    // Hide controls after 3 seconds
    LaunchedEffect(showControls) {
        if (showControls && isPlaying) {
            kotlinx.coroutines.delay(3000)
            showControls = false
        }
    }
    
    Dialog(
        onDismissRequest = onExitFullscreen,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // Enter immersive mode
        val activity = context as? Activity
        DisposableEffect(Unit) {
            activity?.window?.let { window ->
                // Keep screen on during fullscreen playback
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                
                // Hide system bars for immersive mode
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            
            onDispose {
                activity?.window?.let { window ->
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    WindowCompat.setDecorFitsSystemWindows(window, true)
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    controller.show(WindowInsetsCompat.Type.systemBars())
                    
                    // Restore system bar appearance
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    controller.isAppearanceLightStatusBars = false
                    controller.isAppearanceLightNavigationBars = false
                }
            }
        }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { showControls = !showControls }
        ) {
            // Video player
            exoPlayer?.let { player ->
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // Buffering indicator
            if (isBuffering) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            
            // Controls overlay
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                ) {
                    // Close button (top right)
                    IconButton(
                        onClick = onExitFullscreen,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.5f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            TablerIcons.ArrowsMinimize,
                            contentDescription = "Exit fullscreen"
                        )
                    }
                    
                    // Center playback controls
                    if (!isReadOnly) {
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(32.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { onSeek(maxOf(0, currentPosition - 10000)) },
                                modifier = Modifier.size(56.dp)
                            ) {
                                Icon(
                                    TablerIcons.Rotate2,
                                    contentDescription = "Rewind 10s",
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                            
                            FilledIconButton(
                                onClick = { 
                                    if (isPlaying) onPause() else onPlay()
                                    showControls = true
                                },
                                modifier = Modifier.size(72.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color.White.copy(alpha = 0.9f),
                                    contentColor = Color.Black
                                )
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) TablerIcons.PlayerPause else TablerIcons.PlayerPlay,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                            
                            IconButton(
                                onClick = { onSeek(currentPosition + 10000) },
                                modifier = Modifier.size(56.dp)
                            ) {
                                Icon(
                                    TablerIcons.RotateClockwise2,
                                    contentDescription = "Forward 10s",
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                    }
                    
                    // Bottom progress bar
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Slider(
                            value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                            onValueChange = { if (!isReadOnly) onSeek((it * duration).toLong()) },
                            enabled = !isReadOnly,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                formatDuration(currentPosition),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White
                            )
                            Text(
                                formatDuration(duration),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DuoSurface),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "HOSTING CONTROL",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = DuoTextPrimary
            )
            
            if (isHosting) {
                DuolingoButton(
                    text = "STOP HOSTING",
                    onClick = onStopHosting,
                    icon = TablerIcons.PlayerStop,
                    color = DuoRed,
                    shadowColor = DuoRedShadow,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                DuolingoButton(
                    text = if (isLoading) "STARTING..." else "START HOSTING",
                    onClick = onStartHosting,
                    icon = TablerIcons.Microphone,
                    color = DuoGreen,
                    shadowColor = DuoGreenShadow,
                    enabled = hasFiles && !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                
                if (!hasFiles) {
                    Text(
                        text = "Select files first to start hosting",
                        style = MaterialTheme.typography.bodySmall,
                        color = DuoTextSecondary
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DuoSurface),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "CONNECT TO HOST",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = DuoTextPrimary
            )
            
            OutlinedTextField(
                value = hostAddress,
                onValueChange = onHostAddressChange,
                label = { Text("Host IP Address") },
                placeholder = { Text("192.168.1.x") },
                enabled = !isConnected,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DuoBlue,
                    unfocusedBorderColor = DuoSurfaceHighlight,
                    focusedLabelColor = DuoBlue,
                    unfocusedLabelColor = DuoTextSecondary,
                    cursorColor = DuoBlue,
                    focusedTextColor = DuoTextPrimary,
                    unfocusedTextColor = DuoTextPrimary
                )
            )
            
            OutlinedTextField(
                value = sessionId,
                onValueChange = onSessionIdChange,
                label = { Text("Session ID") },
                enabled = !isConnected,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DuoBlue,
                    unfocusedBorderColor = DuoSurfaceHighlight,
                    focusedLabelColor = DuoBlue,
                    unfocusedLabelColor = DuoTextSecondary,
                    cursorColor = DuoBlue,
                    focusedTextColor = DuoTextPrimary,
                    unfocusedTextColor = DuoTextPrimary
                )
            )
            
            if (isConnected) {
                DuolingoButton(
                    text = "DISCONNECT",
                    onClick = onDisconnect,
                    icon = TablerIcons.Unlink,
                    color = DuoRed,
                    shadowColor = DuoRedShadow,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                DuolingoButton(
                    text = if (isLoading) "CONNECTING..." else "CONNECT",
                    onClick = onConnect,
                    icon = TablerIcons.Link,
                    color = DuoBlue,
                    shadowColor = DuoBlueShadow,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DuoSurface),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "DOWNLOADING FILES",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = DuoTextPrimary
            )
            
            if (progress.isEmpty()) {
                Text(
                    text = "Verifying local files...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DuoTextSecondary
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
                                modifier = Modifier.weight(1f),
                                color = DuoTextPrimary
                            )
                            Text(
                                text = "${(transfer.progressPercent * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = DuoBlue
                            )
                        }
                        LinearProgressIndicator(
                            progress = { transfer.progressPercent },
                            modifier = Modifier.fillMaxWidth(),
                            color = DuoBlue,
                            trackColor = DuoSurfaceHighlight
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
        isMeasuring -> DuoTextDisabled
        absDrift < 30 -> DuoGreen
        absDrift < 70 -> DuoOrange
        else -> DuoRed
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = driftColor.copy(alpha = 0.1f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, driftColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                if (isMeasuring) TablerIcons.Refresh else TablerIcons.Clock,
                contentDescription = null,
                tint = driftColor
            )
            Column {
                Text(
                    text = if (isMeasuring) "SYNC DRIFT: MEASURING..." else "SYNC DRIFT: ${absDrift}ms",
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
                    color = DuoTextSecondary
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
            containerColor = DuoSurface
        ),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
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
                    TablerIcons.ChartBar,
                    contentDescription = null,
                    tint = DuoBlue,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "SYNC STATISTICS",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = DuoTextPrimary
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
                            kotlin.math.abs(clockOffsetMs) < 50 -> DuoGreen
                            kotlin.math.abs(clockOffsetMs) < 200 -> DuoOrange
                            else -> DuoRed
                        }
                    )
                    if (!isHost) {
                        val absDrift = kotlin.math.abs(driftMs)
                        StatRow(
                            label = "Drift",
                            value = if (absDrift == 0L) "..." else "${absDrift}ms",
                            valueColor = when {
                                absDrift == 0L -> DuoTextDisabled
                                absDrift < 30 -> DuoGreen
                                absDrift < 70 -> DuoOrange
                                else -> DuoRed
                            }
                        )
                    }
                }
                
                // Right column
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatRow(
                        label = "Status",
                        value = if (isPlaying) "Playing" else "Paused",
                        valueColor = if (isPlaying) DuoGreen else DuoTextSecondary
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
    valueColor: Color = DuoTextPrimary
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = DuoTextSecondary
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
            containerColor = DuoSurface
        ),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
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
                    TablerIcons.InfoCircle,
                    contentDescription = null,
                    tint = DuoBlue
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = DuoTextPrimary
                )
            }
            
            instructions.forEachIndexed { index, instruction ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DuoBlue,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = instruction,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DuoTextSecondary
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
