package io.github.gauravyad69.speakershare.data.model

/**
 * Represents an active audio broadcasting session.
 * Lifecycle: Created when user starts hosting, destroyed when broadcasting stops
 */
data class HostSession(
    val sessionId: String,           // Unique session identifier
    val sessionName: String,         // User-friendly session name
    val hostName: String,            // Display name for this host
    val audioSource: AudioSource,    // MICROPHONE or SYSTEM_AUDIO
    val quality: AudioQuality,       // Bitrate and encoding settings
    val isActive: Boolean,           // Currently broadcasting
    val startTime: Long,             // Session start timestamp
    val connectedClients: List<ClientConnection>,
    val networkInfo: NetworkInfo,    // IP, port, discovery info
    val maxClients: Int = 50,        // Maximum concurrent clients allowed
    val requiresPassword: Boolean = false, // Whether session requires password
    val password: String? = null     // Session password (if required)
)

/**
 * Audio source types available for broadcasting
 */
enum class AudioSource {
    MICROPHONE,
    SYSTEM_AUDIO,
    SCREEN_AND_AUDIO  // Combined screen mirroring with system audio
}

/**
 * Audio quality settings for the stream
 */
data class AudioQuality(
    val bitrate: Int = 128,          // kbps, tunable 64-320
    val sampleRate: Int = 44100,     // Hz
    val encoding: AudioEncoding = AudioEncoding.AAC
)

/**
 * Supported audio encoding formats
 */
enum class AudioEncoding { 
    AAC, 
    MP3,
    PCM  // Raw PCM - no encoding/decoding latency but high bandwidth
}

/**
 * Latency profiles for audio streaming.
 * Controls buffer sizes, encoding settings, and trade-offs between latency and stability.
 */
enum class LatencyProfile {
    /**
     * Ultra low latency - No buffering, raw PCM streaming
     * WARNING: May cause audio glitches, requires excellent network
     * Latency: ~20-50ms
     * Bandwidth: ~1.4 Mbps for 44.1kHz mono
     */
    NO_LATENCY,
    
    /**
     * Low latency - Minimal buffering with AAC encoding
     * Good for real-time scenarios with decent network
     * Latency: ~50-100ms
     */
    LOW_LATENCY,
    
    /**
     * Balanced - Default mode with moderate buffering
     * Good trade-off between latency and stability
     * Latency: ~100-200ms
     */
    BALANCED,
    
    /**
     * High quality - Larger buffers for maximum stability
     * Best for poor network conditions or high quality priority
     * Latency: ~200-500ms
     */
    HIGH_QUALITY
}

/**
 * Configuration derived from latency profile
 */
data class LatencyConfig(
    val profile: LatencyProfile,
    val encoding: AudioEncoding,
    val sampleRate: Int,
    val bitrate: Int,
    val captureBufferCapacity: Int,      // AudioCaptureService buffer
    val encoderBufferCapacity: Int,      // AudioEncoder buffer
    val decoderBufferCapacity: Int,      // AudioDecoder buffer
    val playbackBufferMultiplier: Int,   // AudioPlaybackService buffer multiplier
    val playbackQueueMax: Int,           // Max queue size before dropping
    val codecTimeoutUs: Long             // Timeout for codec operations
) {
    companion object {
        fun fromProfile(profile: LatencyProfile): LatencyConfig {
            return when (profile) {
                LatencyProfile.NO_LATENCY -> LatencyConfig(
                    profile = profile,
                    encoding = AudioEncoding.PCM,  // No codec = no codec latency
                    sampleRate = 22050,            // Lower sample rate = less data
                    bitrate = 0,                   // N/A for PCM
                    captureBufferCapacity = 1,     // Minimal buffering
                    encoderBufferCapacity = 1,     // Minimal buffering
                    decoderBufferCapacity = 1,     // Minimal buffering
                    playbackBufferMultiplier = 1,  // Minimum hardware buffer
                    playbackQueueMax = 2,          // Almost no queue
                    codecTimeoutUs = 1000L         // 1ms timeout
                )
                LatencyProfile.LOW_LATENCY -> LatencyConfig(
                    profile = profile,
                    encoding = AudioEncoding.AAC,
                    sampleRate = 44100,
                    bitrate = 96000,               // 96 kbps - lower bitrate = faster encoding
                    captureBufferCapacity = 3,
                    encoderBufferCapacity = 3,
                    decoderBufferCapacity = 3,
                    playbackBufferMultiplier = 2,
                    playbackQueueMax = 4,
                    codecTimeoutUs = 5000L         // 5ms timeout
                )
                LatencyProfile.BALANCED -> LatencyConfig(
                    profile = profile,
                    encoding = AudioEncoding.AAC,
                    sampleRate = 44100,
                    bitrate = 128000,              // 128 kbps - good quality
                    captureBufferCapacity = 5,
                    encoderBufferCapacity = 5,
                    decoderBufferCapacity = 5,
                    playbackBufferMultiplier = 2,
                    playbackQueueMax = 8,
                    codecTimeoutUs = 10000L        // 10ms timeout
                )
                LatencyProfile.HIGH_QUALITY -> LatencyConfig(
                    profile = profile,
                    encoding = AudioEncoding.AAC,
                    sampleRate = 48000,            // Higher sample rate
                    bitrate = 192000,              // 192 kbps - high quality
                    captureBufferCapacity = 10,
                    encoderBufferCapacity = 10,
                    decoderBufferCapacity = 10,
                    playbackBufferMultiplier = 4,
                    playbackQueueMax = 20,
                    codecTimeoutUs = 20000L        // 20ms timeout
                )
            }
        }
    }
}

