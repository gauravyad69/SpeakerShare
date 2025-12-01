package io.github.gauravyad69.speakershare.services

import io.github.gauravyad69.speakershare.data.model.*
import io.github.gauravyad69.speakershare.data.repository.HostSessionRepository
import io.github.gauravyad69.speakershare.network.UdpAudioServer
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import android.content.Intent
import java.net.InetAddress
import java.util.UUID

/**
 * Service for managing host broadcasting sessions.
 * Handles session lifecycle, client connections, and host controls.
 */
@Singleton
class HostService @Inject constructor(
    private val audioStreamManager: AudioStreamManager,
    private val httpApiServer: io.github.gauravyad69.speakershare.network.HttpApiServer,
    private val networkDiscoveryService: NetworkDiscoveryService,
    private val hostSessionRepository: HostSessionRepository,
    private val udpAudioServer: UdpAudioServer
) {
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val _currentSession = MutableStateFlow<HostSession?>(null)
    val currentSession: StateFlow<HostSession?> = _currentSession.asStateFlow()
    
    private val _connectedClients = MutableStateFlow<List<ClientConnection>>(emptyList())
    val connectedClients: StateFlow<List<ClientConnection>> = _connectedClients.asStateFlow()
    
    private val _isHosting = MutableStateFlow(false)
    val isHosting: StateFlow<Boolean> = _isHosting.asStateFlow()
    
    // Transfer events from UDP server
    private val _transferEvents = MutableStateFlow<TransferEvent?>(null)
    val transferEvents: StateFlow<TransferEvent?> = _transferEvents.asStateFlow()
    
    init {
        observeServerEvents()
    }
    
    private fun observeServerEvents() {
        serviceScope.launch {
            udpAudioServer.serverEvents.collect { event ->
                when (event) {
                    is io.github.gauravyad69.speakershare.network.UdpServerEvent.ClientDisconnected -> {
                        Log.d(TAG, "Client disconnected from UDP server: ${event.clientId}")
                        // Remove client from our connected clients list
                        val currentClients = _connectedClients.value
                        val updatedClients = currentClients.filter { it.clientId != event.clientId }
                        if (updatedClients.size != currentClients.size) {
                            _connectedClients.value = updatedClients
                            _currentSession.value = _currentSession.value?.copy(connectedClients = updatedClients)
                            Log.d(TAG, "Removed client ${event.clientId}, ${updatedClients.size} clients remaining")
                        }
                    }
                    is io.github.gauravyad69.speakershare.network.UdpServerEvent.TransferAccepted -> {
                        Log.d(TAG, "Transfer accepted by ${event.clientId} at ${event.clientAddress}:${event.newServerPort}")
                        _transferEvents.value = TransferEvent.Accepted(
                            event.clientId,
                            event.clientAddress,
                            event.newServerPort
                        )
                        // Auto-handle the transfer if we have a pending request
                        handleTransferAccepted(event.clientId, event.clientAddress, event.newServerPort)
                    }
                    is io.github.gauravyad69.speakershare.network.UdpServerEvent.TransferRejected -> {
                        Log.d(TAG, "Transfer rejected by ${event.clientId}")
                        _transferEvents.value = TransferEvent.Rejected(event.clientId)
                        handleTransferRejected(event.clientId)
                    }
                    else -> {}
                }
            }
        }
    }
    
    companion object {
        private const val TAG = "HostService"
        private const val DEFAULT_PORT = 8080
        private const val MAX_CLIENTS_DEFAULT = 50
    }
    
    /**
     * Start hosting a new session
     */
    suspend fun startHosting(
        hostName: String,
        audioSource: AudioSource = AudioSource.MICROPHONE,
        quality: AudioQuality = AudioQuality(),
        maxClients: Int = MAX_CLIENTS_DEFAULT
    ): Result<HostSession> {
        return try {
            if (_isHosting.value) {
                return Result.failure(IllegalStateException("Already hosting a session"))
            }
            
            // Get the current latency config for proper sample rate
            val latencyConfig = audioStreamManager.latencyConfig.value
            val effectiveQuality = quality.copy(
                sampleRate = latencyConfig.sampleRate,
                encoding = latencyConfig.encoding
            )
            
            val sessionId = UUID.randomUUID().toString()
            val networkInfo = NetworkInfo(
                localIpAddress = getLocalIpAddress(),
                port = DEFAULT_PORT,
                networkInterface = "wlan0", // Default Wi-Fi interface
                isHotspot = false, // TODO: Detect actual hotspot status
                discoveryMethod = DiscoveryMethod.MDNS,
                serviceName = "speakershare-$sessionId"
            )
            
            Log.d(TAG, "Starting host session: $sessionId for $hostName with sampleRate=${effectiveQuality.sampleRate}, encoding=${effectiveQuality.encoding}")
            
            val hostSession = HostSession(
                sessionId = sessionId,
                sessionName = hostName, // Use hostName as sessionName for now
                hostName = hostName,
                audioSource = audioSource,
                quality = effectiveQuality,
                isActive = false, // Will be activated after audio stream starts
                startTime = System.currentTimeMillis(),
                connectedClients = emptyList(),
                networkInfo = networkInfo,
                maxClients = maxClients
            )
            
            // Start audio streaming
            val streamResult = audioStreamManager.startStreaming(
                sessionId, audioSource, effectiveQuality, "UDP"
            )
            
            if (streamResult.isFailure) {
                return Result.failure(streamResult.exceptionOrNull()!!)
            }
            
            // Start HTTP API server for client communication
            if (!startHttpApiServer(DEFAULT_PORT)) {
                audioStreamManager.stopStreaming()
                return Result.failure(Exception("Failed to start HTTP server on port $DEFAULT_PORT"))
            }
            
            // Start network discovery broadcasting
            startDiscoveryBroadcast(hostSession)
            
            // Update session state
            val activeSession = hostSession.copy(isActive = true)
            _currentSession.value = activeSession
            _isHosting.value = true
            
            // Sync with repository
            hostSessionRepository.createSession(
                hostName = hostName,
                audioSource = audioSource,
                quality = effectiveQuality,
                networkInfo = networkInfo
            )
            hostSessionRepository.startBroadcasting()
            
            Log.d(TAG, "Host session started successfully with sampleRate=${effectiveQuality.sampleRate}")
            Result.success(activeSession)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start hosting", e)
            cleanup()
            Result.failure(e)
        }
    }
    
    /**
     * Stop current hosting session
     */
    suspend fun stopHosting(): Result<Unit> {
        return try {
            Log.d(TAG, "Stopping host session")
            
            val session = _currentSession.value
            if (session == null) {
                return Result.failure(IllegalStateException("No active session to stop"))
            }
            
            // Disconnect all clients
            disconnectAllClients("Host session ended")
            
            // Stop audio streaming
            audioStreamManager.stopStreaming()
            
            // Stop services
            stopHttpApiServer()
            stopDiscoveryBroadcast()
            
            // Update state
            _currentSession.value = session.copy(isActive = false)
            _isHosting.value = false
            _connectedClients.value = emptyList()
            
            // Sync with repository
            hostSessionRepository.stopBroadcasting()
            hostSessionRepository.endSession()
            
            // Clear session after a delay
            _currentSession.value = null
            
            Log.d(TAG, "Host session stopped successfully")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop hosting", e)
            Result.failure(e)
        }
    }
    
    /**
     * Handle client connection request
     */
    suspend fun handleClientConnection(
        clientId: String,
        clientName: String,
        clientIp: String,
        clientAudioPort: Int = 9091  // Default client audio port
    ): Result<Boolean> {
        return try {
            val session = _currentSession.value
            if (session == null || !session.isActive) {
                return Result.failure(IllegalStateException("No active session"))
            }
            
            val currentClients = _connectedClients.value
            if (currentClients.size >= session.maxClients) {
                Log.w(TAG, "Client connection rejected: max clients reached")
                return Result.success(false)
            }
            
            if (currentClients.any { it.clientId == clientId }) {
                Log.w(TAG, "Client $clientId already connected")
                return Result.success(true)
            }
            
            Log.d(TAG, "Accepting client connection: $clientId ($clientName)")
            
            val clientConnection = ClientConnection(
                clientId = clientId,
                clientName = clientName,
                ipAddress = clientIp,
                connectionTime = System.currentTimeMillis(),
                status = ConnectionStatus.CONNECTED,
                audioSettings = ClientAudioSettings(),
                networkMetrics = NetworkMetrics(latency = 0L, packetLoss = 0.0f, bandwidth = 0L)
            )
            
            val updatedClients = currentClients + clientConnection
            _connectedClients.value = updatedClients
            
            // Register client with UDP audio server for streaming
            try {
                val clientAddress = InetAddress.getByName(clientIp)
                udpAudioServer.addClient(clientId, clientAddress, clientAudioPort)
                Log.d(TAG, "Registered client $clientId for UDP audio streaming at $clientIp:$clientAudioPort")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register client with UDP server", e)
                // Continue anyway - WebRTC might still work
            }
            
            // Update session with new client list
            _currentSession.value = session.copy(connectedClients = updatedClients)
            
            Log.d(TAG, "Client connected successfully: $clientName (${updatedClients.size} total)")
            Result.success(true)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle client connection", e)
            Result.failure(e)
        }
    }
    
    /**
     * Disconnect a specific client
     */
    suspend fun disconnectClient(clientId: String, reason: String = "Disconnected by host"): Result<Unit> {
        return try {
            val currentClients = _connectedClients.value
            val client = currentClients.find { it.clientId == clientId }
                ?: return Result.failure(IllegalArgumentException("Client not found: $clientId"))
            
            Log.d(TAG, "Disconnecting client: ${client.clientName} - $reason")
            
            // Remove from UDP audio server
            udpAudioServer.removeClient(clientId)
            
            // Update client status
            val updatedClient = client.copy(status = ConnectionStatus.DISCONNECTED)
            val updatedClients = currentClients.map { 
                if (it.clientId == clientId) updatedClient else it 
            }.filter { it.status != ConnectionStatus.DISCONNECTED }
            
            _connectedClients.value = updatedClients
            
            // Update session
            _currentSession.value = _currentSession.value?.copy(connectedClients = updatedClients)
            
            // TODO: Send disconnection message to client via HTTP API
            notifyClientDisconnection(clientId, reason)
            
            Log.d(TAG, "Client disconnected: ${client.clientName} (${updatedClients.size} remaining)")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect client", e)
            Result.failure(e)
        }
    }
    
    /**
     * Kick a client (prevents reconnection)
     */
    suspend fun kickClient(clientId: String, reason: String = "Kicked by host"): Result<Unit> {
        return try {
            val currentClients = _connectedClients.value
            val client = currentClients.find { it.clientId == clientId }
                ?: return Result.failure(IllegalArgumentException("Client not found: $clientId"))
            
            Log.d(TAG, "Kicking client: ${client.clientName} - $reason")
            
            // Update client status to kicked
            val updatedClient = client.copy(status = ConnectionStatus.KICKED)
            val updatedClients = currentClients.map { 
                if (it.clientId == clientId) updatedClient else it 
            }.filter { it.status == ConnectionStatus.CONNECTED }
            
            _connectedClients.value = updatedClients
            _currentSession.value = _currentSession.value?.copy(connectedClients = updatedClients)
            
            // TODO: Send kick message to client and add to blacklist
            notifyClientKick(clientId, reason)
            
            Log.d(TAG, "Client kicked: ${client.clientName}")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to kick client", e)
            Result.failure(e)
        }
    }
    
    /**
     * Request to transfer host role to a connected client.
     * This sends a transfer request to the client and waits for their response.
     */
    suspend fun requestTransferHost(clientId: String): Result<Unit> {
        return try {
            val currentClients = _connectedClients.value
            val client = currentClients.find { it.clientId == clientId }
                ?: return Result.failure(IllegalArgumentException("Client not found: $clientId"))
            
            if (!_isHosting.value) {
                return Result.failure(IllegalStateException("Not currently hosting"))
            }
            
            Log.d(TAG, "Requesting host transfer to client: ${client.clientName}")
            
            // Send transfer request via UDP
            val success = udpAudioServer.sendTransferRequest(clientId)
            if (success) {
                Log.d(TAG, "Transfer request sent to ${client.clientName}")
                Result.success(Unit)
            } else {
                Log.e(TAG, "Failed to send transfer request")
                Result.failure(Exception("Failed to send transfer request"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request host transfer", e)
            Result.failure(e)
        }
    }
    
    /**
     * Handle transfer acceptance from a client.
     * This initiates the actual handover process.
     */
    suspend fun handleTransferAccepted(clientId: String, newHostIp: String, newHostPort: Int): Result<Unit> {
        return try {
            val currentClients = _connectedClients.value
            val client = currentClients.find { it.clientId == clientId }
            // Client might not be in our list if they just accepted (logging only)
            
            Log.d(TAG, "Transfer accepted by ${client?.clientName ?: clientId}, redirecting clients to $newHostIp:$newHostPort")
            
            // Broadcast redirect to all other clients
            udpAudioServer.sendRedirectToAllClients(newHostIp, newHostPort)
            
            // Stop hosting after a short delay to allow redirect messages to be sent
            kotlinx.coroutines.delay(500)
            
            // Stop our hosting session
            stopHosting()
            
            Log.d(TAG, "Host transfer complete - stopped hosting")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle transfer acceptance", e)
            Result.failure(e)
        }
    }
    
    /**
     * Handle transfer rejection from a client.
     */
    fun handleTransferRejected(clientId: String) {
        val client = _connectedClients.value.find { it.clientId == clientId }
        Log.d(TAG, "Transfer rejected by ${client?.clientName ?: clientId}")
    }
    
    /**
     * Switch audio source during session
     */
    suspend fun switchAudioSource(newSource: AudioSource): Result<Unit> {
        return try {
            val session = _currentSession.value
            if (session == null || !session.isActive) {
                return Result.failure(IllegalStateException("No active session"))
            }
            
            Log.d(TAG, "Switching audio source to $newSource")
            
            val result = audioStreamManager.switchAudioSource(newSource)
            if (result.isSuccess) {
                _currentSession.value = session.copy(audioSource = newSource)
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch audio source", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get audio level for visualization
     */
    fun getAudioLevel(): StateFlow<Float> {
        return audioStreamManager.getAudioLevel()
    }

    /**
     * Initialize MediaProjection for system audio capture. Delegates to
     * the AudioStreamManager which forwards to AudioCaptureService.
     */
    fun initializeMediaProjection(resultCode: Int, data: Intent): Result<Unit> {
        return audioStreamManager.initializeMediaProjection(resultCode, data)
    }
    
    // Private helper methods
    private suspend fun getLocalIpAddress(): String {
        val ips = networkDiscoveryService.getLocalIpAddresses()
        Log.d(TAG, "Detected local IPs: $ips")
        return ips.firstOrNull() ?: "127.0.0.1"
    }
    
    private fun startHttpApiServer(port: Int): Boolean {
        Log.d(TAG, "Starting HTTP API server on port $port")
        return httpApiServer.startServer(port)
    }
    
    private fun stopHttpApiServer() {
        Log.d(TAG, "Stopping HTTP API server")
        httpApiServer.stopServer()
    }
    
    private fun startDiscoveryBroadcast(session: HostSession) {
        Log.d(TAG, "Starting discovery broadcast for session ${session.sessionId}")
        serviceScope.launch {
            networkDiscoveryService.registerHost(
                hostName = session.hostName,
                port = DEFAULT_PORT,
                userName = session.hostName, // Using hostName as userName for now
                currentClients = session.connectedClients.size,
                maxClients = session.maxClients
            )
        }
    }
    
    private fun stopDiscoveryBroadcast() {
        Log.d(TAG, "Stopping discovery broadcast")
        serviceScope.launch {
            networkDiscoveryService.unregisterHost()
        }
    }
    
    private suspend fun disconnectAllClients(reason: String) {
        val clients = _connectedClients.value
        Log.d(TAG, "Disconnecting all clients: ${clients.size} clients")
        
        clients.forEach { client ->
            serviceScope.launch {
                disconnectClient(client.clientId, reason)
            }
        }
    }
    
    private fun notifyClientDisconnection(clientId: String, reason: String) {
        Log.d(TAG, "Notifying client $clientId of disconnection: $reason")
        // TODO: Send HTTP notification to client
    }
    
    private fun notifyClientKick(clientId: String, reason: String) {
        Log.d(TAG, "Notifying client $clientId of kick: $reason")
        serviceScope.launch {
            val success = udpAudioServer.sendKickCommand(clientId)
            if (success) {
                Log.i(TAG, "Kick notification sent to client $clientId")
            } else {
                Log.w(TAG, "Failed to send kick notification to client $clientId - client may not be connected via UDP")
            }
        }
    }
    
    private fun cleanup() {
        _currentSession.value = null
        _connectedClients.value = emptyList()
        _isHosting.value = false
    }
}

/**
 * Represents transfer events that occurred
 */
sealed class TransferEvent {
    data class Accepted(
        val clientId: String,
        val clientAddress: String,
        val newServerPort: Int
    ) : TransferEvent()
    
    data class Rejected(val clientId: String) : TransferEvent()
}