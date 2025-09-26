@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.gauravyad69.speakershare.network

import android.content.Context
import android.util.Log
import io.github.gauravyad69.speakershare.data.model.ClientConnection
import io.getstream.webrtc.android.compose.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.*
import kotlin.coroutines.resume
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebRTC Manager for audio streaming
 * Handles peer connections, audio tracks, and signaling
 */
@Singleton
class WebRTCManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "WebRTCManager"
        private const val AUDIO_TRACK_ID = "audio_track"
        private const val STREAM_ID = "audio_stream"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // WebRTC components
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var mediaStream: MediaStream? = null
    
    // Peer connections management
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    
    // Events
    private val _connectionEvents = MutableSharedFlow<WebRTCEvent>()
    val connectionEvents: SharedFlow<WebRTCEvent> = _connectionEvents.asSharedFlow()
    
    // WebRTC configuration
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        // Add local STUN server for better LAN performance
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
            .createPeerConnectionFactory()
        
        Log.d(TAG, "PeerConnectionFactory initialized")
    }
    
    /**
     * Start broadcasting audio as host
     */
    fun startBroadcasting(): Boolean {
        return try {
            createLocalAudioTrack()
            Log.d(TAG, "Started broadcasting audio")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start broadcasting", e)
            false
        }
    }
    
    /**
     * Stop broadcasting audio
     */
    fun stopBroadcasting() {
        localAudioTrack?.setEnabled(false)
        localAudioTrack?.dispose()
        localAudioTrack = null
        
        audioSource?.dispose()
        audioSource = null
        
        mediaStream?.dispose()
        mediaStream = null
        
        // Close all peer connections
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        
        Log.d(TAG, "Stopped broadcasting")
    }
    
    /**
     * Create local audio track for broadcasting
     */
    private fun createLocalAudioTrack() {
        val factory = peerConnectionFactory ?: throw IllegalStateException("PeerConnectionFactory not initialized")
        
        // Create audio source with constraints for optimal audio quality
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))
        }
        
        audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        
        // Create media stream and add audio track
        mediaStream = factory.createLocalMediaStream(STREAM_ID)
        mediaStream?.addTrack(localAudioTrack)
        
        Log.d(TAG, "Local audio track created")
    }
    
    /**
     * Add a new peer connection for a client
     */
    suspend fun addPeerConnection(clientId: String): Boolean {
        return try {
            val peerConnection = createPeerConnection(clientId)
            peerConnections[clientId] = peerConnection
            
            // Add local stream to the peer connection
            mediaStream?.let { stream ->
                peerConnection.addStream(stream)
            }
            
            scope.launch {
                _connectionEvents.emit(WebRTCEvent.PeerConnected(clientId))
            }
            
            Log.d(TAG, "Peer connection added for client: $clientId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add peer connection for client: $clientId", e)
            false
        }
    }
    
    /**
     * Remove peer connection for a client
     */
    fun removePeerConnection(clientId: String) {
        peerConnections.remove(clientId)?.let { peerConnection ->
            peerConnection.close()
            scope.launch {
                _connectionEvents.emit(WebRTCEvent.PeerDisconnected(clientId))
            }
            Log.d(TAG, "Peer connection removed for client: $clientId")
        }
    }
    
    /**
     * Create a new peer connection for a client
     */
    private fun createPeerConnection(clientId: String): PeerConnection {
        val factory = peerConnectionFactory ?: throw IllegalStateException("PeerConnectionFactory not initialized")
        
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
                scope.launch {
                    _connectionEvents.emit(WebRTCEvent.IceCandidate(clientId, candidate))
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) {
                // Handle removed ICE candidates
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE connection state changed for $clientId: $state")
                scope.launch {
                    _connectionEvents.emit(WebRTCEvent.ConnectionStateChanged(clientId, state))
                }
            }
            
            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                // Handle ICE receiving changes
            }
            
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "ICE gathering state changed for $clientId: $state")
            }
            
            override fun onAddStream(stream: MediaStream) {
                Log.d(TAG, "Stream added for $clientId")
            }
            
            override fun onRemoveStream(stream: MediaStream) {
                Log.d(TAG, "Stream removed for $clientId")
            }
            
            override fun onDataChannel(channel: DataChannel) {
                // Handle data channel
            }
            
            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed for $clientId")
            }
            
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "Signaling state changed for $clientId: $state")
            }
        }
        
        return factory.createPeerConnection(rtcConfiguration, observer)
            ?: throw RuntimeException("Failed to create peer connection for $clientId")
    }
    
    /**
     * Create offer for a client
     */
    suspend fun createOffer(clientId: String): SessionDescription? {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { 
                Log.w(TAG, "createOffer for $clientId was cancelled")
            }
            peerConnections[clientId]?.let { peerConnection ->
                try {
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                    }
                    
                    peerConnection.createOffer(object : SdpObserver {
                        override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                            sessionDescription?.let { sdp ->
                                peerConnection.setLocalDescription(object : SdpObserver {
                                    override fun onCreateSuccess(sd: SessionDescription?) {}
                                    override fun onSetSuccess() {
                                        Log.d(TAG, "Created offer for client: $clientId")
                                        continuation.resume(sdp)
                                    }
                                    override fun onCreateFailure(error: String?) {
                                        Log.e(TAG, "Set local description failed: $error")
                                        continuation.resume(null)
                                    }
                                    override fun onSetFailure(error: String?) {
                                        Log.e(TAG, "Set local description failed: $error")
                                        continuation.resume(null)
                                    }
                                }, sdp)
                            } ?: continuation.resume(null)
                        }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(error: String?) {
                            Log.e(TAG, "Create offer failed: $error")
                            continuation.resume(null)
                        }
                        override fun onSetFailure(error: String?) {}
                    }, constraints)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create offer for client: $clientId", e)
                    continuation.resume(null)
                }
            } ?: continuation.resume(null)
        }
    }
    
    /**
     * Set remote description (answer) from client
     */
    suspend fun setRemoteDescription(clientId: String, answer: SessionDescription): Boolean {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { 
                Log.w(TAG, "setRemoteDescription for $clientId was cancelled")
            }
            peerConnections[clientId]?.let { peerConnection ->
                try {
                    peerConnection.setRemoteDescription(object : SdpObserver {
                        override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.d(TAG, "Set remote description for client: $clientId")
                            continuation.resume(true)
                        }
                        override fun onCreateFailure(error: String?) {
                            Log.e(TAG, "Set remote description failed: $error")
                            continuation.resume(false)
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Set remote description failed: $error")
                            continuation.resume(false)
                        }
                    }, answer)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set remote description for client: $clientId", e)
                    continuation.resume(false)
                }
            } ?: continuation.resume(false)
        }
    }
    
    /**
     * Add ICE candidate from client
     */
    fun addIceCandidate(clientId: String, iceCandidate: org.webrtc.IceCandidate) {
        peerConnections[clientId]?.addIceCandidate(iceCandidate)
    }
    
    /**
     * Get connected peer count
     */
    fun getConnectedPeerCount(): Int = peerConnections.size
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        stopBroadcasting()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        Log.d(TAG, "WebRTC cleanup completed")
    }
}

/**
 * WebRTC Events
 */
sealed class WebRTCEvent {
    data class PeerConnected(val clientId: String) : WebRTCEvent()
    data class PeerDisconnected(val clientId: String) : WebRTCEvent()
    data class IceCandidate(val clientId: String, val candidate: org.webrtc.IceCandidate) : WebRTCEvent()
    data class ConnectionStateChanged(val clientId: String, val state: PeerConnection.IceConnectionState) : WebRTCEvent()
    data class Error(val message: String, val exception: Exception? = null) : WebRTCEvent()
}