package io.github.gauravyad69.speakershare.data.repository

import android.content.Context
import android.content.SharedPreferences
import io.github.gauravyad69.speakershare.data.model.UserSettings
import io.github.gauravyad69.speakershare.data.model.AudioSource
import io.github.gauravyad69.speakershare.data.model.AudioQuality
import io.github.gauravyad69.speakershare.data.model.AudioEncoding
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Repository for managing user settings using SharedPreferences.
 * Handles persistence of audio preferences, network settings, and UI preferences.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    
    private val _userSettings = MutableStateFlow(loadSettings())
    val userSettings: StateFlow<UserSettings> = _userSettings.asStateFlow()
    
    companion object {
        private const val TAG = "SettingsRepository"
        private const val PREFS_NAME = "speakershare_settings"
        
        // Settings keys
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_DEFAULT_AUDIO_SOURCE = "default_audio_source"
        private const val KEY_AUDIO_BITRATE = "audio_bitrate"
        private const val KEY_AUDIO_SAMPLE_RATE = "audio_sample_rate"
        private const val KEY_AUDIO_ENCODING = "audio_encoding"
        private const val KEY_DEFAULT_VOLUME = "default_volume"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_SHOW_NOTIFICATIONS = "show_notifications"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_USE_DARK_THEME = "use_dark_theme"
        private const val KEY_MAX_CLIENTS = "max_clients"
        private const val KEY_CONNECTION_TIMEOUT = "connection_timeout"
        private const val KEY_DISCOVERY_TIMEOUT = "discovery_timeout"
        
        // Default values
        private const val DEFAULT_USER_NAME = "SpeakerShare User"
        private const val DEFAULT_AUDIO_SOURCE = "MICROPHONE"
        private const val DEFAULT_BITRATE = 128
        private const val DEFAULT_SAMPLE_RATE = 44100
        private const val DEFAULT_ENCODING = "AAC"
        private const val DEFAULT_VOLUME = 1.0f
        private const val DEFAULT_MAX_CLIENTS = 50
        private const val DEFAULT_CONNECTION_TIMEOUT = 5000L
        private const val DEFAULT_DISCOVERY_TIMEOUT = 10000L
    }
    
    /**
     * Load settings from SharedPreferences
     */
    private fun loadSettings(): UserSettings {
        Log.d(TAG, "Loading user settings from SharedPreferences")
        
        val audioSource = try {
            AudioSource.valueOf(sharedPreferences.getString(KEY_DEFAULT_AUDIO_SOURCE, DEFAULT_AUDIO_SOURCE)!!)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid audio source, using default", e)
            AudioSource.MICROPHONE
        }
        
        val audioEncoding = try {
            AudioEncoding.valueOf(sharedPreferences.getString(KEY_AUDIO_ENCODING, DEFAULT_ENCODING)!!)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid audio encoding, using default", e)
            AudioEncoding.AAC
        }
        
        val audioQuality = AudioQuality(
            bitrate = sharedPreferences.getInt(KEY_AUDIO_BITRATE, DEFAULT_BITRATE),
            sampleRate = sharedPreferences.getInt(KEY_AUDIO_SAMPLE_RATE, DEFAULT_SAMPLE_RATE),
            encoding = audioEncoding
        )
        
        return UserSettings(
            userName = sharedPreferences.getString(KEY_USER_NAME, DEFAULT_USER_NAME)!!,
            defaultAudioSource = audioSource,
            defaultQuality = audioQuality,
            defaultVolume = sharedPreferences.getFloat(KEY_DEFAULT_VOLUME, DEFAULT_VOLUME),
            autoConnect = sharedPreferences.getBoolean(KEY_AUTO_CONNECT, false),
            showNotifications = sharedPreferences.getBoolean(KEY_SHOW_NOTIFICATIONS, true),
            keepScreenOn = sharedPreferences.getBoolean(KEY_KEEP_SCREEN_ON, true),
            useDarkTheme = sharedPreferences.getBoolean(KEY_USE_DARK_THEME, false),
            maxClients = sharedPreferences.getInt(KEY_MAX_CLIENTS, DEFAULT_MAX_CLIENTS),
            connectionTimeout = sharedPreferences.getLong(KEY_CONNECTION_TIMEOUT, DEFAULT_CONNECTION_TIMEOUT),
            discoveryTimeout = sharedPreferences.getLong(KEY_DISCOVERY_TIMEOUT, DEFAULT_DISCOVERY_TIMEOUT)
        )
    }
    
    /**
     * Save settings to SharedPreferences
     */
    private fun saveSettings(settings: UserSettings) {
        Log.d(TAG, "Saving user settings to SharedPreferences")
        
        sharedPreferences.edit().apply {
            putString(KEY_USER_NAME, settings.userName)
            putString(KEY_DEFAULT_AUDIO_SOURCE, settings.defaultAudioSource.name)
            putInt(KEY_AUDIO_BITRATE, settings.defaultQuality.bitrate)
            putInt(KEY_AUDIO_SAMPLE_RATE, settings.defaultQuality.sampleRate)
            putString(KEY_AUDIO_ENCODING, settings.defaultQuality.encoding.name)
            putFloat(KEY_DEFAULT_VOLUME, settings.defaultVolume)
            putBoolean(KEY_AUTO_CONNECT, settings.autoConnect)
            putBoolean(KEY_SHOW_NOTIFICATIONS, settings.showNotifications)
            putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            putBoolean(KEY_USE_DARK_THEME, settings.useDarkTheme)
            putInt(KEY_MAX_CLIENTS, settings.maxClients)
            putLong(KEY_CONNECTION_TIMEOUT, settings.connectionTimeout)
            putLong(KEY_DISCOVERY_TIMEOUT, settings.discoveryTimeout)
            apply()
        }
    }
    
    /**
     * Update user settings
     */
    suspend fun updateSettings(settings: UserSettings) {
        Log.d(TAG, "Updating user settings")
        _userSettings.value = settings
        saveSettings(settings)
    }
    
    /**
     * Update user name
     */
    suspend fun updateUserName(userName: String) {
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(userName = userName)
        updateSettings(updatedSettings)
    }
    
    /**
     * Update default audio source
     */
    suspend fun updateDefaultAudioSource(audioSource: AudioSource) {
        Log.d(TAG, "Updating default audio source to $audioSource")
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(defaultAudioSource = audioSource)
        updateSettings(updatedSettings)
    }
    
    /**
     * Update audio quality settings
     */
    suspend fun updateAudioQuality(quality: AudioQuality) {
        Log.d(TAG, "Updating audio quality: ${quality.bitrate}kbps, ${quality.sampleRate}Hz, ${quality.encoding}")
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(defaultQuality = quality)
        updateSettings(updatedSettings)
    }
    
    /**
     * Update default volume
     */
    suspend fun updateDefaultVolume(volume: Float) {
        if (volume < 0.0f || volume > 1.0f) {
            Log.w(TAG, "Invalid volume value: $volume, must be between 0.0 and 1.0")
            return
        }
        
        Log.d(TAG, "Updating default volume to $volume")
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(defaultVolume = volume)
        updateSettings(updatedSettings)
    }
    
    /**
     * Update auto-connect setting
     */
    suspend fun updateAutoConnect(autoConnect: Boolean) {
        Log.d(TAG, "Updating auto-connect to $autoConnect")
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(autoConnect = autoConnect)
        updateSettings(updatedSettings)
    }
    
    /**
     * Update notification settings
     */
    suspend fun updateShowNotifications(showNotifications: Boolean) {
        Log.d(TAG, "Updating show notifications to $showNotifications")
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(showNotifications = showNotifications)
        updateSettings(updatedSettings)
    }
    
    /**
     * Update keep screen on setting
     */
    suspend fun updateKeepScreenOn(keepScreenOn: Boolean) {
        Log.d(TAG, "Updating keep screen on to $keepScreenOn")
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(keepScreenOn = keepScreenOn)
        updateSettings(updatedSettings)
    }
    
    /**
     * Update dark theme setting
     */
    suspend fun updateDarkTheme(useDarkTheme: Boolean) {
        Log.d(TAG, "Updating dark theme to $useDarkTheme")
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(useDarkTheme = useDarkTheme)
        updateSettings(updatedSettings)
    }
    
    /**
     * Update maximum clients setting
     */
    suspend fun updateMaxClients(maxClients: Int) {
        if (maxClients <= 0) {
            Log.w(TAG, "Invalid max clients value: $maxClients, must be positive")
            return
        }
        
        Log.d(TAG, "Updating max clients to $maxClients")
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(maxClients = maxClients)
        updateSettings(updatedSettings)
    }
    
    /**
     * Update connection timeout
     */
    suspend fun updateConnectionTimeout(timeoutMs: Long) {
        if (timeoutMs <= 0) {
            Log.w(TAG, "Invalid connection timeout: $timeoutMs, must be positive")
            return
        }
        
        Log.d(TAG, "Updating connection timeout to ${timeoutMs}ms")
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(connectionTimeout = timeoutMs)
        updateSettings(updatedSettings)
    }
    
    /**
     * Update discovery timeout
     */
    suspend fun updateDiscoveryTimeout(timeoutMs: Long) {
        if (timeoutMs <= 0) {
            Log.w(TAG, "Invalid discovery timeout: $timeoutMs, must be positive")
            return
        }
        
        Log.d(TAG, "Updating discovery timeout to ${timeoutMs}ms")
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(discoveryTimeout = timeoutMs)
        updateSettings(updatedSettings)
    }
    
    /**
     * Reset settings to defaults
     */
    suspend fun resetToDefaults() {
        Log.d(TAG, "Resetting settings to defaults")
        sharedPreferences.edit().clear().apply()
        val defaultSettings = loadSettings()
        _userSettings.value = defaultSettings
    }
    
    /**
     * Get current user name
     */
    fun getCurrentUserName(): String {
        return _userSettings.value.userName
    }
    
    /**
     * Get current audio quality
     */
    fun getCurrentAudioQuality(): AudioQuality {
        return _userSettings.value.defaultQuality
    }
    
    /**
     * Check if auto-connect is enabled
     */
    fun isAutoConnectEnabled(): Boolean {
        return _userSettings.value.autoConnect
    }
    
    /**
     * Check if notifications are enabled
     */
    fun areNotificationsEnabled(): Boolean {
        return _userSettings.value.showNotifications
    }
    
    /**
     * Check if dark theme is enabled
     */
    fun isDarkThemeEnabled(): Boolean {
        return _userSettings.value.useDarkTheme
    }
    
    /**
     * Export settings as JSON string (for backup/restore)
     */
    fun exportSettings(): String {
        // TODO: Implement JSON serialization
        return "{\"exported\": true}"
    }
    
    /**
     * Import settings from JSON string (for backup/restore)
     */
    suspend fun importSettings(json: String): Result<Unit> {
        return try {
            // TODO: Implement JSON deserialization and validation
            Log.d(TAG, "Settings import not yet implemented")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import settings", e)
            Result.failure(e)
        }
    }
}
