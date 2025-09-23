package io.github.gauravyad69.speakershare.data.model

/**
 * Represents an active audio broadcasting session.
 * Lifecycle: Created when user starts hosting, destroyed when broadcasting stops
 */
data class HostSession(
    val sessionId: String,           // Unique session identifier
    val hostName: String,            // Display name for this host
    val audioSource: AudioSource,    // MICROPHONE or SYSTEM_AUDIO
    val quality: AudioQuality,       // Bitrate and encoding settings
    val isActive: Boolean,           // Currently broadcasting
    val startTime: Long,             // Session start timestamp
    val connectedClients: List<ClientConnection>,
    val networkInfo: NetworkInfo,    // IP, port, discovery info
    val maxClients: Int = 50         // Maximum concurrent clients allowed
)

/**
 * Audio source types available for broadcasting
 */
enum class AudioSource {
    MICROPHONE,
    SYSTEM_AUDIO
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
    MP3 
}
