package io.github.gauravyad69.speakershare.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AudioBufferManager optimizes audio streaming latency and provides adaptive buffering.
 * 
 * Features:
 * - Adaptive buffer sizing based on network conditions
 * - Latency optimization algorithms
 * - Buffer overflow/underrun prevention
 * - Real-time buffer health monitoring
 * - Automatic buffer adjustment
 * - Jitter compensation
 */
@Singleton
class AudioBufferManager @Inject constructor() {

    // Buffer configuration
    data class BufferConfig(
        val minBufferSizeMs: Int = 50,    // Minimum buffer size in milliseconds
        val maxBufferSizeMs: Int = 500,   // Maximum buffer size in milliseconds
        val targetBufferSizeMs: Int = 150, // Target buffer size in milliseconds
        val adaptationRate: Float = 0.1f,  // How quickly to adapt (0.0 - 1.0)
        val sampleRate: Int = 44100,
        val channels: Int = 1,
        val bytesPerSample: Int = 2        // 16-bit PCM
    )

    // Buffer health metrics
    data class BufferHealth(
        val currentSizeMs: Int = 0,
        val targetSizeMs: Int = 150,
        val fillPercentage: Float = 0.0f,
        val underrunCount: Int = 0,
        val overrunCount: Int = 0,
        val jitterMs: Float = 0.0f,
        val latencyMs: Int = 0,
        val isHealthy: Boolean = true
    )

    // Audio buffer item
    data class AudioBufferItem(
        val data: ByteArray,
        val timestamp: Long,
        val sequenceNumber: Long,
        val size: Int = data.size
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AudioBufferItem
            return data.contentEquals(other.data) && 
                   timestamp == other.timestamp &&
                   sequenceNumber == other.sequenceNumber
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + sequenceNumber.hashCode()
            return result
        }
    }

    // Buffer state
    data class BufferState(
        val isActive: Boolean = false,
        val config: BufferConfig = BufferConfig(),
        val health: BufferHealth = BufferHealth(),
        val itemsBuffered: Long = 0,
        val bytesBuffered: Long = 0,
        val lastAdaptationTime: Long = 0L
    )

    // State management
    private val _bufferState = MutableStateFlow(BufferState())
    val bufferState: StateFlow<BufferState> = _bufferState.asStateFlow()

    // Buffer output stream
    private val _bufferedAudioFlow = MutableSharedFlow<AudioBufferItem>(
        replay = 0,
        extraBufferCapacity = 5,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val bufferedAudioFlow: SharedFlow<AudioBufferItem> = _bufferedAudioFlow.asSharedFlow()

    // Internal buffer storage
    private val audioBuffer = ConcurrentLinkedQueue<AudioBufferItem>()
    private val bufferMutex = Mutex()
    private var bufferJob: Job? = null
    private val bufferScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Timing and sequence tracking
    private var nextSequenceNumber = 0L
    private var lastOutputTime = 0L
    private val jitterHistory = ArrayDeque<Float>()
    private val maxJitterHistorySize = 10

    /**
     * Start the buffer manager with specified configuration
     */
    suspend fun startBuffering(config: BufferConfig = BufferConfig()): Result<Unit> {
        return try {
            if (_bufferState.value.isActive) {
                stopBuffering()
            }

            bufferJob = bufferScope.launch {
                bufferingLoop()
            }

            _bufferState.value = _bufferState.value.copy(
                isActive = true,
                config = config,
                itemsBuffered = 0,
                bytesBuffered = 0,
                lastAdaptationTime = System.currentTimeMillis()
            )

            Result.success(Unit)
        } catch (e: Exception) {
            stopBuffering()
            Result.failure(e)
        }
    }

    /**
     * Stop the buffer manager
     */
    suspend fun stopBuffering(): Result<Unit> {
        return try {
            bufferJob?.cancel()
            bufferJob = null

            bufferMutex.withLock {
                audioBuffer.clear()
            }

            jitterHistory.clear()
            nextSequenceNumber = 0L
            lastOutputTime = 0L

            _bufferState.value = _bufferState.value.copy(isActive = false)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Add audio data to buffer
     */
    suspend fun bufferAudio(audioData: ByteArray, timestamp: Long = System.currentTimeMillis()): Result<Unit> {
        return try {
            if (!_bufferState.value.isActive) {
                return Result.failure(IllegalStateException("Buffer manager not started"))
            }

            val bufferItem = AudioBufferItem(
                data = audioData.clone(),
                timestamp = timestamp,
                sequenceNumber = nextSequenceNumber++
            )

            bufferMutex.withLock {
                audioBuffer.offer(bufferItem)
                
                // Check for buffer overflow
                val config = _bufferState.value.config
                val maxItems = calculateMaxBufferItems(config)
                
                while (audioBuffer.size > maxItems) {
                    audioBuffer.poll()
                    incrementOverrunCount()
                }
            }

            updateBufferStats()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Main buffering loop - manages buffer output timing
     */
    private suspend fun bufferingLoop() {
        val config = _bufferState.value.config
        val outputIntervalMs = calculateOutputInterval(config)
        
        while (currentCoroutineContext().isActive) {
            try {
                val currentTime = System.currentTimeMillis()
                
                // Check if it's time to output audio
                if (lastOutputTime == 0L || (currentTime - lastOutputTime) >= outputIntervalMs) {
                    outputBufferedAudio()
                    lastOutputTime = currentTime
                }
                
                // Perform buffer health checks and adaptation
                if (shouldAdaptBuffer()) {
                    adaptBufferSize()
                }
                
                updateBufferHealth()
                
                // Sleep for a short interval to prevent busy waiting
                delay(5)
                
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    println("Buffer loop error: ${e.message}")
                    delay(10)
                }
            }
        }
    }

    /**
     * Output buffered audio if available
     */
    private suspend fun outputBufferedAudio() {
        val config = _bufferState.value.config
        val minBufferItems = calculateMinBufferItems(config)
        
        bufferMutex.withLock {
            if (audioBuffer.size >= minBufferItems) {
                val item = audioBuffer.poll()
                if (item != null) {
                    _bufferedAudioFlow.tryEmit(item)
                    updateJitter(item)
                }
            } else if (audioBuffer.isEmpty()) {
                incrementUnderrunCount()
            }
        }
    }

    /**
     * Update jitter measurements
     */
    private fun updateJitter(item: AudioBufferItem) {
        val expectedTime = item.timestamp
        val actualTime = System.currentTimeMillis()
        val jitter = kotlin.math.abs(actualTime - expectedTime).toFloat()
        
        jitterHistory.offer(jitter)
        if (jitterHistory.size > maxJitterHistorySize) {
            jitterHistory.poll()
        }
    }

    /**
     * Calculate average jitter
     */
    private fun calculateAverageJitter(): Float {
        return if (jitterHistory.isNotEmpty()) {
            jitterHistory.average().toFloat()
        } else {
            0.0f
        }
    }

    /**
     * Determine if buffer adaptation is needed
     */
    private fun shouldAdaptBuffer(): Boolean {
        val state = _bufferState.value
        val currentTime = System.currentTimeMillis()
        val timeSinceLastAdaptation = currentTime - state.lastAdaptationTime
        
        return timeSinceLastAdaptation >= 1000 // Adapt at most once per second
    }

    /**
     * Adapt buffer size based on current conditions
     */
    private suspend fun adaptBufferSize() {
        val config = _bufferState.value.config
        val health = _bufferState.value.health
        
        val newTargetSize = when {
            health.underrunCount > 5 -> {
                // Increase buffer size to prevent underruns
                (config.targetBufferSizeMs * 1.2f).toInt().coerceAtMost(config.maxBufferSizeMs)
            }
            health.overrunCount > 5 -> {
                // Decrease buffer size to reduce latency
                (config.targetBufferSizeMs * 0.8f).toInt().coerceAtLeast(config.minBufferSizeMs)
            }
            health.jitterMs > 50f -> {
                // Increase buffer size to handle jitter
                (config.targetBufferSizeMs * 1.1f).toInt().coerceAtMost(config.maxBufferSizeMs)
            }
            else -> config.targetBufferSizeMs
        }
        
        val newConfig = config.copy(targetBufferSizeMs = newTargetSize)
        
        _bufferState.value = _bufferState.value.copy(
            config = newConfig,
            lastAdaptationTime = System.currentTimeMillis()
        )
        
        println("Buffer adapted: target size ${newTargetSize}ms")
    }

    /**
     * Update buffer health metrics
     */
    private suspend fun updateBufferHealth() {
        val config = _bufferState.value.config
        val currentState = _bufferState.value
        
        bufferMutex.withLock {
            val currentSizeMs = calculateCurrentBufferSizeMs(config)
            val fillPercentage = currentSizeMs.toFloat() / config.targetBufferSizeMs.toFloat()
            val avgJitter = calculateAverageJitter()
            val latencyMs = currentSizeMs + avgJitter.toInt()
            
            val isHealthy = fillPercentage in 0.3f..0.8f && 
                           avgJitter < 100f &&
                           currentState.health.underrunCount < 10 &&
                           currentState.health.overrunCount < 10
            
            val newHealth = BufferHealth(
                currentSizeMs = currentSizeMs,
                targetSizeMs = config.targetBufferSizeMs,
                fillPercentage = fillPercentage,
                underrunCount = currentState.health.underrunCount,
                overrunCount = currentState.health.overrunCount,
                jitterMs = avgJitter,
                latencyMs = latencyMs,
                isHealthy = isHealthy
            )
            
            _bufferState.value = currentState.copy(health = newHealth)
        }
    }

    /**
     * Update buffer statistics
     */
    private suspend fun updateBufferStats() {
        bufferMutex.withLock {
            val totalBytes = audioBuffer.sumOf { it.size.toLong() }
            
            _bufferState.value = _bufferState.value.copy(
                itemsBuffered = audioBuffer.size.toLong(),
                bytesBuffered = totalBytes
            )
        }
    }

    /**
     * Calculate current buffer size in milliseconds
     */
    private fun calculateCurrentBufferSizeMs(config: BufferConfig): Int {
        val bytesPerMs = (config.sampleRate * config.channels * config.bytesPerSample) / 1000
        val currentBytes = audioBuffer.sumOf { it.size }
        return if (bytesPerMs > 0) currentBytes / bytesPerMs else 0
    }

    /**
     * Calculate maximum buffer items based on config
     */
    private fun calculateMaxBufferItems(config: BufferConfig): Int {
        val bytesPerMs = (config.sampleRate * config.channels * config.bytesPerSample) / 1000
        val maxBytes = config.maxBufferSizeMs * bytesPerMs
        return maxBytes / 1024 // Assuming ~1KB per item
    }

    /**
     * Calculate minimum buffer items for stable playback
     */
    private fun calculateMinBufferItems(config: BufferConfig): Int {
        val bytesPerMs = (config.sampleRate * config.channels * config.bytesPerSample) / 1000
        val minBytes = config.minBufferSizeMs * bytesPerMs
        return minBytes / 1024 // Assuming ~1KB per item
    }

    /**
     * Calculate output interval for buffering loop
     */
    private fun calculateOutputInterval(config: BufferConfig): Long {
        // Output every 20ms for smooth playback
        return 20L
    }

    /**
     * Increment underrun count
     */
    private fun incrementUnderrunCount() {
        val currentHealth = _bufferState.value.health
        _bufferState.value = _bufferState.value.copy(
            health = currentHealth.copy(underrunCount = currentHealth.underrunCount + 1)
        )
    }

    /**
     * Increment overrun count
     */
    private fun incrementOverrunCount() {
        val currentHealth = _bufferState.value.health
        _bufferState.value = _bufferState.value.copy(
            health = currentHealth.copy(overrunCount = currentHealth.overrunCount + 1)
        )
    }

    /**
     * Get buffer performance metrics
     */
    fun getBufferMetrics(): BufferMetrics {
        val state = _bufferState.value
        return BufferMetrics(
            isActive = state.isActive,
            currentSizeMs = state.health.currentSizeMs,
            targetSizeMs = state.config.targetBufferSizeMs,
            fillPercentage = state.health.fillPercentage,
            itemsBuffered = state.itemsBuffered,
            bytesBuffered = state.bytesBuffered,
            underrunCount = state.health.underrunCount,
            overrunCount = state.health.overrunCount,
            jitterMs = state.health.jitterMs,
            latencyMs = state.health.latencyMs,
            isHealthy = state.health.isHealthy
        )
    }

    /**
     * Reset buffer statistics
     */
    suspend fun resetStats(): Result<Unit> {
        return try {
            val currentState = _bufferState.value
            val resetHealth = currentState.health.copy(
                underrunCount = 0,
                overrunCount = 0
            )
            
            _bufferState.value = currentState.copy(
                health = resetHealth,
                itemsBuffered = 0,
                bytesBuffered = 0
            )
            
            jitterHistory.clear()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Flush buffer contents
     */
    suspend fun flush(): Result<Unit> {
        return try {
            bufferMutex.withLock {
                audioBuffer.clear()
            }
            
            updateBufferStats()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get optimal buffer configuration based on device capabilities
     */
    fun getOptimalConfig(deviceLatency: Long = 100L): BufferConfig {
        return BufferConfig(
            minBufferSizeMs = (deviceLatency / 2).toInt().coerceAtLeast(50),
            maxBufferSizeMs = (deviceLatency * 5).toInt().coerceAtMost(500),
            targetBufferSizeMs = deviceLatency.toInt().coerceIn(100, 200)
        )
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        bufferScope.cancel()
        runBlocking {
            stopBuffering()
        }
    }

    // Supporting data classes
    
    data class BufferMetrics(
        val isActive: Boolean,
        val currentSizeMs: Int,
        val targetSizeMs: Int,
        val fillPercentage: Float,
        val itemsBuffered: Long,
        val bytesBuffered: Long,
        val underrunCount: Int,
        val overrunCount: Int,
        val jitterMs: Float,
        val latencyMs: Int,
        val isHealthy: Boolean
    )

    /**
     * Buffer quality assessment
     */
    data class BufferQuality(
        val stability: Float,    // 0.0 to 1.0
        val efficiency: Float,   // 0.0 to 1.0  
        val latency: Float,      // 0.0 to 1.0 (lower is better)
        val overall: Float       // 0.0 to 1.0
    )

    /**
     * Assess current buffer quality
     */
    fun assessBufferQuality(): BufferQuality {
        val metrics = getBufferMetrics()
        
        val stability = if (metrics.underrunCount + metrics.overrunCount == 0) 1.0f 
                       else 1.0f / (1.0f + (metrics.underrunCount + metrics.overrunCount) * 0.1f)
        
        val efficiency = metrics.fillPercentage.coerceIn(0.0f, 1.0f)
        
        val latency = 1.0f - (metrics.latencyMs / 500.0f).coerceIn(0.0f, 1.0f)
        
        val overall = (stability + efficiency + latency) / 3.0f
        
        return BufferQuality(
            stability = stability,
            efficiency = efficiency,
            latency = latency,
            overall = overall
        )
    }
}