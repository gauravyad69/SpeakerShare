package io.github.gauravyad69.speakershare.audio

import android.content.Context
import android.media.*
import androidx.annotation.RequiresPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AudioPlaybackService handles real-time PCM audio playback using AudioTrack.
 * 
 * Features:
 * - Low-latency audio playback
 * - Volume control and mute functionality
 * - Real-time buffer management
 * - Automatic audio focus handling
 * - Support for different audio formats
 * - Background playback support
 */
@Singleton
class AudioPlaybackService @Inject constructor(
    @ApplicationContext private val context: Context
) : AudioManager.OnAudioFocusChangeListener {

    // Playback configuration
    data class PlaybackConfig(
        val sampleRate: Int = 44100,
        val channelConfig: Int = AudioFormat.CHANNEL_OUT_MONO,
        val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
        val bufferSizeBytes: Int = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 4,
        val streamType: Int = AudioManager.STREAM_MUSIC
    )

    // Playback state
    data class PlaybackState(
        val isPlaying: Boolean = false,
        val volume: Float = 1.0f,
        val isMuted: Boolean = false,
        val sampleRate: Int = 44100,
        val channels: Int = 1,
        val bufferLevel: Float = 0.0f,
        val underrunCount: Int = 0
    )

    // State management
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // Audio components
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var playbackJob: Job? = null
    private val playbackScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Audio buffer for playback
    private val playbackBufferQueue = ArrayDeque<ByteArray>()
    private val playbackBufferMutex = kotlinx.coroutines.sync.Mutex()
    private var hasAudioFocus = false

    /**
     * Start audio playback with specified configuration
     */
    suspend fun startPlayback(config: PlaybackConfig = PlaybackConfig()): Result<Unit> {
        return try {
            if (_playbackState.value.isPlaying) {
                stopPlayback()
            }

            // Request audio focus
            requestAudioFocus()

            initializeAudioTrack(config)
            
            playbackJob = playbackScope.launch {
                playbackLoop()
            }

            _playbackState.value = _playbackState.value.copy(
                isPlaying = true,
                sampleRate = config.sampleRate,
                channels = if (config.channelConfig == AudioFormat.CHANNEL_OUT_MONO) 1 else 2,
                underrunCount = 0
            )

            Result.success(Unit)
        } catch (e: Exception) {
            stopPlayback()
            Result.failure(e)
        }
    }

    /**
     * Stop audio playback
     */
    suspend fun stopPlayback(): Result<Unit> {
        return try {
            playbackJob?.cancel()
            playbackJob = null

            audioTrack?.apply {
                if (state == AudioTrack.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
            audioTrack = null

            playbackBufferMutex.withLock {
                playbackBufferQueue.clear()
            }

            // Release audio focus
            releaseAudioFocus()

            _playbackState.value = _playbackState.value.copy(isPlaying = false)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Queue PCM audio data for playback
     */
    suspend fun queueAudioData(pcmData: ByteArray): Result<Unit> {
        return try {
            if (!_playbackState.value.isPlaying) {
                return Result.failure(IllegalStateException("Playback not started"))
            }

            playbackBufferMutex.withLock {
                playbackBufferQueue.offer(pcmData.clone())
                
                // Prevent buffer overflow
                while (playbackBufferQueue.size > 20) {
                    playbackBufferQueue.poll()
                }
            }

            updateBufferLevel()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Set playback volume (0.0 to 1.0)
     */
    suspend fun setVolume(volume: Float): Result<Unit> {
        return try {
            val clampedVolume = volume.coerceIn(0.0f, 1.0f)
            
            audioTrack?.setVolume(if (_playbackState.value.isMuted) 0.0f else clampedVolume)
            
            _playbackState.value = _playbackState.value.copy(volume = clampedVolume)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Set mute state
     */
    suspend fun setMuted(muted: Boolean): Result<Unit> {
        return try {
            val effectiveVolume = if (muted) 0.0f else _playbackState.value.volume
            audioTrack?.setVolume(effectiveVolume)
            
            _playbackState.value = _playbackState.value.copy(isMuted = muted)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Initialize AudioTrack for playback
     */
    private suspend fun initializeAudioTrack(config: PlaybackConfig) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setLegacyStreamType(config.streamType)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(config.sampleRate)
            .setChannelMask(config.channelConfig)
            .setEncoding(config.audioFormat)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(config.bufferSizeBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            throw IllegalStateException("Failed to initialize AudioTrack")
        }

        // Set initial volume
        val currentState = _playbackState.value
        val effectiveVolume = if (currentState.isMuted) 0.0f else currentState.volume
        audioTrack?.setVolume(effectiveVolume)
        
        audioTrack?.play()
    }

    /**
     * Main playback loop
     */
    private suspend fun playbackLoop() {
        while (currentCoroutineContext().isActive && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            try {
                // Get audio data from buffer
                val audioData = playbackBufferMutex.withLock {
                    playbackBufferQueue.poll()
                }

                if (audioData != null) {
                    // Write to AudioTrack
                    val bytesWritten = audioTrack?.write(audioData, 0, audioData.size) ?: 0
                    
                    if (bytesWritten < 0) {
                        // Handle AudioTrack errors
                        when (bytesWritten) {
                            AudioTrack.ERROR_INVALID_OPERATION -> {
                                println("AudioTrack invalid operation")
                                break
                            }
                            AudioTrack.ERROR_BAD_VALUE -> {
                                println("AudioTrack bad value")
                                break
                            }
                            AudioTrack.ERROR_DEAD_OBJECT -> {
                                println("AudioTrack dead object")
                                break
                            }
                        }
                    } else if (bytesWritten < audioData.size) {
                        // Partial write - queue remaining data
                        val remainingData = audioData.copyOfRange(bytesWritten, audioData.size)
                        playbackBufferMutex.withLock {
                            playbackBufferQueue.offerFirst(remainingData)
                        }
                    }
                } else {
                    // No audio data available - brief delay to prevent busy wait
                    delay(5)
                    incrementUnderrunCount()
                }

                updateBufferLevel()
                yield()
                
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    println("Playback loop error: ${e.message}")
                    delay(10)
                }
            }
        }
    }

    /**
     * Request audio focus
     */
    private fun requestAudioFocus() {
        val result = audioManager.requestAudioFocus(
            this,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    /**
     * Release audio focus
     */
    private fun releaseAudioFocus() {
        if (hasAudioFocus) {
            audioManager.abandonAudioFocus(this)
            hasAudioFocus = false
        }
    }

    /**
     * Handle audio focus changes
     */
    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                // Resume playback or restore volume
                runBlocking {
                    val currentState = _playbackState.value
                    if (!currentState.isMuted) {
                        setVolume(currentState.volume)
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                // Stop playback
                runBlocking {
                    stopPlayback()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                // Pause playback temporarily
                runBlocking {
                    setVolume(0.0f)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Lower volume but continue playing
                runBlocking {
                    setVolume(_playbackState.value.volume * 0.3f)
                }
            }
        }
    }

    /**
     * Update buffer level indicator
     */
    private fun updateBufferLevel() {
        val bufferLevel = playbackBufferQueue.size / 20.0f // Percentage of queue filled
        _playbackState.value = _playbackState.value.copy(bufferLevel = bufferLevel)
    }

    /**
     * Increment underrun count
     */
    private fun incrementUnderrunCount() {
        _playbackState.value = _playbackState.value.copy(
            underrunCount = _playbackState.value.underrunCount + 1
        )
    }

    /**
     * Get current playback position
     */
    fun getPlaybackPosition(): Long {
        return audioTrack?.playbackHeadPosition?.toLong() ?: 0L
    }

    /**
     * Get playback performance metrics
     */
    fun getPlaybackMetrics(): PlaybackMetrics {
        val state = _playbackState.value
        val bufferHealth = 1.0f - state.bufferLevel
        
        return PlaybackMetrics(
            isActive = state.isPlaying,
            volume = state.volume,
            isMuted = state.isMuted,
            bufferHealth = bufferHealth,
            underrunCount = state.underrunCount,
            sampleRate = state.sampleRate,
            channels = state.channels,
            hasAudioFocus = hasAudioFocus
        )
    }

    /**
     * Check if audio output is available
     */
    fun isAudioOutputAvailable(): Boolean {
        return try {
            val deviceInfos = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            deviceInfos.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get estimated playback latency
     */
    fun getEstimatedLatency(): Long {
        val config = PlaybackConfig()
        return (config.bufferSizeBytes * 1000L) / (config.sampleRate * 2) // milliseconds
    }

    /**
     * Flush playback buffers
     */
    suspend fun flush(): Result<Unit> {
        return try {
            playbackBufferMutex.withLock {
                playbackBufferQueue.clear()
            }
            
            audioTrack?.flush()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get available audio devices
     */
    fun getAvailableAudioDevices(): List<AudioDeviceInfo> {
        return try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Set preferred audio device
     */
    fun setPreferredDevice(deviceInfo: AudioDeviceInfo): Result<Unit> {
        return try {
            audioTrack?.setPreferredDevice(deviceInfo)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        playbackScope.cancel()
        runBlocking {
            stopPlayback()
        }
    }

    // Supporting data classes
    
    data class PlaybackMetrics(
        val isActive: Boolean,
        val volume: Float,
        val isMuted: Boolean,
        val bufferHealth: Float,
        val underrunCount: Int,
        val sampleRate: Int,
        val channels: Int,
        val hasAudioFocus: Boolean
    )

    /**
     * Audio device information
     */
    data class AudioDevice(
        val id: Int,
        val name: String,
        val type: Int,
        val isDefault: Boolean
    )

    /**
     * Convert AudioDeviceInfo to simpler AudioDevice
     */
    fun AudioDeviceInfo.toAudioDevice(): AudioDevice {
        return AudioDevice(
            id = id,
            name = productName?.toString() ?: "Unknown Device",
            type = type,
            isDefault = false // Would need to check against default device
        )
    }

    /**
     * Get simple audio device list
     */
    fun getSimpleAudioDevices(): List<AudioDevice> {
        return getAvailableAudioDevices().map { it.toAudioDevice() }
    }
}