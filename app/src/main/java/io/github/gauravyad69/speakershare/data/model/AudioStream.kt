package io.github.gauravyad69.speakershare.data.model

/**
 * Represents real-time audio data flow.
 * Lifecycle: Exists during active session, recreated on audio source changes
 */
data class AudioStream(
    val streamId: String,
    val sessionId: String,           // Parent session
    val audioSource: AudioSource,    // Updated to match HostSession field name
    val transport: StreamTransport,  // WebRTC or UDP
    val quality: AudioQuality,
    val state: StreamState,          // Added state field
    val isActive: Boolean,
    val startTime: Long,             // Added start time
    val bufferSize: Int,             // Audio buffer size
    val metrics: Map<String, Any>    // Simplified metrics as map
)

/**
 * Stream state enumeration
 */
enum class StreamState {
    IDLE,
    STARTING,
    ACTIVE,
    STOPPING,
    STOPPED,
    ERROR
}

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
