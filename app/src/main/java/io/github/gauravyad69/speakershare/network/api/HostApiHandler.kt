package io.github.gauravyad69.speakershare.network.api

import io.github.gauravyad69.speakershare.data.model.*

/**
 * Host API Handler for HTTP REST endpoints.
 * Handles client connection requests and host discovery.
 */
interface HostApiHandler {
    
    /**
     * Handle client connection request
     * POST /clients/connect
     */
    suspend fun connectClient(request: ClientConnectRequest): ClientConnectResponse
    
    /**
     * Handle client disconnection
     * POST /clients/{clientId}/disconnect
     */
    suspend fun disconnectClient(clientId: String): ClientDisconnectResponse
    
    /**
     * Kick a client
     * POST /clients/{clientId}/kick
     */
    suspend fun kickClient(clientId: String): ClientDisconnectResponse
    
    /**
     * Get list of connected clients
     * GET /clients/list
     */
    suspend fun getClientList(): ClientListResponse
    
    /**
     * Get host discovery information
     * GET /discovery/info
     */
    suspend fun getDiscoveryInfo(): HostDiscoveryInfo
    
    /**
     * Update host settings
     * PUT /host/settings
     */
    suspend fun updateHostSettings(settings: HostSettingsRequest): HostSettingsResponse
    
    /**
     * Get session status
     * GET /session/status
     */
    suspend fun getSessionStatus(): SessionStatusResponse
}

// Data classes for API requests/responses
data class ClientConnectRequest(
    val clientId: String,
    val clientName: String,
    val preferredTransport: String,
    val capabilities: List<String>
)

data class ClientConnectResponse(
    val status: String,
    val assignedTransport: String? = null,
    val streamEndpoint: StreamEndpoint? = null,
    val clientId: String? = null,
    val reason: String? = null,
    val maxClients: Int = 0,
    val sampleRate: Int = 44100
)

data class StreamEndpoint(
    val webrtc: WebRTCEndpoint? = null,
    val udp: UdpEndpoint? = null
)

data class WebRTCEndpoint(
    val signalingUrl: String,
    val iceServers: List<String>
)

data class UdpEndpoint(
    val host: String,
    val port: Int
)

data class ClientDisconnectResponse(
    val status: String,
    val reason: String
)

data class ConnectedClient(
    val id: String,
    val ipAddress: String,
    val deviceName: String,
    val connectedAt: String,
    val audioLatency: Int,
    val connectionQuality: String
)

data class ClientListResponse(
    val totalClients: Int,
    val clients: List<ConnectedClient>
)

data class QualityInfo(
    val bitrate: Int,
    val sampleRate: Int,
    val encoding: String
)

data class HostDiscoveryInfo(
    val sessionId: String,
    val hostName: String,
    val audioSource: String,
    val quality: QualityInfo,
    val isAcceptingClients: Boolean,
    val connectedClients: Int,
    val maxClients: Int,
    val transport: List<String>
)

data class HostSettingsRequest(
    val hostName: String? = null,
    val audioSource: String? = null,
    val quality: QualityInfo? = null,
    val maxClients: Int? = null,
    val isAcceptingClients: Boolean? = null
)

data class HostSettingsResponse(
    val status: String,
    val message: String? = null,
    val updatedSettings: HostSettingsRequest? = null
)

data class SessionStatusResponse(
    val sessionId: String,
    val isActive: Boolean,
    val startTime: Long,
    val uptime: Long,
    val connectedClients: Int,
    val audioSource: String,
    val quality: QualityInfo,
    val bytesTransferred: Long,
    val averageLatency: Int
)

// Exception classes
class BadRequestException(val statusCode: Int, message: String) : Exception(message)
class TooManyRequestsException(val statusCode: Int, message: String) : Exception(message)
class ServiceUnavailableException(val statusCode: Int, message: String) : Exception(message)
class NotFoundException(val statusCode: Int, message: String) : Exception(message)
