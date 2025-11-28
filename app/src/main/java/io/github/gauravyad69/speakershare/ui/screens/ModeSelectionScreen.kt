package io.github.gauravyad69.speakershare.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gauravyad69.speakershare.ui.theme.SpeakerShareTheme

/**
 * Mode Selection Screen - Initial screen for choosing Host or Client mode
 * Implements T046: Mode selection screen (Host/Client)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSelectionScreen(
    onHostModeSelected: () -> Unit,
    onClientModeSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Permission states
    var showMicPermissionDialog by remember { mutableStateOf(false) }
    var showBatteryOptimizationDialog by remember { mutableStateOf(false) }
    var hasMicPermission by remember { mutableStateOf(false) }
    var isBatteryOptimizationDisabled by remember { mutableStateOf(false) }
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
                isBatteryOptimizationDisabled = isDisabled
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
            
            // Check mic permission
            val micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == 
                android.content.pm.PackageManager.PERMISSION_GRANTED
            hasMicPermission = micGranted
            
            if (!micGranted) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                // Check battery optimization
                checkBatteryOptimization(context) { isDisabled ->
                    isBatteryOptimizationDisabled = isDisabled
                    if (!isDisabled) {
                        showBatteryOptimizationDialog = true
                    }
                }
            }
        }
    }
    
    // Mic Permission Dialog
    if (showMicPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showMicPermissionDialog = false },
            title = { Text("Microphone Permission Required") },
            text = { 
                Text("SpeakerShare needs microphone access to broadcast audio in Host mode. " +
                     "Please grant the permission in Settings.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showMicPermissionDialog = false
                    // Open app settings
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMicPermissionDialog = false }) {
                    Text("Later")
                }
            }
        )
    }
    
    // Battery Optimization Dialog
    if (showBatteryOptimizationDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryOptimizationDialog = false },
            title = { Text("Disable Battery Optimization") },
            text = { 
                Text("To prevent audio interruptions when the screen is off, please disable " +
                     "battery optimization for SpeakerShare.\n\n" +
                     "This ensures the app can stream audio reliably in the background.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryOptimizationDialog = false
                    requestBatteryOptimizationExemption(context)
                }) {
                    Text("Disable Optimization")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryOptimizationDialog = false }) {
                    Text("Later")
                }
            }
        )
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App Title
        Text(
            text = "SpeakerShare",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // App Subtitle
        Text(
            text = "Real-time Audio Broadcasting",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // Mode Selection Title
        Text(
            text = "Choose Your Mode",
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Host Mode Card
        Card(
            onClick = onHostModeSelected,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Host Mode",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Broadcast your audio to multiple clients",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Client Mode Card
        Card(
            onClick = onClientModeSelected,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Client Mode",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Connect and listen to a host's audio",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Feature Description
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Features:",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "• Real-time audio streaming over LAN\n" +
                            "• No internet connection required\n" +
                            "• Individual volume controls\n" +
                            "• Microphone and system audio support",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ModeSelectionScreenPreview() {
    SpeakerShareTheme {
        ModeSelectionScreen(
            onHostModeSelected = { },
            onClientModeSelected = { }
        )
    }
}

/**
 * Check if battery optimization is disabled for this app
 */
private fun checkBatteryOptimization(context: Context, callback: (Boolean) -> Unit) {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    callback(isIgnoringBatteryOptimizations)
}

/**
 * Request battery optimization exemption
 */
@android.annotation.SuppressLint("BatteryLife")
private fun requestBatteryOptimizationExemption(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback to battery optimization settings
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            context.startActivity(intent)
        } catch (e2: Exception) {
            android.util.Log.e("ModeSelectionScreen", "Failed to open battery settings", e2)
        }
    }
}