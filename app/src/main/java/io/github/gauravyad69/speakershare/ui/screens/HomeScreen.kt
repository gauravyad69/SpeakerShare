package io.github.gauravyad69.speakershare.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gauravyad69.speakershare.ui.theme.*

/**
 * Modern Home Screen with organized streaming modes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onHostModeSelected: () -> Unit,
    onClientModeSelected: () -> Unit,
    onSettingsClick: () -> Unit,
    onSyncedAudioPlayerSelected: () -> Unit = {},
    onSyncedVideoPlayerSelected: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    // Permission states
    var showMicPermissionDialog by remember { mutableStateOf(false) }
    var showBatteryOptimizationDialog by remember { mutableStateOf(false) }
    var hasMicPermission by remember { mutableStateOf(false) }
    var permissionsChecked by remember { mutableStateOf(false) }
    
    // Mic permission launcher
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (!isGranted) {
            showMicPermissionDialog = true
        } else {
            // Check battery optimization after mic permission is granted
            checkBatteryOptimization(context) { isDisabled ->
                if (!isDisabled) {
                    showBatteryOptimizationDialog = true
                }
            }
        }
    }
    
    // Check permissions on first load
    LaunchedEffect(Unit) {
        if (!permissionsChecked) {
            permissionsChecked = true
            val micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == 
                android.content.pm.PackageManager.PERMISSION_GRANTED
            hasMicPermission = micGranted
            
            if (!micGranted) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                checkBatteryOptimization(context) { isDisabled ->
                    if (!isDisabled) {
                        showBatteryOptimizationDialog = true
                    }
                }
            }
        }
    }
    
    // Permission Dialogs
    if (showMicPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showMicPermissionDialog = false },
            title = { Text("Microphone Permission Required") },
            text = { 
                Text("SpeakerShare needs microphone access to broadcast audio in Host mode. Please grant the permission in Settings.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showMicPermissionDialog = false
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    })
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showMicPermissionDialog = false }) { Text("Later") }
            }
        )
    }
    
    if (showBatteryOptimizationDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryOptimizationDialog = false },
            title = { Text("Disable Battery Optimization") },
            text = { 
                Text("To prevent audio interruptions when the screen is off, please disable battery optimization for SpeakerShare.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryOptimizationDialog = false
                    requestBatteryOptimizationExemption(context)
                }) { Text("Disable Optimization") }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryOptimizationDialog = false }) { Text("Later") }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
        ) {
            // Header Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    )
                    .padding(24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Logo/Icon
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.SpatialAudio,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "SpeakerShare",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    
                    Text(
                        text = "Stream Audio Anywhere",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Quick Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Host Button
                QuickActionCard(
                    icon = Icons.Filled.Podcasts,
                    title = "Start Hosting",
                    subtitle = "Broadcast audio",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = onHostModeSelected,
                    modifier = Modifier.weight(1f)
                )
                
                // Join Button
                QuickActionCard(
                    icon = Icons.Filled.Headphones,
                    title = "Join Session",
                    subtitle = "Listen to host",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onClientModeSelected,
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Synced Playback Section - Main Feature
            SectionHeader(
                title = "Synced File Playback",
                subtitle = "All devices play the same file in perfect sync",
                icon = Icons.Outlined.Sync
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StreamingModeCard(
                    icon = Icons.Filled.MusicNote,
                    title = "Synced Audio",
                    description = "Select audio files and play them perfectly synced across devices",
                    accentColor = SystemAudioColor,
                    onClick = onSyncedAudioPlayerSelected,
                    badge = "New"
                )
                
                StreamingModeCard(
                    icon = Icons.Filled.Videocam,
                    title = "Synced Video",
                    description = "Watch videos together with audio synced across devices",
                    accentColor = ScreenAudioColor,
                    onClick = onSyncedVideoPlayerSelected,
                    badge = "New"
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Tips Section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Outlined.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Pro Tip",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Make sure all devices are connected to the same WiFi network for the best experience.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(100.dp)) // Space for bottom nav
        }
    }
}

@Composable
private fun QuickActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(120.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = contentColor
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = contentColor
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = contentColor.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamingModeCard(
    icon: ImageVector,
    title: String,
    description: String,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    enabled: Boolean = true
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp,
            disabledElevation = 0.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon with accent background
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (enabled) accentColor.copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (enabled) accentColor else Color.Gray
                )
            }
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    
                    badge?.let {
                        Surface(
                            color = if (enabled && it == "New") accentColor else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = it,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = if (enabled && it == "New") Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
            
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else Color.Gray.copy(alpha = 0.3f)
            )
        }
    }
}

private fun checkBatteryOptimization(context: android.content.Context, onResult: (Boolean) -> Unit) {
    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
    val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    onResult(isIgnoring)
}

private fun requestBatteryOptimizationExemption(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to battery optimization settings
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                // Ignore if we can't open settings
            }
        }
    }
}
