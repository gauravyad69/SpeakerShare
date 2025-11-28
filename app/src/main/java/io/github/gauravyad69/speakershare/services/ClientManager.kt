package io.github.gauravyad69.speakershare.services

import io.github.gauravyad69.speakershare.audio.AudioDecoder
import io.github.gauravyad69.speakershare.audio.AudioEncoder
import io.github.gauravyad69.speakershare.audio.AudioPlaybackService
import io.github.gauravyad69.speakershare.network.UdpAudioClient
import io.github.gauravyad69.speakershare.network.UdpClientEvent
import io.github.gauravyad69.speakershare.data.model.*
import io.github.gauravyad69.speakershare.network.api.ClientConnectRequest
import io.github.gauravyad69.speakershare.network.api.ClientConnectResponse
import io.github.gauravyad69.speakershare.network.api.ClientDisconnectResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.gson.gson
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import java.util.UUID

/**
 * Service for managing client connections to host sessions.
 * Handles discovery, connection, audio playback, and reconnection logic.
 */
@Singleton
class ClientManager @Inject constructor(
    private val audioStreamManager: AudioStreamManager,
    private val networkDiscoveryService: NetworkDiscoveryService,
    private val audioPlaybackService: AudioPlaybackService,
    private val audioDecoder: AudioDecoder,
    private val udpAudioClient: UdpAudioClient
) {
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    init {
        observeUdpEvents()
        observeDecodedAudio()
    }
    
    private fun observeDecodedAudio() {
        // Listen for decoded PCM data and send to playback service
        serviceScope.launch {
            var receivedCount = 0
            audioDecoder.decodedAudioFlow.collect { decodedData ->
                receivedCount++
                if (receivedCount % 50 == 1) {
                    Log.d(TAG, "Received decoded PCM #$receivedCount: ${decodedData.pcmData.size} bytes")
                }
                audioPlaybackService.queueAudioData(decodedData.pcmData)
            }
        }
    }

    private fun observeUdpEvents() {
        serviceScope.launch {
            udpAudioClient.clientEvents.collect { event ->
                when (event) {
                    is UdpClientEvent.AudioDataReceived -> {
                        // Queue AAC data to decoder, not directly to playback
                        val encodedPacket = AudioEncoder.EncodedAudioPacket(
                            data = event.audioData,
                            presentationTimeUs = event.timestamp * 1000, // Convert ms to us
                            size = event.audioData.size,
                            isKeyFrame = true
                        )
                        audioDecoder.decodeAACPacket(encodedPacket)
                    }
                    is UdpClientEvent.Connected -> {
                        Log.d(TAG, "UDP Client connected")
                    }
                    is UdpClientEvent.Disconnected -> {
                        Log.d(TAG, "UDP Client disconnected")
                    }
                    is UdpClientEvent.ConnectionError -> {
                        Log.e(TAG, "UDP Connection error: ${event.message}")
                    }
                    is UdpClientEvent.ReceiveError -> {
                        Log.e(TAG, "UDP Receive error: ${event.message}")
                    }
                    else -> {}
                }
            }
        }
    }
    
    private val _discoveredHosts = MutableStateFlow<List<HostSession>>(emptyList())
    val discoveredHosts: StateFlow<List<HostSession>> = _discoveredHosts.asStateFlow()
    
    private val _currentConnection = MutableStateFlow<ClientConnection?>(null)
    val currentConnection: StateFlow<ClientConnection?> = _currentConnection.asStateFlow()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _audioSettings = MutableStateFlow(ClientAudioSettings())
    val audioSettings: StateFlow<ClientAudioSettings> = _audioSettings.asStateFlow()

    // Expose audio level from playback service
    val audioLevel: StateFlow<Float> = audioPlaybackService.audioLevel
    
    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()
    
    companion object {
        private const val TAG = "ClientManager"
        private const val DISCOVERY_TIMEOUT_MS = 10000L
        private const val CONNECTION_TIMEOUT_MS = 5000L
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val CLIENT_AUDIO_PORT = 9091  // Port where client listens for UDP audio
    }
    
    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            gson {
                setPrettyPrinting()
                disableHtmlEscaping()
            }
        }
        engine {
            connectTimeout = 5000
            socketTimeout = 5000
        }
    }

    /**
     * Start discovering available hosts on the network
     */
    suspend fun startDiscovery(): Result<Unit> {
        return try {
            if (_isDiscovering.value) {
                Log.w(TAG, "Discovery already in progress")
                return Result.success(Unit)
            }
            
            Log.d(TAG, "Starting host discovery")
            _isDiscovering.value = true
            
            // Start network discovery service
            networkDiscoveryService.startDiscovery()
            
            // Observe discovered hosts
            serviceScope.launch {
                networkDiscoveryService.discoveredHosts.collect { networkInfos ->
                    val hostSessions = networkInfos.map { networkInfo ->
                        HostSession(
                            sessionId = networkInfo.serviceName, // Use service name as session ID for now
                            sessionName = networkInfo.serviceName,
                            hostName = networkInfo.serviceName,
                            networkInfo = networkInfo,
                            isActive = true,
                            audioSource = AudioSource.MICROPHONE, // Default
                            quality = AudioQuality(), // Default
                            connectedClients = emptyList(),
                            maxClients = 50, // Default
                            startTime = System.currentTimeMillis()
                        )
                    }
                    _discoveredHosts.value = hostSessions
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            _isDiscovering.value = false
            Result.failure(e)
        }
    }
    
    /**
     * Stop host discovery
     */
    fun stopDiscovery() {
        Log.d(TAG, "Stopping host discovery")
        _isDiscovering.value = false
        serviceScope.launch {
            networkDiscoveryService.stopDiscovery()
        }
    }
    
    /**
     * Connect to a discovered host
     */
    suspend fun connectToHost(
        hostSession: HostSession,
        clientName: String
    ): Result<Unit> {
        return try {
            if (_isConnected.value) {
                return Result.failure(IllegalStateException("Already connected to a host"))
            }
            
            val clientId = UUID.randomUUID().toString()
            Log.d(TAG, "Connecting to host: ${hostSession.hostName} (${hostSession.sessionId})")
            
            // Create client connection
            val clientConnection = ClientConnection(
                clientId = clientId,
                clientName = clientName,
                ipAddress = getLocalIpAddress(),
                connectionTime = System.currentTimeMillis(),
                status = ConnectionStatus.CONNECTING,
                audioSettings = _audioSettings.value,
                networkMetrics = NetworkMetrics(latency = 0L, packetLoss = 0.0f, bandwidth = 0L)
            )
            
            _currentConnection.value = clientConnection
            
            // Send connection request to host
            val connectionResult = sendConnectionRequest(hostSession, clientConnection)
            
            if (connectionResult.isFailure) {
                _currentConnection.value = clientConnection.copy(status = ConnectionStatus.ERROR)
                return Result.failure(connectionResult.exceptionOrNull() ?: Exception("Unknown error"))
            }
            
            val response = connectionResult.getOrNull()!!

            // Update connection status
            val connectedClient = clientConnection.copy(status = ConnectionStatus.CONNECTED)
            _currentConnection.value = connectedClient
            _isConnected.value = true
            
            // Start audio playback with clientId for heartbeat identification
            startAudioPlayback(hostSession, response.sampleRate, clientId)
            
            Log.d(TAG, "Connected to host successfully")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to host", e)
            _currentConnection.value = _currentConnection.value?.copy(status = ConnectionStatus.ERROR)
            Result.failure(e)
        }
    }
    
    /**
     * Disconnect from current host
     */
    suspend fun disconnect(): Result<Unit> {
        return try {
            val connection = _currentConnection.value
            if (connection == null) {
                return Result.failure(IllegalStateException("Not connected to any host"))
            }
            
            Log.d(TAG, "Disconnecting from host")
            
            // Stop audio playback
            stopAudioPlayback()
            
            // Send disconnection request to host
            sendDisconnectionRequest(connection)
            
            // Update connection status
            _currentConnection.value = connection.copy(status = ConnectionStatus.DISCONNECTED)
            _isConnected.value = false
            
            // Clear connection after a delay
            _currentConnection.value = null
            
            Log.d(TAG, "Disconnected successfully")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
            Result.failure(e)
        }
    }
    
    /**
     * Update volume setting
     */
    suspend fun setVolume(volume: Float): Result<Unit> {
        return try {
            if (volume < 0.0f || volume > 1.0f) {
                return Result.failure(IllegalArgumentException("Volume must be between 0.0 and 1.0"))
            }
            
            Log.d(TAG, "Setting volume to $volume")
            
            val newSettings = _audioSettings.value.copy(volume = volume)
            _audioSettings.value = newSettings
            
            // Update connection if active
            _currentConnection.value?.let { connection ->
                _currentConnection.value = connection.copy(audioSettings = newSettings)
            }
            
            // Apply volume change to audio playback
            applyVolumeChange(volume)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
            Result.failure(e)
        }
    }
    
    /**
     * Toggle mute setting
     */
    suspend fun toggleMute(): Result<Unit> {
        return try {
            val currentSettings = _audioSettings.value
            val newMuteState = !currentSettings.isMuted
            
            Log.d(TAG, "Setting mute to $newMuteState")
            
            val newSettings = currentSettings.copy(isMuted = newMuteState)
            _audioSettings.value = newSettings
            
            // Update connection if active
            _currentConnection.value?.let { connection ->
                _currentConnection.value = connection.copy(audioSettings = newSettings)
            }
            
            // Apply mute change to audio playback
            applyMuteChange(newMuteState)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle mute", e)
            Result.failure(e)
        }
    }
    
    /**
     * Handle connection loss and attempt reconnection
     */
    suspend fun handleConnectionLoss(): Result<Unit> {
        return try {
            val connection = _currentConnection.value
            if (connection == null) {
                return Result.failure(IllegalStateException("No connection to recover"))
            }
            
            Log.w(TAG, "Connection lost, attempting to reconnect")
            
            _currentConnection.value = connection.copy(status = ConnectionStatus.CONNECTING)
            _isConnected.value = false
            
            // Attempt reconnection with exponential backoff
            var attempts = 0
            while (attempts < MAX_RECONNECT_ATTEMPTS) {
                attempts++
                Log.d(TAG, "Reconnection attempt $attempts/$MAX_RECONNECT_ATTEMPTS")
                
                delay(RECONNECT_DELAY_MS * attempts)
                
                // Try to reconnect
                val reconnectResult = attemptReconnection(connection)
                if (reconnectResult.isSuccess) {
                    Log.d(TAG, "Reconnected successfully")
                    return Result.success(Unit)
                }
                
                if (attempts < MAX_RECONNECT_ATTEMPTS) {
                    Log.w(TAG, "Reconnection attempt $attempts failed, retrying...")
                }
            }
            
            // All reconnection attempts failed
            Log.e(TAG, "All reconnection attempts failed")
            _currentConnection.value = connection.copy(status = ConnectionStatus.ERROR)
            
            Result.failure(Exception("Failed to reconnect after $MAX_RECONNECT_ATTEMPTS attempts"))
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle connection loss", e)
            Result.failure(e)
        }
    }
    
    // Private helper methods
    private suspend fun getLocalIpAddress(): String {
        return networkDiscoveryService.getLocalIpAddresses().firstOrNull() ?: "127.0.0.1"
    }
    
    private suspend fun sendConnectionRequest(
        hostSession: HostSession,
        clientConnection: ClientConnection
    ): Result<ClientConnectResponse> {
        return try {
            val hostIp = hostSession.networkInfo.localIpAddress
            val port = hostSession.networkInfo.port
            val url = "http://$hostIp:$port/api/v1/clients/connect"
            
            Log.d(TAG, "Sending connection request to $url")
            
            val request = ClientConnectRequest(
                clientId = clientConnection.clientId,
                clientName = clientConnection.clientName,
                preferredTransport = "UDP",  // Request UDP transport for audio streaming
                capabilities = listOf("OPUS", "AAC"),
                audioPort = CLIENT_AUDIO_PORT  // Tell host which port we listen on
            )
            
            val response: ClientConnectResponse = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()
            
            if (response.status == "ACCEPTED") {
                Log.d(TAG, "Connection accepted by host")
                Result.success(response)
            } else {
                Log.w(TAG, "Connection rejected: ${response.reason}")
                Result.failure(Exception("Connection rejected: ${response.reason}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection request failed", e)
            Result.failure(e)
        }
    }
    
    private suspend fun sendDisconnectionRequest(connection: ClientConnection) {
        Log.d(TAG, "Sending disconnection request")
        // TODO: Implement HTTP disconnection request
        // We need the host IP to send the request. 
        // For now, we'll just log it as we don't have the host session stored in this context easily.
    }
    
    private suspend fun startAudioPlayback(hostSession: HostSession, sampleRate: Int, clientId: String) {
        // Audio stream uses 22050Hz sample rate (configured in AudioCaptureService/AudioEncoder)
        val actualSampleRate = 22050
        Log.d(TAG, "Starting audio playback for session ${hostSession.sessionId} at ${actualSampleRate}Hz with clientId=$clientId")
        
        // Start decoder with matching sample rate (host uses 22050Hz for encoding)
        val decoderConfig = AudioDecoder.DecoderConfig(
            sampleRate = actualSampleRate,  // Must match encoder sample rate
            channelCount = 1,
            bufferTimeoutUs = 10000L
        )
        audioDecoder.startDecoding(decoderConfig)
        Log.d(TAG, "Audio decoder started")
        
        // Start playback with matching sample rate 
        audioPlaybackService.startPlayback(AudioPlaybackService.PlaybackConfig(sampleRate = actualSampleRate))
        
        // Start UDP client to receive audio with clientId for heartbeat identification
        // The host will send audio to CLIENT_AUDIO_PORT on this device
        Log.d(TAG, "Starting UDP audio client on port $CLIENT_AUDIO_PORT with clientId=$clientId")
        udpAudioClient.startListening(CLIENT_AUDIO_PORT, clientId)
    }
    
    private suspend fun stopAudioPlayback() {
        Log.d(TAG, "Stopping audio playback")
        audioPlaybackService.stopPlayback()
        audioDecoder.stopDecoding()
        udpAudioClient.disconnect()  // This will clean up and stop listening
    }
    
    private suspend fun applyVolumeChange(volume: Float) {
        Log.d(TAG, "Applying volume change: $volume")
        audioPlaybackService.setVolume(volume)
    }
    
    private suspend fun applyMuteChange(muted: Boolean) {
        Log.d(TAG, "Applying mute change: $muted")
        audioPlaybackService.setMuted(muted)
    }
    
    private suspend fun attemptReconnection(connection: ClientConnection): Result<Unit> {
        return try {
            Log.d(TAG, "Attempting to reconnect...")
            // TODO: Implement reconnection logic
            
            // For now, simulate reconnection attempt
            delay(1000)
            
            // Update connection status
            _currentConnection.value = connection.copy(status = ConnectionStatus.CONNECTED)
            _isConnected.value = true
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Reconnection attempt failed", e)
            Result.failure(e)
        }
    }
}
