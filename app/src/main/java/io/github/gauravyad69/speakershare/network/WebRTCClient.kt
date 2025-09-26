package io.github.gauravyad69.speakershare.network

import android.content.Context
import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebRTC Client for connecting to host and receiving audio
 * Handles peer connection as client, audio playback, and signaling
 */
@Singleton
class WebRTCClient @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "WebRTCClient"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    
    // WebRTC components
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var remoteAudioTrack: AudioTrack? = null
    
    // Signaling
    private var httpClient: HttpClient? = null
    private var websocketSession: DefaultClientWebSocketSession? = null
    private var hostUrl: String? = null
    private var clientId: String? = null
    
    // Events
    private val _connectionEvents = MutableSharedFlow<ClientEvent>()
    val connectionEvents: SharedFlow<ClientEvent> = _connectionEvents.asSharedFlow()
    
    // WebRTC configuration
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )
    
    private val rtcConfiguration = PeerConnection.RTCConfiguration(iceServers).apply {
        tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }
    
    init {
        initializePeerConnectionFactory()
    }
    
    /**
     * Initialize WebRTC PeerConnectionFactory
     */
    private fun initializePeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .setFieldTrials("")
            .createInitializationOptions()
        
        PeerConnectionFactory.initialize(options)
        
        val encoderFactory = DefaultVideoEncoderFactory(null, false, false)
        val decoderFactory = DefaultVideoDecoderFactory(null)
        
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(context).createAudioDeviceModule())
            .createPeerConnectionFactory()
        
        Log.d(TAG, "PeerConnectionFactory initialized")
    }
    
    /**
     * Connect to host
     */
    suspend fun connectToHost(hostIp: String, hostPort: Int = 8081, clientName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                clientId = "client_${System.currentTimeMillis()}"
                hostUrl = "ws://$hostIp:$hostPort"
                
                // Initialize HTTP client for WebSocket
                httpClient = HttpClient(Android) {
                    install(WebSockets)
                }
                
                // Connect to signaling server
                if (connectToSignalingServer()) {
                    // Create peer connection
                    createPeerConnection()
                    
                    // Register with signaling server
                    sendSignalingMessage(SignalingMessage(
                        type = SignalingMessageType.REGISTER,
                        clientId = clientId!!
                    ))
                    
                    scope.launch {
                        _connectionEvents.emit(ClientEvent.Connecting(hostIp))
                    }
                    
                    Log.d(TAG, "Connected to host: $hostIp:$hostPort")
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to host", e)
                scope.launch {
                    _connectionEvents.emit(ClientEvent.ConnectionError("Failed to connect: ${e.message}"))
                }
                false
            }
        }
    }
    
    /**
     * Disconnect from host
     */
    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                // Send disconnect message
                clientId?.let { id ->
                    sendSignalingMessage(SignalingMessage(
                        type = SignalingMessageType.DISCONNECT,
                        clientId = id
                    ))
                }
                
                // Close WebSocket connection
                websocketSession?.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnect"))
                websocketSession = null
                
                // Close peer connection
                peerConnection?.close()
                peerConnection = null
                
                // Stop audio playback
                remoteAudioTrack?.setEnabled(false)
                remoteAudioTrack = null
                
                // Close HTTP client
                httpClient?.close()
                httpClient = null
                
                scope.launch {
                    _connectionEvents.emit(ClientEvent.Disconnected)
                }
                
                Log.d(TAG, "Disconnected from host")
            } catch (e: Exception) {
                Log.e(TAG, "Error during disconnect", e)
            }
        }
    }
    
    /**
     * Connect to signaling server via WebSocket
     */
    private suspend fun connectToSignalingServer(): Boolean {
        return try {
            val client = httpClient ?: return false
            val uri = URI.create("$hostUrl/signaling")
            
            client.webSocket(
                host = uri.host,
                port = uri.port,
                path = uri.path
            ) {
                websocketSession = this
                handleSignalingMessages()
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to signaling server", e)
            false
        }
    }
    
    /**
     * Handle incoming signaling messages
     */
    private suspend fun handleSignalingMessages() {
        val session = websocketSession ?: return
        
        try {
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val message = frame.readText()
                        Log.d(TAG, "Received signaling message: $message")
                        
                        try {
                            val signalingMessage = json.decodeFromString<SignalingMessage>(message)
                            handleSignalingMessage(signalingMessage)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse signaling message", e)
                        }
                    }
                    is Frame.Close -> {
                        Log.d(TAG, "Signaling connection closed")
                        break
                    }
                    else -> {
                        // Ignore other frame types
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Signaling message handling error", e)
        }
    }
    
    /**
     * Handle different types of signaling messages
     */
    private suspend fun handleSignalingMessage(message: SignalingMessage) {
        when (message.type) {
            SignalingMessageType.REGISTER_ACK -> {
                scope.launch {
                    _connectionEvents.emit(ClientEvent.RegistrationConfirmed)
                }
                Log.d(TAG, "Registration confirmed by host")
            }
            
            SignalingMessageType.OFFER -> {
                handleOffer(message.sdp!!)
            }
            
            SignalingMessageType.ICE_CANDIDATE -> {
                handleIceCandidate(message.iceCandidate!!)
            }
            
            SignalingMessageType.DISCONNECT -> {
                disconnect()
            }
            
            else -> {
                Log.w(TAG, "Unknown signaling message type: ${message.type}")
            }
        }
    }
    
    /**
     * Handle offer from host
     */
    private suspend fun handleOffer(sdpData: SdpData) {
        try {
            val offer = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdpData.type),
                sdpData.description
            )
            
            // Set remote description with SdpObserver
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
                override fun onSetSuccess() {
                    Log.d(TAG, "Remote description set successfully")
                    // Create answer after setting remote description
                    createAndSendAnswer()
                }
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Set remote description failed: $error")
                }
                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Set remote description failed: $error")
                }
            }, offer)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle offer", e)
        }
    }
    
    /**
     * Create and send answer to host
     */
    private fun createAndSendAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                sessionDescription?.let { answer ->
                    // Set local description
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(sd: SessionDescription?) {}
                        override fun onSetSuccess() {
                            // Send answer to host
                            scope.launch {
                                sendSignalingMessage(SignalingMessage(
                                    type = SignalingMessageType.ANSWER,
                                    clientId = clientId!!,
                                    sdp = SdpData(answer.type.canonicalForm(), answer.description)
                                ))
                                Log.d(TAG, "Sent answer to host")
                            }
                        }
                        override fun onCreateFailure(error: String?) {
                            Log.e(TAG, "Set local description failed: $error")
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Set local description failed: $error")
                        }
                    }, answer)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create answer failed: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }
    
    /**
     * Handle ICE candidate from host
     */
    private fun handleIceCandidate(candidateData: IceCandidateData) {
        try {
            val candidate = IceCandidate(
                candidateData.sdpMid,
                candidateData.sdpMLineIndex,
                candidateData.candidate
            )
            
            peerConnection?.addIceCandidate(candidate)
            Log.d(TAG, "Added ICE candidate from host")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle ICE candidate", e)
        }
    }
    
    /**
     * Create peer connection
     */
    private fun createPeerConnection() {
        val factory = peerConnectionFactory ?: throw IllegalStateException("PeerConnectionFactory not initialized")
        
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch {
                    sendSignalingMessage(SignalingMessage(
                        type = SignalingMessageType.ICE_CANDIDATE,
                        clientId = clientId!!,
                        iceCandidate = IceCandidateData(
                            candidate = candidate.sdp,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex
                        )
                    ))
                }
            }
            
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
                // Handle removed ICE candidates
            }
            
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE connection state changed: $state")
                scope.launch {
                    _connectionEvents.emit(ClientEvent.ConnectionStateChanged(state))
                    
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            _connectionEvents.emit(ClientEvent.Connected)
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            _connectionEvents.emit(ClientEvent.ConnectionError("ICE connection failed"))
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            _connectionEvents.emit(ClientEvent.Disconnected)
                        }
                        else -> { /* Other states */ }
                    }
                }
            }
            
            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "ICE receiving change: $receiving")
            }
            
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "ICE gathering state changed: $state")
            }
            
            override fun onAddStream(stream: MediaStream) {
                Log.d(TAG, "Remote stream added")
                
                // Handle remote audio track
                if (stream.audioTracks.isNotEmpty()) {
                    remoteAudioTrack = stream.audioTracks[0]
                    remoteAudioTrack?.setEnabled(true)
                    
                    scope.launch {
                        _connectionEvents.emit(ClientEvent.AudioStreamStarted)
                    }
                }
            }
            
            override fun onRemoveStream(stream: MediaStream) {
                Log.d(TAG, "Remote stream removed")
                remoteAudioTrack?.setEnabled(false)
                remoteAudioTrack = null
                
                scope.launch {
                    _connectionEvents.emit(ClientEvent.AudioStreamStopped)
                }
            }
            
            override fun onDataChannel(channel: DataChannel) {
                // Handle data channel if needed
            }
            
            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }
            
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "Signaling state changed: $state")
            }
        }
        
        peerConnection = factory.createPeerConnection(rtcConfiguration, observer)
            ?: throw RuntimeException("Failed to create peer connection")
        
        Log.d(TAG, "Peer connection created")
    }
    
    /**
     * Send signaling message
     */
    private suspend fun sendSignalingMessage(message: SignalingMessage) {
        try {
            val messageJson = json.encodeToString(message)
            websocketSession?.send(Frame.Text(messageJson))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send signaling message", e)
        }
    }
    
    /**
     * Set audio volume
     */
    fun setAudioVolume(volume: Float) {
        // Note: WebRTC handles audio routing through the system
        // Volume control should be implemented through AudioManager
        Log.d(TAG, "Audio volume set to: $volume")
    }
    
    /**
     * Check if connected to host
     */
    fun isConnected(): Boolean {
        return peerConnection?.connectionState() == PeerConnection.PeerConnectionState.CONNECTED
    }
    
    /**
     * Get connection statistics
     */
    suspend fun getConnectionStats(): String? {
        return try {
            // WebRTC stats can be complex, return basic info for now
            val state = peerConnection?.connectionState()
            val iceState = peerConnection?.iceConnectionState()
            "Connection: $state, ICE: $iceState"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get connection stats", e)
            null
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        scope.launch {
            disconnect()
        }
        
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        
        Log.d(TAG, "WebRTC client cleanup completed")
    }
}

/**
 * Client events
 */
sealed class ClientEvent {
    data class Connecting(val hostIp: String) : ClientEvent()
    object RegistrationConfirmed : ClientEvent()
    object Connected : ClientEvent()
    object Disconnected : ClientEvent()
    object AudioStreamStarted : ClientEvent()
    object AudioStreamStopped : ClientEvent()
    data class ConnectionStateChanged(val state: PeerConnection.IceConnectionState) : ClientEvent()
    data class ConnectionError(val message: String) : ClientEvent()
}