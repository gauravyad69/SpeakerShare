package io.github.gauravyad69.speakershare.ui.components

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowsMaximize
import compose.icons.tablericons.ArrowsMinimize
import compose.icons.tablericons.DeviceTv
import compose.icons.tablericons.DeviceTv
import compose.icons.tablericons.Eye
import compose.icons.tablericons.EyeOff
import compose.icons.tablericons.ZoomOut
import io.github.gauravyad69.speakershare.ui.theme.*
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Screen viewer component for displaying shared screen from host
 */
@Composable
fun ScreenViewer(
    screenFrame: Bitmap?,
    isScreenAvailable: Boolean,
    isStreaming: Boolean,
    onStartViewing: () -> Unit,
    onStopViewing: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isFullscreen by remember { mutableStateOf(false) }
    
    // Fullscreen Dialog
    if (isFullscreen && screenFrame != null) {
        FullscreenScreenDialog(
            screenFrame = screenFrame,
            onExitFullscreen = { isFullscreen = false }
        )
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = DuoSurface
        ),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = TablerIcons.DeviceTv,
                        contentDescription = null,
                        tint = if (isStreaming) DuoGreen else DuoTextSecondary
                    )
                    Text(
                        text = "Screen Share",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DuoTextPrimary
                    )
                }
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isStreaming && screenFrame != null) {
                        // Fullscreen toggle
                        IconButton(onClick = { isFullscreen = true }) {
                            Icon(
                                imageVector = TablerIcons.ArrowsMaximize,
                                contentDescription = "Fullscreen",
                                tint = DuoBlue
                            )
                        }
                        
                        // Reset zoom
                        IconButton(onClick = { 
                            scale = 1f
                            offset = Offset.Zero
                        }) {
                            Icon(
                                imageVector = TablerIcons.ZoomOut,
                                contentDescription = "Reset zoom",
                                tint = DuoTextSecondary
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Screen content with scroll support
            val horizontalScrollState = rememberScrollState()
            val verticalScrollState = rememberScrollState()
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .background(DuoBackground, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = screenFrame != null && isStreaming) { isFullscreen = true },
                contentAlignment = Alignment.Center
            ) {
                when {
                    !isScreenAvailable -> {
                        // Screen sharing not available from host
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = TablerIcons.DeviceTv,
                                contentDescription = null,
                                tint = DuoTextSecondary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Screen sharing not available",
                                color = DuoTextSecondary,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    !isStreaming -> {
                        // Streaming paused
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = TablerIcons.DeviceTv,
                                contentDescription = null,
                                tint = DuoSurfaceHighlight,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Screen sharing paused",
                                color = DuoTextSecondary,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    screenFrame == null -> {
                        // Waiting for frame
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = DuoGreen,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Waiting for video...",
                                color = DuoTextSecondary,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    else -> {
                        // Display screen frame
                        Image(
                            bitmap = screenFrame.asImageBitmap(),
                            contentDescription = "Shared Screen",
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(1f, 5f)
                                        val maxOffset = (scale - 1) * 1000 // Approximate bounds
                                        offset = Offset(
                                            (offset.x + pan.x).coerceIn(-maxOffset, maxOffset),
                                            (offset.y + pan.y).coerceIn(-maxOffset, maxOffset)
                                        )
                                    }
                                }
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                ),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Controls
            if (isScreenAvailable) {
                DuolingoButton(
                    text = if (isStreaming) "STOP VIEWING" else "VIEW SCREEN",
                    onClick = {
                        if (isStreaming) onStopViewing() else onStartViewing()
                    },
                    color = if (isStreaming) DuoRed else DuoGreen,
                    shadowColor = if (isStreaming) DuoRedShadow else DuoGreenShadow,
                    icon = if (isStreaming) TablerIcons.EyeOff else TablerIcons.Eye,
                    height = 48.dp
                )
            } else {
                Text(
                    text = "Host is not sharing screen",
                    style = MaterialTheme.typography.bodySmall,
                    color = DuoTextSecondary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Fullscreen dialog for screen share viewing with immersive mode
 */
@Composable
private fun FullscreenScreenDialog(
    screenFrame: Bitmap,
    onExitFullscreen: () -> Unit
) {
    val context = LocalContext.current
    var showControls by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    
    // Handle back button to exit fullscreen
    BackHandler { onExitFullscreen() }
    
    // Hide controls after 3 seconds
    LaunchedEffect(showControls) {
        if (showControls) {
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
        val window = (LocalView.current.parent as? android.app.Dialog)?.window
        SideEffect {
            window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                val controller = WindowCompat.getInsetsController(it, it.decorView)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        val maxOffset = (scale - 1) * 1000 // Approximate bounds
                        offset = Offset(
                            (offset.x + pan.x).coerceIn(-maxOffset, maxOffset),
                            (offset.y + pan.y).coerceIn(-maxOffset, maxOffset)
                        )
                        showControls = true
                    }
                }
                .clickable { showControls = !showControls },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = screenFrame.asImageBitmap(),
                contentDescription = "Fullscreen Screen Share",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                contentScale = ContentScale.Fit
            )
            
            // Overlay controls
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                IconButton(
                    onClick = onExitFullscreen,
                    modifier = Modifier
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                ) {
                    Icon(
                        imageVector = TablerIcons.ArrowsMinimize,
                        contentDescription = "Exit Fullscreen",
                        tint = Color.White
                    )
                }
            }
            
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Row(
                    modifier = Modifier
                        .padding(bottom = 32.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { 
                        scale = 1f
                        offset = Offset.Zero
                    }) {
                        Icon(
                            imageVector = TablerIcons.ZoomOut,
                            contentDescription = "Reset Zoom",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}
