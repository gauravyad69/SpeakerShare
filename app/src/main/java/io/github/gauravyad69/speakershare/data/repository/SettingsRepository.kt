package io.github.gauravyad69.speakershare.data.repository

import android.content.Context
import android.content.SharedPreferences
import io.github.gauravyad69.speakershare.data.model.UserSettings
import io.github.gauravyad69.speakershare.data.model.AudioSource
import io.github.gauravyad69.speakershare.data.model.AudioQuality
import io.github.gauravyad69.speakershare.data.model.AudioEncoding
import io.github.gauravyad69.speakershare.data.model.LatencyProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Repository for managing user settings using SharedPreferences.
 * Handles persistence of audio preferences, network settings, and UI preferences.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : UserSettingsRepository {
    
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    
    private val _userSettings = MutableStateFlow(loadSettings())
    val userSettings: StateFlow<UserSettings> = _userSettings.asStateFlow()

    override fun getUserSettings(): Flow<UserSettings> = userSettings

    override suspend fun getCurrentSettings(): UserSettings = _userSettings.value
    
    companion object {
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
        private const val KEY_LATENCY_PROFILE = "latency_profile"
        // Audio sync settings
        private const val KEY_AUDIO_SYNC_POSITION_TOLERANCE = "audio_sync_position_tolerance"
        private const val KEY_AUDIO_SYNC_MIN_SEEK_INTERVAL = "audio_sync_min_seek_interval"
        // Video sync settings
        private const val KEY_VIDEO_SYNC_POSITION_TOLERANCE = "video_sync_position_tolerance"
        private const val KEY_VIDEO_SYNC_MIN_SEEK_INTERVAL = "video_sync_min_seek_interval"
        // Legacy keys (for migration)
        private const val KEY_SYNC_POSITION_TOLERANCE = "sync_position_tolerance"
        private const val KEY_SYNC_MIN_SEEK_INTERVAL = "sync_min_seek_interval"
        
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
        // Audio defaults - tighter sync
        private const val DEFAULT_AUDIO_SYNC_POSITION_TOLERANCE = 250  // ms
        private const val DEFAULT_AUDIO_SYNC_MIN_SEEK_INTERVAL = 2000  // ms
        // Video defaults - more relaxed for buffering
        private const val DEFAULT_VIDEO_SYNC_POSITION_TOLERANCE = 500  // ms
        private const val DEFAULT_VIDEO_SYNC_MIN_SEEK_INTERVAL = 5000  // ms
        // Legacy defaults
        private const val DEFAULT_SYNC_POSITION_TOLERANCE = 250  // ms
        private const val DEFAULT_SYNC_MIN_SEEK_INTERVAL = 3000  // ms
    }
    
    /**
     * Load settings from SharedPreferences
     */
    private fun loadSettings(): UserSettings {
        Timber.d("Loading user settings from SharedPreferences")
        
        val audioSource = try {
            AudioSource.valueOf(sharedPreferences.getString(KEY_DEFAULT_AUDIO_SOURCE, DEFAULT_AUDIO_SOURCE)!!)
        } catch (e: IllegalArgumentException) {
            Timber.w("Invalid audio source, using default", e)
            AudioSource.MICROPHONE
        }
        
        val audioEncoding = try {
            AudioEncoding.valueOf(sharedPreferences.getString(KEY_AUDIO_ENCODING, DEFAULT_ENCODING)!!)
        } catch (e: IllegalArgumentException) {
            Timber.w("Invalid audio encoding, using default", e)
            AudioEncoding.AAC
        }
        
        val audioQuality = AudioQuality(
            bitrate = sharedPreferences.getInt(KEY_AUDIO_BITRATE, DEFAULT_BITRATE),
            sampleRate = sharedPreferences.getInt(KEY_AUDIO_SAMPLE_RATE, DEFAULT_SAMPLE_RATE),
            encoding = audioEncoding
        )
        
        return UserSettings(
            displayName = sharedPreferences.getString(KEY_USER_NAME, DEFAULT_USER_NAME)!!,
            defaultAudioSource = audioSource,
            defaultQuality = audioQuality,
            autoStartHost = sharedPreferences.getBoolean(KEY_AUTO_CONNECT, false),
            keepScreenOn = sharedPreferences.getBoolean(KEY_KEEP_SCREEN_ON, true),
            showNetworkMetrics = sharedPreferences.getBoolean(KEY_SHOW_NOTIFICATIONS, true),
            maxClients = sharedPreferences.getInt(KEY_MAX_CLIENTS, DEFAULT_MAX_CLIENTS)
        )
    }
    
    /**
     * Save settings to SharedPreferences
     */
    private fun saveSettings(settings: UserSettings) {
        Timber.d("Saving user settings to SharedPreferences")
        
        sharedPreferences.edit().apply {
            putString(KEY_USER_NAME, settings.displayName)
            putString(KEY_DEFAULT_AUDIO_SOURCE, settings.defaultAudioSource.name)
            putInt(KEY_AUDIO_BITRATE, settings.defaultQuality.bitrate)
            putInt(KEY_AUDIO_SAMPLE_RATE, settings.defaultQuality.sampleRate)
            putString(KEY_AUDIO_ENCODING, settings.defaultQuality.encoding.name)
            putBoolean(KEY_AUTO_CONNECT, settings.autoStartHost)
            putBoolean(KEY_SHOW_NOTIFICATIONS, settings.showNetworkMetrics)
            putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            putInt(KEY_MAX_CLIENTS, settings.maxClients)
            apply()
        }
    }
    
    /**
     * Update user settings
     */
    override suspend fun updateSettings(settings: UserSettings): Result<Unit> {
        return runCatching {
            Timber.d("Updating user settings")
            _userSettings.value = settings
            saveSettings(settings)
        }
    }

    /**
     * Update user name
     */
    override suspend fun updateDisplayName(displayName: String): Result<Unit> {
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(displayName = displayName)
        return updateSettings(updatedSettings)
    }
    
    /**
     * Update default audio source
     */
    override suspend fun updateDefaultAudioSource(audioSource: AudioSource): Result<Unit> {
        Timber.d("Updating default audio source to $audioSource")
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(defaultAudioSource = audioSource)
        return updateSettings(updatedSettings)
    }
    
    /**
     * Update audio quality settings
     */
    override suspend fun updateDefaultQuality(quality: AudioQuality): Result<Unit> {
        Timber.d("Updating audio quality: ${quality.bitrate}kbps, ${quality.sampleRate}Hz, ${quality.encoding}")
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(defaultQuality = quality)
        return updateSettings(updatedSettings)
    }
    
    /**
     * Update auto-start host setting
     */
    suspend fun updateAutoStartHost(autoStartHost: Boolean) {
        Timber.d("Updating auto-start host to $autoStartHost")
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(autoStartHost = autoStartHost)
        updateSettings(updatedSettings)
    }
    
    /**
     * Update network metrics display setting
     */
    suspend fun updateShowNetworkMetrics(showNetworkMetrics: Boolean) {
        Timber.d("Updating show network metrics to $showNetworkMetrics")
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(showNetworkMetrics = showNetworkMetrics)
        updateSettings(updatedSettings)
    }
    
    /**
     * Update keep screen on setting
     */
    suspend fun updateKeepScreenOn(keepScreenOn: Boolean) {
        Timber.d("Updating keep screen on to $keepScreenOn")
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(keepScreenOn = keepScreenOn)
        updateSettings(updatedSettings)
    }
    
    /**
     * Update maximum clients setting
     */
    suspend fun updateMaxClients(maxClients: Int) {
        if (maxClients <= 0) {
            Timber.w("Invalid max clients value: $maxClients, must be positive")
            return
        }
        
        Timber.d("Updating max clients to $maxClients")
        val currentSettings = _userSettings.value
        val updatedSettings = currentSettings.copy(maxClients = maxClients)
        updateSettings(updatedSettings)
    }
    

    
    /**
     * Reset settings to defaults
     */
    override suspend fun resetToDefaults(): Result<Unit> {
        return runCatching {
            Timber.d("Resetting settings to defaults")
            sharedPreferences.edit().clear().apply()
            val defaultSettings = loadSettings()
            _userSettings.value = defaultSettings
        }
    }
    
    /**
     * Get current user name
     */
    fun getCurrentUserName(): String {
        return _userSettings.value.displayName
    }
    
    /**
     * Get current audio quality
     */
    fun getCurrentAudioQuality(): AudioQuality {
        return _userSettings.value.defaultQuality
    }
    
    /**
     * Check if auto-start host is enabled
     */
    fun isAutoStartHostEnabled(): Boolean {
        return _userSettings.value.autoStartHost
    }
    
    /**
     * Check if network metrics are enabled
     */
    fun areNetworkMetricsEnabled(): Boolean {
        return _userSettings.value.showNetworkMetrics
    }
    
    /**
     * Check if keep screen on is enabled
     */
    fun isKeepScreenOnEnabled(): Boolean {
        return _userSettings.value.keepScreenOn
    }
    
    /**
     * Get latency profile from SharedPreferences
     */
    fun getLatencyProfile(): LatencyProfile {
        return try {
            val profileName = sharedPreferences.getString(KEY_LATENCY_PROFILE, LatencyProfile.BALANCED.name)
            LatencyProfile.valueOf(profileName!!)
        } catch (e: Exception) {
            Timber.w("Invalid latency profile, using default", e)
            LatencyProfile.BALANCED
        }
    }
    
    /**
     * Save latency profile to SharedPreferences
     */
    fun saveLatencyProfile(profile: LatencyProfile) {
        Timber.d("Saving latency profile: $profile")
        sharedPreferences.edit().putString(KEY_LATENCY_PROFILE, profile.name).apply()
    }
    
    /**
     * Get audio sync position tolerance (ms) - how much drift before corrective seek
     */
    fun getAudioSyncPositionTolerance(): Int {
        return sharedPreferences.getInt(KEY_AUDIO_SYNC_POSITION_TOLERANCE, DEFAULT_AUDIO_SYNC_POSITION_TOLERANCE)
    }
    
    /**
     * Save audio sync position tolerance
     */
    fun saveAudioSyncPositionTolerance(toleranceMs: Int) {
        Timber.d("Saving audio sync position tolerance: ${toleranceMs}ms")
        sharedPreferences.edit().putInt(KEY_AUDIO_SYNC_POSITION_TOLERANCE, toleranceMs).apply()
    }
    
    /**
     * Get audio minimum seek interval (ms) - cooldown between corrective seeks
     */
    fun getAudioSyncMinSeekInterval(): Int {
        return sharedPreferences.getInt(KEY_AUDIO_SYNC_MIN_SEEK_INTERVAL, DEFAULT_AUDIO_SYNC_MIN_SEEK_INTERVAL)
    }
    
    /**
     * Save audio minimum seek interval
     */
    fun saveAudioSyncMinSeekInterval(intervalMs: Int) {
        Timber.d("Saving audio sync min seek interval: ${intervalMs}ms")
        sharedPreferences.edit().putInt(KEY_AUDIO_SYNC_MIN_SEEK_INTERVAL, intervalMs).apply()
    }
    
    /**
     * Get video sync position tolerance (ms) - how much drift before corrective seek
     */
    fun getVideoSyncPositionTolerance(): Int {
        return sharedPreferences.getInt(KEY_VIDEO_SYNC_POSITION_TOLERANCE, DEFAULT_VIDEO_SYNC_POSITION_TOLERANCE)
    }
    
    /**
     * Save video sync position tolerance
     */
    fun saveVideoSyncPositionTolerance(toleranceMs: Int) {
        Timber.d("Saving video sync position tolerance: ${toleranceMs}ms")
        sharedPreferences.edit().putInt(KEY_VIDEO_SYNC_POSITION_TOLERANCE, toleranceMs).apply()
    }
    
    /**
     * Get video minimum seek interval (ms) - cooldown between corrective seeks
     */
    fun getVideoSyncMinSeekInterval(): Int {
        return sharedPreferences.getInt(KEY_VIDEO_SYNC_MIN_SEEK_INTERVAL, DEFAULT_VIDEO_SYNC_MIN_SEEK_INTERVAL)
    }
    
    /**
     * Save video minimum seek interval
     */
    fun saveVideoSyncMinSeekInterval(intervalMs: Int) {
        Timber.d("Saving video sync min seek interval: ${intervalMs}ms")
        sharedPreferences.edit().putInt(KEY_VIDEO_SYNC_MIN_SEEK_INTERVAL, intervalMs).apply()
    }
    
    /**
     * Get sync position tolerance (ms) - legacy, returns audio value
     * @deprecated Use getAudioSyncPositionTolerance or getVideoSyncPositionTolerance instead
     */
    fun getSyncPositionTolerance(): Int {
        return sharedPreferences.getInt(KEY_SYNC_POSITION_TOLERANCE, DEFAULT_SYNC_POSITION_TOLERANCE)
    }
    
    /**
     * Save sync position tolerance - legacy
     * @deprecated Use saveAudioSyncPositionTolerance or saveVideoSyncPositionTolerance instead
     */
    fun saveSyncPositionTolerance(toleranceMs: Int) {
        Timber.d("Saving sync position tolerance: ${toleranceMs}ms")
        sharedPreferences.edit().putInt(KEY_SYNC_POSITION_TOLERANCE, toleranceMs).apply()
    }
    
    /**
     * Get minimum seek interval (ms) - legacy, returns audio value
     * @deprecated Use getAudioSyncMinSeekInterval or getVideoSyncMinSeekInterval instead
     */
    fun getSyncMinSeekInterval(): Int {
        return sharedPreferences.getInt(KEY_SYNC_MIN_SEEK_INTERVAL, DEFAULT_SYNC_MIN_SEEK_INTERVAL)
    }
    
    /**
     * Save minimum seek interval - legacy
     * @deprecated Use saveAudioSyncMinSeekInterval or saveVideoSyncMinSeekInterval instead
     */
    fun saveSyncMinSeekInterval(intervalMs: Int) {
        Timber.d("Saving sync min seek interval: ${intervalMs}ms")
        sharedPreferences.edit().putInt(KEY_SYNC_MIN_SEEK_INTERVAL, intervalMs).apply()
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
            Timber.d("Settings import not yet implemented")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e("Failed to import settings", e)
            Result.failure(e)
        }
    }
}
