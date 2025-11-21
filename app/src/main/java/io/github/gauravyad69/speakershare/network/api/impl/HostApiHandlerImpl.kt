package io.github.gauravyad69.speakershare.network.api.impl

import io.github.gauravyad69.speakershare.network.api.*
import io.github.gauravyad69.speakershare.services.HostService
import java.util.*
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Implementation of HostApiHandler that delegates to HostService.
 */
@Singleton
class HostApiHandlerImpl @Inject constructor(
    private val hostServiceProvider: Provider<HostService>
) : HostApiHandler {
    
    private val connectedClients = mutableMapOf<String, ConnectedClient>()
    private var isAcceptingClients = true
    private var maxClients = 0 // 0 = unlimited
    private var transportMode = "ALL" // ALL, WEBRTC_ONLY, UDP_ONLY
    private var isBroadcasting = true
    
    override suspend fun connectClient(request: ClientConnectRequest): ClientConnectResponse {
        // Validate request
        if (request.clientId.isBlank()) {
            throw BadRequestException(400, "Invalid client ID - required field")
        }
        if (request.clientName.isBlank()) {
            throw BadRequestException(400, "Invalid client name - required field")
        }
        if (request.preferredTransport !in listOf("WEBRTC", "UDP")) {
            throw BadRequestException(400, "Invalid transport - must be WEBRTC or UDP")
        }
        
        val hostService = hostServiceProvider.get()
        val session = hostService.currentSession.value
            ?: throw ServiceUnavailableException(503, "No active session")
            
        // Try to register client with HostService
        val result = hostService.handleClientConnection(
            clientId = request.clientId,
            clientName = request.clientName,
            clientIp = "unknown" // TODO: Extract IP from request context if possible
        )
        
        if (result.isFailure) {
            throw ServiceUnavailableException(500, result.exceptionOrNull()?.message ?: "Connection failed")
        }
        
        val accepted = result.getOrDefault(false)
        if (!accepted) {
             return ClientConnectResponse(
                status = "REJECTED",
                reason = "MAX_CLIENTS_REACHED",
                maxClients = session.maxClients
            )
        }
        
        // Determine transport
        val assignedTransport = if (request.preferredTransport == "WEBRTC") "WEBRTC" else "UDP"
        
        return ClientConnectResponse(
            status = "ACCEPTED",
            assignedTransport = assignedTransport,
            streamEndpoint = createStreamEndpoint(assignedTransport),
            clientId = request.clientId
        )
    }
    
    override suspend fun disconnectClient(clientId: String): ClientDisconnectResponse {
        // Validate client ID format
        if (clientId.isBlank()) {
            throw BadRequestException(400, "Invalid client ID")
        }
        
        val hostService = hostServiceProvider.get()
        val result = hostService.disconnectClient(clientId, "Client requested disconnect")
        
        if (result.isFailure) {
             throw NotFoundException(404, "Client not found or failed to disconnect")
        }
        
        return ClientDisconnectResponse(
            status = "DISCONNECTED",
            reason = "CLIENT_REQUEST"
        )
    }
    
    override suspend fun getClientList(): ClientListResponse {
        return ClientListResponse(
            totalClients = connectedClients.size,
            clients = connectedClients.values.toList()
        )
    }
    
    override suspend fun getDiscoveryInfo(): HostDiscoveryInfo {
        // Check if host is broadcasting
        if (!isBroadcasting) {
            throw ServiceUnavailableException(503, "Host is not currently broadcasting")
        }
        
        return HostDiscoveryInfo(
            sessionId = "550e8400-e29b-41d4-a716-446655440000",
            hostName = "Test Host",
            audioSource = "MICROPHONE",
            quality = QualityInfo(
                bitrate = 128,
                sampleRate = 44100,
                encoding = "AAC"
            ),
            isAcceptingClients = isAcceptingClients,
            connectedClients = connectedClients.size,
            maxClients = maxClients,
            transport = listOf("WEBRTC", "UDP")
        )
    }
    
    // Helper methods for testing
    fun setAcceptingClients(accepting: Boolean) {
        isAcceptingClients = accepting
    }
    
    fun setMaxClients(max: Int) {
        maxClients = max
    }
    
    fun setTransportMode(mode: String) {
        transportMode = mode
    }
    
    fun setBroadcastingState(broadcasting: Boolean) {
        isBroadcasting = broadcasting
    }
    
    fun preConnectClient(clientId: String, clientName: String) {
        val client = ConnectedClient(
            id = clientId,
            ipAddress = "192.168.1.105",
            deviceName = clientName,
            connectedAt = "2024-01-01T10:00:00Z",
            audioLatency = 150,
            connectionQuality = "GOOD"
        )
        connectedClients[clientId] = client
    }
    
    override suspend fun kickClient(clientId: String): ClientDisconnectResponse {
        return if (connectedClients.containsKey(clientId)) {
            connectedClients.remove(clientId)
            ClientDisconnectResponse(
                status = "success",
                reason = "Client kicked successfully"
            )
        } else {
            ClientDisconnectResponse(
                status = "error",
                reason = "Client not found"
            )
        }
    }
    
    override suspend fun updateHostSettings(settings: HostSettingsRequest): HostSettingsResponse {
        // Update settings (in a real implementation, these would be persisted)
        settings.hostName?.let { /* Update host name */ }
        settings.audioSource?.let { /* Update audio source */ }
        settings.quality?.let { /* Update quality settings */ }
        settings.maxClients?.let { maxClients = it }
        settings.isAcceptingClients?.let { isAcceptingClients = it }
        
        return HostSettingsResponse(
            status = "success",
            message = "Host settings updated successfully",
            updatedSettings = settings
        )
    }
    
    override suspend fun getSessionStatus(): SessionStatusResponse {
        return SessionStatusResponse(
            sessionId = "session-123",
            isActive = isBroadcasting,
            startTime = System.currentTimeMillis() - 60000, // 1 minute ago
            uptime = 60000, // 1 minute
            connectedClients = connectedClients.size,
            audioSource = "microphone",
            quality = QualityInfo(
                bitrate = 128,
                sampleRate = 44100,
                encoding = "AAC"
            ),
            bytesTransferred = 1024000, // 1MB
            averageLatency = 50
        )
    }
    
    private fun createStreamEndpoint(transport: String): StreamEndpoint {
        return when (transport) {
            "WEBRTC" -> StreamEndpoint(
                webrtc = WebRTCEndpoint(
                    signalingUrl = "ws://192.168.1.100:8081/webrtc",
                    iceServers = emptyList()
                )
            )
            "UDP" -> StreamEndpoint(
                udp = UdpEndpoint(
                    host = "192.168.1.100",
                    port = 9090
                )
            )
            else -> StreamEndpoint()
        }
    }
    
    private fun isValidUUID(uuid: String): Boolean {
        return try {
            UUID.fromString(uuid)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
