package io.github.gauravyad69.speakershare.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UDP Audio Server for fallback audio streaming
 * Handles UDP broadcast of audio data to multiple clients
 */
@Singleton
class UdpAudioServer @Inject constructor(
    private val packetHandler: UdpPacketHandler
) {
    companion object {
        private const val TAG = "UdpAudioServer"
        private const val DEFAULT_AUDIO_PORT = 9090
        private const val DEFAULT_DISCOVERY_PORT = 9089
        private const val DISCOVERY_INTERVAL_MS = 5000L
        private const val HEARTBEAT_INTERVAL_MS = 10000L
        private const val CLIENT_TIMEOUT_MS = 30000L
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Server state
    private val isRunning = AtomicBoolean(false)
    private var audioSocket: DatagramSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private val sequenceNumber = AtomicLong(0)
    
    // Session information
    private var sessionId: String = ""
    private var hostName: String = ""
    private var audioPort: Int = DEFAULT_AUDIO_PORT
    private var discoveryPort: Int = DEFAULT_DISCOVERY_PORT
    
    // Connected clients
    private val connectedClients = ConcurrentHashMap<String, UdpClient>()
    private val clientLastSeen = ConcurrentHashMap<String, Long>()
    
    // Jobs for background tasks
    private var discoveryJob: Job? = null
    private var heartbeatJob: Job? = null
    private var clientMonitorJob: Job? = null
    
    // Events
    private val _serverEvents = MutableSharedFlow<UdpServerEvent>()
    val serverEvents: SharedFlow<UdpServerEvent> = _serverEvents.asSharedFlow()
    
    /**
     * Start UDP audio server
     */
    suspend fun startServer(
        sessionId: String,
        hostName: String,
        audioPort: Int = DEFAULT_AUDIO_PORT,
        discoveryPort: Int = DEFAULT_DISCOVERY_PORT
    ): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "Server already running")
            return true
        }
        
        return withContext(Dispatchers.IO) {
            try {
                this@UdpAudioServer.sessionId = sessionId
                this@UdpAudioServer.hostName = hostName
                this@UdpAudioServer.audioPort = audioPort
                this@UdpAudioServer.discoveryPort = discoveryPort
                
                // Create and bind sockets
                audioSocket = DatagramSocket(audioPort).apply {
                    broadcast = true
                    reuseAddress = true
                }
                
                discoverySocket = DatagramSocket(discoveryPort).apply {
                    broadcast = true
                    reuseAddress = true
                }
                
                isRunning.set(true)
                
                // Start background tasks
                startDiscoveryBroadcast()
                startHeartbeat()
                startClientMonitoring()
                
                scope.launch {
                    _serverEvents.emit(UdpServerEvent.ServerStarted(audioPort, discoveryPort))
                }
                
                Log.i(TAG, "UDP server started on audio port $audioPort, discovery port $discoveryPort")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start UDP server", e)
                cleanup()
                
                scope.launch {
                    _serverEvents.emit(UdpServerEvent.ServerError("Failed to start server: ${e.message}"))
                }
                false
            }
        }
    }
    
    /**
     * Stop UDP audio server
     */
    suspend fun stopServer() {
        withContext(Dispatchers.IO) {
            if (!isRunning.get()) {
                return@withContext
            }
            
            isRunning.set(false)
            
            // Stop background tasks
            discoveryJob?.cancel()
            heartbeatJob?.cancel()
            clientMonitorJob?.cancel()
            
            // Clear clients
            connectedClients.clear()
            clientLastSeen.clear()
            
            cleanup()
            
            scope.launch {
                _serverEvents.emit(UdpServerEvent.ServerStopped)
            }
            
            Log.i(TAG, "UDP server stopped")
        }
    }
    
    /**
     * Broadcast audio data to all connected clients
     */
    suspend fun broadcastAudio(audioData: ByteArray): Boolean {
        if (!isRunning.get() || connectedClients.isEmpty()) {
            // Log occasionally to avoid spamming
            if (sequenceNumber.get() % 100 == 0L) {
                Log.d(TAG, "Not broadcasting: isRunning=${isRunning.get()}, connectedClients=${connectedClients.size}")
            }
            return false
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val seqNum = sequenceNumber.incrementAndGet()
                val timestamp = System.currentTimeMillis()
                
                // Create audio packets (may be fragmented)
                val packets = packetHandler.createAudioPacket(
                    sessionId = sessionId,
                    sequenceNumber = seqNum,
                    timestamp = timestamp,
                    audioData = audioData
                )
                
                // Debug first few packets to verify format
                if (seqNum <= 5) {
                    packets.forEachIndexed { index, packetData ->
                        val hexDump = packetData.take(32).joinToString(" ") { String.format("%02X", it) }
                        Log.d(TAG, "Packet $seqNum/$index before send (size=${packetData.size}): $hexDump...")
                    }
                }
                
                // Send to all connected clients
                val socket = audioSocket ?: return@withContext false
                var successCount = 0
                
                connectedClients.values.forEach { client ->
                    try {
                        packets.forEach { packetData ->
                            val packet = DatagramPacket(
                                packetData,
                                packetData.size,
                                client.address,
                                client.audioPort
                            )
                            socket.send(packet)
                        }
                        successCount++
                        if (seqNum % 100 == 0L) {
                            Log.d(TAG, "Sent audio packet seq=$seqNum to ${client.address}:${client.audioPort}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send audio to client ${client.clientId}", e)
                        // Remove problematic client
                        removeClient(client.clientId)
                    }
                }
                
                successCount > 0
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast audio", e)
                false
            }
        }
    }
    
    /**
     * Add a client to the server
     */
    fun addClient(clientId: String, clientAddress: InetAddress, clientPort: Int) {
        val client = UdpClient(
            clientId = clientId,
            address = clientAddress,
            audioPort = clientPort,
            connectedAt = System.currentTimeMillis()
        )
        
        connectedClients[clientId] = client
        clientLastSeen[clientId] = System.currentTimeMillis()
        
        scope.launch {
            _serverEvents.emit(UdpServerEvent.ClientConnected(clientId, clientAddress.hostAddress))
        }
        
        Log.d(TAG, "Client connected: $clientId from ${clientAddress.hostAddress}:$clientPort")
    }
    
    /**
     * Remove a client from the server
     */
    fun removeClient(clientId: String) {
        connectedClients.remove(clientId)?.let { client ->
            clientLastSeen.remove(clientId)
            
            scope.launch {
                _serverEvents.emit(UdpServerEvent.ClientDisconnected(clientId))
            }
            
            Log.d(TAG, "Client disconnected: $clientId")
        }
    }
    
    /**
     * Handle control message from client
     */
    suspend fun handleControlMessage(packet: UdpPacket, senderAddress: InetAddress) {
        val controlCommand = packetHandler.parseControlCommand(packet) ?: return
        
        when (controlCommand.command) {
            UdpPacketHandler.CONTROL_CONNECT -> {
                handleClientConnect(packet.sessionId, senderAddress)
            }
            
            UdpPacketHandler.CONTROL_DISCONNECT -> {
                removeClient(packet.sessionId)
            }
            
            UdpPacketHandler.CONTROL_ACK -> {
                // Update client last seen time
                clientLastSeen[packet.sessionId] = System.currentTimeMillis()
            }
            
            else -> {
                Log.w(TAG, "Unknown control command: ${controlCommand.command}")
            }
        }
    }
    
    /**
     * Handle client connection request
     */
    private suspend fun handleClientConnect(clientId: String, clientAddress: InetAddress) {
        if (!connectedClients.containsKey(clientId)) {
            addClient(clientId, clientAddress, audioPort)
            
            // Send acknowledgment
            val ackPacket = packetHandler.createControlPacket(
                sessionId = sessionId,
                sequenceNumber = sequenceNumber.incrementAndGet(),
                command = UdpPacketHandler.CONTROL_ACK
            )
            
            try {
                val socket = audioSocket ?: return
                val packet = DatagramPacket(
                    ackPacket,
                    ackPacket.size,
                    clientAddress,
                    audioPort
                )
                socket.send(packet)
                
                Log.d(TAG, "Sent connection acknowledgment to $clientId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send acknowledgment to $clientId", e)
            }
        }
    }
    
    /**
     * Start discovery broadcast
     */
    private fun startDiscoveryBroadcast() {
        discoveryJob = scope.launch {
            val discoveryPacket = packetHandler.createDiscoveryPacket(hostName, audioPort)
            val broadcastAddress = InetAddress.getByName("255.255.255.255")
            
            while (isRunning.get()) {
                try {
                    val socket = discoverySocket ?: break
                    val packet = DatagramPacket(
                        discoveryPacket,
                        discoveryPacket.size,
                        broadcastAddress,
                        discoveryPort
                    )
                    socket.send(packet)
                    
                    Log.v(TAG, "Sent discovery broadcast")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send discovery broadcast", e)
                }
                
                delay(DISCOVERY_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Start heartbeat to clients
     */
    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isRunning.get()) {
                try {
                    val heartbeatPacket = packetHandler.createHeartbeatPacket(sessionId)
                    val socket = audioSocket ?: break
                    
                    connectedClients.values.forEach { client ->
                        try {
                            val packet = DatagramPacket(
                                heartbeatPacket,
                                heartbeatPacket.size,
                                client.address,
                                client.audioPort
                            )
                            socket.send(packet)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send heartbeat to ${client.clientId}", e)
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send heartbeats", e)
                }
                
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Monitor client connections and remove stale ones
     */
    private fun startClientMonitoring() {
        clientMonitorJob = scope.launch {
            while (isRunning.get()) {
                val currentTime = System.currentTimeMillis()
                val staleClients = mutableListOf<String>()
                
                clientLastSeen.forEach { (clientId, lastSeen) ->
                    if (currentTime - lastSeen > CLIENT_TIMEOUT_MS) {
                        staleClients.add(clientId)
                    }
                }
                
                staleClients.forEach { clientId ->
                    Log.w(TAG, "Removing stale client: $clientId")
                    removeClient(clientId)
                }
                
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Get connected clients info
     */
    fun getConnectedClients(): List<UdpClient> {
        return connectedClients.values.toList()
    }
    
    /**
     * Get connected clients count
     */
    fun getConnectedClientsCount(): Int = connectedClients.size
    
    /**
     * Check if server is running
     */
    fun isServerRunning(): Boolean = isRunning.get()
    
    /**
     * Get server info
     */
    fun getServerInfo(): UdpServerInfo {
        return UdpServerInfo(
            sessionId = sessionId,
            hostName = hostName,
            audioPort = audioPort,
            discoveryPort = discoveryPort,
            connectedClients = connectedClients.size,
            isRunning = isRunning.get()
        )
    }
    
    /**
     * Cleanup resources
     */
    private fun cleanup() {
        try {
            audioSocket?.close()
            discoverySocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing sockets", e)
        } finally {
            audioSocket = null
            discoverySocket = null
        }
    }
}

/**
 * UDP Client information
 */
data class UdpClient(
    val clientId: String,
    val address: InetAddress,
    val audioPort: Int,
    val connectedAt: Long
)

/**
 * UDP Server information
 */
data class UdpServerInfo(
    val sessionId: String,
    val hostName: String,
    val audioPort: Int,
    val discoveryPort: Int,
    val connectedClients: Int,
    val isRunning: Boolean
)

/**
 * UDP Server events
 */
sealed class UdpServerEvent {
    data class ServerStarted(val audioPort: Int, val discoveryPort: Int) : UdpServerEvent()
    object ServerStopped : UdpServerEvent()
    data class ClientConnected(val clientId: String, val clientAddress: String) : UdpServerEvent()
    data class ClientDisconnected(val clientId: String) : UdpServerEvent()
    data class ServerError(val message: String) : UdpServerEvent()
}