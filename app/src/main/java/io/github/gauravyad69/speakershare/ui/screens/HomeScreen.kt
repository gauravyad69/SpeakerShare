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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.TablerIcons
import compose.icons.tablericons.DeviceSpeaker
import compose.icons.tablericons.Headphones
import compose.icons.tablericons.Microphone
import compose.icons.tablericons.Music
import compose.icons.tablericons.Settings
import compose.icons.tablericons.Video
import compose.icons.tablericons.Wifi
import io.github.gauravyad69.speakershare.ui.components.DuolingoButton
import io.github.gauravyad69.speakershare.ui.theme.*

/**
 * Modern Home Screen with Duolingo-inspired Dark UI
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
            title = { Text("Microphone Permission Required", style = MaterialTheme.typography.titleLarge) },
            text = { 
                Text("SpeakerShare needs microphone access to broadcast audio in Host mode. Please grant the permission in Settings.", style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                DuolingoButton(
                    text = "OPEN SETTINGS",
                    onClick = {
                        showMicPermissionDialog = false
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        })
                    },
                    height = 40.dp
                )
            },
            dismissButton = {
                TextButton(onClick = { showMicPermissionDialog = false }) { 
                    Text("LATER", color = DuoTextSecondary, fontWeight = FontWeight.Bold) 
                }
            },
            containerColor = DuoSurface,
            titleContentColor = DuoTextPrimary,
            textContentColor = DuoTextSecondary
        )
    }
    
    if (showBatteryOptimizationDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryOptimizationDialog = false },
            title = { Text("Disable Battery Optimization", style = MaterialTheme.typography.titleLarge) },
            text = { 
                Text("To prevent audio interruptions when the screen is off, please disable battery optimization for SpeakerShare.", style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                DuolingoButton(
                    text = "DISABLE",
                    onClick = {
                        showBatteryOptimizationDialog = false
                        requestBatteryOptimizationExemption(context)
                    },
                    height = 40.dp
                )
            },
            dismissButton = {
                TextButton(onClick = { showBatteryOptimizationDialog = false }) { 
                    Text("LATER", color = DuoTextSecondary, fontWeight = FontWeight.Bold) 
                }
            },
            containerColor = DuoSurface,
            titleContentColor = DuoTextPrimary,
            textContentColor = DuoTextSecondary
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DuoBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "SpeakerShare",
                    style = MaterialTheme.typography.displaySmall,
                    color = DuoTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        TablerIcons.Settings,
                        contentDescription = "Settings",
                        tint = DuoTextSecondary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            
            Text(
                text = "Stream Audio Anywhere",
                style = MaterialTheme.typography.titleMedium,
                color = DuoTextSecondary
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Live Streaming Section
            SectionTitle("LIVE STREAMING")
            
            Spacer(modifier = Modifier.height(16.dp))
            
            DuolingoButton(
                text = "START HOSTING",
                onClick = onHostModeSelected,
                icon = TablerIcons.Microphone,
                color = DuoGreen,
                shadowColor = DuoGreenShadow,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            DuolingoButton(
                text = "JOIN SESSION",
                onClick = onClientModeSelected,
                icon = TablerIcons.Headphones,
                color = DuoBlue,
                shadowColor = DuoBlueShadow,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Synced Playback Section
            SectionTitle("SYNCED PLAYBACK")
            
            Spacer(modifier = Modifier.height(16.dp))
            
            DuolingoButton(
                text = "SYNCED AUDIO",
                onClick = onSyncedAudioPlayerSelected,
                icon = TablerIcons.Music,
                color = DuoPurple,
                shadowColor = DuoPurpleShadow,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            DuolingoButton(
                text = "SYNCED VIDEO",
                onClick = onSyncedVideoPlayerSelected,
                icon = TablerIcons.Video,
                color = DuoOrange,
                shadowColor = DuoOrangeShadow,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = DuoTextDisabled,
        modifier = Modifier.padding(start = 4.dp)
    )
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

