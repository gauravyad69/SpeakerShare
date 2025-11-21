package io.github.gauravyad69.speakershare.network.discovery

import io.github.gauravyad69.speakershare.data.model.NetworkInfo
import kotlinx.coroutines.flow.Flow

/**
 * Network Discovery Handler for host/client discovery.
 * Handles mDNS and UDP broadcast discovery mechanisms.
 */
interface NetworkDiscovery {
    
    /**
     * Start advertising host service for discovery
     */
    suspend fun startAdvertising(
        serviceName: String,
        port: Int,
        networkInfo: NetworkInfo
    ): Result<Unit>
    
    /**
     * Stop advertising host service
     */
    suspend fun stopAdvertising(): Result<Unit>
    
    /**
     * Scan for available hosts on the network
     */
    suspend fun scanForHosts(timeoutMs: Long = 5000): Result<List<DiscoveredHost>>
    
    /**
     * Get discovered hosts as a flow for reactive updates
     */
    fun getDiscoveredHosts(): Flow<List<DiscoveredHost>>
    
    /**
     * Connect to a discovered host
     */
    suspend fun connectToHost(host: DiscoveredHost): Result<HostConnectionInfo>
    
    /**
     * Get current network interface information
     */
    suspend fun getNetworkInterfaceInfo(): NetworkInterfaceInfo
    
    /**
     * Check if device is currently a Wi-Fi hotspot
     */
    suspend fun isHotspot(): Boolean
}

// Network discovery data classe/s
/*data class DiscoveredHost(
    val hostId: String,
    val hostName: String,
    val ipAddress: String,
    val port: Int,
    val serviceName: String,
    val discoveryMethod: String, // "MDNS" or "UDP_BROADCAST"
    val signalStrength: Int? = null,
    val lastSeen: Long,
    val audioSource: String,
    val quality: String,
    val connectedClients: Int,
    val maxClients: Int,
    val isAcceptingClients: Boolean
)*/

data class HostConnectionInfo(
    val hostId: String,
    val baseUrl: String,
    val webrtcSignalingUrl: String?,
    val udpEndpoint: String?,
    val supportedTransports: List<String>
)

data class NetworkInterfaceInfo(
    val interfaceName: String,
    val ipAddress: String,
    val networkName: String?, // Wi-Fi SSID
    val isWiFi: Boolean,
    val isHotspot: Boolean,
    val signalStrength: Int?
)
