package io.github.gauravyad69.speakershare.data.repository.impl

import io.github.gauravyad69.speakershare.data.model.AudioQuality
import io.github.gauravyad69.speakershare.data.model.AudioSource
import io.github.gauravyad69.speakershare.data.model.AudioStream
import io.github.gauravyad69.speakershare.data.model.StreamMetrics
import io.github.gauravyad69.speakershare.data.model.StreamState
import io.github.gauravyad69.speakershare.data.model.StreamTransport
import io.github.gauravyad69.speakershare.data.repository.AudioStreamRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory [AudioStreamRepository] implementation.
 */
@Singleton
class AudioStreamRepositoryImpl @Inject constructor() : AudioStreamRepository {

    private val streamState = MutableStateFlow<AudioStream?>(null)
    private val metricsState = MutableStateFlow<StreamMetrics?>(null)

    override fun getCurrentStream(): Flow<AudioStream?> = streamState.asStateFlow()

    override suspend fun createStream(
        sessionId: String,
        source: AudioSource,
        transport: StreamTransport,
        quality: AudioQuality,
        bufferSize: Int
    ): Result<AudioStream> = runCatching {
        val stream = AudioStream(
            streamId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            audioSource = source,
            transport = transport,
            quality = quality,
            state = StreamState.IDLE,
            isActive = false,
            startTime = System.currentTimeMillis(),
            bufferSize = bufferSize,
            metrics = emptyMap()
        )
        streamState.value = stream
        stream
    }

    override suspend fun startStream(): Result<Unit> = runCatching {
        updateStream { it.copy(state = StreamState.ACTIVE, isActive = true) }
    }

    override suspend fun stopStream(): Result<Unit> = runCatching {
        updateStream { it.copy(state = StreamState.STOPPED, isActive = false) }
    }

    override suspend fun updateTransport(transport: StreamTransport): Result<Unit> = runCatching {
        updateStream { it.copy(transport = transport) }
    }

    override suspend fun updateQuality(quality: AudioQuality): Result<Unit> = runCatching {
        updateStream { it.copy(quality = quality) }
    }

    override suspend fun updateMetrics(metrics: StreamMetrics): Result<Unit> = runCatching {
        metricsState.value = metrics
        updateStream {
            it.copy(
                metrics = mapOf(
                    "bytesTransmitted" to metrics.bytesTransmitted,
                    "packetsLost" to metrics.packetsLost,
                    "averageLatency" to metrics.averageLatency,
                    "peakLatency" to metrics.peakLatency,
                    "bufferUnderruns" to metrics.bufferUnderruns
                )
            )
        }
    }

    override suspend fun getStreamState(): AudioStream? = streamState.value

    override suspend fun isStreamActive(): Boolean = streamState.value?.isActive == true

    override suspend fun getStreamMetrics(): StreamMetrics? = metricsState.value

    override suspend fun resetMetrics(): Result<Unit> = runCatching {
        metricsState.value = null
        updateStream { it.copy(metrics = emptyMap()) }
    }

    private fun updateStream(transform: (AudioStream) -> AudioStream) {
        val current = streamState.value ?: error("Stream not initialized")
        streamState.update { transform(current) }
    }
}
