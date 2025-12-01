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
        private const val CLIENT_AUDIO_PORT = 9091  // Port where clients listen for audio
        private const val DISCOVERY_INTERVAL_MS = 5000L
        private const val HEARTBEAT_INTERVAL_MS = 10000L
        private const val CLIENT_TIMEOUT_MS = 30000L  // 30 seconds - clients send heartbeats every 10s
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
    private var controlReceiverJob: Job? = null
    
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
                startControlReceiver()  // Listen for client heartbeats
                
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
            controlReceiverJob?.cancel()
            
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
     * Add a client to the server.
     * HTTP-connected clients are passive receivers - they don't send heartbeats.
     * The lastSeen timestamp is refreshed on successful audio broadcasts.
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
        
        Log.i(TAG, "Client connected: $clientId from ${clientAddress.hostAddress}:$clientPort (total: ${connectedClients.size})")
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
     * Send kick command to a specific client
     * This notifies the client that they have been kicked and removes them from the server
     */
    suspend fun sendKickCommand(clientId: String): Boolean {
        val client = connectedClients[clientId] ?: return false
        
        return withContext(Dispatchers.IO) {
            try {
                val kickPacket = packetHandler.createControlPacket(
                    sessionId = sessionId,
                    sequenceNumber = sequenceNumber.incrementAndGet(),
                    command = UdpPacketHandler.CONTROL_KICK
                )
                
                val socket = audioSocket ?: return@withContext false
                val packet = DatagramPacket(
                    kickPacket,
                    kickPacket.size,
                    client.address,
                    client.audioPort
                )
                socket.send(packet)
                
                // Remove client from connected list
                removeClient(clientId)
                
                Log.i(TAG, "Sent kick command to client $clientId at ${client.address.hostAddress}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send kick command to $clientId", e)
                false
            }
        }
    }
    
    /**
     * Send host transfer request to a specific client
     */
    suspend fun sendTransferRequest(clientId: String): Boolean {
        val client = connectedClients[clientId] ?: return false
        
        return withContext(Dispatchers.IO) {
            try {
                val transferPacket = packetHandler.createControlPacket(
                    sessionId = sessionId,
                    sequenceNumber = sequenceNumber.incrementAndGet(),
                    command = UdpPacketHandler.CONTROL_TRANSFER_REQUEST
                )
                
                val socket = audioSocket ?: return@withContext false
                val packet = DatagramPacket(
                    transferPacket,
                    transferPacket.size,
                    client.address,
                    client.audioPort
                )
                socket.send(packet)
                
                Log.i(TAG, "Sent transfer request to client $clientId at ${client.address.hostAddress}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send transfer request to $clientId", e)
                false
            }
        }
    }
    
    /**
     * Send redirect command to all clients to connect to new host
     */
    suspend fun sendRedirectToAllClients(newHostIp: String, newHostPort: Int) {
        withContext(Dispatchers.IO) {
            // Encode new host IP and port
            val ipParts = newHostIp.split(".")
            val redirectData = byteArrayOf(
                ipParts[0].toInt().toByte(),
                ipParts[1].toInt().toByte(),
                ipParts[2].toInt().toByte(),
                ipParts[3].toInt().toByte(),
                ((newHostPort shr 8) and 0xFF).toByte(),
                (newHostPort and 0xFF).toByte()
            )
            
            val redirectPacket = packetHandler.createControlPacket(
                sessionId = sessionId,
                sequenceNumber = sequenceNumber.incrementAndGet(),
                command = UdpPacketHandler.CONTROL_TRANSFER_REDIRECT,
                data = redirectData
            )
            
            val socket = audioSocket ?: return@withContext
            
            connectedClients.values.forEach { client ->
                try {
                    val packet = DatagramPacket(
                        redirectPacket,
                        redirectPacket.size,
                        client.address,
                        client.audioPort
                    )
                    socket.send(packet)
                    Log.d(TAG, "Sent redirect to ${client.clientId} -> $newHostIp:$newHostPort")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send redirect to ${client.clientId}", e)
                }
            }
            
            Log.i(TAG, "Sent redirect to all ${connectedClients.size} clients -> $newHostIp:$newHostPort")
        }
    }
    
    /**
     * Get list of all connected clients (for transfer)
     */
    fun getConnectedClientsList(): List<UdpClient> = connectedClients.values.toList()

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
                val clientSessionId = packet.sessionId
                if (clientLastSeen.containsKey(clientSessionId)) {
                    clientLastSeen[clientSessionId] = System.currentTimeMillis()
                    Log.v(TAG, "Heartbeat received from client $clientSessionId")
                } else {
                    // Try to find by address if session ID doesn't match exactly
                    val matchingClient = connectedClients.entries.find { 
                        it.value.address == senderAddress 
                    }
                    if (matchingClient != null) {
                        clientLastSeen[matchingClient.key] = System.currentTimeMillis()
                        Log.d(TAG, "Heartbeat from ${senderAddress.hostAddress} matched to ${matchingClient.key}")
                    } else {
                        Log.w(TAG, "Heartbeat from unknown client: $clientSessionId from ${senderAddress.hostAddress}")
                    }
                }
            }
            
            UdpPacketHandler.CONTROL_TRANSFER_ACCEPT -> {
                // Client accepted host transfer - parse their new server port
                val newServerPort = if (controlCommand.data.size >= 2) {
                    ((controlCommand.data[0].toInt() and 0xFF) shl 8) or (controlCommand.data[1].toInt() and 0xFF)
                } else {
                    9090 // Default port
                }
                Log.i(TAG, "Client ${packet.sessionId} accepted host transfer, new server port: $newServerPort")
                scope.launch {
                    _serverEvents.emit(UdpServerEvent.TransferAccepted(
                        packet.sessionId,
                        senderAddress.hostAddress,
                        newServerPort
                    ))
                }
            }
            
            UdpPacketHandler.CONTROL_TRANSFER_REJECT -> {
                Log.i(TAG, "Client ${packet.sessionId} rejected host transfer")
                scope.launch {
                    _serverEvents.emit(UdpServerEvent.TransferRejected(packet.sessionId))
                }
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
        // Don't register ourselves as a client
        if (clientAddress.isLoopbackAddress || clientAddress.hostAddress == getLocalIpAddress()) {
            Log.w(TAG, "Ignoring self-connection from ${clientAddress.hostAddress}")
            return
        }
        
        if (!connectedClients.containsKey(clientId)) {
            // Use CLIENT_AUDIO_PORT (9091) - the port where clients listen for audio
            addClient(clientId, clientAddress, CLIENT_AUDIO_PORT)
            
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
                    CLIENT_AUDIO_PORT
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
     * Start receiver for control packets (heartbeats from clients)
     */
    private fun startControlReceiver() {
        controlReceiverJob = scope.launch {
            val buffer = ByteArray(256)  // Small buffer for control packets
            Log.d(TAG, "Control receiver started, listening for client heartbeats")
            
            while (isRunning.get()) {
                try {
                    val socket = audioSocket ?: break
                    socket.soTimeout = 1000  // 1 second timeout
                    
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    
                    val rawData = packet.data.copyOfRange(0, packet.length)
                    val udpPacket = packetHandler.parsePacket(rawData)
                    
                    if (udpPacket?.isControlPacket() == true) {
                        handleControlMessage(udpPacket, packet.address)
                    }
                    
                } catch (e: SocketTimeoutException) {
                    // Expected, continue listening
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.w(TAG, "Control receiver error: ${e.message}")
                    }
                }
            }
            Log.d(TAG, "Control receiver stopped")
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
                    val timeSinceLastSeen = currentTime - lastSeen
                    if (timeSinceLastSeen > CLIENT_TIMEOUT_MS) {
                        staleClients.add(clientId)
                        Log.d(TAG, "Client $clientId timed out after ${timeSinceLastSeen/1000}s")
                    }
                }
                
                staleClients.forEach { clientId ->
                    Log.w(TAG, "Removing stale client: $clientId (remaining: ${connectedClients.size - 1})")
                    removeClient(clientId)
                }
                
                // Log client status periodically
                if (connectedClients.isNotEmpty()) {
                    Log.d(TAG, "Connected clients: ${connectedClients.size}, monitoring active")
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
     * Get local IP address to avoid self-connection
     */
    private fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get local IP address", e)
            null
        }
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
    
    // Host transfer events
    data class TransferAccepted(val clientId: String, val clientAddress: String, val newServerPort: Int) : UdpServerEvent()
    data class TransferRejected(val clientId: String) : UdpServerEvent()
}