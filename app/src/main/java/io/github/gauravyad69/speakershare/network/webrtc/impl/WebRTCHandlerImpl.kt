package io.github.gauravyad69.speakershare.network.webrtc.impl

import io.github.gauravyad69.speakershare.network.webrtc.*
import io.github.gauravyad69.speakershare.network.api.BadRequestException

/**
 * Basic stub implementation of WebRTCHandler for TDD green phase.
 * Provides realistic WebRTC signaling responses to make tests pass.
 */
class WebRTCHandlerImpl : WebRTCHandler {
    
    private val activeSessions = mutableMapOf<String, String>() // clientId -> sessionState
    
    override suspend fun processOffer(request: WebRTCOfferRequest): WebRTCOfferResponse {
        // Validate request
        if (request.clientId.isBlank()) {
            throw BadRequestException(400, "Invalid client ID - required field")
        }
        if (request.offer.isBlank() || !isValidSDP(request.offer)) {
            throw BadRequestException(400, "Invalid SDP offer format")
        }
        
        // Create session
        activeSessions[request.clientId] = "OFFER_RECEIVED"
        
        // Generate mock answer SDP
        val mockAnswer = "v=0\r\no=- 987654321 2 IN IP4 192.168.1.100\r\n" +
                "s=-\r\nt=0 0\r\n" +
                "m=audio 9090 RTP/AVP 111\r\n" +
                "a=rtpmap:111 opus/48000/2\r\n"
        
        return WebRTCOfferResponse(
            clientId = request.clientId,
            answer = mockAnswer,
            sessionState = "OFFER_RECEIVED"
        )
    }
    
    override suspend fun processAnswer(request: WebRTCAnswerRequest): WebRTCAnswerResponse {
        // Validate request
        if (request.clientId.isBlank()) {
            throw BadRequestException(400, "Invalid client ID - required field")
        }
        if (request.answer.isBlank() || !isValidSDP(request.answer)) {
            throw BadRequestException(400, "Invalid SDP answer format")
        }
        
        // Check if session exists
        if (!activeSessions.containsKey(request.clientId)) {
            throw BadRequestException(400, "No active session for client")
        }
        
        // Update session state
        activeSessions[request.clientId] = "ESTABLISHED"
        
        return WebRTCAnswerResponse(
            clientId = request.clientId,
            sessionState = "ESTABLISHED",
            connectionReady = true
        )
    }
    
    override suspend fun addICECandidate(request: ICECandidateRequest): ICECandidateResponse {
        // Validate request
        if (request.clientId.isBlank()) {
            throw BadRequestException(400, "Invalid client ID - required field")
        }
        if (request.candidate.isBlank() || !isValidICECandidate(request.candidate)) {
            throw BadRequestException(400, "Invalid ICE candidate format")
        }
        
        // Check if session exists
        if (!activeSessions.containsKey(request.clientId)) {
            throw BadRequestException(400, "No active session for client")
        }
        
        // Mock adding the candidate (always succeeds in stub)
        return ICECandidateResponse(
            clientId = request.clientId,
            candidateAdded = true
        )
    }
    
    // Helper validation methods
    private fun isValidSDP(sdp: String): Boolean {
        return sdp.contains("v=0") && sdp.contains("o=")
    }
    
    private fun isValidICECandidate(candidate: String): Boolean {
        return candidate.contains("candidate:")
    }
    
    // Helper method for testing
    fun preCreateSession(clientId: String, sessionState: String) {
        activeSessions[clientId] = sessionState
    }
}
