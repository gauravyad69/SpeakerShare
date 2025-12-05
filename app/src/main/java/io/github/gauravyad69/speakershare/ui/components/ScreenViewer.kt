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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        modifier = modifier.fillMaxWidth()
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
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        tint = if (isStreaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Screen Share",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isStreaming && screenFrame != null) {
                        // Fullscreen toggle
                        IconButton(onClick = { isFullscreen = true }) {
                            Icon(
                                imageVector = Icons.Default.Fullscreen,
                                contentDescription = "Fullscreen"
                            )
                        }
                        
                        // Reset zoom
                        IconButton(onClick = { 
                            scale = 1f
                            offset = Offset.Zero
                        }) {
                            Icon(
                                imageVector = Icons.Default.ZoomOutMap,
                                contentDescription = "Reset zoom"
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
                    .height(400.dp)
                    .background(Color.Black)
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
                                imageVector = Icons.Default.DesktopAccessDisabled,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Screen sharing not available",
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Host is not sharing screen",
                                color = Color.Gray.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    
                    !isStreaming -> {
                        // Available but not streaming
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayCircleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Screen available",
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = onStartViewing) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Start Viewing")
                            }
                        }
                    }
                    
                    screenFrame != null -> {
                        // Display screen frame with scroll and zoom support
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(horizontalScrollState)
                                .verticalScroll(verticalScrollState)
                        ) {
                            Image(
                                bitmap = screenFrame.asImageBitmap(),
                                contentDescription = "Shared screen",
                                contentScale = ContentScale.None,  // Show actual size for scrolling
                                modifier = Modifier
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale
                                    )
                                    .pointerInput(Unit) {
                                        detectTransformGestures { _, _, zoom, _ ->
                                            scale = (scale * zoom).coerceIn(0.5f, 3f)
                                        }
                                    }
                            )
                        }
                    }
                    
                    else -> {
                        // Streaming but no frame yet
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Loading screen...",
                                color = Color.White
                            )
                        }
                    }
                }
            }
            
            // Stop viewing button when streaming
            if (isStreaming) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onStopViewing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop Viewing")
                }
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
        // Enter immersive mode
        val activity = context as? Activity
        DisposableEffect(Unit) {
            activity?.window?.let { window ->
                // Keep screen on during fullscreen
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
                }
            }
        }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { showControls = !showControls }
        ) {
            // Screen image with zoom support
            Image(
                bitmap = screenFrame.asImageBitmap(),
                contentDescription = "Shared screen",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 3f)
                        }
                    }
            )
            
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
                        .background(Color.Black.copy(alpha = 0.3f))
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
                            Icons.Default.FullscreenExit,
                            contentDescription = "Exit fullscreen"
                        )
                    }
                    
                    // Zoom reset button (top left)
                    if (scale != 1f) {
                        IconButton(
                            onClick = { scale = 1f },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(16.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color.Black.copy(alpha = 0.5f),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                Icons.Default.ZoomOutMap,
                                contentDescription = "Reset zoom"
                            )
                        }
                    }
                    
                    // Zoom indicator (bottom center)
                    Text(
                        text = "${(scale * 100).toInt()}%",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
