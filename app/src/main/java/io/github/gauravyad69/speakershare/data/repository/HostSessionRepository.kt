package io.github.gauravyad69.speakershare.data.repository

import io.github.gauravyad69.speakershare.data.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing host session state.
 * Handles in-memory session data during app lifecycle.
 */
interface HostSessionRepository {
    
    /**
     * Get current session as a flow for reactive updates
     */
    fun getCurrentSession(): Flow<HostSession?>
    
    /**
     * Create a new host session
     */
    suspend fun createSession(
        hostName: String,
        audioSource: AudioSource,
        quality: AudioQuality,
        networkInfo: NetworkInfo
    ): Result<HostSession>
    
    /**
     * Start broadcasting for current session
     */
    suspend fun startBroadcasting(): Result<Unit>
    
    /**
     * Stop broadcasting but keep session
     */
    suspend fun stopBroadcasting(): Result<Unit>
    
    /**
     * End session completely
     */
    suspend fun endSession(): Result<Unit>
    
    /**
     * Update audio source for active session
     */
    suspend fun updateAudioSource(audioSource: AudioSource): Result<Unit>
    
    /**
     * Update audio quality for active session
     */
    suspend fun updateAudioQuality(quality: AudioQuality): Result<Unit>
    
    /**
     * Get current session state synchronously
     */
    suspend fun getSessionState(): HostSession?
    
    /**
     * Check if session is active and broadcasting
     */
    suspend fun isSessionActive(): Boolean
}
