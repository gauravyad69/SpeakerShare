package io.github.gauravyad69.speakershare.services

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import io.github.gauravyad69.speakershare.audio.AudioCaptureService
import io.github.gauravyad69.speakershare.audio.AudioEncoder
import io.github.gauravyad69.speakershare.data.model.AudioQuality
import io.github.gauravyad69.speakershare.data.model.AudioSource
import io.github.gauravyad69.speakershare.data.model.AudioStream
import io.github.gauravyad69.speakershare.data.model.StreamState
import io.github.gauravyad69.speakershare.data.model.StreamTransport
import io.github.gauravyad69.speakershare.network.UdpAudioServer
import io.github.gauravyad69.speakershare.network.WebRTCManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central manager for audio streaming functionality.
 * Coordinates audio capture, encoding, transmission, and playback.
 * Supports both WebRTC and UDP transport protocols.
 */
@Singleton
class AudioStreamManager @Inject constructor(
    private val webRTCManager: WebRTCManager,
    private val udpAudioServer: UdpAudioServer,
    private val audioCaptureService: AudioCaptureService,
    private val audioEncoder: AudioEncoder
) {
    
    private val _currentStream = MutableStateFlow<AudioStream?>(null)
    val currentStream: StateFlow<AudioStream?> = _currentStream.asStateFlow()
    
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    companion object {
        private const val TAG = "AudioStreamManager"
    }
    
    /**
     * Start audio streaming with specified configuration
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun startStreaming(
        streamId: String,
        audioSource: AudioSource,
        quality: AudioQuality,
        transport: String = "WEBRTC"
    ): Result<Unit> {
        return try {
            Log.d(TAG, "Starting audio stream: $streamId, source: $audioSource, transport: $transport")
            
            val transportType = if (transport == "WEBRTC") StreamTransport.WEBRTC else StreamTransport.UDP
            
            val audioStream = AudioStream(
                streamId = streamId,
                sessionId = streamId, // Use same as streamId for now
                audioSource = audioSource,
                transport = transportType,
                quality = quality,
                state = StreamState.STARTING,
                isActive = false,
                startTime = System.currentTimeMillis(),
                bufferSize = 4096,
                metrics = mapOf("latency" to 0L, "packetLoss" to 0.0f)
            )
            
            _currentStream.value = audioStream
            
            // Initialize audio capture and transport
            if (transportType == StreamTransport.WEBRTC) {
                startWebRTCStreaming(streamId)
                // Try to start capture service for visualization only
                try {
                    audioCaptureService.startCapture(audioSource, quality.sampleRate)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start audio capture for visualization (WebRTC active): ${e.message}")
                }
            } else {
                startUdpStreaming(streamId, audioSource, quality)
            }
            
            // Update state to active
            _currentStream.value = audioStream.copy(state = StreamState.ACTIVE, isActive = true)
            _isStreaming.value = true
            
            Log.d(TAG, "Audio stream started successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio stream", e)
            _currentStream.value = _currentStream.value?.copy(state = StreamState.ERROR)
            Result.failure(e)
        }
    }
    
    private suspend fun startWebRTCStreaming(streamId: String) {
        if (!webRTCManager.startBroadcasting()) {
            throw RuntimeException("Failed to start WebRTC broadcasting")
        }
    }
    
    private suspend fun startUdpStreaming(streamId: String, audioSource: AudioSource, quality: AudioQuality) {
        // 1. Start UDP Server
        if (!udpAudioServer.startServer(streamId, "Host")) {
             throw RuntimeException("Failed to start UDP server")
        }
        
        // 2. Start Encoder
        val encoderConfig = audioEncoder.getQualityPreset(
            when {
                quality.bitrate <= 64000 -> AudioEncoder.AudioQuality.LOW
                quality.bitrate <= 128000 -> AudioEncoder.AudioQuality.MEDIUM
                quality.bitrate <= 256000 -> AudioEncoder.AudioQuality.HIGH
                else -> AudioEncoder.AudioQuality.ULTRA
            }
        )
        audioEncoder.startEncoding(encoderConfig)
        
        // 3. Set up pipeline collectors BEFORE starting capture
        Log.d(TAG, "Setting up audio data pipeline")
        
        scope.launch {
            Log.d(TAG, "Starting audioDataFlow collector")
            audioCaptureService.audioDataFlow.collect { pcmData ->
                Log.d(TAG, "Received PCM data: ${pcmData.size} bytes")
                audioEncoder.encodePCMData(pcmData)
            }
        }
        
        scope.launch {
            Log.d(TAG, "Starting encodedPacketFlow collector")
            audioEncoder.encodedPacketFlow.collect { packet ->
                Log.d(TAG, "Broadcasting encoded packet: ${packet.size} bytes")
                udpAudioServer.broadcastAudio(packet.data)
            }
        }
        
        Log.d(TAG, "Audio data pipeline set up complete")
        
        // 4. Start Capture AFTER pipeline is set up
        Log.d(TAG, "Starting audio capture")
        audioCaptureService.startCapture(audioSource, encoderConfig.sampleRate)
    }
    
    /**
     * Stop current audio streaming
     */
    suspend fun stopStreaming(): Result<Unit> {
        return try {
            Log.d(TAG, "Stopping audio stream")
            
            _currentStream.value?.let { stream ->
                if (stream.transport == StreamTransport.WEBRTC) {
                    webRTCManager.stopBroadcasting()
                    audioCaptureService.stopCapture() // Stop visualization capture
                } else {
                    udpAudioServer.stopServer()
                    audioEncoder.stopEncoding()
                    audioCaptureService.stopCapture()
                }
                
                // Update state
                _currentStream.value = stream.copy(state = StreamState.STOPPED, isActive = false)
                _isStreaming.value = false
                
                // Clear current stream after a delay
                _currentStream.value = null
            }
            
            Log.d(TAG, "Audio stream stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop audio stream", e)
            _currentStream.value = _currentStream.value?.copy(state = StreamState.ERROR)
            Result.failure(e)
        }
    }
    
    /**
     * Switch audio source during active streaming
     */
    suspend fun switchAudioSource(newSource: AudioSource): Result<Unit> {
        return try {
            val currentStream = _currentStream.value
            if (currentStream == null || currentStream.state != StreamState.ACTIVE) {
                return Result.failure(IllegalStateException("No active stream to switch source"))
            }
            
            Log.d(TAG, "Switching audio source from ${currentStream.audioSource} to $newSource")
            
            // Stop current capture
            audioCaptureService.stopCapture()
            
            // Initialize new capture
            audioCaptureService.startCapture(newSource, currentStream.quality.sampleRate)
            
            // Update stream
            _currentStream.value = currentStream.copy(audioSource = newSource)
            
            Log.d(TAG, "Audio source switched successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch audio source", e)
            Result.failure(e)
        }
    }
    
    /**
     * Update audio quality settings
     */
    suspend fun updateQuality(newQuality: AudioQuality): Result<Unit> {
        return try {
            val currentStream = _currentStream.value
            if (currentStream == null || currentStream.state != StreamState.ACTIVE) {
                return Result.failure(IllegalStateException("No active stream to update quality"))
            }
            
            Log.d(TAG, "Updating audio quality: ${newQuality.bitrate}kbps, ${newQuality.sampleRate}Hz")
            
            // Reconfigure encoder with new quality
            // TODO: Implement dynamic reconfiguration in AudioEncoder
            // For now, we'll just update the stream info
            
            // Update stream
            _currentStream.value = currentStream.copy(quality = newQuality)
            
            Log.d(TAG, "Audio quality updated successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update audio quality", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get current streaming metrics
     */
    fun getStreamingMetrics(): Map<String, Any>? {
        return _currentStream.value?.metrics
    }
    
    /**
     * Get audio level for visualization
     */
    fun getAudioLevel(): StateFlow<Float> {
        return audioCaptureService.currentAudioLevel
    }
}
