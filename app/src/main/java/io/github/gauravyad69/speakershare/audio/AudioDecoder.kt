package io.github.gauravyad69.speakershare.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AudioDecoder handles real-time AAC to PCM decoding for audio playback.
 * 
 * Features:
 * - Hardware-accelerated AAC decoding when available
 * - Real-time streaming optimization
 * - Low-latency decoding pipeline
 * - Automatic error recovery
 * - Buffer management for smooth playback
 */
@Singleton
class AudioDecoder @Inject constructor() {

    // Decoder configuration
    data class DecoderConfig(
        val sampleRate: Int = 44100,
        val channelCount: Int = 1,
        val bufferTimeoutUs: Long = 10000L, // 10ms timeout
        val maxInputSize: Int = 16384
    )

    // Decoded audio data
    data class DecodedAudioData(
        val pcmData: ByteArray,
        val presentationTimeUs: Long,
        val sampleRate: Int,
        val channelCount: Int,
        val size: Int = pcmData.size
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DecodedAudioData
            return pcmData.contentEquals(other.pcmData) && 
                   presentationTimeUs == other.presentationTimeUs &&
                   sampleRate == other.sampleRate &&
                   channelCount == other.channelCount
        }

        override fun hashCode(): Int {
            var result = pcmData.contentHashCode()
            result = 31 * result + presentationTimeUs.hashCode()
            result = 31 * result + sampleRate.hashCode()
            result = 31 * result + channelCount.hashCode()
            return result
        }
    }

    // Decoder state
    data class DecoderState(
        val isDecoding: Boolean = false,
        val config: DecoderConfig = DecoderConfig(),
        val packetsDecoded: Long = 0,
        val bytesDecoded: Long = 0,
        val errorCount: Int = 0,
        val bufferUnderrunCount: Int = 0
    )

    // State management
    private val _decoderState = MutableStateFlow(DecoderState())
    val decoderState: StateFlow<DecoderState> = _decoderState.asStateFlow()

    // Decoded PCM stream
    private val _decodedAudioFlow = MutableSharedFlow<DecodedAudioData>(
        replay = 0,
        extraBufferCapacity = 15,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val decodedAudioFlow: SharedFlow<DecodedAudioData> = _decodedAudioFlow.asSharedFlow()

    // Decoder components
    private var mediaCodec: MediaCodec? = null
    private var decodingJob: Job? = null
    private val decodingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Input packet queue for AAC data
    private val inputPacketQueue = ArrayDeque<AudioEncoder.EncodedAudioPacket>()
    private val inputPacketMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Start the AAC decoder with specified configuration
     */
    suspend fun startDecoding(config: DecoderConfig = DecoderConfig()): Result<Unit> {
        return try {
            if (_decoderState.value.isDecoding) {
                stopDecoding()
            }

            initializeDecoder(config)
            
            decodingJob = decodingScope.launch {
                decodingLoop()
            }

            _decoderState.value = _decoderState.value.copy(
                isDecoding = true,
                config = config,
                packetsDecoded = 0,
                bytesDecoded = 0,
                errorCount = 0,
                bufferUnderrunCount = 0
            )

            Result.success(Unit)
        } catch (e: Exception) {
            stopDecoding()
            Result.failure(e)
        }
    }

    /**
     * Stop the AAC decoder
     */
    suspend fun stopDecoding(): Result<Unit> {
        return try {
            decodingJob?.cancel()
            decodingJob = null

            mediaCodec?.apply {
                try {
                    stop()
                    release()
                } catch (e: Exception) {
                    // Ignore cleanup errors
                }
            }
            mediaCodec = null

            inputPacketMutex.withLock {
                inputPacketQueue.clear()
            }

            _decoderState.value = _decoderState.value.copy(isDecoding = false)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Decode AAC audio packet to PCM
     */
    suspend fun decodeAACPacket(packet: AudioEncoder.EncodedAudioPacket): Result<Unit> {
        return try {
            if (!_decoderState.value.isDecoding) {
                return Result.failure(IllegalStateException("Decoder not started"))
            }

            inputPacketMutex.withLock {
                inputPacketQueue.offer(packet)
                
                // Prevent queue overflow
                while (inputPacketQueue.size > 15) {
                    inputPacketQueue.poll()
                    incrementBufferUnderrun()
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            incrementErrorCount()
            Result.failure(e)
        }
    }

    /**
     * Initialize MediaCodec for AAC decoding
     */
    private suspend fun initializeDecoder(config: DecoderConfig) {
        val mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, config.channelCount).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, config.maxInputSize)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }

        mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(mediaFormat, null, null, 0)
            start()
        }
    }

    /**
     * Main decoding loop
     */
    private suspend fun decodingLoop() {
        val config = _decoderState.value.config
        
        while (currentCoroutineContext().isActive && mediaCodec != null) {
            try {
                // Process input buffers
                processInputBuffers()
                
                // Process output buffers
                processOutputBuffers()
                
                // Brief yield to prevent blocking
                yield()
                
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    incrementErrorCount()
                    println("Decoding loop error: ${e.message}")
                    delay(10) // Brief pause on error
                }
            }
        }
    }

    /**
     * Process input buffers (AAC -> Decoder)
     */
    private suspend fun processInputBuffers() {
        val codec = mediaCodec ?: return
        
        val inputBufferIndex = codec.dequeueInputBuffer(0)
        if (inputBufferIndex >= 0) {
            inputPacketMutex.withLock {
                val inputPacket = inputPacketQueue.poll()
                
                if (inputPacket != null) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                    
                    inputBuffer?.apply {
                        clear()
                        put(inputPacket.data)
                    }
                    
                    codec.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        inputPacket.data.size,
                        inputPacket.presentationTimeUs,
                        0
                    )
                }
            }
        }
    }

    /**
     * Process output buffers (Decoder -> PCM)
     */
    private suspend fun processOutputBuffers() {
        val codec = mediaCodec ?: return
        val config = _decoderState.value.config
        val bufferInfo = MediaCodec.BufferInfo()
        
        var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        
        while (outputBufferIndex >= 0) {
            try {
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                
                if (outputBuffer != null && bufferInfo.size > 0) {
                    val pcmData = ByteArray(bufferInfo.size)
                    outputBuffer.apply {
                        position(bufferInfo.offset)
                        get(pcmData)
                    }
                    
                    val decodedData = DecodedAudioData(
                        pcmData = pcmData,
                        presentationTimeUs = bufferInfo.presentationTimeUs,
                        sampleRate = config.sampleRate,
                        channelCount = config.channelCount
                    )
                    
                    _decodedAudioFlow.tryEmit(decodedData)
                    updateDecodingStats(decodedData)
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
     * Update decoding statistics
     */
    private fun updateDecodingStats(data: DecodedAudioData) {
        _decoderState.value = _decoderState.value.copy(
            packetsDecoded = _decoderState.value.packetsDecoded + 1,
            bytesDecoded = _decoderState.value.bytesDecoded + data.size
        )
    }

    /**
     * Increment error count
     */
    private fun incrementErrorCount() {
        _decoderState.value = _decoderState.value.copy(
            errorCount = _decoderState.value.errorCount + 1
        )
    }

    /**
     * Increment buffer underrun count
     */
    private fun incrementBufferUnderrun() {
        _decoderState.value = _decoderState.value.copy(
            bufferUnderrunCount = _decoderState.value.bufferUnderrunCount + 1
        )
    }

    /**
     * Get decoding performance metrics
     */
    fun getDecodingMetrics(): DecodingMetrics {
        val state = _decoderState.value
        val avgPacketSize = if (state.packetsDecoded > 0) {
            state.bytesDecoded / state.packetsDecoded
        } else {
            0L
        }
        
        return DecodingMetrics(
            isActive = state.isDecoding,
            packetsDecoded = state.packetsDecoded,
            bytesDecoded = state.bytesDecoded,
            errorCount = state.errorCount,
            bufferUnderrunCount = state.bufferUnderrunCount,
            averagePacketSize = avgPacketSize,
            sampleRate = state.config.sampleRate,
            channelCount = state.config.channelCount
        )
    }

    /**
     * Check if hardware decoding is available
     */
    fun isHardwareDecodingAvailable(): Boolean {
        return try {
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val codecInfo = codec.codecInfo
            val isHardware = !codecInfo.name.lowercase().contains("sw")
            codec.release()
            isHardware
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Estimate decoding latency
     */
    fun getEstimatedLatency(): Long {
        val config = _decoderState.value.config
        // Estimate based on buffer size and processing time
        return (config.maxInputSize * 1000L) / (config.sampleRate * 2) // milliseconds
    }

    /**
     * Get current buffer fill level
     */
    fun getBufferFillLevel(): Float {
        return inputPacketQueue.size / 15.0f // Percentage of queue filled
    }

    /**
     * Flush decoder buffers
     */
    suspend fun flush(): Result<Unit> {
        return try {
            inputPacketMutex.withLock {
                inputPacketQueue.clear()
            }
            
            mediaCodec?.flush()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Handle format change during decoding
     */
    private suspend fun handleFormatChange(newFormat: MediaFormat) {
        try {
            val newSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val newChannelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            
            val newConfig = _decoderState.value.config.copy(
                sampleRate = newSampleRate,
                channelCount = newChannelCount
            )
            
            _decoderState.value = _decoderState.value.copy(config = newConfig)
            
            println("Decoder format changed: ${newSampleRate}Hz, ${newChannelCount} channels")
        } catch (e: Exception) {
            println("Error handling format change: ${e.message}")
        }
    }

    /**
     * Process codec info changes
     */
    private fun handleCodecInfoChange() {
        // Handle codec capabilities or format changes
        println("Decoder codec info changed")
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        decodingScope.cancel()
        runBlocking {
            stopDecoding()
        }
    }

    // Supporting data classes
    
    data class DecodingMetrics(
        val isActive: Boolean,
        val packetsDecoded: Long,
        val bytesDecoded: Long,
        val errorCount: Int,
        val bufferUnderrunCount: Int,
        val averagePacketSize: Long,
        val sampleRate: Int,
        val channelCount: Int
    )

    /**
     * Decoder quality assessment
     */
    data class QualityMetrics(
        val bufferHealth: Float, // 0.0 to 1.0
        val errorRate: Float, // Errors per packet
        val underrunRate: Float, // Underruns per second
        val latency: Long // Estimated latency in ms
    )

    /**
     * Get current quality metrics
     */
    fun getQualityMetrics(): QualityMetrics {
        val state = _decoderState.value
        val bufferHealth = 1.0f - getBufferFillLevel()
        val errorRate = if (state.packetsDecoded > 0) {
            state.errorCount.toFloat() / state.packetsDecoded.toFloat()
        } else {
            0.0f
        }
        val underrunRate = state.bufferUnderrunCount.toFloat() // Simplified calculation
        
        return QualityMetrics(
            bufferHealth = bufferHealth,
            errorRate = errorRate,
            underrunRate = underrunRate,
            latency = getEstimatedLatency()
        )
    }
}