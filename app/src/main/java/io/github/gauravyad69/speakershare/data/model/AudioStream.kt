package io.github.gauravyad69.speakershare.data.model

/**
 * Represents real-time audio data flow.
 * Lifecycle: Exists during active session, recreated on audio source changes
 */
data class AudioStream(
    val streamId: String,
    val sessionId: String,           // Parent session
    val source: AudioSource,
    val transport: StreamTransport,  // WebRTC or UDP
    val quality: AudioQuality,
    val isActive: Boolean,
    val bufferSize: Int,             // Audio buffer size
    val metrics: StreamMetrics
)

/**
 * Transport methods for audio streaming
 */
enum class StreamTransport {
    WEBRTC,                          // Primary transport
    UDP                              // Fallback transport
}

/**
 * Streaming performance metrics
 */
data class StreamMetrics(
    val bytesTransmitted: Long,
    val packetsLost: Int,
    val averageLatency: Long,        // ms
    val peakLatency: Long,           // ms
    val bufferUnderruns: Int
)
