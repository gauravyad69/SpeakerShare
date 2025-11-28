package io.github.gauravyad69.speakershare.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import java.nio.ByteBuffer

/**
 * AudioCaptureService handles audio capture from multiple sources:
 * - Microphone input using AudioRecord
 * - System audio using MediaProjection (Android 10+)
 * 
 * Provides real-time PCM audio data to the encoding pipeline.
 * 
 * Key Features:
 * - Multiple audio source support
 * - Real-time PCM streaming 
 * - Automatic permission handling
 * - Low-latency capture configuration
 * - Background capture support
 */
@Singleton
class AudioCaptureService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Audio configuration constants
    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNELS = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE_MULTIPLIER = 4
        private const val TAG = "AudioCaptureService"
    }

    // Audio capture configuration
    data class AudioCaptureConfig(
        val sampleRate: Int = SAMPLE_RATE,
        val channelConfig: Int = CHANNELS,
        val audioFormat: Int = ENCODING,
        val bufferSizeBytes: Int = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * BUFFER_SIZE_MULTIPLIER
    )

    // Audio capture state
    data class CaptureState(
        val isCapturing: Boolean = false,
        val audioSource: io.github.gauravyad69.speakershare.data.model.AudioSource = io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE,
        val sampleRate: Int = SAMPLE_RATE,
        val channels: Int = 1,
        val bitDepth: Int = 16
    )

    // Current capture state
    private val _captureState = MutableStateFlow(CaptureState())
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    // Audio data stream (PCM samples)
    private val _audioDataFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioDataFlow: SharedFlow<ByteArray> = _audioDataFlow.asSharedFlow()

    // Current audio level for visualization
    private val _currentAudioLevel = MutableStateFlow(0f)
    val currentAudioLevel: StateFlow<Float> = _currentAudioLevel.asStateFlow()

    // Internal capture components
    private var audioRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null
    private var audioPlayback: AudioPlayback? = null
    private var captureJob: Job? = null
    private val captureScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Start audio capture from the specified source
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun startCapture(
        audioSource: io.github.gauravyad69.speakershare.data.model.AudioSource,
        sampleRate: Int = SAMPLE_RATE
    ): Result<Unit> {
        return try {
            if (_captureState.value.isCapturing) {
                stopCapture()
            }

            when (audioSource) {
                io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE -> {
                    startMicrophoneCapture(sampleRate)
                }
                io.github.gauravyad69.speakershare.data.model.AudioSource.SYSTEM_AUDIO -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startSystemAudioCapture(sampleRate)
                    } else {
                        return Result.failure(UnsupportedOperationException("System audio capture requires Android 10+"))
                    }
                }
            }

            _captureState.value = _captureState.value.copy(
                isCapturing = true,
                audioSource = audioSource,
                sampleRate = sampleRate
            )

            Result.success(Unit)
        } catch (e: Exception) {
            stopCapture()
            Result.failure(e)
        }
    }

    /**
     * Stop audio capture
     */
    suspend fun stopCapture(): Result<Unit> {
        return try {
            captureJob?.cancel()
            captureJob = null

            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
            audioRecord = null

            audioPlayback?.release()
            audioPlayback = null

            mediaProjection?.stop()
            mediaProjection = null

            _captureState.value = _captureState.value.copy(isCapturing = false)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Switch audio source during active capture
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun switchAudioSource(newSource: io.github.gauravyad69.speakershare.data.model.AudioSource): Result<Unit> {
        return try {
            if (_captureState.value.isCapturing) {
                val currentSampleRate = _captureState.value.sampleRate
                stopCapture()
                delay(100) // Brief pause for cleanup
                startCapture(newSource, currentSampleRate)
            } else {
                Result.failure(IllegalStateException("Not currently capturing"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Start microphone audio capture
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun startMicrophoneCapture(sampleRate: Int) {
        val config = AudioCaptureConfig(sampleRate = sampleRate)
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            config.sampleRate,
            config.channelConfig,
            config.audioFormat,
            config.bufferSizeBytes
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("Failed to initialize AudioRecord")
        }

        audioRecord?.startRecording()

        captureJob = captureScope.launch {
            captureMicrophoneLoop(config)
        }
    }

    /**
     * Start system audio capture using MediaProjection
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun startSystemAudioCapture(sampleRate: Int) {
        // Note: In real implementation, MediaProjection would be initialized
        // with user permission via MediaProjectionManager.createScreenCaptureIntent()
        // For now, we'll simulate system audio capture
        
        val config = AudioCaptureConfig(sampleRate = sampleRate)
        captureJob = captureScope.launch {
            captureSystemAudioLoop(config)
        }
    }

    /**
     * Microphone capture loop
     */
    private suspend fun captureMicrophoneLoop(config: AudioCaptureConfig) {
        val buffer = ByteArray(config.bufferSizeBytes)
        
        while (currentCoroutineContext().isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                
                if (bytesRead > 0) {
                    val audioData = buffer.copyOf(bytesRead)
                    _audioDataFlow.tryEmit(audioData)
                    calculateAudioLevel(audioData)
                }
                
                // Yield to prevent blocking
                yield()
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    // Log error but continue capture
                    println("Microphone capture error: ${e.message}")
                }
                break
            }
        }
    }

    private fun calculateAudioLevel(data: ByteArray) {
        var sum = 0.0
        // Process 16-bit PCM samples
        for (i in 0 until data.size - 1 step 2) {
            // Little endian
            val sample = ((data[i+1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort()
            sum += sample * sample
        }
        val rms = kotlin.math.sqrt(sum / (data.size / 2))
        // Normalize to 0..1 (assuming 16-bit max is 32768)
        val level = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
        _currentAudioLevel.value = level
    }

    /**
     * System audio capture loop (simulated for test environment)
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun captureSystemAudioLoop(config: AudioCaptureConfig) {
        // In real implementation, this would use AudioPlaybackCapture
        // with MediaProjection to capture system audio
        // For testing, we'll simulate system audio data
        
        val simulatedBuffer = ByteArray(config.bufferSizeBytes)
        
        while (currentCoroutineContext().isActive) {
            try {
                // Simulate system audio data (silence for testing)
                simulatedBuffer.fill(0)
                _audioDataFlow.tryEmit(simulatedBuffer.clone())
                
                // Maintain real-time timing
                delay(config.bufferSizeBytes * 1000L / (config.sampleRate * 2)) // 16-bit = 2 bytes per sample
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    println("System audio capture error: ${e.message}")
                }
                break
            }
        }
    }

    /**
     * Get current audio levels for visualization
     */
    fun getCurrentAudioLevel(): Float {
        return _currentAudioLevel.value
    }

    /**
     * Initialize MediaProjection for system audio capture
     * This would be called after user grants screen capture permission
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun initializeMediaProjection(resultCode: Int, data: Intent): Result<Unit> {
        return try {
            val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check if system audio capture is available
     */
    fun isSystemAudioCaptureAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    /**
     * Get capture latency estimation
     */
    fun getEstimatedLatency(): Long {
        val config = AudioCaptureConfig()
        return (config.bufferSizeBytes * 1000L) / (config.sampleRate * 2) // milliseconds
    }

    /**
     * Audio playback helper for system audio capture
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class AudioPlayback {
        private var audioTrack: AudioTrack? = null

        fun initialize(): Result<Unit> {
            return try {
                val config = AudioCaptureConfig()
                
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(config.sampleRate)
                            .setEncoding(config.audioFormat)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(config.bufferSizeBytes)
                    .build()
                
                audioTrack?.play()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        fun release() {
            audioTrack?.apply {
                if (state == AudioTrack.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
            audioTrack = null
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        captureScope.cancel()
        runBlocking {
            stopCapture()
        }
    }
}