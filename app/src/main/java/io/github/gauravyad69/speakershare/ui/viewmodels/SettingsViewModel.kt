package io.github.gauravyad69.speakershare.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.gauravyad69.speakershare.data.model.*
import io.github.gauravyad69.speakershare.data.repository.SettingsRepository
import io.github.gauravyad69.speakershare.services.AudioStreamManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Settings functionality.
 * Manages user preferences, app configuration, and settings persistence.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val audioStreamManager: AudioStreamManager
) : ViewModel() {

    // User settings state
    private val _userSettings = MutableStateFlow<UserSettings?>(null)
    val userSettings: StateFlow<UserSettings?> = _userSettings.asStateFlow()

    // Audio settings
    private val _audioQuality = MutableStateFlow(AudioQuality())
    val audioQuality: StateFlow<AudioQuality> = _audioQuality.asStateFlow()

    private val _defaultAudioSource = MutableStateFlow(AudioSource.MICROPHONE)
    val defaultAudioSource: StateFlow<AudioSource> = _defaultAudioSource.asStateFlow()

    private val _bufferSize = MutableStateFlow(1024)
    val bufferSize: StateFlow<Int> = _bufferSize.asStateFlow()

    // Latency profile
    private val _latencyProfile = MutableStateFlow(LatencyProfile.BALANCED)
    val latencyProfile: StateFlow<LatencyProfile> = _latencyProfile.asStateFlow()
    
    private val _latencyConfig = MutableStateFlow(LatencyConfig.fromProfile(LatencyProfile.BALANCED))
    val latencyConfig: StateFlow<LatencyConfig> = _latencyConfig.asStateFlow()

    // Network settings
    private val _preferredTransport = MutableStateFlow(StreamTransport.WEBRTC)
    val preferredTransport: StateFlow<StreamTransport> = _preferredTransport.asStateFlow()

    private val _defaultPort = MutableStateFlow(8080)
    val defaultPort: StateFlow<Int> = _defaultPort.asStateFlow()

    private val _enableAutoDiscovery = MutableStateFlow(true)
    val enableAutoDiscovery: StateFlow<Boolean> = _enableAutoDiscovery.asStateFlow()

    private val _maxClients = MutableStateFlow(0) // 0 = unlimited
    val maxClients: StateFlow<Int> = _maxClients.asStateFlow()

    // UI settings
    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _darkMode = MutableStateFlow<DarkModePreference>(DarkModePreference.SYSTEM)
    val darkMode: StateFlow<DarkModePreference> = _darkMode.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(true)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _showNotifications = MutableStateFlow(true)
    val showNotifications: StateFlow<Boolean> = _showNotifications.asStateFlow()

    // Privacy settings
    private val _allowDiscovery = MutableStateFlow(true)
    val allowDiscovery: StateFlow<Boolean> = _allowDiscovery.asStateFlow()

    private val _requirePermissionForClients = MutableStateFlow(false)
    val requirePermissionForClients: StateFlow<Boolean> = _requirePermissionForClients.asStateFlow()

    // Advanced settings
    private val _enableDebugLogging = MutableStateFlow(false)
    val enableDebugLogging: StateFlow<Boolean> = _enableDebugLogging.asStateFlow()

    private val _autoStopTimer = MutableStateFlow(0) // 0 = no auto-stop
    val autoStopTimer: StateFlow<Int> = _autoStopTimer.asStateFlow()

    // Audio sync settings
    private val _audioSyncPositionTolerance = MutableStateFlow(250) // ms
    val audioSyncPositionTolerance: StateFlow<Int> = _audioSyncPositionTolerance.asStateFlow()

    private val _audioSyncMinSeekInterval = MutableStateFlow(2000) // ms  
    val audioSyncMinSeekInterval: StateFlow<Int> = _audioSyncMinSeekInterval.asStateFlow()

    // Video sync settings
    private val _videoSyncPositionTolerance = MutableStateFlow(500) // ms
    val videoSyncPositionTolerance: StateFlow<Int> = _videoSyncPositionTolerance.asStateFlow()

    private val _videoSyncMinSeekInterval = MutableStateFlow(5000) // ms  
    val videoSyncMinSeekInterval: StateFlow<Int> = _videoSyncMinSeekInterval.asStateFlow()

    // Legacy sync settings (for backward compatibility)
    private val _syncPositionTolerance = MutableStateFlow(250) // ms
    val syncPositionTolerance: StateFlow<Int> = _syncPositionTolerance.asStateFlow()

    private val _syncMinSeekInterval = MutableStateFlow(3000) // ms  
    val syncMinSeekInterval: StateFlow<Int> = _syncMinSeekInterval.asStateFlow()

    // UI state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges.asStateFlow()

    init {
        loadSettings()
        loadLatencyProfile()
        loadSyncSettings()
        observeSettingsChanges()
    }
    
    /**
     * Load latency profile from settings repository
     */
    private fun loadLatencyProfile() {
        val savedProfile = settingsRepository.getLatencyProfile()
        _latencyProfile.value = savedProfile
        _latencyConfig.value = LatencyConfig.fromProfile(savedProfile)
        audioStreamManager.setLatencyProfile(savedProfile)
    }
    
    /**
     * Load sync settings from settings repository
     */
    private fun loadSyncSettings() {
        // Load audio sync settings
        _audioSyncPositionTolerance.value = settingsRepository.getAudioSyncPositionTolerance()
        _audioSyncMinSeekInterval.value = settingsRepository.getAudioSyncMinSeekInterval()
        // Load video sync settings
        _videoSyncPositionTolerance.value = settingsRepository.getVideoSyncPositionTolerance()
        _videoSyncMinSeekInterval.value = settingsRepository.getVideoSyncMinSeekInterval()
        // Load legacy settings (for backward compatibility)
        _syncPositionTolerance.value = settingsRepository.getSyncPositionTolerance()
        _syncMinSeekInterval.value = settingsRepository.getSyncMinSeekInterval()
    }

    /**
     * Load user settings from repository
     */
    private fun loadSettings() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                settingsRepository.getUserSettings().collect { settings ->
                    settings?.let {
                        _userSettings.value = it
                        updateUIStateFromSettings(it)
                    } ?: createDefaultSettings()
                }
            } catch (e: Exception) {
                _error.value = "Failed to load settings: ${e.message}"
                createDefaultSettings()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Create default settings if none exist
     */
    private fun createDefaultSettings() {
        viewModelScope.launch {
            val defaultSettings = UserSettings(
                displayName = getDefaultDeviceName(),
                defaultAudioSource = AudioSource.MICROPHONE,
                defaultQuality = AudioQuality(),
                autoStartHost = false,
                keepScreenOn = false,
                showNetworkMetrics = false,
                maxClients = 0
            )
            
            val result = settingsRepository.updateSettings(defaultSettings)
            result.onSuccess {
                _userSettings.value = defaultSettings
            }.onFailure { e ->
                _error.value = "Failed to create default settings: ${e.message}"
            }
        }
    }

    /**
     * Update UI state from loaded settings
     */
    private fun updateUIStateFromSettings(settings: UserSettings) {
        _audioQuality.value = settings.defaultQuality
        _defaultAudioSource.value = settings.defaultAudioSource
        _maxClients.value = settings.maxClients
        _keepScreenOn.value = settings.keepScreenOn
        _hasUnsavedChanges.value = false
    }
    

    /**
     * Observe settings changes to detect unsaved changes
     */
    private fun observeSettingsChanges() {
        viewModelScope.launch {
            combine(
                _audioQuality,
                _defaultAudioSource,
                _maxClients
            ) { _, _, _ ->
                // Mark as having unsaved changes when any setting changes
                if (_userSettings.value != null) {
                    _hasUnsavedChanges.value = true
                }
            }.collect()
        }
    }

    // Settings update methods
    fun setAudioQuality(quality: AudioQuality) {
        _audioQuality.value = quality
    }

    fun setDefaultAudioSource(source: AudioSource) {
        _defaultAudioSource.value = source
    }

    fun setLatencyProfile(profile: LatencyProfile) {
        _latencyProfile.value = profile
        _latencyConfig.value = LatencyConfig.fromProfile(profile)
        
        // Save to persistent storage
        settingsRepository.saveLatencyProfile(profile)
        
        // Apply the latency profile to the audio stream manager
        audioStreamManager.setLatencyProfile(profile)
        
        // Update related settings based on profile
        val config = _latencyConfig.value
        _audioQuality.value = _audioQuality.value.copy(
            sampleRate = config.sampleRate,
            bitrate = if (config.bitrate > 0) config.bitrate / 1000 else 128,
            encoding = config.encoding
        )
        _bufferSize.value = config.captureBufferCapacity * 256 // Approximate mapping
    }

    fun setPreferredTransport(transport: StreamTransport) {
        _preferredTransport.value = transport
    }

    fun setMaxClients(maxClients: Int) {
        _maxClients.value = maxClients.coerceAtLeast(0)
    }

    fun setDefaultPort(port: Int) {
        _defaultPort.value = port.coerceIn(1024, 65535)
    }

    fun setBufferSize(size: Int) {
        _bufferSize.value = size.coerceIn(256, 8192)
    }

    fun setEnableAutoDiscovery(enabled: Boolean) {
        _enableAutoDiscovery.value = enabled
    }

    fun setAllowDiscovery(allowed: Boolean) {
        _allowDiscovery.value = allowed
    }

    fun setRequirePermissionForClients(required: Boolean) {
        _requirePermissionForClients.value = required
    }

    fun setDeviceName(name: String) {
        _deviceName.value = name.trim().take(50) // Limit device name length
    }

    fun setDarkMode(mode: DarkModePreference) {
        _darkMode.value = mode
    }

    fun setKeepScreenOn(enabled: Boolean) {
        _keepScreenOn.value = enabled
    }

    fun setShowNotifications(enabled: Boolean) {
        _showNotifications.value = enabled
    }

    fun setEnableDebugLogging(enabled: Boolean) {
        _enableDebugLogging.value = enabled
    }

    fun setAutoStopTimer(minutes: Int) {
        _autoStopTimer.value = minutes.coerceAtLeast(0)
    }

    /**
     * Set audio sync position tolerance (how much drift before corrective seek)
     */
    fun setAudioSyncPositionTolerance(toleranceMs: Int) {
        val clamped = toleranceMs.coerceIn(50, 1000)
        _audioSyncPositionTolerance.value = clamped
        settingsRepository.saveAudioSyncPositionTolerance(clamped)
    }

    /**
     * Set audio sync minimum seek interval (cooldown between corrective seeks)
     */
    fun setAudioSyncMinSeekInterval(intervalMs: Int) {
        val clamped = intervalMs.coerceIn(500, 10000)
        _audioSyncMinSeekInterval.value = clamped
        settingsRepository.saveAudioSyncMinSeekInterval(clamped)
    }

    /**
     * Set video sync position tolerance (how much drift before corrective seek)
     */
    fun setVideoSyncPositionTolerance(toleranceMs: Int) {
        val clamped = toleranceMs.coerceIn(100, 2000)
        _videoSyncPositionTolerance.value = clamped
        settingsRepository.saveVideoSyncPositionTolerance(clamped)
    }

    /**
     * Set video sync minimum seek interval (cooldown between corrective seeks)
     */
    fun setVideoSyncMinSeekInterval(intervalMs: Int) {
        val clamped = intervalMs.coerceIn(1000, 15000)
        _videoSyncMinSeekInterval.value = clamped
        settingsRepository.saveVideoSyncMinSeekInterval(clamped)
    }

    /**
     * Set sync position tolerance (legacy - updates audio setting)
     */
    fun setSyncPositionTolerance(toleranceMs: Int) {
        val clamped = toleranceMs.coerceIn(50, 1000)
        _syncPositionTolerance.value = clamped
        settingsRepository.saveSyncPositionTolerance(clamped)
    }

    /**
     * Set sync minimum seek interval (legacy - updates audio setting)
     */
    fun setSyncMinSeekInterval(intervalMs: Int) {
        val clamped = intervalMs.coerceIn(500, 10000)
        _syncMinSeekInterval.value = clamped
        settingsRepository.saveSyncMinSeekInterval(clamped)
    }

    /**
     * Save all settings
     */
    fun saveSettings() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val currentSettings = _userSettings.value
                if (currentSettings != null) {
                    val updatedSettings = currentSettings.copy(
                        displayName = _deviceName.value,
                        defaultQuality = _audioQuality.value,
                        defaultAudioSource = _defaultAudioSource.value,
                        maxClients = _maxClients.value,
                        keepScreenOn = _keepScreenOn.value,
                        showNetworkMetrics = _showNotifications.value // Map showNotifications to showNetworkMetrics
                    )
                    
                    val result = settingsRepository.updateSettings(updatedSettings)
                    result.onSuccess {
                        _userSettings.value = updatedSettings
                        _hasUnsavedChanges.value = false
                    }.onFailure { e ->
                        _error.value = "Failed to save settings: ${e.message}"
                    }
                }
            } catch (e: Exception) {
                _error.value = "Failed to save settings: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Reset to default settings
     */
    fun resetToDefaults() {
        viewModelScope.launch {
            try {
                createDefaultSettings()
            } catch (e: Exception) {
                _error.value = "Failed to reset settings: ${e.message}"
            }
        }
    }

    /**
     * Discard unsaved changes
     */
    fun discardChanges() {
        _userSettings.value?.let { settings ->
            updateUIStateFromSettings(settings)
        }
    }

    /**
     * Clear error state
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Export settings to JSON for backup
     */
    fun exportSettings() {
        viewModelScope.launch {
            try {
                _userSettings.value?.let { settings ->
                    // TODO: Implement actual export logic (e.g., save to file or share)
                    _error.value = "Export functionality coming soon"
                }
            } catch (e: Exception) {
                _error.value = "Failed to export settings: ${e.message}"
            }
        }
    }

    /**
     * Import settings from JSON backup
     */
    fun importSettings() {
        viewModelScope.launch {
            try {
                // TODO: Implement actual import logic (e.g., read from file or clipboard)
                _error.value = "Import functionality coming soon"
            } catch (e: Exception) {
                _error.value = "Failed to import settings: ${e.message}"
            }
        }
    }

    // Helper functions
    private fun generateUserId(): String = java.util.UUID.randomUUID().toString()
    private fun getDefaultDeviceName(): String = android.os.Build.MODEL
}

/**
 * Dark mode preferences
 */
enum class DarkModePreference {
    LIGHT,
    DARK,
    SYSTEM
}
