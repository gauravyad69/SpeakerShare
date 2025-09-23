package io.github.gauravyad69.speakershare.network

import io.github.gauravyad69.speakershare.network.api.impl.HostApiHandlerImpl
import io.github.gauravyad69.speakershare.network.webrtc.impl.WebRTCHandlerImpl
import io.github.gauravyad69.speakershare.network.api.*
import io.github.gauravyad69.speakershare.network.webrtc.*

/**
 * Shared test data classes and interfaces for contract tests.
 * Updated to use real implementations for TDD green phase.
 * Using typealiases to bridge test expectations with main source implementations.
 */

// Type aliases for API data classes (from main source)
typealias ClientConnectRequest = io.github.gauravyad69.speakershare.network.api.ClientConnectRequest
typealias ClientConnectResponse = io.github.gauravyad69.speakershare.network.api.ClientConnectResponse
typealias StreamEndpoint = io.github.gauravyad69.speakershare.network.api.StreamEndpoint
typealias WebRTCEndpoint = io.github.gauravyad69.speakershare.network.api.WebRTCEndpoint
typealias UdpEndpoint = io.github.gauravyad69.speakershare.network.api.UdpEndpoint
typealias ClientDisconnectResponse = io.github.gauravyad69.speakershare.network.api.ClientDisconnectResponse
typealias ConnectedClient = io.github.gauravyad69.speakershare.network.api.ConnectedClient
typealias ClientListResponse = io.github.gauravyad69.speakershare.network.api.ClientListResponse
typealias QualityInfo = io.github.gauravyad69.speakershare.network.api.QualityInfo
typealias HostDiscoveryInfo = io.github.gauravyad69.speakershare.network.api.HostDiscoveryInfo

// Type aliases for WebRTC data classes (from main source)
typealias WebRTCOfferRequest = io.github.gauravyad69.speakershare.network.webrtc.WebRTCOfferRequest
typealias WebRTCOfferResponse = io.github.gauravyad69.speakershare.network.webrtc.WebRTCOfferResponse
typealias WebRTCAnswerRequest = io.github.gauravyad69.speakershare.network.webrtc.WebRTCAnswerRequest
typealias WebRTCAnswerResponse = io.github.gauravyad69.speakershare.network.webrtc.WebRTCAnswerResponse
typealias ICECandidateRequest = io.github.gauravyad69.speakershare.network.webrtc.ICECandidateRequest
typealias ICECandidateResponse = io.github.gauravyad69.speakershare.network.webrtc.ICECandidateResponse

// Exception classes - use the ones from main source
typealias BadRequestException = io.github.gauravyad69.speakershare.network.api.BadRequestException
typealias TooManyRequestsException = io.github.gauravyad69.speakershare.network.api.TooManyRequestsException  
typealias ServiceUnavailableException = io.github.gauravyad69.speakershare.network.api.ServiceUnavailableException
typealias NotFoundException = io.github.gauravyad69.speakershare.network.api.NotFoundException

// Factory functions that return working implementations (TDD green phase)
fun createHostApiHandler(): HostApiHandlerImpl {
    val handler = HostApiHandlerImpl()
    // Pre-connect clients for tests that expect them
    handler.preConnectClient("550e8400-e29b-41d4-a716-446655440001", "Test Client")
    handler.preConnectClient("550e8400-e29b-41d4-a716-446655440002", "Test Client 2")
    return handler
}

fun createHostApiHandlerEmpty(): HostApiHandlerImpl {
    // Return a clean handler with no pre-connected clients
    return HostApiHandlerImpl()
}

fun createHostApiHandlerAtCapacity(): HostApiHandlerImpl {
    val handler = HostApiHandlerImpl()
    handler.setMaxClients(1)
    // Pre-populate with one client to reach capacity
    handler.preConnectClient("550e8400-e29b-41d4-a716-446655440000", "Existing Client")
    return handler
}

fun createHostApiHandlerNotAccepting(): HostApiHandlerImpl {
    val handler = HostApiHandlerImpl()
    handler.setAcceptingClients(false)
    return handler
}

fun createHostApiHandlerNotBroadcasting(): HostApiHandlerImpl {
    val handler = HostApiHandlerImpl()
    handler.setBroadcastingState(false)
    return handler
}

fun createHostApiHandlerUdpOnly(): HostApiHandlerImpl {
    val handler = HostApiHandlerImpl()
    handler.setTransportMode("UDP_ONLY")
    return handler
}

fun createWebRTCHandler(): WebRTCHandlerImpl {
    val handler = WebRTCHandlerImpl()
    // Pre-create sessions for answer and ICE candidate tests
    handler.preCreateSession("550e8400-e29b-41d4-a716-446655440002", "OFFER_RECEIVED")
    handler.preCreateSession("550e8400-e29b-41d4-a716-446655440003", "OFFER_RECEIVED")
    return handler
}

// Type aliases to bridge test expectations with implementations
typealias HostApiHandler = HostApiHandlerImpl
typealias WebRTCHandler = WebRTCHandlerImpl

// Helper functions for validation
fun isValidUUID(uuid: String): Boolean = uuid.matches(Regex("[0-9a-fA-F-]{36}"))
fun isValidIPAddress(ip: String): Boolean = ip.matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))
fun isValidSDP(sdp: String): Boolean = sdp.contains("v=0") && sdp.contains("o=")
fun isValidICECandidate(candidate: String): Boolean = candidate.contains("candidate:")
