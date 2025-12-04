package io.github.gauravyad69.speakershare.services

import android.Manifest
import android.content.Intent
import timber.log.Timber
import androidx.annotation.RequiresPermission
import io.github.gauravyad69.speakershare.audio.AudioCaptureService
import io.github.gauravyad69.speakershare.audio.AudioEncoder
import io.github.gauravyad69.speakershare.data.model.AudioEncoding
import io.github.gauravyad69.speakershare.data.model.AudioQuality
import io.github.gauravyad69.speakershare.data.model.AudioSource
import io.github.gauravyad69.speakershare.data.model.AudioStream
import io.github.gauravyad69.speakershare.data.model.LatencyConfig
import io.github.gauravyad69.speakershare.data.model.LatencyProfile
import io.github.gauravyad69.speakershare.data.model.StreamState
import io.github.gauravyad69.speakershare.data.model.StreamTransport
import io.github.gauravyad69.speakershare.network.UdpAudioServer
import io.github.gauravyad69.speakershare.network.WebRTCManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    
    // Mute state - when true, audio is not broadcast
    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()
    
    // Current latency configuration
    private val _latencyConfig = MutableStateFlow(LatencyConfig.fromProfile(LatencyProfile.BALANCED))
    val latencyConfig: StateFlow<LatencyConfig> = _latencyConfig.asStateFlow()
    
    // Coroutine scope and jobs for cleanup
    private var streamingScope: CoroutineScope? = null
    private var pcmCollectorJob: Job? = null
    private var encoderCollectorJob: Job? = null
    
    companion object {
    }
    
    /**
     * Set the latency profile for audio streaming
     */
    fun setLatencyProfile(profile: LatencyProfile) {
        _latencyConfig.value = LatencyConfig.fromProfile(profile)
        Timber.d("Latency profile set to: $profile")
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
            Timber.d("Starting audio stream: $streamId, source: $audioSource, transport: $transport")
            Timber.d("Using latency config: ${_latencyConfig.value}")
            
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
                    Timber.w("Failed to start audio capture for visualization (WebRTC active): ${e.message}")
                }
            } else {
                startUdpStreaming(streamId, audioSource, quality)
            }
            
            // Update state to active
            _currentStream.value = audioStream.copy(state = StreamState.ACTIVE, isActive = true)
            _isStreaming.value = true

            
            Timber.d("Audio stream started successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e("Failed to start audio stream", e)
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
        val config = _latencyConfig.value
        Timber.d("Starting UDP streaming with latency profile: ${config.profile}")
        
        // Create a new scope for this streaming session
        streamingScope?.cancel()
        streamingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val scope = streamingScope!!
        
        // 1. Start UDP Server
        if (!udpAudioServer.startServer(streamId, "Host")) {
             throw RuntimeException("Failed to start UDP server")
        }
        
        // 2. Check if using PCM mode (NO_LATENCY profile)
        val usePcmMode = config.encoding == AudioEncoding.PCM
        
        if (usePcmMode) {
            // PCM Mode: Skip encoding, send raw PCM directly
            Timber.d("Using PCM mode (no codec) for lowest latency")
            
            pcmCollectorJob = scope.launch {
                Timber.d("Starting direct PCM pipeline")
                audioCaptureService.audioDataFlow.collect { pcmData ->
                    if (!_isMuted.value) {
                        // Directly broadcast raw PCM data with isPcm=true flag
                        udpAudioServer.broadcastAudio(pcmData, isPcm = true)
                    }
                }
            }
        } else {
            // AAC Mode: Use encoder
            // 2. Start Encoder with latency-optimized config
            val encoderConfig = AudioEncoder.EncoderConfig(
                sampleRate = config.sampleRate,
                channelCount = 1,
                bitrate = config.bitrate,
                bufferTimeoutUs = config.codecTimeoutUs
            )
            audioEncoder.startEncoding(encoderConfig)
            
            // 3. Set up pipeline collectors BEFORE starting capture
            Timber.d("Setting up audio data pipeline")
            
            pcmCollectorJob = scope.launch {
                Timber.d("Starting audioDataFlow collector")
                audioCaptureService.audioDataFlow.collect { pcmData ->
                    // Only process audio if not muted
                    if (!_isMuted.value) {
                        Timber.d("Received PCM data: ${pcmData.size} bytes")
                        audioEncoder.encodePCMData(pcmData)
                    }
                }
            }
            
            encoderCollectorJob = scope.launch {
                Timber.d("Starting encodedPacketFlow collector")
                audioEncoder.encodedPacketFlow.collect { packet ->
                    // Only broadcast if not muted
                    if (!_isMuted.value) {
                        Timber.d("Broadcasting encoded packet: ${packet.size} bytes")
                        udpAudioServer.broadcastAudio(packet.data)
                    }
                }
            }
        }
        
        Timber.d("Audio data pipeline set up complete")
        
        // 4. Start Capture AFTER pipeline is set up with latency-optimized sample rate
        Timber.d("Starting audio capture with sample rate: ${config.sampleRate}")
        audioCaptureService.startCapture(audioSource, config.sampleRate)

    }
    
    /**
     * Stop current audio streaming
     * @param preserveMediaProjection If true, keeps MediaProjection alive for restart scenarios
     */
    suspend fun stopStreaming(preserveMediaProjection: Boolean = true): Result<Unit> {
        return try {
            Timber.d("Stopping audio stream (preserveMediaProjection=$preserveMediaProjection)")
            
            // Cancel all collector jobs first to stop the pipeline
            Timber.d("Canceling collector jobs...")
            pcmCollectorJob?.cancel()
            pcmCollectorJob = null
            encoderCollectorJob?.cancel()
            encoderCollectorJob = null
            
            // Cancel the streaming scope
            streamingScope?.cancel()
            streamingScope = null
            
            _currentStream.value?.let { stream ->
                if (stream.transport == StreamTransport.WEBRTC) {
                    webRTCManager.stopBroadcasting()
                    audioCaptureService.stopCapture(preserveMediaProjection = preserveMediaProjection)
                } else {
                    // Stop in correct order: capture -> encoder -> server
                    Timber.d("Stopping audio capture (preserveMediaProjection=$preserveMediaProjection)...")
                    audioCaptureService.stopCapture(preserveMediaProjection = preserveMediaProjection)
                    
                    Timber.d("Stopping audio encoder...")
                    audioEncoder.stopEncoding()
                    
                    Timber.d("Stopping UDP server...")
                    udpAudioServer.stopServer()
                }
                
                // Update state
                _currentStream.value = stream.copy(state = StreamState.STOPPED, isActive = false)
                _isStreaming.value = false
                
                // Reset mute state
                _isMuted.value = false
                
                // Clear current stream
                _currentStream.value = null
            }
            
            Timber.d("Audio stream stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e("Failed to stop audio stream", e)
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
            
            Timber.d("Switching audio source from ${currentStream.audioSource} to $newSource")
            
            // Stop current capture but preserve MediaProjection in case user switches back to system audio
            audioCaptureService.stopCapture(preserveMediaProjection = true)
            
            // Initialize new capture
            audioCaptureService.startCapture(newSource, currentStream.quality.sampleRate)
            
            // Update stream
            _currentStream.value = currentStream.copy(audioSource = newSource)
            
            Timber.d("Audio source switched successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e("Failed to switch audio source", e)
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
            
            Timber.d("Updating audio quality: ${newQuality.bitrate}kbps, ${newQuality.sampleRate}Hz")
            
            // Reconfigure encoder with new quality
            // TODO: Implement dynamic reconfiguration in AudioEncoder
            // For now, we'll just update the stream info
            
            // Update stream
            _currentStream.value = currentStream.copy(quality = newQuality)
            
            Timber.d("Audio quality updated successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e("Failed to update audio quality", e)
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
    
    /**
     * Toggle mute state for audio streaming
     * When muted, audio is still captured but not broadcast to clients
     */
    fun toggleMute(): Boolean {
        val newMuteState = !_isMuted.value
        _isMuted.value = newMuteState
        Timber.d("Audio mute toggled: $newMuteState")
        return newMuteState
    }
    
    /**
     * Set mute state directly
     */
    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        Timber.d("Audio mute set to: $muted")
    }

    /**
     * Initialize MediaProjection for system audio capture by delegating
     * to the underlying AudioCaptureService. This should be called with
     * the result received from the MediaProjection permission activity.
     */
    fun initializeMediaProjection(resultCode: Int, data: Intent): Result<Unit> {
        return audioCaptureService.initializeMediaProjection(resultCode, data)
    }
    
    /**
     * Fully release all resources including MediaProjection.
     * Call this only when the app is completely done (e.g., service destroyed).
     */
    suspend fun releaseAllResources() {
        Timber.d("Releasing all audio resources including MediaProjection")
        stopStreaming(preserveMediaProjection = false)
    }
}
