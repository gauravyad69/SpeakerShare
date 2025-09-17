package io.github.gauravyad69.speakershare.network.udp

import io.github.gauravyad69.speakershare.data.model.AudioQuality
import kotlinx.coroutines.flow.Flow

/**
 * UDP Handler for fallback audio streaming.
 * Handles UDP-based audio transmission when WebRTC is not available.
 */
interface UDPHandler {
    
    /**
     * Start UDP audio broadcasting
     */
    suspend fun startBroadcasting(
        port: Int,
        quality: AudioQuality
    ): Result<Unit>
    
    /**
     * Stop UDP broadcasting
     */
    suspend fun stopBroadcasting(): Result<Unit>
    
    /**
     * Send audio data over UDP
     */
    suspend fun sendAudioData(data: ByteArray): Result<Unit>
    
    /**
     * Get connected UDP clients
     */
    fun getConnectedClients(): Flow<List<UDPClient>>
    
    /**
     * Remove UDP client
     */
    suspend fun removeClient(clientAddress: String): Result<Unit>
    
    /**
     * Get UDP broadcasting status
     */
    suspend fun isBroadcasting(): Boolean
    
    /**
     * Get UDP network metrics
     */
    suspend fun getNetworkMetrics(): UDPNetworkMetrics
}

// UDP-specific data classes
data class UDPClient(
    val clientId: String,
    val address: String,
    val port: Int,
    val lastSeen: Long,
    val packetsReceived: Int,
    val packetLoss: Float
)

data class UDPNetworkMetrics(
    val totalPacketsSent: Long,
    val totalBytesTransmitted: Long,
    val averageLatency: Long,
    val clientCount: Int,
    val packetsDropped: Int
)
