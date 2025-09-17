package io.github.gauravyad69.speakershare.network

/**
 * Shared test data classes and interfaces for contract tests.
 * These are placeholder implementations that will FAIL (TDD approach).
 */

// Request/Response data classes
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
    val maxClients: Int = 0
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

data class WebRTCOfferRequest(
    val clientId: String,
    val offer: String
)

data class WebRTCOfferResponse(
    val clientId: String,
    val answer: String,
    val sessionState: String
)

data class WebRTCAnswerRequest(
    val clientId: String,
    val answer: String
)

data class WebRTCAnswerResponse(
    val clientId: String,
    val sessionState: String,
    val connectionReady: Boolean = false
)

data class ICECandidateRequest(
    val clientId: String,
    val candidate: String,
    val sdpMid: String,
    val sdpMLineIndex: Int
)

data class ICECandidateResponse(
    val clientId: String,
    val candidateAdded: Boolean
)

// Exception classes
class BadRequestException(val statusCode: Int, message: String) : Exception(message)
class TooManyRequestsException(val statusCode: Int, message: String) : Exception(message) 
class ServiceUnavailableException(val statusCode: Int, message: String) : Exception(message)
class NotFoundException(val statusCode: Int, message: String) : Exception(message)

// Handler classes that will FAIL (TDD approach)
class HostApiHandler {
    suspend fun connectClient(request: ClientConnectRequest): ClientConnectResponse {
        throw NotImplementedError("HostApiHandler.connectClient not implemented - this test should FAIL")
    }
    
    suspend fun disconnectClient(clientId: String): ClientDisconnectResponse {
        throw NotImplementedError("HostApiHandler.disconnectClient not implemented - this test should FAIL")
    }
    
    suspend fun getClientList(): ClientListResponse {
        throw NotImplementedError("HostApiHandler.getClientList not implemented - this test should FAIL")
    }
    
    suspend fun getDiscoveryInfo(): HostDiscoveryInfo {
        throw NotImplementedError("HostApiHandler.getDiscoveryInfo not implemented - this test should FAIL")
    }
}

class WebRTCHandler {
    suspend fun processOffer(request: WebRTCOfferRequest): WebRTCOfferResponse {
        throw NotImplementedError("WebRTCHandler.processOffer not implemented - this test should FAIL")
    }
    
    suspend fun processAnswer(request: WebRTCAnswerRequest): WebRTCAnswerResponse {
        throw NotImplementedError("WebRTCHandler.processAnswer not implemented - this test should FAIL")
    }
    
    suspend fun addICECandidate(request: ICECandidateRequest): ICECandidateResponse {
        throw NotImplementedError("WebRTCHandler.addICECandidate not implemented - this test should FAIL")
    }
}

// Factory functions that will FAIL (TDD approach)
fun createHostApiHandler(): HostApiHandler = HostApiHandler()
fun createHostApiHandlerAtCapacity(): HostApiHandler = HostApiHandler()
fun createHostApiHandlerNotAccepting(): HostApiHandler = HostApiHandler()
fun createHostApiHandlerUdpOnly(): HostApiHandler = HostApiHandler()
fun createWebRTCHandler(): WebRTCHandler = WebRTCHandler()

// Helper functions for validation
fun isValidUUID(uuid: String): Boolean = uuid.matches(Regex("[0-9a-fA-F-]{36}"))
fun isValidIPAddress(ip: String): Boolean = ip.matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))
fun isValidSDP(sdp: String): Boolean = sdp.contains("v=0") && sdp.contains("o=")
fun isValidICECandidate(candidate: String): Boolean = candidate.contains("candidate:")
