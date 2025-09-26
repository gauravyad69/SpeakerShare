package io.github.gauravyad69.speakershare.network

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.gauravyad69.speakershare.data.model.ClientConnection
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket-based signaling server for WebRTC connections
 * Handles offer/answer exchange and ICE candidate relay
 */
@Singleton
class SignalingServer @Inject constructor() {
    companion object {
        private const val TAG = "SignalingServer"
        private const val DEFAULT_PORT = 8081
        private const val WEBSOCKET_PATH = "/signaling"
    }

    private var server: NettyApplicationEngine? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    
    // Connected clients
    private val connectedClients = ConcurrentHashMap<String, WebSocketSession>()
    
    // Events
    private val _signalingEvents = MutableSharedFlow<SignalingEvent>()
    val signalingEvents: SharedFlow<SignalingEvent> = _signalingEvents.asSharedFlow()
    
    /**
     * Start the signaling server
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun startServer(port: Int = DEFAULT_PORT): Boolean {
        return try {
            server = embeddedServer(Netty, port = port) {
                install(WebSockets) {
                    pingPeriod = Duration.ofSeconds(15)
                    timeout = Duration.ofSeconds(15)
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }
                
                routing {
                    webSocket(WEBSOCKET_PATH) {
                        handleWebSocketConnection(this)
                    }
                }
            }
            
            server?.start(wait = false)
            Log.i(TAG, "Signaling server started on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start signaling server", e)
            false
        }
    }
    
    /**
     * Stop the signaling server
     */
    fun stopServer() {
        scope.launch {
            try {
                // Close all client connections
                connectedClients.values.forEach { session ->
                    session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutdown"))
                }
                connectedClients.clear()
                
                // Stop server
                server?.stop(1000, 2000)
                server = null
                
                Log.i(TAG, "Signaling server stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping signaling server", e)
            }
        }
    }
    
    /**
     * Handle WebSocket connection
     */
    private suspend fun handleWebSocketConnection(session: WebSocketSession) {
        var clientId: String? = null
        
        try {
            // Handle incoming messages
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val message = frame.readText()
                        Log.d(TAG, "Received message: $message")
                        
                        try {
                            val signalingMessage = json.decodeFromString<SignalingMessage>(message)
                            
                            // Register client on first message
                            if (clientId == null && signalingMessage.type == SignalingMessageType.REGISTER) {
                                clientId = signalingMessage.clientId
                                connectedClients[clientId] = session
                                
                                scope.launch {
                                    _signalingEvents.emit(SignalingEvent.ClientConnected(clientId))
                                }
                                
                                // Send registration confirmation
                                sendMessage(session, SignalingMessage(
                                    type = SignalingMessageType.REGISTER_ACK,
                                    clientId = clientId
                                ))
                                
                                Log.d(TAG, "Client registered: $clientId")
                            } else {
                                handleSignalingMessage(signalingMessage)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse signaling message", e)
                        }
                    }
                    is Frame.Close -> {
                        Log.d(TAG, "WebSocket connection closed")
                        break
                    }
                    else -> {
                        // Ignore other frame types
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket connection error", e)
        } finally {
            // Clean up client connection
            clientId?.let { id ->
                connectedClients.remove(id)
                scope.launch {
                    _signalingEvents.emit(SignalingEvent.ClientDisconnected(id))
                }
                Log.d(TAG, "Client disconnected: $id")
            }
        }
    }
    
    /**
     * Handle different types of signaling messages
     */
    private suspend fun handleSignalingMessage(message: SignalingMessage) {
        when (message.type) {
            SignalingMessageType.OFFER -> {
                scope.launch {
                    _signalingEvents.emit(SignalingEvent.OfferReceived(message.clientId, message.sdp!!))
                }
            }
            
            SignalingMessageType.ANSWER -> {
                scope.launch {
                    _signalingEvents.emit(SignalingEvent.AnswerReceived(message.clientId, message.sdp!!))
                }
            }
            
            SignalingMessageType.ICE_CANDIDATE -> {
                scope.launch {
                    _signalingEvents.emit(SignalingEvent.IceCandidateReceived(
                        message.clientId, 
                        message.iceCandidate!!
                    ))
                }
            }
            
            SignalingMessageType.DISCONNECT -> {
                connectedClients.remove(message.clientId)?.close()
                scope.launch {
                    _signalingEvents.emit(SignalingEvent.ClientDisconnected(message.clientId))
                }
            }
            
            else -> {
                Log.w(TAG, "Unknown message type: ${message.type}")
            }
        }
    }
    
    /**
     * Send offer to a specific client
     */
    suspend fun sendOfferToClient(clientId: String, offer: SessionDescription): Boolean {
        return connectedClients[clientId]?.let { session ->
            try {
                val message = SignalingMessage(
                    type = SignalingMessageType.OFFER,
                    clientId = "host", // Host identifier
                    sdp = SdpData(offer.type.canonicalForm(), offer.description)
                )
                
                sendMessage(session, message)
                Log.d(TAG, "Sent offer to client: $clientId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send offer to client: $clientId", e)
                false
            }
        } ?: false
    }
    
    /**
     * Send ICE candidate to a specific client
     */
    suspend fun sendIceCandidateToClient(clientId: String, candidate: IceCandidate): Boolean {
        return connectedClients[clientId]?.let { session ->
            try {
                val message = SignalingMessage(
                    type = SignalingMessageType.ICE_CANDIDATE,
                    clientId = "host",
                    iceCandidate = IceCandidateData(
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex
                    )
                )
                
                sendMessage(session, message)
                Log.d(TAG, "Sent ICE candidate to client: $clientId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send ICE candidate to client: $clientId", e)
                false
            }
        } ?: false
    }
    
    /**
     * Send answer to a specific client
     */
    suspend fun sendAnswerToClient(clientId: String, answer: SessionDescription): Boolean {
        return connectedClients[clientId]?.let { session ->
            try {
                val message = SignalingMessage(
                    type = SignalingMessageType.ANSWER,
                    clientId = "host",
                    sdp = SdpData(answer.type.canonicalForm(), answer.description)
                )
                
                sendMessage(session, message)
                Log.d(TAG, "Sent answer to client: $clientId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send answer to client: $clientId", e)
                false
            }
        } ?: false
    }
    
    /**
     * Disconnect a specific client
     */
    suspend fun disconnectClient(clientId: String) {
        connectedClients.remove(clientId)?.let { session ->
            try {
                val message = SignalingMessage(
                    type = SignalingMessageType.DISCONNECT,
                    clientId = "host"
                )
                
                sendMessage(session, message)
                session.close(CloseReason(CloseReason.Codes.NORMAL, "Disconnected by host"))
                
                Log.d(TAG, "Disconnected client: $clientId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disconnect client: $clientId", e)
            }
        }
    }
    
    /**
     * Broadcast message to all connected clients
     */
    suspend fun broadcastMessage(message: SignalingMessage) {
        connectedClients.values.forEach { session ->
            try {
                sendMessage(session, message)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast message to client", e)
            }
        }
    }
    
    /**
     * Send message to WebSocket session
     */
    private suspend fun sendMessage(session: WebSocketSession, message: SignalingMessage) {
        val messageJson = json.encodeToString( message)
        session.send(Frame.Text(messageJson))
    }
    
    /**
     * Get connected clients count
     */
    fun getConnectedClientsCount(): Int = connectedClients.size
    
    /**
     * Get connected client IDs
     */
    fun getConnectedClientIds(): List<String> = connectedClients.keys.toList()
    
    /**
     * Check if client is connected
     */
    fun isClientConnected(clientId: String): Boolean = connectedClients.containsKey(clientId)
}

/**
 * Signaling message data classes
 */
@Serializable
data class SignalingMessage(
    val type: SignalingMessageType,
    val clientId: String,
    val sdp: SdpData? = null,
    val iceCandidate: IceCandidateData? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
enum class SignalingMessageType {
    REGISTER,
    REGISTER_ACK,
    OFFER,
    ANSWER,
    ICE_CANDIDATE,
    DISCONNECT,
    ERROR
}

@Serializable
data class SdpData(
    val type: String,
    val description: String
)

@Serializable
data class IceCandidateData(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int
)

/**
 * Signaling events
 */
sealed class SignalingEvent {
    data class ClientConnected(val clientId: String) : SignalingEvent()
    data class ClientDisconnected(val clientId: String) : SignalingEvent()
    data class OfferReceived(val clientId: String, val sdp: SdpData) : SignalingEvent()
    data class AnswerReceived(val clientId: String, val sdp: SdpData) : SignalingEvent()
    data class IceCandidateReceived(val clientId: String, val candidate: IceCandidateData) : SignalingEvent()
    data class Error(val message: String, val clientId: String? = null) : SignalingEvent()
}