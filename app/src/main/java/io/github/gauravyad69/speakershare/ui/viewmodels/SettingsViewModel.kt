package io.github.gauravyad69.speakershare.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.gauravyad69.speakershare.data.model.*
import io.github.gauravyad69.speakershare.data.repository.UserSettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Settings functionality.
 * Manages user preferences, app configuration, and settings persistence.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userSettingsRepository: UserSettingsRepository
) : ViewModel() {

    // User settings state
    private val _userSettings = MutableStateFlow<UserSettings?>(null)
    val userSettings: StateFlow<UserSettings?> = _userSettings.asStateFlow()

    // Audio settings
    private val _audioQuality = MutableStateFlow(AudioQuality.STANDARD)
    val audioQuality: StateFlow<AudioQuality> = _audioQuality.asStateFlow()

    private val _defaultAudioSource = MutableStateFlow(AudioSource.MICROPHONE)
    val defaultAudioSource: StateFlow<AudioSource> = _defaultAudioSource.asStateFlow()

    private val _bufferSize = MutableStateFlow(1024)
    val bufferSize: StateFlow<Int> = _bufferSize.asStateFlow()

    // Network settings
    private val _preferredTransport = MutableStateFlow(TransportType.WEBRTC)
    val preferredTransport: StateFlow<TransportType> = _preferredTransport.asStateFlow()

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

    // UI state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges.asStateFlow()

    init {
        loadSettings()
        observeSettingsChanges()
    }

    /**
     * Load user settings from repository
     */
    private fun loadSettings() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                userSettingsRepository.getUserSettings().collect { settings ->
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
            try {
                val defaultSettings = UserSettings(
                    userId = generateUserId(),
                    deviceName = getDefaultDeviceName(),
                    audioQuality = AudioQuality.STANDARD,
                    defaultAudioSource = AudioSource.MICROPHONE,
                    preferredTransport = TransportType.WEBRTC,
                    maxClients = 0,
                    defaultPort = 8080,
                    bufferSize = 1024,
                    enableAutoDiscovery = true,
                    allowDiscovery = true,
                    requirePermissionForClients = false,
                    darkMode = DarkModePreference.SYSTEM,
                    keepScreenOn = true,
                    showNotifications = true,
                    enableDebugLogging = false,
                    autoStopTimer = 0,
                    createdAt = System.currentTimeMillis(),
                    lastUpdated = System.currentTimeMillis()
                )
                
                userSettingsRepository.updateUserSettings(defaultSettings)
                _userSettings.value = defaultSettings
                updateUIStateFromSettings(defaultSettings)
                
            } catch (e: Exception) {
                _error.value = "Failed to create default settings: ${e.message}"
            }
        }
    }

    /**
     * Update UI state from loaded settings
     */
    private fun updateUIStateFromSettings(settings: UserSettings) {
        _audioQuality.value = settings.audioQuality
        _defaultAudioSource.value = settings.defaultAudioSource
        _preferredTransport.value = settings.preferredTransport
        _maxClients.value = settings.maxClients
        _defaultPort.value = settings.defaultPort
        _bufferSize.value = settings.bufferSize
        _enableAutoDiscovery.value = settings.enableAutoDiscovery
        _allowDiscovery.value = settings.allowDiscovery
        _requirePermissionForClients.value = settings.requirePermissionForClients
        _deviceName.value = settings.deviceName
        _darkMode.value = settings.darkMode
        _keepScreenOn.value = settings.keepScreenOn
        _showNotifications.value = settings.showNotifications
        _enableDebugLogging.value = settings.enableDebugLogging
        _autoStopTimer.value = settings.autoStopTimer
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
                _preferredTransport,
                _maxClients,
                _defaultPort,
                _deviceName,
                _darkMode
            ) { _, _, _, _, _, _, _ ->
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

    fun setPreferredTransport(transport: TransportType) {
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
     * Save all settings
     */
    fun saveSettings() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val currentSettings = _userSettings.value
                if (currentSettings != null) {
                    val updatedSettings = currentSettings.copy(
                        deviceName = _deviceName.value,
                        audioQuality = _audioQuality.value,
                        defaultAudioSource = _defaultAudioSource.value,
                        preferredTransport = _preferredTransport.value,
                        maxClients = _maxClients.value,
                        defaultPort = _defaultPort.value,
                        bufferSize = _bufferSize.value,
                        enableAutoDiscovery = _enableAutoDiscovery.value,
                        allowDiscovery = _allowDiscovery.value,
                        requirePermissionForClients = _requirePermissionForClients.value,
                        darkMode = _darkMode.value,
                        keepScreenOn = _keepScreenOn.value,
                        showNotifications = _showNotifications.value,
                        enableDebugLogging = _enableDebugLogging.value,
                        autoStopTimer = _autoStopTimer.value,
                        lastUpdated = System.currentTimeMillis()
                    )
                    
                    userSettingsRepository.updateUserSettings(updatedSettings)
                    _userSettings.value = updatedSettings
                    _hasUnsavedChanges.value = false
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
