package io.github.gauravyad69.speakershare.network.webrtc

/**
 * WebRTC Handler for signaling and connection management.
 * Handles WebRTC offer/answer exchange and ICE candidates.
 */
interface WebRTCHandler {
    
    /**
     * Process WebRTC offer from client
     * POST /webrtc/offer
     */
    suspend fun processOffer(request: WebRTCOfferRequest): WebRTCOfferResponse
    
    /**
     * Process WebRTC answer from client
     * POST /webrtc/answer
     */
    suspend fun processAnswer(request: WebRTCAnswerRequest): WebRTCAnswerResponse
    
    /**
     * Add ICE candidate to WebRTC session
     * POST /webrtc/ice-candidate
     */
    suspend fun addICECandidate(request: ICECandidateRequest): ICECandidateResponse
}

// WebRTC signaling data classes
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
