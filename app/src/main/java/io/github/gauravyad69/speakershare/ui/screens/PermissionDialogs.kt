package io.github.gauravyad69.speakershare.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Permission management dialogs and screens for handling Android permissions
 */

// Data class for permission states
data class PermissionState(
    val permission: String,
    val isGranted: Boolean,
    val shouldShowRationale: Boolean,
    val isPermanentlyDenied: Boolean = false
)

// Main permission dialog that shows different UIs based on permission state
@Composable
fun PermissionDialog(
    permissions: List<PermissionState>,
    onPermissionsResult: (Map<String, Boolean>) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Permissions Required",
    showSettings: Boolean = false
) {
    val allGranted = permissions.all { it.isGranted }
    val hasRationale = permissions.any { it.shouldShowRationale }
    val hasPermanentlyDenied = permissions.any { it.isPermanentlyDenied }

    when {
        allGranted -> {
            // All permissions granted, dismiss automatically
            LaunchedEffect(Unit) {
                onDismiss()
            }
        }
        hasPermanentlyDenied || showSettings -> {
            PermissionSettingsDialog(
                permissions = permissions,
                onDismiss = onDismiss,
                title = title
            )
        }
        hasRationale -> {
            PermissionRationaleDialog(
                permissions = permissions,
                onPermissionsResult = onPermissionsResult,
                onDismiss = onDismiss,
                title = title
            )
        }
        else -> {
            PermissionRequestDialog(
                permissions = permissions,
                onPermissionsResult = onPermissionsResult,
                onDismiss = onDismiss,
                title = title
            )
        }
    }
}

@Composable
private fun PermissionRequestDialog(
    permissions: List<PermissionState>,
    onPermissionsResult: (Map<String, Boolean>) -> Unit,
    onDismiss: () -> Unit,
    title: String
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        onPermissionsResult(results)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(title)
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "SpeakerShare needs the following permissions to function properly:",
                    style = MaterialTheme.typography.bodyMedium
                )

                permissions.forEach { permission ->
                    PermissionItem(permission = permission)
                }

                Text(
                    text = "These permissions are essential for the app's core functionality and will not be used for any other purposes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val permissionsToRequest = permissions
                        .filterNot { it.isGranted }
                        .map { it.permission }
                        .toTypedArray()
                    
                    if (permissionsToRequest.isNotEmpty()) {
                        permissionLauncher.launch(permissionsToRequest)
                    }
                }
            ) {
                Text("Grant Permissions")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun PermissionRationaleDialog(
    permissions: List<PermissionState>,
    onPermissionsResult: (Map<String, Boolean>) -> Unit,
    onDismiss: () -> Unit,
    title: String
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        onPermissionsResult(results)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Why We Need These Permissions")
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "SpeakerShare requires these permissions to provide audio broadcasting functionality:",
                    style = MaterialTheme.typography.bodyMedium
                )

                permissions.filter { it.shouldShowRationale }.forEach { permission ->
                    PermissionRationaleItem(permission = permission)
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
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
                                Icons.Default.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Privacy Guarantee",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            text = "Your audio data stays on your local network. No data is sent to external servers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val permissionsToRequest = permissions
                        .filterNot { it.isGranted }
                        .map { it.permission }
                        .toTypedArray()
                    
                    if (permissionsToRequest.isNotEmpty()) {
                        permissionLauncher.launch(permissionsToRequest)
                    }
                }
            ) {
                Text("I Understand - Grant Permissions")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun PermissionSettingsDialog(
    permissions: List<PermissionState>,
    onDismiss: () -> Unit,
    title: String
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text("Permission Required")
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Some permissions have been permanently denied. Please enable them manually in the app settings:",
                    style = MaterialTheme.typography.bodyMedium
                )

                permissions.filter { it.isPermanentlyDenied || !it.isGranted }.forEach { permission ->
                    PermissionSettingsItem(permission = permission)
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Steps to enable:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "1. Tap 'Open Settings' below\n2. Find 'Permissions' or 'App permissions'\n3. Enable the required permissions\n4. Return to the app",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun PermissionItem(permission: PermissionState) {
    val (icon, title, description) = getPermissionInfo(permission.permission)
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (permission.isGranted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        if (permission.isGranted) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Granted",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                Icons.Default.RadioButtonUnchecked,
                contentDescription = "Not granted",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionRationaleItem(permission: PermissionState) {
    val (icon, title, rationale) = getPermissionRationale(permission.permission)
    
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = rationale,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun PermissionSettingsItem(permission: PermissionState) {
    val (icon, title, _) = getPermissionInfo(permission.permission)
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.error
        )
        
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        
        AssistChip(
            onClick = {},
            label = { Text("Required", style = MaterialTheme.typography.labelSmall) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                labelColor = MaterialTheme.colorScheme.onErrorContainer
            )
        )
    }
}

// Specialized permission dialogs for common scenarios
@Composable
fun AudioPermissionDialog(
    isVisible: Boolean,
    onPermissionResult: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return
    
    val permissionState = remember {
        PermissionState(
            permission = Manifest.permission.RECORD_AUDIO,
            isGranted = false,
            shouldShowRationale = true
        )
    }
    
    PermissionDialog(
        permissions = listOf(permissionState),
        onPermissionsResult = { results ->
            onPermissionResult(results[Manifest.permission.RECORD_AUDIO] == true)
        },
        onDismiss = onDismiss,
        title = "Microphone Access Required"
    )
}

@Composable
fun NetworkPermissionDialog(
    isVisible: Boolean,
    onPermissionsResult: (Map<String, Boolean>) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return
    
    val permissions = remember {
        listOf(
            PermissionState(
                permission = Manifest.permission.ACCESS_WIFI_STATE,
                isGranted = false,
                shouldShowRationale = true
            ),
            PermissionState(
                permission = Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                isGranted = false,
                shouldShowRationale = true
            ),
            PermissionState(
                permission = Manifest.permission.ACCESS_NETWORK_STATE,
                isGranted = false,
                shouldShowRationale = true
            )
        )
    }
    
    PermissionDialog(
        permissions = permissions,
        onPermissionsResult = onPermissionsResult,
        onDismiss = onDismiss,
        title = "Network Access Required"
    )
}

@Composable
fun StoragePermissionDialog(
    isVisible: Boolean,
    onPermissionResult: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return
    
    val permissionState = remember {
        PermissionState(
            permission = Manifest.permission.WRITE_EXTERNAL_STORAGE,
            isGranted = false,
            shouldShowRationale = true
        )
    }
    
    PermissionDialog(
        permissions = listOf(permissionState),
        onPermissionsResult = { results ->
            onPermissionResult(results[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true)
        },
        onDismiss = onDismiss,
        title = "Storage Access Required"
    )
}

// Combined permission dialog for all app permissions
@Composable
fun AllPermissionsDialog(
    isVisible: Boolean,
    onPermissionsResult: (Map<String, Boolean>) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return
    
    val permissions = remember {
        listOf(
            PermissionState(
                permission = Manifest.permission.RECORD_AUDIO,
                isGranted = false,
                shouldShowRationale = true
            ),
            PermissionState(
                permission = Manifest.permission.ACCESS_WIFI_STATE,
                isGranted = false,
                shouldShowRationale = true
            ),
            PermissionState(
                permission = Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                isGranted = false,
                shouldShowRationale = true
            ),
            PermissionState(
                permission = Manifest.permission.ACCESS_NETWORK_STATE,
                isGranted = false,
                shouldShowRationale = true
            ),
            PermissionState(
                permission = Manifest.permission.WRITE_EXTERNAL_STORAGE,
                isGranted = false,
                shouldShowRationale = true
            )
        )
    }
    
    PermissionDialog(
        permissions = permissions,
        onPermissionsResult = onPermissionsResult,
        onDismiss = onDismiss,
        title = "Permissions Required"
    )
}

// Helper functions to get permission information
private fun getPermissionInfo(permission: String): Triple<androidx.compose.ui.graphics.vector.ImageVector, String, String> {
    return when (permission) {
        Manifest.permission.RECORD_AUDIO -> Triple(
            Icons.Default.Mic,
            "Microphone",
            "Required to capture and broadcast audio from your device"
        )
        Manifest.permission.ACCESS_WIFI_STATE -> Triple(
            Icons.Default.Wifi,
            "Wi-Fi State",
            "Required to detect network connectivity and discover other devices"
        )
        Manifest.permission.CHANGE_WIFI_MULTICAST_STATE -> Triple(
            Icons.Default.NetworkWifi,
            "Network Multicast",
            "Required for network discovery and device communication"
        )
        Manifest.permission.ACCESS_NETWORK_STATE -> Triple(
            Icons.Default.NetworkCheck,
            "Network State",
            "Required to monitor network connectivity and optimize streaming"
        )
        Manifest.permission.WRITE_EXTERNAL_STORAGE -> Triple(
            Icons.Default.Storage,
            "Storage",
            "Required to save audio recordings and app settings"
        )
        else -> Triple(
            Icons.Default.Security,
            "Permission",
            "Required for app functionality"
        )
    }
}

private fun getPermissionRationale(permission: String): Triple<androidx.compose.ui.graphics.vector.ImageVector, String, String> {
    return when (permission) {
        Manifest.permission.RECORD_AUDIO -> Triple(
            Icons.Default.Mic,
            "Microphone Access",
            "SpeakerShare needs microphone access to capture audio from your device and broadcast it to connected clients. The audio is only transmitted over your local network and is never sent to external servers."
        )
        Manifest.permission.ACCESS_WIFI_STATE -> Triple(
            Icons.Default.Wifi,
            "Wi-Fi Network Information",
            "This permission allows the app to check your Wi-Fi connection status and network information, which is essential for establishing connections with other devices on your local network."
        )
        Manifest.permission.CHANGE_WIFI_MULTICAST_STATE -> Triple(
            Icons.Default.NetworkWifi,
            "Network Discovery",
            "Required to enable multicast networking, which allows the app to discover other devices running SpeakerShare on your local network automatically."
        )
        Manifest.permission.ACCESS_NETWORK_STATE -> Triple(
            Icons.Default.NetworkCheck,
            "Network Monitoring",
            "This permission helps the app monitor your network connection quality and automatically adjust streaming parameters for the best audio quality and reliability."
        )
        Manifest.permission.WRITE_EXTERNAL_STORAGE -> Triple(
            Icons.Default.Storage,
            "File Storage",
            "Used to save your app settings, audio quality presets, and optionally record broadcast sessions for later playback. All files remain on your device."
        )
        else -> Triple(
            Icons.Default.Security,
            "App Permission",
            "This permission is required for the app to function properly."
        )
    }
}