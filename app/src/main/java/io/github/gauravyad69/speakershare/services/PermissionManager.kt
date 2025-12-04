package io.github.gauravyad69.speakershare.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber
import androidx.annotation.RequiresApi

/**
 * Manager for handling Android permissions required for audio streaming.
 * Handles runtime permissions for audio recording, network access, and system audio capture.
 */
@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val _permissionState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val permissionState: StateFlow<Map<String, Boolean>> = _permissionState.asStateFlow()
    
    private val _hasAllRequiredPermissions = MutableStateFlow(false)
    val hasAllRequiredPermissions: StateFlow<Boolean> = _hasAllRequiredPermissions.asStateFlow()
    
    companion object {
        
        // Core permissions required for basic functionality
        val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )
        
        // Additional permissions for enhanced functionality
        val OPTIONAL_PERMISSIONS = listOf(
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.WAKE_LOCK
        )
        
        // System audio permissions (API 29+)
        val SYSTEM_AUDIO_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(
                Manifest.permission.FOREGROUND_SERVICE,
                "android.permission.CAPTURE_AUDIO_OUTPUT" // System permission, requires special handling
            )
        } else {
            emptyList()
        }
    }
    
    init {
        updatePermissionState()
    }
    
    /**
     * Check if a specific permission is granted
     */
    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if all required permissions are granted
     */
    fun areRequiredPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all { isPermissionGranted(it) }
    }
    
    /**
     * Check if microphone permission is granted
     */
    fun isMicrophonePermissionGranted(): Boolean {
        return isPermissionGranted(Manifest.permission.RECORD_AUDIO)
    }
    
    /**
     * Check if network permissions are granted
     */
    fun areNetworkPermissionsGranted(): Boolean {
        return isPermissionGranted(Manifest.permission.ACCESS_NETWORK_STATE) &&
               isPermissionGranted(Manifest.permission.ACCESS_WIFI_STATE)
    }
    
    /**
     * Check if system audio capture is possible
     */
    fun canCaptureSystemAudio(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // System audio capture requires MediaProjection service
            // This is handled through MediaProjectionManager, not traditional permissions
            isPermissionGranted(Manifest.permission.FOREGROUND_SERVICE)
        } else {
            false // System audio not supported on older versions
        }
    }
    
    /**
     * Get list of missing required permissions
     */
    fun getMissingRequiredPermissions(): List<String> {
        return REQUIRED_PERMISSIONS.filter { !isPermissionGranted(it) }
    }
    
    /**
     * Get list of missing optional permissions
     */
    fun getMissingOptionalPermissions(): List<String> {
        return OPTIONAL_PERMISSIONS.filter { !isPermissionGranted(it) }
    }
    
    /**
     * Create permission request launcher for activity
     */
    fun createPermissionLauncher(
        activity: FragmentActivity,
        onResult: (Map<String, Boolean>) -> Unit
    ): ActivityResultLauncher<Array<String>> {
        return activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            Timber.d("Permission request result: $permissions")
            updatePermissionState()
            onResult(permissions)
        }
    }
    
    /**
     * Request required permissions
     */
    fun requestRequiredPermissions(launcher: ActivityResultLauncher<Array<String>>) {
        val missingPermissions = getMissingRequiredPermissions()
        if (missingPermissions.isNotEmpty()) {
            Timber.d("Requesting required permissions: $missingPermissions")
            launcher.launch(missingPermissions.toTypedArray())
        } else {
            Timber.d("All required permissions already granted")
        }
    }
    
    /**
     * Request optional permissions
     */
    fun requestOptionalPermissions(launcher: ActivityResultLauncher<Array<String>>) {
        val missingPermissions = getMissingOptionalPermissions()
        if (missingPermissions.isNotEmpty()) {
            Timber.d("Requesting optional permissions: $missingPermissions")
            launcher.launch(missingPermissions.toTypedArray())
        } else {
            Timber.d("All optional permissions already granted")
        }
    }
    
    /**
     * Check if permission should show rationale
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun shouldShowRationale(activity: FragmentActivity, permission: String): Boolean {
        return activity.shouldShowRequestPermissionRationale(permission)
    }
    
    /**
     * Get permission rationale message
     */
    fun getPermissionRationale(permission: String): String {
        return when (permission) {
            Manifest.permission.RECORD_AUDIO -> 
                "Microphone permission is required to capture and broadcast audio from your device."
            
            Manifest.permission.ACCESS_NETWORK_STATE -> 
                "Network access permission is required to detect network connectivity and broadcast audio over LAN."
            
            Manifest.permission.ACCESS_WIFI_STATE -> 
                "WiFi state permission is required to discover other devices and establish audio connections."
            
            Manifest.permission.FOREGROUND_SERVICE -> 
                "Foreground service permission allows the app to continue streaming audio in the background."
            
            Manifest.permission.WAKE_LOCK -> 
                "Wake lock permission helps maintain stable audio streaming by preventing the device from sleeping."
            
            else -> "This permission is required for the app to function properly."
        }
    }
    
    /**
     * Get user-friendly permission name
     */
    fun getPermissionName(permission: String): String {
        return when (permission) {
            Manifest.permission.RECORD_AUDIO -> "Microphone"
            Manifest.permission.ACCESS_NETWORK_STATE -> "Network Access"
            Manifest.permission.ACCESS_WIFI_STATE -> "WiFi Access"
            Manifest.permission.FOREGROUND_SERVICE -> "Background Service"
            Manifest.permission.WAKE_LOCK -> "Keep Device Awake"
            else -> "Unknown Permission"
        }
    }
    
    /**
     * Check hosting permissions (microphone + network)
     */
    fun canStartHosting(): Pair<Boolean, List<String>> {
        val missing = mutableListOf<String>()
        
        if (!isMicrophonePermissionGranted()) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (!areNetworkPermissionsGranted()) {
            missing.addAll(listOf(
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_WIFI_STATE
            ).filter { !isPermissionGranted(it) })
        }
        
        return Pair(missing.isEmpty(), missing)
    }
    
    /**
     * Check client permissions (network only)
     */
    fun canStartClient(): Pair<Boolean, List<String>> {
        val missing = mutableListOf<String>()
        
        if (!areNetworkPermissionsGranted()) {
            missing.addAll(listOf(
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_WIFI_STATE
            ).filter { !isPermissionGranted(it) })
        }
        
        return Pair(missing.isEmpty(), missing)
    }
    
    /**
     * Update internal permission state
     */
    private fun updatePermissionState() {
        val allPermissions = REQUIRED_PERMISSIONS + OPTIONAL_PERMISSIONS
        val currentState = allPermissions.associateWith { isPermissionGranted(it) }
        
        _permissionState.value = currentState
        _hasAllRequiredPermissions.value = areRequiredPermissionsGranted()
        
        Timber.d("Permission state updated: $currentState")
        Timber.d("All required permissions granted: ${_hasAllRequiredPermissions.value}")
    }
    
    /**
     * Log current permission status (for debugging)
     */
    fun logPermissionStatus() {
        Timber.d("=== Permission Status ===")
        Timber.d("Required permissions:")
        REQUIRED_PERMISSIONS.forEach { permission ->
            val granted = isPermissionGranted(permission)
            Timber.d("  ${getPermissionName(permission)}: ${if (granted) "GRANTED" else "DENIED"}")
        }
        
        Timber.d("Optional permissions:")
        OPTIONAL_PERMISSIONS.forEach { permission ->
            val granted = isPermissionGranted(permission)
            Timber.d("  ${getPermissionName(permission)}: ${if (granted) "GRANTED" else "DENIED"}")
        }
        
        Timber.d("Can start hosting: ${canStartHosting().first}")
        Timber.d("Can start client: ${canStartClient().first}")
        Timber.d("Can capture system audio: ${canCaptureSystemAudio()}")
        Timber.d("========================")
    }
}
