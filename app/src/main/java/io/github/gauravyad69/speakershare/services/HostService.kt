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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber
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
    
    // Mutex for thread-safe client list modifications
    private val clientsMutex = Mutex()
    
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
                        Timber.d("Client disconnected from UDP server: ${event.clientId}")
                        // Remove client from our connected clients list (thread-safe)
                        clientsMutex.withLock {
                            val currentClients = _connectedClients.value
                            val updatedClients = currentClients.filter { it.clientId != event.clientId }
                            if (updatedClients.size != currentClients.size) {
                                _connectedClients.value = updatedClients
                                _currentSession.value = _currentSession.value?.copy(connectedClients = updatedClients)
                                Timber.d("Removed client ${event.clientId}, ${updatedClients.size} clients remaining")
                            }
                        }
                    }
                    is io.github.gauravyad69.speakershare.network.UdpServerEvent.TransferAccepted -> {
                        Timber.d("Transfer accepted by ${event.clientId} at ${event.clientAddress}:${event.newServerPort}")
                        _transferEvents.value = TransferEvent.Accepted(
                            event.clientId,
                            event.clientAddress,
                            event.newServerPort
                        )
                        // Auto-handle the transfer if we have a pending request
                        handleTransferAccepted(event.clientId, event.clientAddress, event.newServerPort)
                    }
                    is io.github.gauravyad69.speakershare.network.UdpServerEvent.TransferRejected -> {
                        Timber.d("Transfer rejected by ${event.clientId}")
                        _transferEvents.value = TransferEvent.Rejected(event.clientId)
                        handleTransferRejected(event.clientId)
                    }
                    else -> {}
                }
            }
        }
    }
    
    companion object {
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
            
            Timber.d("Starting host session: $sessionId for $hostName with sampleRate=${effectiveQuality.sampleRate}, encoding=${effectiveQuality.encoding}")
            
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
            
            Timber.d("Host session started successfully with sampleRate=${effectiveQuality.sampleRate}")
            Result.success(activeSession)
            
        } catch (e: Exception) {
            Timber.e("Failed to start hosting", e)
            cleanup()
            Result.failure(e)
        }
    }
    
    /**
     * Stop current hosting session
     */
    suspend fun stopHosting(): Result<Unit> {
        return try {
            Timber.d("Stopping host session")
            
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
            
            Timber.d("Host session stopped successfully")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e("Failed to stop hosting", e)
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
            
            // Thread-safe client list modification
            val (accepted, updatedClients) = clientsMutex.withLock {
                val currentClients = _connectedClients.value
                if (currentClients.size >= session.maxClients) {
                    Timber.w("Client connection rejected: max clients reached")
                    return@withLock Pair(false, currentClients)
                }
                
                if (currentClients.any { it.clientId == clientId }) {
                    Timber.w("Client $clientId already connected")
                    return@withLock Pair(true, currentClients)  // Already connected is success
                }
                
                Timber.d("Accepting client connection: $clientId ($clientName)")
                
                val clientConnection = ClientConnection(
                    clientId = clientId,
                    clientName = clientName,
                    ipAddress = clientIp,
                    connectionTime = System.currentTimeMillis(),
                    status = ConnectionStatus.CONNECTED,
                    audioSettings = ClientAudioSettings(),
                    networkMetrics = NetworkMetrics(latency = 0L, packetLoss = 0.0f, bandwidth = 0L)
                )
                
                val newClients = currentClients + clientConnection
                _connectedClients.value = newClients
                Pair(true, newClients)
            }
            
            if (!accepted) {
                return Result.success(false)
            }
            
            // Register client with UDP audio server for streaming (outside mutex - network I/O)
            try {
                val clientAddress = InetAddress.getByName(clientIp)
                udpAudioServer.addClient(clientId, clientAddress, clientAudioPort)
                Timber.d("Registered client $clientId for UDP audio streaming at $clientIp:$clientAudioPort")
            } catch (e: Exception) {
                Timber.e("Failed to register client with UDP server", e)
                // Continue anyway - WebRTC might still work
            }
            
            // Update session with new client list
            _currentSession.value = session.copy(connectedClients = updatedClients)
            
            Timber.d("Client connected successfully: $clientName (${updatedClients.size} total)")
            Result.success(true)
            
        } catch (e: Exception) {
            Timber.e("Failed to handle client connection", e)
            Result.failure(e)
        }
    }
    
    /**
     * Disconnect a specific client
     */
    suspend fun disconnectClient(clientId: String, reason: String = "Disconnected by host"): Result<Unit> {
        return try {
            // Thread-safe client removal
            val (client, updatedClients) = clientsMutex.withLock {
                val currentClients = _connectedClients.value
                val foundClient = currentClients.find { it.clientId == clientId }
                    ?: return Result.failure(IllegalArgumentException("Client not found: $clientId"))
                
                Timber.d("Disconnecting client: ${foundClient.clientName} - $reason")
                
                val newClients = currentClients.filter { it.clientId != clientId }
                _connectedClients.value = newClients
                Pair(foundClient, newClients)
            }
            
            // Remove from UDP audio server (outside mutex - network I/O)
            udpAudioServer.removeClient(clientId)
            
            // Update session
            _currentSession.value = _currentSession.value?.copy(connectedClients = updatedClients)
            
            // TODO: Send disconnection message to client via HTTP API
            notifyClientDisconnection(clientId, reason)
            
            Timber.d("Client disconnected: ${client.clientName} (${updatedClients.size} remaining)")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e("Failed to disconnect client", e)
            Result.failure(e)
        }
    }
    
    /**
     * Kick a client (prevents reconnection)
     */
    suspend fun kickClient(clientId: String, reason: String = "Kicked by host"): Result<Unit> {
        return try {
            // Thread-safe client removal
            val (client, updatedClients) = clientsMutex.withLock {
                val currentClients = _connectedClients.value
                val foundClient = currentClients.find { it.clientId == clientId }
                    ?: return Result.failure(IllegalArgumentException("Client not found: $clientId"))
                
                Timber.d("Kicking client: ${foundClient.clientName} - $reason")
                
                val newClients = currentClients.filter { it.clientId != clientId }
                _connectedClients.value = newClients
                Pair(foundClient, newClients)
            }
            
            // Remove from UDP audio server (outside mutex - network I/O)
            udpAudioServer.removeClient(clientId)
            
            // Update session
            _currentSession.value = _currentSession.value?.copy(connectedClients = updatedClients)
            
            // TODO: Send kick message to client and add to blacklist
            notifyClientKick(clientId, reason)
            
            Timber.d("Client kicked: ${client.clientName}")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e("Failed to kick client", e)
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
            
            Timber.d("Requesting host transfer to client: ${client.clientName}")
            
            // Send transfer request via UDP
            val success = udpAudioServer.sendTransferRequest(clientId)
            if (success) {
                Timber.d("Transfer request sent to ${client.clientName}")
                Result.success(Unit)
            } else {
                Timber.e("Failed to send transfer request")
                Result.failure(Exception("Failed to send transfer request"))
            }
        } catch (e: Exception) {
            Timber.e("Failed to request host transfer", e)
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
            
            Timber.d("Transfer accepted by ${client?.clientName ?: clientId}, redirecting clients to $newHostIp:$newHostPort")
            
            // Broadcast redirect to all other clients
            udpAudioServer.sendRedirectToAllClients(newHostIp, newHostPort)
            
            // Stop hosting after a short delay to allow redirect messages to be sent
            kotlinx.coroutines.delay(500)
            
            // Stop our hosting session
            stopHosting()
            
            Timber.d("Host transfer complete - stopped hosting")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e("Failed to handle transfer acceptance", e)
            Result.failure(e)
        }
    }
    
    /**
     * Handle transfer rejection from a client.
     */
    fun handleTransferRejected(clientId: String) {
        val client = _connectedClients.value.find { it.clientId == clientId }
        Timber.d("Transfer rejected by ${client?.clientName ?: clientId}")
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
            
            Timber.d("Switching audio source to $newSource")
            
            val result = audioStreamManager.switchAudioSource(newSource)
            if (result.isSuccess) {
                _currentSession.value = session.copy(audioSource = newSource)
            }
            
            result
        } catch (e: Exception) {
            Timber.e("Failed to switch audio source", e)
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
        Timber.d("Detected local IPs: $ips")
        return ips.firstOrNull() ?: "127.0.0.1"
    }
    
    private fun startHttpApiServer(port: Int): Boolean {
        Timber.d("Starting HTTP API server on port $port")
        return httpApiServer.startServer(port)
    }
    
    private fun stopHttpApiServer() {
        Timber.d("Stopping HTTP API server")
        httpApiServer.stopServer()
    }
    
    private fun startDiscoveryBroadcast(session: HostSession) {
        Timber.d("Starting discovery broadcast for session ${session.sessionId}")
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
        Timber.d("Stopping discovery broadcast")
        serviceScope.launch {
            networkDiscoveryService.unregisterHost()
        }
    }
    
    private suspend fun disconnectAllClients(reason: String) {
        val clients = _connectedClients.value
        Timber.d("Disconnecting all clients: ${clients.size} clients")
        
        clients.forEach { client ->
            serviceScope.launch {
                disconnectClient(client.clientId, reason)
            }
        }
    }
    
    private fun notifyClientDisconnection(clientId: String, reason: String) {
        Timber.d("Notifying client $clientId of disconnection: $reason")
        // TODO: Send HTTP notification to client
    }
    
    private fun notifyClientKick(clientId: String, reason: String) {
        Timber.d("Notifying client $clientId of kick: $reason")
        serviceScope.launch {
            val success = udpAudioServer.sendKickCommand(clientId)
            if (success) {
                Timber.i("Kick notification sent to client $clientId")
            } else {
                Timber.w("Failed to send kick notification to client $clientId - client may not be connected via UDP")
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