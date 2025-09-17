package io.github.gauravyad69.speakershare.data.repository

import io.github.gauravyad69.speakershare.data.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing audio stream state.
 * Handles real-time audio streaming data and metrics.
 */
interface AudioStreamRepository {
    
    /**
     * Get current audio stream as a flow for reactive updates
     */
    fun getCurrentStream(): Flow<AudioStream?>
    
    /**
     * Create new audio stream
     */
    suspend fun createStream(
        sessionId: String,
        source: AudioSource,
        transport: StreamTransport,
        quality: AudioQuality,
        bufferSize: Int = 2048
    ): Result<AudioStream>
    
    /**
     * Start streaming
     */
    suspend fun startStream(): Result<Unit>
    
    /**
     * Stop streaming
     */
    suspend fun stopStream(): Result<Unit>
    
    /**
     * Update stream transport (WebRTC <-> UDP fallback)
     */
    suspend fun updateTransport(transport: StreamTransport): Result<Unit>
    
    /**
     * Update stream quality
     */
    suspend fun updateQuality(quality: AudioQuality): Result<Unit>
    
    /**
     * Update stream metrics
     */
    suspend fun updateMetrics(metrics: StreamMetrics): Result<Unit>
    
    /**
     * Get current stream state synchronously
     */
    suspend fun getStreamState(): AudioStream?
    
    /**
     * Check if stream is active
     */
    suspend fun isStreamActive(): Boolean
    
    /**
     * Get stream metrics for monitoring
     */
    suspend fun getStreamMetrics(): StreamMetrics?
    
    /**
     * Reset stream metrics
     */
    suspend fun resetMetrics(): Result<Unit>
}
