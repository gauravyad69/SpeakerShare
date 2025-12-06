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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import io.github.gauravyad69.speakershare.ui.theme.*
import io.github.gauravyad69.speakershare.ui.components.DuolingoButton

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
                    TablerIcons.ShieldLock,
                    contentDescription = null,
                    tint = DuoBlue
                )
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "SpeakerShare needs the following permissions to function properly:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DuoTextPrimary
                )

                permissions.forEach { permission ->
                    PermissionItem(permission = permission)
                }

                Text(
                    text = "These permissions are essential for the app's core functionality and will not be used for any other purposes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DuoTextSecondary
                )
            }
        },
        confirmButton = {
            DuolingoButton(
                text = "GRANT PERMISSIONS",
                onClick = {
                    val permissionsToRequest = permissions
                        .filterNot { it.isGranted }
                        .map { it.permission }
                        .toTypedArray()
                    
                    if (permissionsToRequest.isNotEmpty()) {
                        permissionLauncher.launch(permissionsToRequest)
                    }
                },
                color = DuoGreen,
                shadowColor = DuoGreenShadow,
                height = 40.dp
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = DuoTextSecondary, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = DuoSurface,
        titleContentColor = DuoTextPrimary,
        textContentColor = DuoTextSecondary
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
                    TablerIcons.InfoCircle,
                    contentDescription = null,
                    tint = DuoBlue
                )
                Text("Why We Need These Permissions", style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "SpeakerShare requires these permissions to provide audio broadcasting functionality:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DuoTextPrimary
                )

                permissions.filter { it.shouldShowRationale }.forEach { permission ->
                    PermissionRationaleItem(permission = permission)
                }

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = DuoBlue.copy(alpha = 0.1f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(2.dp, DuoBlue)
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
                                TablerIcons.ShieldCheck,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = DuoBlue
                            )
                            Text(
                                text = "Privacy Guarantee",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = DuoBlue
                            )
                        }
                        Text(
                            text = "Your audio data stays on your local network. No data is sent to external servers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = DuoBlue
                        )
                    }
                }
            }
        },
        confirmButton = {
            DuolingoButton(
                text = "I UNDERSTAND - GRANT",
                onClick = {
                    val permissionsToRequest = permissions
                        .filterNot { it.isGranted }
                        .map { it.permission }
                        .toTypedArray()
                    
                    if (permissionsToRequest.isNotEmpty()) {
                        permissionLauncher.launch(permissionsToRequest)
                    }
                },
                color = DuoGreen,
                shadowColor = DuoGreenShadow,
                height = 40.dp
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = DuoTextSecondary, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = DuoSurface,
        titleContentColor = DuoTextPrimary,
        textContentColor = DuoTextSecondary
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
                    TablerIcons.Settings,
                    contentDescription = null,
                    tint = DuoRed
                )
                Text("Permission Required", style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Some permissions have been permanently denied. Please enable them manually in the app settings:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DuoTextPrimary
                )

                permissions.filter { it.isPermanentlyDenied || !it.isGranted }.forEach { permission ->
                    PermissionSettingsItem(permission = permission)
                }

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = DuoRed.copy(alpha = 0.1f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(2.dp, DuoRed)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Steps to enable:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = DuoRed
                        )
                        Text(
                            text = "1. Tap 'Open Settings' below\n2. Find 'Permissions' or 'App permissions'\n3. Enable the required permissions\n4. Return to the app",
                            style = MaterialTheme.typography.bodySmall,
                            color = DuoRed
                        )
                    }
                }
            }
        },
        confirmButton = {
            DuolingoButton(
                text = "OPEN SETTINGS",
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                color = DuoBlue,
                shadowColor = DuoBlueShadow,
                height = 40.dp
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = DuoTextSecondary, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = DuoSurface,
        titleContentColor = DuoTextPrimary,
        textContentColor = DuoTextSecondary
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
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (permission.isGranted) DuoGreen.copy(alpha = 0.1f) else DuoSurfaceHighlight,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (permission.isGranted) TablerIcons.Check else icon,
                    contentDescription = null,
                    tint = if (permission.isGranted) DuoGreen else DuoTextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = DuoTextPrimary
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = DuoTextSecondary
            )
        }
    }
}

@Composable
private fun PermissionRationaleItem(permission: PermissionState) {
    val (icon, title, rationale) = getPermissionRationale(permission.permission)
    
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = DuoSurfaceHighlight
        ),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
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
                tint = DuoBlue
            )
            
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = DuoTextPrimary
                )
                Text(
                    text = rationale,
                    style = MaterialTheme.typography.bodySmall,
                    color = DuoTextSecondary
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
            tint = DuoRed
        )
        
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = DuoTextPrimary,
            modifier = Modifier.weight(1f)
        )
        
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DuoRed.copy(alpha = 0.1f)
        ) {
            Text(
                text = "REQUIRED",
                style = MaterialTheme.typography.labelSmall,
                color = DuoRed,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                fontWeight = FontWeight.Bold
            )
        }
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
            TablerIcons.Microphone,
            "Microphone",
            "Required to capture and broadcast audio from your device"
        )
        Manifest.permission.ACCESS_WIFI_STATE -> Triple(
            TablerIcons.Wifi,
            "Wi-Fi State",
            "Required to detect network connectivity and discover other devices"
        )
        Manifest.permission.CHANGE_WIFI_MULTICAST_STATE -> Triple(
            TablerIcons.AccessPoint,
            "Network Multicast",
            "Required for network discovery and device communication"
        )
        Manifest.permission.ACCESS_NETWORK_STATE -> Triple(
            TablerIcons.Activity,
            "Network State",
            "Required to monitor network connectivity and optimize streaming"
        )
        Manifest.permission.WRITE_EXTERNAL_STORAGE -> Triple(
            TablerIcons.DeviceFloppy,
            "Storage",
            "Required to save audio recordings and app settings"
        )
        else -> Triple(
            TablerIcons.ShieldLock,
            "Permission",
            "Required for app functionality"
        )
    }
}

private fun getPermissionRationale(permission: String): Triple<androidx.compose.ui.graphics.vector.ImageVector, String, String> {
    return when (permission) {
        Manifest.permission.RECORD_AUDIO -> Triple(
            TablerIcons.Microphone,
            "Microphone Access",
            "SpeakerShare needs microphone access to capture audio from your device and broadcast it to connected clients. The audio is only transmitted over your local network and is never sent to external servers."
        )
        Manifest.permission.ACCESS_WIFI_STATE -> Triple(
            TablerIcons.Wifi,
            "Wi-Fi Network Information",
            "This permission allows the app to check your Wi-Fi connection status and network information, which is essential for establishing connections with other devices on your local network."
        )
        Manifest.permission.CHANGE_WIFI_MULTICAST_STATE -> Triple(
            TablerIcons.AccessPoint,
            "Network Discovery",
            "Required to enable multicast networking, which allows the app to discover other devices running SpeakerShare on your local network automatically."
        )
        Manifest.permission.ACCESS_NETWORK_STATE -> Triple(
            TablerIcons.Activity,
            "Network Monitoring",
            "This permission helps the app monitor your network connection quality and automatically adjust streaming parameters for the best audio quality and reliability."
        )
        Manifest.permission.WRITE_EXTERNAL_STORAGE -> Triple(
            TablerIcons.DeviceFloppy,
            "File Storage",
            "Used to save your app settings, audio quality presets, and optionally record broadcast sessions for later playback. All files remain on your device."
        )
        else -> Triple(
            TablerIcons.ShieldLock,
            "App Permission",
            "This permission is required for the app to function properly."
        )
    }
}