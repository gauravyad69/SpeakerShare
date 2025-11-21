package io.github.gauravyad69.speakershare.services

import io.github.gauravyad69.speakershare.data.model.*
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
import java.util.UUID

/**
 * Service for managing host broadcasting sessions.
 * Handles session lifecycle, client connections, and host controls.
 */
@Singleton
class HostService @Inject constructor(
    private val audioStreamManager: AudioStreamManager,
    private val httpApiServer: io.github.gauravyad69.speakershare.network.HttpApiServer,
    private val networkDiscoveryService: NetworkDiscoveryService
) {
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val _currentSession = MutableStateFlow<HostSession?>(null)
    val currentSession: StateFlow<HostSession?> = _currentSession.asStateFlow()
    
    private val _connectedClients = MutableStateFlow<List<ClientConnection>>(emptyList())
    val connectedClients: StateFlow<List<ClientConnection>> = _connectedClients.asStateFlow()
    
    private val _isHosting = MutableStateFlow(false)
    val isHosting: StateFlow<Boolean> = _isHosting.asStateFlow()
    
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
            
            val sessionId = UUID.randomUUID().toString()
            val networkInfo = NetworkInfo(
                localIpAddress = getLocalIpAddress(),
                port = DEFAULT_PORT,
                networkInterface = "wlan0", // Default Wi-Fi interface
                isHotspot = false, // TODO: Detect actual hotspot status
                discoveryMethod = DiscoveryMethod.MDNS,
                serviceName = "speakershare-$sessionId"
            )
            
            Log.d(TAG, "Starting host session: $sessionId for $hostName")
            
            val hostSession = HostSession(
                sessionId = sessionId,
                sessionName = hostName, // Use hostName as sessionName for now
                hostName = hostName,
                audioSource = audioSource,
                quality = quality,
                isActive = false, // Will be activated after audio stream starts
                startTime = System.currentTimeMillis(),
                connectedClients = emptyList(),
                networkInfo = networkInfo,
                maxClients = maxClients
            )
            
            // Start audio streaming
            val streamResult = audioStreamManager.startStreaming(
                sessionId, audioSource, quality
            )
            
            if (streamResult.isFailure) {
                return Result.failure(streamResult.exceptionOrNull()!!)
            }
            
            // Start HTTP API server for client communication
            startHttpApiServer(DEFAULT_PORT)
            
            // Start network discovery broadcasting
            startDiscoveryBroadcast(hostSession)
            
            // Update session state
            val activeSession = hostSession.copy(isActive = true)
            _currentSession.value = activeSession
            _isHosting.value = true
            
            Log.d(TAG, "Host session started successfully")
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
        clientIp: String
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
    
    // Private helper methods
    private fun getLocalIpAddress(): String {
        // TODO: Implement actual local IP address detection
        return "192.168.1.100" // Placeholder
    }
    
    private fun startHttpApiServer(port: Int) {
        Log.d(TAG, "Starting HTTP API server on port $port")
        httpApiServer.startServer(port)
    }
    
    private fun stopHttpApiServer() {
        Log.d(TAG, "Stopping HTTP API server")
        httpApiServer.stopServer()
    }
    
    private fun startDiscoveryBroadcast(session: HostSession) {
        Log.d(TAG, "Starting discovery broadcast for session ${session.sessionId}")
        // TODO: Implement discovery broadcast (T028)
    }
    
    private fun stopDiscoveryBroadcast() {
        Log.d(TAG, "Stopping discovery broadcast")
        // TODO: Implement discovery broadcast cleanup
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
        // TODO: Send HTTP notification to client and add to blacklist
    }
    
    private fun cleanup() {
        _currentSession.value = null
        _connectedClients.value = emptyList()
        _isHosting.value = false
    }
}
