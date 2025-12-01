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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gauravyad69.speakershare.ui.theme.*
import kotlinx.coroutines.delay

/**
 * File Player Mode types
 */
enum class FilePlayerMode {
    AUDIO,
    VIDEO
}

/**
 * Media item data class
 */
data class MediaItem(
    val uri: Uri,
    val name: String,
    val duration: Long = 0L,
    val mimeType: String = ""
)

/**
 * Playback state for file player
 */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val isBroadcasting: Boolean = false,
    val connectedClients: Int = 0
)

/**
 * File Player Screen for broadcasting audio/video files
 */
@Composable
fun FilePlayerScreen(
    mode: FilePlayerMode,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // State for selected media files
    var selectedFiles by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var currentFileIndex by remember { mutableIntStateOf(0) }
    
    // Playback state
    var playbackState by remember { mutableStateOf(PlaybackState()) }
    
    // UI state
    var showFileSelector by remember { mutableStateOf(selectedFiles.isEmpty()) }
    var showPlaylist by remember { mutableStateOf(false) }
    var showQualitySettings by remember { mutableStateOf(false) }
    
    // Animation states
    val infiniteTransition = rememberInfiniteTransition(label = "background")
    val animatedRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(30000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    // File picker launchers
    val audioFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedFiles = uris.map { uri ->
                MediaItem(
                    uri = uri,
                    name = getFileNameFromUri(context, uri) ?: "Unknown File",
                    mimeType = context.contentResolver.getType(uri) ?: ""
                )
            }
            currentFileIndex = 0
            showFileSelector = false
        }
    }
    
    val videoFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedFiles = uris.map { uri ->
                MediaItem(
                    uri = uri,
                    name = getFileNameFromUri(context, uri) ?: "Unknown File",
                    mimeType = context.contentResolver.getType(uri) ?: ""
                )
            }
            currentFileIndex = 0
            showFileSelector = false
        }
    }
    
    // Background gradient based on mode
    val backgroundGradient = if (mode == FilePlayerMode.AUDIO) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF1A1A2E),
                Color(0xFF16213E),
                Color(0xFF0F3460)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF1A0A2E),
                Color(0xFF2D1B46),
                Color(0xFF0F2027)
            )
        )
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundGradient)
    ) {
        // Animated background elements
        AnimatedBackgroundEffects(
            rotation = animatedRotation,
            isPlaying = playbackState.isPlaying,
            mode = mode
        )
        
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top Bar
            FilePlayerTopBar(
                mode = mode,
                isBroadcasting = playbackState.isBroadcasting,
                connectedClients = playbackState.connectedClients,
                onNavigateBack = onNavigateBack,
                onShowPlaylist = { showPlaylist = true },
                onShowSettings = { showQualitySettings = true }
            )
            
            // Main Content
            if (showFileSelector || selectedFiles.isEmpty()) {
                // File Selection UI
                FileSelectionView(
                    mode = mode,
                    onSelectFiles = {
                        when (mode) {
                            FilePlayerMode.AUDIO -> audioFilePicker.launch(arrayOf("audio/*"))
                            FilePlayerMode.VIDEO -> videoFilePicker.launch(arrayOf("video/*"))
                        }
                    }
                )
            } else {
                // Player UI
                PlayerView(
                    currentFile = selectedFiles.getOrNull(currentFileIndex),
                    playbackState = playbackState,
                    mode = mode,
                    hasNext = currentFileIndex < selectedFiles.size - 1,
                    hasPrevious = currentFileIndex > 0,
                    onPlayPause = { 
                        playbackState = playbackState.copy(isPlaying = !playbackState.isPlaying)
                    },
                    onNext = { 
                        if (currentFileIndex < selectedFiles.size - 1) {
                            currentFileIndex++
                        }
                    },
                    onPrevious = { 
                        if (currentFileIndex > 0) {
                            currentFileIndex--
                        }
                    },
                    onSeek = { position ->
                        playbackState = playbackState.copy(currentPosition = position)
                    },
                    onToggleBroadcast = {
                        playbackState = playbackState.copy(
                            isBroadcasting = !playbackState.isBroadcasting
                        )
                    },
                    onSelectNewFiles = { showFileSelector = true }
                )
            }
        }
        
        // Playlist Sheet
        if (showPlaylist) {
            PlaylistBottomSheet(
                files = selectedFiles,
                currentIndex = currentFileIndex,
                onDismiss = { showPlaylist = false },
                onSelectFile = { index ->
                    currentFileIndex = index
                    showPlaylist = false
                },
                onRemoveFile = { index ->
                    selectedFiles = selectedFiles.toMutableList().apply { removeAt(index) }
                    if (currentFileIndex >= selectedFiles.size) {
                        currentFileIndex = (selectedFiles.size - 1).coerceAtLeast(0)
                    }
                },
                onAddFiles = {
                    when (mode) {
                        FilePlayerMode.AUDIO -> audioFilePicker.launch(arrayOf("audio/*"))
                        FilePlayerMode.VIDEO -> videoFilePicker.launch(arrayOf("video/*"))
                    }
                }
            )
        }
        
        // Quality Settings Dialog
        if (showQualitySettings) {
            QualitySettingsDialog(
                mode = mode,
                onDismiss = { showQualitySettings = false }
            )
        }
    }
}

@Composable
private fun AnimatedBackgroundEffects(
    rotation: Float,
    isPlaying: Boolean,
    mode: FilePlayerMode
) {
    val color1 = if (mode == FilePlayerMode.AUDIO) StreamingPrimary else Color(0xFF9C27B0)
    val color2 = if (mode == FilePlayerMode.AUDIO) StreamingSecondary else Color(0xFF673AB7)
    
    val scale by animateFloatAsState(
        targetValue = if (isPlaying) 1.1f else 1.0f,
        animationSpec = tween(1000),
        label = "scale"
    )
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Large blurred circle 1
        Box(
            modifier = Modifier
                .size(300.dp)
                .offset(x = (-50).dp, y = 100.dp)
                .rotate(rotation)
                .scale(scale)
                .blur(100.dp)
                .alpha(0.3f)
                .background(color1, CircleShape)
        )
        
        // Large blurred circle 2
        Box(
            modifier = Modifier
                .size(250.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 50.dp, y = (-100).dp)
                .rotate(-rotation)
                .scale(scale)
                .blur(80.dp)
                .alpha(0.25f)
                .background(color2, CircleShape)
        )
    }
}

@Composable
private fun FilePlayerTopBar(
    mode: FilePlayerMode,
    isBroadcasting: Boolean,
    connectedClients: Int,
    onNavigateBack: () -> Unit,
    onShowPlaylist: () -> Unit,
    onShowSettings: () -> Unit
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
                text = if (mode == FilePlayerMode.AUDIO) "Audio Player" else "Video Player",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            if (isBroadcasting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color.Red, CircleShape)
                    )
                    Text(
                        text = "$connectedClients listening",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
        
        Row {
            IconButton(onClick = onShowPlaylist) {
                Icon(
                    Icons.Default.QueueMusic,
                    contentDescription = "Playlist",
                    tint = Color.White
                )
            }
            IconButton(onClick = onShowSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun FileSelectionView(
    mode: FilePlayerMode,
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
            val iconScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = EaseInOutCubic),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )
            
            Card(
                modifier = Modifier
                    .size(160.dp)
                    .scale(iconScale),
                shape = CircleShape,
                colors = CardDefaults.cardColors(
                    containerColor = if (mode == FilePlayerMode.AUDIO) 
                        StreamingPrimary.copy(alpha = 0.2f) 
                    else 
                        Color(0xFF9C27B0).copy(alpha = 0.2f)
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (mode == FilePlayerMode.AUDIO) Icons.Outlined.MusicNote else Icons.Outlined.VideoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = if (mode == FilePlayerMode.AUDIO) StreamingPrimary else Color(0xFF9C27B0)
                    )
                }
            }
            
            Text(
                text = if (mode == FilePlayerMode.AUDIO) 
                    "Select Audio Files" 
                else 
                    "Select Video Files",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = if (mode == FilePlayerMode.AUDIO)
                    "Choose music files to broadcast to connected devices"
                else
                    "Choose video files to stream to connected devices",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Select files button
            Button(
                onClick = onSelectFiles,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (mode == FilePlayerMode.AUDIO) StreamingPrimary else Color(0xFF9C27B0)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Browse Files",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            // Supported formats
            Text(
                text = if (mode == FilePlayerMode.AUDIO)
                    "Supported: MP3, M4A, WAV, FLAC, OGG, AAC"
                else
                    "Supported: MP4, MKV, AVI, MOV, WEBM",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun PlayerView(
    currentFile: MediaItem?,
    playbackState: PlaybackState,
    mode: FilePlayerMode,
    hasNext: Boolean,
    hasPrevious: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleBroadcast: () -> Unit,
    onSelectNewFiles: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.5f))
        
        // Album art / Video preview placeholder
        AlbumArtView(
            isPlaying = playbackState.isPlaying,
            mode = mode
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Track info
        currentFile?.let { file ->
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = file.mimeType.ifEmpty { "Unknown format" },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Progress bar
        PlaybackProgressBar(
            currentPosition = playbackState.currentPosition,
            duration = playbackState.duration.takeIf { it > 0 } ?: 180000L, // Default 3 min
            onSeek = onSeek
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Playback controls
        PlaybackControls(
            isPlaying = playbackState.isPlaying,
            hasNext = hasNext,
            hasPrevious = hasPrevious,
            mode = mode,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPrevious = onPrevious
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Broadcast controls
        BroadcastControls(
            isBroadcasting = playbackState.isBroadcasting,
            connectedClients = playbackState.connectedClients,
            mode = mode,
            onToggleBroadcast = onToggleBroadcast
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Select new files button
        TextButton(onClick = onSelectNewFiles) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color.White.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Select Different Files",
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
private fun AlbumArtView(
    isPlaying: Boolean,
    mode: FilePlayerMode
) {
    val infiniteTransition = rememberInfiniteTransition(label = "album")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.9f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )
    
    Card(
        modifier = Modifier
            .size(280.dp)
            .scale(scale)
            .then(if (isPlaying && mode == FilePlayerMode.AUDIO) Modifier.rotate(rotation) else Modifier),
        shape = CircleShape,
        colors = CardDefaults.cardColors(
            containerColor = if (mode == FilePlayerMode.AUDIO)
                StreamingPrimary.copy(alpha = 0.3f)
            else
                Color(0xFF9C27B0).copy(alpha = 0.3f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Outer ring
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .background(
                        if (mode == FilePlayerMode.AUDIO)
                            StreamingPrimary.copy(alpha = 0.2f)
                        else
                            Color(0xFF9C27B0).copy(alpha = 0.2f),
                        CircleShape
                    )
            )
            
            // Inner content
            Icon(
                if (mode == FilePlayerMode.AUDIO) 
                    Icons.Default.MusicNote 
                else 
                    Icons.Default.PlayCircle,
                contentDescription = null,
                modifier = Modifier.size(100.dp),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun PlaybackProgressBar(
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit
) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isSliding by remember { mutableStateOf(false) }
    
    LaunchedEffect(currentPosition, isSliding) {
        if (!isSliding) {
            sliderPosition = (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        }
    }
    
    Column {
        Slider(
            value = sliderPosition,
            onValueChange = { 
                isSliding = true
                sliderPosition = it 
            },
            onValueChangeFinished = {
                isSliding = false
                onSeek((sliderPosition * duration).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = StreamingPrimary,
                activeTrackColor = StreamingPrimary,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            )
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration((sliderPosition * duration).toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    hasNext: Boolean,
    hasPrevious: Boolean,
    mode: FilePlayerMode,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Shuffle
        IconButton(onClick = { /* TODO: Implement shuffle */ }) {
            Icon(
                Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = Color.White.copy(alpha = 0.6f)
            )
        }
        
        // Previous
        IconButton(
            onClick = onPrevious,
            enabled = hasPrevious
        ) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                modifier = Modifier.size(40.dp),
                tint = if (hasPrevious) Color.White else Color.White.copy(alpha = 0.3f)
            )
        }
        
        // Play/Pause
        val playButtonScale by animateFloatAsState(
            targetValue = if (isPlaying) 1.0f else 1.1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "playScale"
        )
        
        FloatingActionButton(
            onClick = onPlayPause,
            modifier = Modifier
                .size(72.dp)
                .scale(playButtonScale),
            containerColor = if (mode == FilePlayerMode.AUDIO) StreamingPrimary else Color(0xFF9C27B0),
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 8.dp,
                pressedElevation = 12.dp
            )
        ) {
            AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    scaleIn() togetherWith scaleOut()
                },
                label = "playIcon"
            ) { playing ->
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    modifier = Modifier.size(36.dp),
                    tint = Color.White
                )
            }
        }
        
        // Next
        IconButton(
            onClick = onNext,
            enabled = hasNext
        ) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "Next",
                modifier = Modifier.size(40.dp),
                tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.3f)
            )
        }
        
        // Repeat
        IconButton(onClick = { /* TODO: Implement repeat */ }) {
            Icon(
                Icons.Default.Repeat,
                contentDescription = "Repeat",
                tint = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun BroadcastControls(
    isBroadcasting: Boolean,
    connectedClients: Int,
    mode: FilePlayerMode,
    onToggleBroadcast: () -> Unit
) {
    val accentColor = if (mode == FilePlayerMode.AUDIO) StreamingPrimary else Color(0xFF9C27B0)
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Broadcast Mode",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isBroadcasting) 
                            "$connectedClients device${if (connectedClients != 1) "s" else ""} connected" 
                        else 
                            "Share ${if (mode == FilePlayerMode.AUDIO) "audio" else "video"} with nearby devices",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
                
                Switch(
                    checked = isBroadcasting,
                    onCheckedChange = { onToggleBroadcast() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = accentColor,
                        checkedTrackColor = accentColor.copy(alpha = 0.5f),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.2f)
                    )
                )
            }
            
            if (isBroadcasting) {
                // Animated broadcast indicator
                val pulseAnim by rememberInfiniteTransition(label = "pulse").animateFloat(
                    initialValue = 1f,
                    targetValue = 1.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse"
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .scale(pulseAnim)
                            .background(Color.Red, CircleShape)
                    )
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Red,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistBottomSheet(
    files: List<MediaItem>,
    currentIndex: Int,
    onDismiss: () -> Unit,
    onSelectFile: (Int) -> Unit,
    onRemoveFile: (Int) -> Unit,
    onAddFiles: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A2E),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Playlist (${files.size} files)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                TextButton(onClick = onAddFiles) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(files.size) { index ->
                    val file = files[index]
                    val isCurrentPlaying = index == currentIndex
                    
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectFile(index) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrentPlaying) 
                                StreamingPrimary.copy(alpha = 0.2f) 
                            else 
                                Color.White.copy(alpha = 0.05f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isCurrentPlaying) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = StreamingPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                } else {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.width(24.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                                
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isCurrentPlaying) StreamingPrimary else Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            
                            IconButton(
                                onClick = { onRemoveFile(index) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun QualitySettingsDialog(
    mode: FilePlayerMode,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A2E),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = {
            Text("Broadcast Quality")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (mode == FilePlayerMode.AUDIO) {
                    QualityOption(
                        title = "High Quality",
                        subtitle = "320 kbps AAC - Best audio fidelity",
                        selected = true,
                        onClick = { }
                    )
                    QualityOption(
                        title = "Balanced",
                        subtitle = "192 kbps AAC - Good quality, lower bandwidth",
                        selected = false,
                        onClick = { }
                    )
                    QualityOption(
                        title = "Low Bandwidth",
                        subtitle = "128 kbps AAC - For slower networks",
                        selected = false,
                        onClick = { }
                    )
                } else {
                    QualityOption(
                        title = "High Quality (1080p)",
                        subtitle = "Full HD streaming",
                        selected = true,
                        onClick = { }
                    )
                    QualityOption(
                        title = "Standard (720p)",
                        subtitle = "Good quality, moderate bandwidth",
                        selected = false,
                        onClick = { }
                    )
                    QualityOption(
                        title = "Low (480p)",
                        subtitle = "For slower networks",
                        selected = false,
                        onClick = { }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = StreamingPrimary)
            }
        }
    )
}

@Composable
private fun QualityOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) 
                StreamingPrimary.copy(alpha = 0.2f) 
            else 
                Color.White.copy(alpha = 0.05f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = StreamingPrimary
                )
            }
        }
    }
}

// Utility functions
private fun getFileNameFromUri(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        }
    } catch (e: Exception) {
        uri.lastPathSegment
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
