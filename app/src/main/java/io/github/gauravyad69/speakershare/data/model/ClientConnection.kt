package io.github.gauravyad69.speakershare.data.model

/**
 * Represents a client's connection to host session.
 * Lifecycle: Created on connection request, destroyed on disconnect
 */
data class ClientConnection(
    val clientId: String,            // Unique client identifier
    val clientName: String,          // Display name
    val ipAddress: String,           // Client IP address
    val connectionTime: Long,        // When connected
    val status: ConnectionStatus,    // Current connection state
    val audioSettings: ClientAudioSettings,
    val networkMetrics: NetworkMetrics
)

/**
 * Connection status states
 */
enum class ConnectionStatus {
    CONNECTING,
    CONNECTED, 
    DISCONNECTED,
    KICKED,
    ERROR
}

/**
 * Client-side audio settings
 */
data class ClientAudioSettings(
    val volume: Float = 1.0f,        // 0.0 - 1.0
    val isMuted: Boolean = false
)

/**
 * Network performance metrics for a client connection
 */
data class NetworkMetrics(
    val latency: Long,               // ms
    val packetLoss: Float,           // percentage
    val bandwidth: Long              // bytes/sec
)
