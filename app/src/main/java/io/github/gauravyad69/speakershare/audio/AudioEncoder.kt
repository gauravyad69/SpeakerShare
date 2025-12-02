package io.github.gauravyad69.speakershare.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AudioEncoder handles real-time PCM to AAC encoding for audio streaming.
 * 
 * Features:
 * - Hardware-accelerated AAC encoding when available
 * - Configurable bitrate and quality settings
 * - Real-time streaming optimization
 * - Low-latency encoding pipeline
 * - Automatic fallback to software encoding
 */
@Singleton
class AudioEncoder @Inject constructor() {

    // Encoding configuration
    data class EncoderConfig(
        val sampleRate: Int = 44100,
        val channelCount: Int = 1,
        val bitrate: Int = 128000, // 128 kbps default
        val aacProfile: Int = MediaCodecInfo.CodecProfileLevel.AACObjectLC,
        val bufferTimeoutUs: Long = 10000L // 10ms timeout
    )

    // Encoded audio packet
    data class EncodedAudioPacket(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val isKeyFrame: Boolean = false,
        val size: Int = data.size
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as EncodedAudioPacket
            return data.contentEquals(other.data) && 
                   presentationTimeUs == other.presentationTimeUs &&
                   isKeyFrame == other.isKeyFrame
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + presentationTimeUs.hashCode()
            result = 31 * result + isKeyFrame.hashCode()
            return result
        }
    }

    // Encoder state
    data class EncoderState(
        val isEncoding: Boolean = false,
        val config: EncoderConfig = EncoderConfig(),
        val packetsEncoded: Long = 0,
        val bytesEncoded: Long = 0,
        val errorCount: Int = 0
    )

    // State management
    private val _encoderState = MutableStateFlow(EncoderState())
    val encoderState: StateFlow<EncoderState> = _encoderState.asStateFlow()

    // Encoded packet stream
    private val _encodedPacketFlow = MutableSharedFlow<EncodedAudioPacket>(
        replay = 0,
        extraBufferCapacity = 5,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val encodedPacketFlow: SharedFlow<EncodedAudioPacket> = _encodedPacketFlow.asSharedFlow()

    // Encoder components
    private var mediaCodec: MediaCodec? = null
    private var encodingJob: Job? = null
    private val encodingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Input buffer queue for PCM data
    private val inputBufferQueue = ArrayDeque<Pair<ByteArray, Long>>()
    private val inputBufferMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Start the AAC encoder with specified configuration
     */
    suspend fun startEncoding(config: EncoderConfig = EncoderConfig()): Result<Unit> {
        return try {
            if (_encoderState.value.isEncoding) {
                stopEncoding()
            }

            initializeEncoder(config)
            
            encodingJob = encodingScope.launch {
                encodingLoop()
            }

            _encoderState.value = _encoderState.value.copy(
                isEncoding = true,
                config = config,
                packetsEncoded = 0,
                bytesEncoded = 0,
                errorCount = 0
            )

            Result.success(Unit)
        } catch (e: Exception) {
            stopEncoding()
            Result.failure(e)
        }
    }

    /**
     * Stop the AAC encoder
     */
    suspend fun stopEncoding(): Result<Unit> {
        return try {
            // Cancel encoding job and wait for it to complete
            encodingJob?.cancel()
            encodingJob?.join()  // Wait for encoding loop to actually stop
            encodingJob = null

            // Safely stop and release MediaCodec after encoding loop has stopped
            mediaCodec?.let { codec ->
                try {
                    codec.stop()
                } catch (e: IllegalStateException) {
                    // Codec not in executing state - this is fine
                    android.util.Log.w("AudioEncoder", "MediaCodec.stop() failed: ${e.message}")
                }
                try {
                    codec.release()
                } catch (e: Exception) {
                    android.util.Log.w("AudioEncoder", "MediaCodec.release() failed: ${e.message}")
                }
            }
            mediaCodec = null

            inputBufferMutex.withLock {
                inputBufferQueue.clear()
            }

            _encoderState.value = _encoderState.value.copy(isEncoding = false)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Encode PCM audio data to AAC
     */
    suspend fun encodePCMData(pcmData: ByteArray, timestampUs: Long = System.nanoTime() / 1000): Result<Unit> {
        return try {
            if (!_encoderState.value.isEncoding) {
                android.util.Log.w("AudioEncoder", "Encoder not started, dropping data")
                return Result.failure(IllegalStateException("Encoder not started"))
            }

            inputBufferMutex.withLock {
                inputBufferQueue.addLast(Pair(pcmData.clone(), timestampUs))
                android.util.Log.d("AudioEncoder", "Queued PCM data: ${pcmData.size} bytes, queue size: ${inputBufferQueue.size}")
                
                // Prevent queue overflow
                while (inputBufferQueue.size > 10) {
                    inputBufferQueue.removeFirstOrNull()
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            incrementErrorCount()
            Result.failure(e)
        }
    }

    /**
     * Initialize MediaCodec for AAC encoding
     */
    private suspend fun initializeEncoder(config: EncoderConfig) {
        val mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, config.channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, config.aacProfile)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    /**
     * Main encoding loop
     */
    private suspend fun encodingLoop() {
        val config = _encoderState.value.config
        var loopCounter = 0
        
        android.util.Log.d("AudioEncoder", "Starting encoding loop")
        
        while (currentCoroutineContext().isActive && mediaCodec != null) {
            try {
                loopCounter++
                // Process input buffers
                processInputBuffers()
                
                // Process output buffers
                processOutputBuffers()
                
                if (loopCounter % 100 == 0) {
                    android.util.Log.d("AudioEncoder", "Encoding loop iteration $loopCounter")
                }
                
                // Brief yield to prevent blocking
                yield()
                
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    incrementErrorCount()
                    android.util.Log.e("AudioEncoder", "Encoding loop error: ${e.message}")
                    delay(10) // Brief pause on error
                }
            }
        }
        
        android.util.Log.d("AudioEncoder", "Encoding loop exited")
    }

    /**
     * Process input buffers (PCM -> Encoder)
     */
    private suspend fun processInputBuffers() {
        val codec = mediaCodec ?: return
        
        // Use a small timeout (10ms) instead of 0 to give the encoder time to process
        val inputBufferIndex = codec.dequeueInputBuffer(10000)
        if (inputBufferIndex >= 0) {
            inputBufferMutex.withLock {
                val inputData = inputBufferQueue.removeFirstOrNull()
                
                if (inputData != null) {
                    val (pcmData, timestampUs) = inputData
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                    
                    inputBuffer?.apply {
                        clear()
                        put(pcmData)
                    }
                    
                    codec.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        pcmData.size,
                        timestampUs,
                        0
                    )
                    android.util.Log.d("AudioEncoder", "Queued ${pcmData.size} bytes to encoder input buffer")
                } else {
                    // No data to encode, return the buffer
                    codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, 0)
                }
            }
        } else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            // No buffer available, this is normal
        } else {
            android.util.Log.w("AudioEncoder", "Unexpected input buffer index: $inputBufferIndex")
        }
    }

    /**
     * Process output buffers (Encoder -> AAC packets)
     */
    private suspend fun processOutputBuffers() {
        val codec = mediaCodec ?: return
        val config = _encoderState.value.config
        val bufferInfo = MediaCodec.BufferInfo()
        
        var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        
        while (outputBufferIndex >= 0) {
            try {
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                
                if (outputBuffer != null && bufferInfo.size > 0) {
                    val encodedData = ByteArray(bufferInfo.size)
                    outputBuffer.apply {
                        position(bufferInfo.offset)
                        get(encodedData)
                    }
                    
                    val packet = EncodedAudioPacket(
                        data = encodedData,
                        presentationTimeUs = bufferInfo.presentationTimeUs,
                        isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    )
                    
                    val emitted = _encodedPacketFlow.tryEmit(packet)
                    android.util.Log.d("AudioEncoder", "Emitted encoded packet: ${encodedData.size} bytes, emitted=$emitted")
                    updateEncodingStats(packet)
                }
                
                codec.releaseOutputBuffer(outputBufferIndex, false)
                outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                
            } catch (e: Exception) {
                codec.releaseOutputBuffer(outputBufferIndex, false)
                incrementErrorCount()
                break
            }
        }
    }

    /**
     * Update encoding statistics
     */
    private fun updateEncodingStats(packet: EncodedAudioPacket) {
        _encoderState.value = _encoderState.value.copy(
            packetsEncoded = _encoderState.value.packetsEncoded + 1,
            bytesEncoded = _encoderState.value.bytesEncoded + packet.size
        )
    }

    /**
     * Increment error count
     */
    private fun incrementErrorCount() {
        _encoderState.value = _encoderState.value.copy(
            errorCount = _encoderState.value.errorCount + 1
        )
    }

    /**
     * Get encoding performance metrics
     */
    fun getEncodingMetrics(): EncodingMetrics {
        val state = _encoderState.value
        val avgPacketSize = if (state.packetsEncoded > 0) {
            state.bytesEncoded / state.packetsEncoded
        } else {
            0L
        }
        
        return EncodingMetrics(
            isActive = state.isEncoding,
            packetsEncoded = state.packetsEncoded,
            bytesEncoded = state.bytesEncoded,
            errorCount = state.errorCount,
            averagePacketSize = avgPacketSize,
            bitrate = state.config.bitrate,
            sampleRate = state.config.sampleRate
        )
    }

    /**
     * Configure encoding quality presets
     */
    fun getQualityPreset(quality: AudioQuality): EncoderConfig {
        return when (quality) {
            AudioQuality.LOW -> EncoderConfig(
                sampleRate = 22050,
                channelCount = 1,
                bitrate = 64000
            )
            AudioQuality.MEDIUM -> EncoderConfig(
                sampleRate = 44100,
                channelCount = 1,
                bitrate = 128000
            )
            AudioQuality.HIGH -> EncoderConfig(
                sampleRate = 44100,
                channelCount = 2,
                bitrate = 256000
            )
            AudioQuality.ULTRA -> EncoderConfig(
                sampleRate = 48000,
                channelCount = 2,
                bitrate = 320000
            )
        }
    }

    /**
     * Check if hardware encoding is available
     */
    fun isHardwareEncodingAvailable(): Boolean {
        return try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val codecInfo = codec.codecInfo
            val isHardware = !codecInfo.name.lowercase().contains("sw")
            codec.release()
            isHardware
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Estimate encoding latency
     */
    fun getEstimatedLatency(): Long {
        val config = _encoderState.value.config
        // Estimate based on buffer size and processing time
        return (16384 * 1000L) / (config.sampleRate * 2) // milliseconds
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        encodingScope.cancel()
        runBlocking {
            stopEncoding()
        }
    }

    // Supporting data classes and enums
    
    data class EncodingMetrics(
        val isActive: Boolean,
        val packetsEncoded: Long,
        val bytesEncoded: Long,
        val errorCount: Int,
        val averagePacketSize: Long,
        val bitrate: Int,
        val sampleRate: Int
    )

    enum class AudioQuality {
        LOW,     // 64kbps, 22kHz, Mono
        MEDIUM,  // 128kbps, 44kHz, Mono  
        HIGH,    // 256kbps, 44kHz, Stereo
        ULTRA    // 320kbps, 48kHz, Stereo
    }
}