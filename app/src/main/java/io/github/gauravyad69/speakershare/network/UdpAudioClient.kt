package io.github.gauravyad69.speakershare.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UDP Audio Client for receiving audio streams
 * Handles UDP discovery, connection to host, and audio data reception
 */
@Singleton
class UdpAudioClient @Inject constructor(
    private val packetHandler: UdpPacketHandler
) {
    companion object {
        private const val TAG = "UdpAudioClient"
        private const val DISCOVERY_TIMEOUT_MS = 10000L
        private const val CONNECTION_TIMEOUT_MS = 5000L
        private const val HEARTBEAT_INTERVAL_MS = 10000L  // Send heartbeat every 10 seconds
        private const val PACKET_TIMEOUT_MS = 1000L
        private const val MAX_PACKET_BUFFER_SIZE = 100
        private const val HOST_AUDIO_PORT = 9090  // Default host port for sending heartbeats
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Client state
    private val isConnected = AtomicBoolean(false)
    private val isDiscovering = AtomicBoolean(false)
    private var audioSocket: DatagramSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private val sequenceNumber = AtomicLong(0)
    
    // Connection info
    private var hostAddress: InetAddress? = null
    private var hostAudioPort: Int = 0
    private var clientId: String = ""
    private var sessionId: String = ""
    
    // Audio packet management
    private val audioPacketBuffer = ConcurrentHashMap<Long, MutableMap<Int, UdpPacket>>()
    private val lastReceivedSequence = AtomicLong(0)
    
    // Jobs for background tasks
    private var discoveryJob: Job? = null
    private var receiverJob: Job? = null
    private var heartbeatJob: Job? = null
    private var bufferCleanupJob: Job? = null
    
    // Events
    private val _clientEvents = MutableSharedFlow<UdpClientEvent>()
    val clientEvents: SharedFlow<UdpClientEvent> = _clientEvents.asSharedFlow()
    
    /**
     * Start discovery for available hosts
     */
    suspend fun startDiscovery(discoveryPort: Int = 9089): Boolean {
        if (isDiscovering.get()) {
            Log.w(TAG, "Discovery already in progress")
            return true
        }
        
        return withContext(Dispatchers.IO) {
            try {
                discoverySocket = DatagramSocket(discoveryPort).apply {
                    broadcast = true
                    reuseAddress = true
                    soTimeout = 1000 // 1 second timeout for receive
                }
                
                isDiscovering.set(true)
                startDiscoveryListener()
                
                scope.launch {
                    _clientEvents.emit(UdpClientEvent.DiscoveryStarted)
                }
                
                Log.i(TAG, "Discovery started on port $discoveryPort")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start discovery", e)
                
                scope.launch {
                    _clientEvents.emit(UdpClientEvent.DiscoveryError("Failed to start discovery: ${e.message}"))
                }
                false
            }
        }
    }
    
    /**
     * Start listening for incoming audio on a specific port.
     * Used when the host already knows about this client (via HTTP connection).
     * The host will send audio directly to this port.
     */
    suspend fun startListening(listenPort: Int, providedClientId: String? = null): Boolean {
        Log.d(TAG, "startListening called with port $listenPort, clientId=$providedClientId, isConnected=${isConnected.get()}")
        if (isConnected.get()) {
            Log.w(TAG, "Already connected/listening")
            return true
        }
        
        return withContext(Dispatchers.IO) {
            try {
                clientId = providedClientId ?: "client_${System.currentTimeMillis()}"
                Log.d(TAG, "Creating DatagramSocket on port $listenPort with clientId=$clientId")
                
                // Create audio socket bound to the specified port
                audioSocket = DatagramSocket(listenPort).apply {
                    reuseAddress = true
                    soTimeout = 1000 // 1 second timeout for receive
                }
                
                isConnected.set(true)
                startAudioReceiver()
                startBufferCleanup()
                
                scope.launch {
                    _clientEvents.emit(UdpClientEvent.Connected("0.0.0.0", listenPort))
                }
                
                Log.i(TAG, "Started listening for audio on port $listenPort")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening on port $listenPort", e)
                cleanup()
                
                scope.launch {
                    _clientEvents.emit(UdpClientEvent.ConnectionError("Failed to listen: ${e.message}"))
                }
                false
            }
        }
    }
    
    /**
     * Stop discovery
     */
    suspend fun stopDiscovery() {
        withContext(Dispatchers.IO) {
            isDiscovering.set(false)
            discoveryJob?.cancel()
            
            try {
                discoverySocket?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing discovery socket", e)
            } finally {
                discoverySocket = null
            }
            
            scope.launch {
                _clientEvents.emit(UdpClientEvent.DiscoveryStopped)
            }
            
            Log.i(TAG, "Discovery stopped")
        }
    }
    
    /**
     * Connect to a specific host
     */
    suspend fun connectToHost(hostIp: String, audioPort: Int): Boolean {
        if (isConnected.get()) {
            Log.w(TAG, "Already connected to a host")
            return true
        }
        
        return withContext(Dispatchers.IO) {
            try {
                hostAddress = InetAddress.getByName(hostIp)
                hostAudioPort = audioPort
                clientId = "client_${System.currentTimeMillis()}"
                
                // Create audio socket
                audioSocket = DatagramSocket().apply {
                    reuseAddress = true
                    soTimeout = 1000 // 1 second timeout for receive
                }
                
                // Send connection request
                val connectPacket = packetHandler.createControlPacket(
                    sessionId = clientId,
                    sequenceNumber = sequenceNumber.incrementAndGet(),
                    command = UdpPacketHandler.CONTROL_CONNECT
                )
                
                val packet = DatagramPacket(
                    connectPacket,
                    connectPacket.size,
                    hostAddress,
                    audioPort
                )
                
                audioSocket?.send(packet)
                
                // Wait for connection acknowledgment
                val ackReceived = waitForConnectionAck()
                
                if (ackReceived) {
                    isConnected.set(true)
                    startAudioReceiver()
                    startHeartbeat()
                    startBufferCleanup()
                    
                    scope.launch {
                        _clientEvents.emit(UdpClientEvent.Connected(hostIp, audioPort))
                    }
                    
                    Log.i(TAG, "Connected to host $hostIp:$audioPort")
                    true
                } else {
                    cleanup()
                    
                    scope.launch {
                        _clientEvents.emit(UdpClientEvent.ConnectionError("Connection timeout"))
                    }
                    false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to host", e)
                cleanup()
                
                scope.launch {
                    _clientEvents.emit(UdpClientEvent.ConnectionError("Failed to connect: ${e.message}"))
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
            if (!isConnected.get()) {
                return@withContext
            }
            
            try {
                // Send disconnect message
                val disconnectPacket = packetHandler.createControlPacket(
                    sessionId = clientId,
                    sequenceNumber = sequenceNumber.incrementAndGet(),
                    command = UdpPacketHandler.CONTROL_DISCONNECT
                )
                
                hostAddress?.let { address ->
                    val packet = DatagramPacket(
                        disconnectPacket,
                        disconnectPacket.size,
                        address,
                        hostAudioPort
                    )
                    audioSocket?.send(packet)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error sending disconnect message", e)
            }
            
            isConnected.set(false)
            
            // Stop background tasks
            receiverJob?.cancel()
            heartbeatJob?.cancel()
            bufferCleanupJob?.cancel()
            
            // Clear buffers
            audioPacketBuffer.clear()
            
            cleanup()
            
            scope.launch {
                _clientEvents.emit(UdpClientEvent.Disconnected)
            }
            
            Log.i(TAG, "Disconnected from host")
        }
    }
    
    /**
     * Start discovery listener
     */
    private fun startDiscoveryListener() {
        discoveryJob = scope.launch {
            val buffer = ByteArray(1024)
            
            while (isDiscovering.get()) {
                try {
                    val socket = discoverySocket ?: break
                    val packet = DatagramPacket(buffer, buffer.size)
                    
                    socket.receive(packet)
                    
                    val udpPacket = packetHandler.parsePacket(
                        packet.data.copyOfRange(0, packet.length)
                    )
                    
                    if (udpPacket?.isDiscoveryPacket() == true) {
                        val discoveryInfo = packetHandler.parseDiscoveryInfo(udpPacket)
                        
                        discoveryInfo?.let { info ->
                            scope.launch {
                                _clientEvents.emit(
                                    UdpClientEvent.HostDiscovered(
                                        hostName = info.hostName,
                                        hostAddress = packet.address.hostAddress,
                                        audioPort = info.port
                                    )
                                )
                            }
                        }
                    }
                    
                } catch (e: SocketTimeoutException) {
                    // Expected timeout, continue listening
                } catch (e: Exception) {
                    if (isDiscovering.get()) {
                        Log.w(TAG, "Discovery listener error", e)
                    }
                }
            }
        }
    }
    
    /**
     * Wait for connection acknowledgment
     */
    private suspend fun waitForConnectionAck(): Boolean {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val buffer = ByteArray(1024)
            
            while (System.currentTimeMillis() - startTime < CONNECTION_TIMEOUT_MS) {
                try {
                    val socket = audioSocket ?: return@withContext false
                    val packet = DatagramPacket(buffer, buffer.size)
                    
                    socket.receive(packet)
                    
                    val udpPacket = packetHandler.parsePacket(
                        packet.data.copyOfRange(0, packet.length)
                    )
                    
                    if (udpPacket?.isControlPacket() == true) {
                        val controlCommand = packetHandler.parseControlCommand(udpPacket)
                        
                        if (controlCommand?.command == UdpPacketHandler.CONTROL_ACK) {
                            sessionId = udpPacket.sessionId
                            return@withContext true
                        }
                    }
                    
                } catch (e: SocketTimeoutException) {
                    // Expected timeout, continue waiting
                } catch (e: Exception) {
                    Log.w(TAG, "Error waiting for connection ack", e)
                    break
                }
            }
            
            false
        }
    }
    
    /**
     * Start audio receiver
     */
    private fun startAudioReceiver() {
        Log.d(TAG, "Starting audio receiver")
        receiverJob = scope.launch {
            val buffer = ByteArray(2048) // Larger buffer for audio data
            var packetCount = 0
            var heartbeatStarted = false
            
            while (isConnected.get()) {
                try {
                    val socket = audioSocket ?: break
                    val packet = DatagramPacket(buffer, buffer.size)
                    
                    socket.receive(packet)
                    packetCount++
                    
                    // Capture host address from first packet to enable heartbeats
                    if (hostAddress == null && packet.address != null) {
                        hostAddress = packet.address
                        hostAudioPort = HOST_AUDIO_PORT  // Use standard host port for heartbeats
                        Log.i(TAG, "Captured host address: ${packet.address.hostAddress}:$hostAudioPort")
                    }
                    
                    // Start heartbeat after we know the host address
                    if (!heartbeatStarted && hostAddress != null) {
                        startHeartbeat()
                        heartbeatStarted = true
                        Log.i(TAG, "Started heartbeat to host")
                    }
                    
                    val rawData = packet.data.copyOfRange(0, packet.length)
                    if (packetCount <= 3) {
                        // Log first few packets in detail
                        val hexDump = rawData.take(32).joinToString(" ") { String.format("%02X", it) }
                        Log.d(TAG, "Raw packet #$packetCount: size=${packet.length}, hex=$hexDump...")
                    }
                    if (packetCount % 50 == 0) {
                        Log.d(TAG, "Received $packetCount packets, last from ${packet.address}:${packet.port}, size=${packet.length}")
                    }
                    
                    val udpPacket = packetHandler.parsePacket(rawData)
                    
                    when {
                        udpPacket?.isAudioPacket() == true -> {
                            if (packetCount <= 5) {
                                Log.d(TAG, "Received AAC packet #$packetCount")
                            }
                            handleAudioPacket(udpPacket, isPcm = false)
                        }
                        udpPacket?.isPcmAudioPacket() == true -> {
                            if (packetCount <= 5) {
                                Log.d(TAG, "Received PCM packet #$packetCount (raw audio, bypassing decoder)")
                            }
                            handleAudioPacket(udpPacket, isPcm = true)
                        }
                        udpPacket?.isHeartbeatPacket() == true -> {
                            // Host is alive, no action needed
                            Log.v(TAG, "Received heartbeat from host")
                        }
                        udpPacket?.isControlPacket() == true -> {
                            handleControlPacket(udpPacket)
                        }
                        else -> {
                            Log.d(TAG, "Received unknown packet type: ${udpPacket?.packetType}")
                        }
                    }
                    
                } catch (e: SocketTimeoutException) {
                    // Expected timeout, continue listening
                } catch (e: Exception) {
                    if (isConnected.get()) {
                        Log.w(TAG, "Audio receiver error", e)
                        scope.launch {
                            _clientEvents.emit(UdpClientEvent.ReceiveError("Audio receiver error: ${e.message}"))
                        }
                    }
                }
            }
            Log.d(TAG, "Audio receiver stopped, total packets received: $packetCount")
        }
    }
    
    /**
     * Handle received audio packet
     */
    private suspend fun handleAudioPacket(packet: UdpPacket, isPcm: Boolean) {
        // Handle fragmented packets
        if (packet.totalFragments > 1) {
            // Store fragment in buffer
            val fragmentMap = audioPacketBuffer.getOrPut(packet.sequenceNumber) { 
                ConcurrentHashMap<Int, UdpPacket>() 
            }
            
            fragmentMap[packet.fragmentIndex] = packet
            
            // Check if all fragments received
            if (fragmentMap.size == packet.totalFragments) {
                // Reconstruct complete audio data
                val completeAudioData = reconstructAudioData(fragmentMap, packet.totalFragments)
                audioPacketBuffer.remove(packet.sequenceNumber)
                
                // Emit complete audio data - use PCM event for raw audio, AAC event for encoded
                scope.launch {
                    if (isPcm) {
                        _clientEvents.emit(UdpClientEvent.PcmDataReceived(completeAudioData, packet.timestamp))
                    } else {
                        _clientEvents.emit(UdpClientEvent.AudioDataReceived(completeAudioData, packet.timestamp))
                    }
                }
            }
        } else {
            // Single packet, emit directly
            scope.launch {
                if (isPcm) {
                    _clientEvents.emit(UdpClientEvent.PcmDataReceived(packet.payload, packet.timestamp))
                } else {
                    _clientEvents.emit(UdpClientEvent.AudioDataReceived(packet.payload, packet.timestamp))
                }
            }
        }
        
        lastReceivedSequence.set(packet.sequenceNumber)
    }
    
    /**
     * Handle control packet
     */
    private suspend fun handleControlPacket(packet: UdpPacket) {
        val controlCommand = packetHandler.parseControlCommand(packet) ?: return
        
        when (controlCommand.command) {
            UdpPacketHandler.CONTROL_DISCONNECT -> {
                Log.i(TAG, "Received disconnect command from host")
                disconnect()
            }
            
            UdpPacketHandler.CONTROL_KICK -> {
                Log.i(TAG, "Received kick command from host - you have been kicked!")
                scope.launch {
                    _clientEvents.emit(UdpClientEvent.Kicked)
                }
                disconnect()
            }
            
            UdpPacketHandler.CONTROL_MUTE -> {
                scope.launch {
                    _clientEvents.emit(UdpClientEvent.HostMuted)
                }
            }
            
            UdpPacketHandler.CONTROL_TRANSFER_REQUEST -> {
                Log.i(TAG, "Received host transfer request!")
                val fromAddress = hostAddress?.hostAddress ?: "unknown"
                scope.launch {
                    _clientEvents.emit(UdpClientEvent.HostTransferRequested(fromAddress))
                }
            }
            
            UdpPacketHandler.CONTROL_TRANSFER_REDIRECT -> {
                // Parse new host info from payload
                if (controlCommand.data.size >= 6) {
                    val newHostIp = "${controlCommand.data[0].toInt() and 0xFF}.${controlCommand.data[1].toInt() and 0xFF}.${controlCommand.data[2].toInt() and 0xFF}.${controlCommand.data[3].toInt() and 0xFF}"
                    val newHostPort = ((controlCommand.data[4].toInt() and 0xFF) shl 8) or (controlCommand.data[5].toInt() and 0xFF)
                    Log.i(TAG, "Received redirect to new host: $newHostIp:$newHostPort")
                    scope.launch {
                        _clientEvents.emit(UdpClientEvent.HostTransferRedirect(newHostIp, newHostPort))
                    }
                }
            }
            
            else -> {
                Log.w(TAG, "Unknown control command: ${controlCommand.command}")
            }
        }
    }
    
    /**
     * Reconstruct audio data from fragments
     */
    private fun reconstructAudioData(fragments: Map<Int, UdpPacket>, totalFragments: Int): ByteArray {
        val totalSize = fragments.values.sumOf { it.payload.size }
        val audioData = ByteArray(totalSize)
        var offset = 0
        
        for (i in 0 until totalFragments) {
            val fragment = fragments[i]
            if (fragment != null) {
                System.arraycopy(fragment.payload, 0, audioData, offset, fragment.payload.size)
                offset += fragment.payload.size
            }
        }
        
        return audioData
    }
    
    /**
     * Start heartbeat to host
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()  // Cancel any existing heartbeat job
        heartbeatJob = scope.launch {
            Log.d(TAG, "Heartbeat job started, will send every ${HEARTBEAT_INTERVAL_MS/1000}s to $hostAddress:$hostAudioPort")
            while (isConnected.get()) {
                try {
                    val ackPacket = packetHandler.createControlPacket(
                        sessionId = clientId,
                        sequenceNumber = sequenceNumber.incrementAndGet(),
                        command = UdpPacketHandler.CONTROL_ACK
                    )
                    
                    hostAddress?.let { address ->
                        val packet = DatagramPacket(
                            ackPacket,
                            ackPacket.size,
                            address,
                            hostAudioPort
                        )
                        audioSocket?.send(packet)
                        Log.v(TAG, "Sent heartbeat to ${address.hostAddress}:$hostAudioPort")
                    }
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send heartbeat", e)
                }
                
                delay(HEARTBEAT_INTERVAL_MS)
            }
            Log.d(TAG, "Heartbeat job stopped")
        }
    }
    
    /**
     * Start buffer cleanup job
     */
    private fun startBufferCleanup() {
        bufferCleanupJob = scope.launch {
            while (isConnected.get()) {
                val currentTime = System.currentTimeMillis()
                val currentSeq = lastReceivedSequence.get()
                
                // Remove old incomplete fragments
                val toRemove = audioPacketBuffer.keys.filter { seq ->
                    seq < currentSeq - MAX_PACKET_BUFFER_SIZE
                }
                
                toRemove.forEach { seq ->
                    audioPacketBuffer.remove(seq)
                }
                
                delay(PACKET_TIMEOUT_MS)
            }
        }
    }
    
    /**
     * Send volume control command
     */
    suspend fun sendVolumeControl(volume: Float) {
        if (!isConnected.get()) return
        
        withContext(Dispatchers.IO) {
            try {
                val volumeBytes = ByteBuffer.allocate(4).putFloat(volume).array()
                val volumePacket = packetHandler.createControlPacket(
                    sessionId = clientId,
                    sequenceNumber = sequenceNumber.incrementAndGet(),
                    command = UdpPacketHandler.CONTROL_VOLUME,
                    data = volumeBytes
                )
                
                hostAddress?.let { address ->
                    val packet = DatagramPacket(
                        volumePacket,
                        volumePacket.size,
                        address,
                        hostAudioPort
                    )
                    audioSocket?.send(packet)
                }
                
                Log.d(TAG, "Sent volume control: $volume")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send volume control", e)
            }
        }
    }
    
    /**
     * Send host transfer accept - includes our new server info
     * @param newServerPort The port our new server will listen on
     */
    suspend fun sendTransferAccept(newServerPort: Int) {
        if (!isConnected.get()) return
        
        withContext(Dispatchers.IO) {
            try {
                // Encode our new server port in the data
                val portBytes = byteArrayOf(
                    ((newServerPort shr 8) and 0xFF).toByte(),
                    (newServerPort and 0xFF).toByte()
                )
                
                val acceptPacket = packetHandler.createControlPacket(
                    sessionId = clientId,
                    sequenceNumber = sequenceNumber.incrementAndGet(),
                    command = UdpPacketHandler.CONTROL_TRANSFER_ACCEPT,
                    data = portBytes
                )
                
                hostAddress?.let { address ->
                    val packet = DatagramPacket(
                        acceptPacket,
                        acceptPacket.size,
                        address,
                        hostAudioPort
                    )
                    audioSocket?.send(packet)
                }
                
                Log.i(TAG, "Sent transfer accept with new server port: $newServerPort")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send transfer accept", e)
            }
        }
    }
    
    /**
     * Send host transfer reject
     */
    suspend fun sendTransferReject() {
        if (!isConnected.get()) return
        
        withContext(Dispatchers.IO) {
            try {
                val rejectPacket = packetHandler.createControlPacket(
                    sessionId = clientId,
                    sequenceNumber = sequenceNumber.incrementAndGet(),
                    command = UdpPacketHandler.CONTROL_TRANSFER_REJECT
                )
                
                hostAddress?.let { address ->
                    val packet = DatagramPacket(
                        rejectPacket,
                        rejectPacket.size,
                        address,
                        hostAudioPort
                    )
                    audioSocket?.send(packet)
                }
                
                Log.i(TAG, "Sent transfer reject")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send transfer reject", e)
            }
        }
    }
    
    /**
     * Get the current client ID (for host transfer)
     */
    fun getClientId(): String = clientId
    
    /**
     * Check if connected
     */
    fun isClientConnected(): Boolean = isConnected.get()
    
    /**
     * Check if discovering
     */
    fun isClientDiscovering(): Boolean = isDiscovering.get()
    
    /**
     * Get connection info
     */
    fun getConnectionInfo(): UdpClientInfo? {
        return if (isConnected.get() && hostAddress != null) {
            UdpClientInfo(
                clientId = clientId,
                sessionId = sessionId,
                hostAddress = hostAddress!!.hostAddress,
                hostPort = hostAudioPort,
                connectedAt = System.currentTimeMillis(),
                lastSequence = lastReceivedSequence.get()
            )
        } else {
            null
        }
    }
    
    /**
     * Cleanup resources
     */
    private fun cleanup() {
        try {
            audioSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing audio socket", e)
        } finally {
            audioSocket = null
        }
        
        hostAddress = null
        hostAudioPort = 0
    }
}

/**
 * UDP Client information
 */
data class UdpClientInfo(
    val clientId: String,
    val sessionId: String,
    val hostAddress: String,
    val hostPort: Int,
    val connectedAt: Long,
    val lastSequence: Long
)

/**
 * UDP Client events
 */
sealed class UdpClientEvent {
    object DiscoveryStarted : UdpClientEvent()
    object DiscoveryStopped : UdpClientEvent()
    data class HostDiscovered(val hostName: String, val hostAddress: String, val audioPort: Int) : UdpClientEvent()
    data class DiscoveryError(val message: String) : UdpClientEvent()
    
    data class Connected(val hostAddress: String, val audioPort: Int) : UdpClientEvent()
    object Disconnected : UdpClientEvent()
    object Kicked : UdpClientEvent()  // Kicked by host
    data class ConnectionError(val message: String) : UdpClientEvent()
    
    data class AudioDataReceived(val audioData: ByteArray, val timestamp: Long) : UdpClientEvent()
    data class PcmDataReceived(val pcmData: ByteArray, val timestamp: Long) : UdpClientEvent()  // Raw PCM (no decoding needed)
    object HostMuted : UdpClientEvent()
    data class ReceiveError(val message: String) : UdpClientEvent()
    
    // Host transfer events
    data class HostTransferRequested(val fromHostAddress: String) : UdpClientEvent()
    data class HostTransferRedirect(val newHostAddress: String, val newHostPort: Int) : UdpClientEvent()
}