@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.gauravyad69.speakershare.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.gauravyad69.speakershare.media.sync.*
import io.github.gauravyad69.speakershare.ui.theme.*
import io.github.gauravyad69.speakershare.ui.viewmodels.SyncedFilePlayerViewModel
import io.github.gauravyad69.speakershare.ui.viewmodels.SyncedPlayerUiState
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Synced File Player Mode
 */
enum class SyncedPlayerMode {
    AUDIO,
    VIDEO
}

/**
 * Synchronized File Player Screen
 * 
 * Enables near-zero latency playback across multiple devices by:
 * - Transferring files to clients (one-time)
 * - Using clock synchronization for precise play timing
 * - All devices play the same file at the same position
 */
@Composable
fun SyncedFilePlayerScreen(
    mode: SyncedPlayerMode,
    onNavigateBack: () -> Unit,
    viewModel: SyncedFilePlayerViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val selectedFiles by viewModel.selectedFiles.collectAsState()
    
    // Initialize player
    LaunchedEffect(Unit) {
        viewModel.initializePlayer()
    }
    
    // File picker
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addFiles(uris)
        }
    }
    
    // Background gradient
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0D1B2A),
            Color(0xFF1B263B),
            Color(0xFF415A77)
        )
    )
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundGradient)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            SyncedPlayerTopBar(
                mode = mode,
                uiState = uiState,
                onNavigateBack = onNavigateBack
            )
            
            when {
                // No files selected - show file selection
                selectedFiles.isEmpty() && uiState.sessionState is SyncSessionState.Idle -> {
                    FileSelectionContent(
                        mode = mode,
                        onSelectFiles = {
                            val mimeType = if (mode == SyncedPlayerMode.AUDIO) "audio/*" else "video/*"
                            filePicker.launch(arrayOf(mimeType))
                        }
                    )
                }
                
                // Files selected but no session - show start session
                selectedFiles.isNotEmpty() && uiState.sessionState is SyncSessionState.Idle -> {
                    SessionSetupContent(
                        files = selectedFiles,
                        mode = mode,
                        onAddMoreFiles = {
                            val mimeType = if (mode == SyncedPlayerMode.AUDIO) "audio/*" else "video/*"
                            filePicker.launch(arrayOf(mimeType))
                        },
                        onRemoveFile = { viewModel.removeFile(it) },
                        onStartSession = { viewModel.startHostSession() }
                    )
                }
                
                // Session active - show player
                uiState.sessionState is SyncSessionState.HostActive ||
                uiState.sessionState is SyncSessionState.ClientReady -> {
                    ActiveSessionContent(
                        uiState = uiState,
                        mode = mode,
                        onPlay = { viewModel.play() },
                        onPause = { viewModel.pause() },
                        onSeek = { viewModel.seekTo(it) },
                        onNext = { viewModel.nextTrack() },
                        onPrevious = { viewModel.previousTrack() },
                        onStopSession = { viewModel.stopSession() }
                    )
                }
                
                // Client joining
                uiState.sessionState is SyncSessionState.ClientJoining -> {
                    ClientJoiningContent(
                        state = uiState.sessionState as SyncSessionState.ClientJoining,
                        transferProgress = uiState.transferProgress
                    )
                }
                
                // Error state
                uiState.sessionState is SyncSessionState.Error -> {
                    ErrorContent(
                        message = (uiState.sessionState as SyncSessionState.Error).message,
                        onRetry = { viewModel.stopSession() }
                    )
                }
            }
        }
        
        // Loading overlay
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = StreamingPrimary)
            }
        }
        
        // Error snackbar
        uiState.error?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("Dismiss", color = StreamingPrimary)
                    }
                }
            ) {
                Text(error)
            }
        }
    }
}

@Composable
private fun SyncedPlayerTopBar(
    mode: SyncedPlayerMode,
    uiState: SyncedPlayerUiState,
    onNavigateBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onNavigateBack) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (mode == SyncedPlayerMode.AUDIO) "Synced Audio" else "Synced Video",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            
            // Sync indicator
            if (uiState.sessionState is SyncSessionState.HostActive ||
                uiState.sessionState is SyncSessionState.ClientReady) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Pulsing sync indicator
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )
                    
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .alpha(alpha)
                            .background(Color.Green, CircleShape)
                    )
                    Text(
                        text = if (uiState.isHost) "Hosting" else "Synced",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    
                    // Show drift if significant
                    if (kotlin.math.abs(uiState.driftMs) > 10) {
                        Text(
                            text = "(${uiState.driftMs}ms)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (kotlin.math.abs(uiState.driftMs) > 30) 
                                Color.Yellow else Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
        
        // Placeholder for symmetry
        Box(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun FileSelectionContent(
    mode: SyncedPlayerMode,
    onSelectFiles: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Animated icon
            val infiniteTransition = rememberInfiniteTransition(label = "icon")
            val scale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )
            
            Card(
                modifier = Modifier.size(120.dp),
                shape = CircleShape,
                colors = CardDefaults.cardColors(
                    containerColor = StreamingPrimary.copy(alpha = 0.2f)
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.SyncAlt,
                        contentDescription = null,
                        modifier = Modifier.size(60.dp),
                        tint = StreamingPrimary
                    )
                }
            }
            
            Text(
                text = "Synchronized Playback",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "Select ${if (mode == SyncedPlayerMode.AUDIO) "audio" else "video"} files to play in perfect sync across all connected devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            
            // Benefits list
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.1f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BenefitItem(
                        icon = Icons.Outlined.Speed,
                        title = "Near-Zero Latency",
                        description = "10-20ms sync vs 100-500ms streaming"
                    )
                    BenefitItem(
                        icon = Icons.Outlined.HighQuality,
                        title = "Original Quality",
                        description = "No compression artifacts"
                    )
                    BenefitItem(
                        icon = Icons.Outlined.Cloud,
                        title = "Cached Files",
                        description = "Download once, play instantly"
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = onSelectFiles,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StreamingPrimary
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "Select ${if (mode == SyncedPlayerMode.AUDIO) "Audio" else "Video"} Files",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun BenefitItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = StreamingPrimary,
            modifier = Modifier.size(24.dp)
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SessionSetupContent(
    files: List<SyncedMediaFile>,
    mode: SyncedPlayerMode,
    onAddMoreFiles: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    onStartSession: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = "Selected Files (${files.size})",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        
        // File list
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(files) { index, file ->
                FileListItem(
                    file = file,
                    index = index,
                    mode = mode,
                    onRemove = { onRemoveFile(index) }
                )
            }
            
            // Add more button
            item {
                OutlinedButton(
                    onClick = onAddMoreFiles,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = StreamingPrimary
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add More Files")
                }
            }
        }
        
        // Start session button
        Button(
            onClick = onStartSession,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = StreamingPrimary
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                "Start Synced Session",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        
        // Info text
        Text(
            text = "Connected clients will download these files for synchronized playback.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun FileListItem(
    file: SyncedMediaFile,
    index: Int,
    mode: SyncedPlayerMode,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Index
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(StreamingPrimary.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            }
            
            // File info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatFileSize(file.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            
            // Remove button
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ActiveSessionContent(
    uiState: SyncedPlayerUiState,
    mode: SyncedPlayerMode,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onStopSession: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.3f))
        
        // Album art / Now playing
        Card(
            modifier = Modifier.size(200.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = StreamingPrimary.copy(alpha = 0.2f)
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (mode == SyncedPlayerMode.AUDIO) Icons.Outlined.MusicNote 
                    else Icons.Outlined.Movie,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = StreamingPrimary
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Track name
        uiState.currentFile?.let { file ->
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Progress bar
        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = if (uiState.durationMs > 0) 
                    uiState.currentPositionMs.toFloat() / uiState.durationMs else 0f,
                onValueChange = { fraction ->
                    onSeek((fraction * uiState.durationMs).toLong())
                },
                colors = SliderDefaults.colors(
                    thumbColor = StreamingPrimary,
                    activeTrackColor = StreamingPrimary
                ),
                enabled = uiState.isHost
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(uiState.currentPositionMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Text(
                    text = formatDuration(uiState.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Playback controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Previous
            IconButton(
                onClick = onPrevious,
                enabled = uiState.isHost
            ) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
            
            // Play/Pause
            FilledIconButton(
                onClick = { if (uiState.isPlaying) onPause() else onPlay() },
                modifier = Modifier.size(72.dp),
                enabled = uiState.isHost,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = StreamingPrimary
                )
            ) {
                Icon(
                    if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(40.dp)
                )
            }
            
            // Next
            IconButton(
                onClick = onNext,
                enabled = uiState.isHost
            ) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        
        // Client notice
        if (!uiState.isHost) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Controls are managed by the host",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Stop session button
        OutlinedButton(
            onClick = onStopSession,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color.Red
            )
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (uiState.isHost) "End Session" else "Leave Session")
        }
        
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ClientJoiningContent(
    state: SyncSessionState.ClientJoining,
    transferProgress: Map<String, TransferProgress>
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            CircularProgressIndicator(color = StreamingPrimary)
            
            Text(
                text = "Joining Session",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "Downloading files: ${state.cachedFiles}/${state.totalFiles}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
            
            // Download progress
            state.downloadingFiles.forEach { file ->
                val progress = transferProgress[file.contentHash]
                if (progress != null) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        LinearProgressIndicator(
                            progress = { progress.progressPercent },
                            modifier = Modifier.fillMaxWidth(),
                            color = StreamingPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = Color.Red,
                modifier = Modifier.size(64.dp)
            )
            
            Text(
                text = "Something went wrong",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )
            
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StreamingPrimary
                )
            ) {
                Text("Try Again")
            }
        }
    }
}

// Utility functions
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(minutes, seconds)
}
