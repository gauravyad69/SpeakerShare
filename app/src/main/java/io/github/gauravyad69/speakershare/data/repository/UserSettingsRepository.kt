package io.github.gauravyad69.speakershare.data.repository

import io.github.gauravyad69.speakershare.data.model.UserSettings
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing persistent user settings.
 * Handles SharedPreferences storage and retrieval.
 */
interface UserSettingsRepository {
    
    /**
     * Get current user settings as a flow for reactive updates
     */
    fun getUserSettings(): Flow<UserSettings>
    
    /**
     * Get current user settings synchronously
     */
    suspend fun getCurrentSettings(): UserSettings
    
    /**
     * Update user settings
     */
    suspend fun updateSettings(settings: UserSettings): Result<Unit>
    
    /**
     * Update display name only
     */
    suspend fun updateDisplayName(displayName: String): Result<Unit>
    
    /**
     * Update default audio source
     */
    suspend fun updateDefaultAudioSource(audioSource: io.github.gauravyad69.speakershare.data.model.AudioSource): Result<Unit>
    
    /**
     * Update default audio quality
     */
    suspend fun updateDefaultQuality(quality: io.github.gauravyad69.speakershare.data.model.AudioQuality): Result<Unit>
    
    /**
     * Reset settings to defaults
     */
    suspend fun resetToDefaults(): Result<Unit>
}
