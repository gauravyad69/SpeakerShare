package io.github.gauravyad69.speakershare.network

import timber.log.Timber
import io.github.gauravyad69.speakershare.network.api.HostDiscoveryInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation // Changed import
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson // Added import
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP Discovery Client for finding available hosts
 * Scans network for SpeakerShare hosts using HTTP discovery
 */
@Singleton
class DiscoveryClient @Inject constructor() {
    companion object {
        private const val DEFAULT_HTTP_PORT = 8080
        private const val DISCOVERY_PATH = "/api/v1/discovery/info"
        private const val SCAN_TIMEOUT_MS = 2000L
        private const val MAX_CONCURRENT_SCANS = 50
        private const val NETWORK_SCAN_DELAY_MS = 100L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var httpClient: HttpClient? = null
    private var isScanning = false
    
    // Events
    private val _discoveryEvents = MutableSharedFlow<DiscoveryEvent>()
    val discoveryEvents: SharedFlow<DiscoveryEvent> = _discoveryEvents.asSharedFlow()
    
    init {
        initializeHttpClient()
    }
    
    /**
     * Initialize HTTP client
     */
    private fun initializeHttpClient() {
        httpClient = HttpClient(Android) {
            install(ContentNegotiation) {
                gson {
                    setPrettyPrinting()
                    disableHtmlEscaping()
                }
            }
            
            engine {
                connectTimeout = SCAN_TIMEOUT_MS.toInt()
                socketTimeout = SCAN_TIMEOUT_MS.toInt()
            }
        }
    }
    
    /**
     * Discover hosts on the network
     */
    suspend fun discoverHosts(networkInterface: String? = null): List<DiscoveredHost> {
        if (isScanning) {
            Timber.w("Discovery already in progress")
            return emptyList()
        }
        
        return withContext(Dispatchers.IO) {
            try {
                isScanning = true
                
                scope.launch {
                    _discoveryEvents.emit(DiscoveryEvent.ScanStarted)
                }
                
                val discoveredHosts = mutableListOf<DiscoveredHost>()
                val networkAddresses = getNetworkAddresses(networkInterface)
                
                Timber.d("Scanning ${networkAddresses.size} network addresses")
                
                // Scan network addresses in batches
                networkAddresses.chunked(MAX_CONCURRENT_SCANS).forEach { batch ->
                    val jobs = batch.map { address ->
                        async {
                            scanHostAtAddress(address)
                        }
                    }
                    
                    // Collect results from this batch
                    jobs.awaitAll().filterNotNull().forEach { host ->
                        discoveredHosts.add(host)
                        scope.launch {
                            _discoveryEvents.emit(DiscoveryEvent.HostFound(host))
                        }
                    }
                    
                    // Small delay between batches to avoid overwhelming network
                    delay(NETWORK_SCAN_DELAY_MS)
                }
                
                scope.launch {
                    _discoveryEvents.emit(DiscoveryEvent.ScanCompleted(discoveredHosts.size))
                }
                
                Timber.i("Discovery completed. Found ${discoveredHosts.size} hosts")
                discoveredHosts.toList()
                
            } catch (e: Exception) {
                Timber.e("Discovery failed", e)
                
                scope.launch {
                    _discoveryEvents.emit(DiscoveryEvent.ScanError("Discovery failed: ${e.message}"))
                }
                
                emptyList()
            } finally {
                isScanning = false
            }
        }
    }
    
    /**
     * Discover hosts by scanning specific IP range
     */
    suspend fun discoverHostsInRange(baseIp: String, startRange: Int = 1, endRange: Int = 254): List<DiscoveredHost> {
        if (isScanning) {
            Timber.w("Discovery already in progress")
            return emptyList()
        }
        
        return withContext(Dispatchers.IO) {
            try {
                isScanning = true
                
                scope.launch {
                    _discoveryEvents.emit(DiscoveryEvent.ScanStarted)
                }
                
                val discoveredHosts = mutableListOf<DiscoveredHost>()
                val ipBase = baseIp.substringBeforeLast('.')
                
                Timber.d("Scanning IP range: $ipBase.$startRange - $ipBase.$endRange")
                
                // Generate IP addresses in the range
                val addressesToScan = (startRange..endRange).map { "$ipBase.$it" }
                
                // Scan addresses in batches
                addressesToScan.chunked(MAX_CONCURRENT_SCANS).forEach { batch ->
                    val jobs = batch.map { address ->
                        async {
                            scanHostAtAddress(address)
                        }
                    }
                    
                    // Collect results from this batch
                    jobs.awaitAll().filterNotNull().forEach { host ->
                        discoveredHosts.add(host)
                        scope.launch {
                            _discoveryEvents.emit(DiscoveryEvent.HostFound(host))
                        }
                    }
                    
                    delay(NETWORK_SCAN_DELAY_MS)
                }
                
                scope.launch {
                    _discoveryEvents.emit(DiscoveryEvent.ScanCompleted(discoveredHosts.size))
                }
                
                Timber.i("Range discovery completed. Found ${discoveredHosts.size} hosts")
                discoveredHosts.toList()
                
            } catch (e: Exception) {
                Timber.e("Range discovery failed", e)
                
                scope.launch {
                    _discoveryEvents.emit(DiscoveryEvent.ScanError("Range discovery failed: ${e.message}"))
                }
                
                emptyList()
            } finally {
                isScanning = false
            }
        }
    }
    
    /**
     * Check specific host availability
     */
    suspend fun checkHost(hostIp: String, port: Int = DEFAULT_HTTP_PORT): DiscoveredHost? {
        return withContext(Dispatchers.IO) {
            scanHostAtAddress(hostIp, port)
        }
    }
    
    /**
     * Get detailed host information
     */
    suspend fun getHostInfo(hostIp: String, port: Int = DEFAULT_HTTP_PORT): HostDiscoveryInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val client = httpClient ?: return@withContext null
                val response = client.get("http://$hostIp:$port$DISCOVERY_PATH")
                
                if (response.status == HttpStatusCode.OK) {
                    response.body<HostDiscoveryInfo>()
                } else {
                    null
                }
            } catch (e: Exception) {
                Timber.v("Failed to get host info from $hostIp:$port - ${e.message}")
                null
            }
        }
    }
    
    /**
     * Scan a single host address
     */
    private suspend fun scanHostAtAddress(address: String, port: Int = DEFAULT_HTTP_PORT): DiscoveredHost? {
        return try {
            val client = httpClient ?: return null
            val url = "http://$address:$port$DISCOVERY_PATH"
            
            val response = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                client.get(url)
            }
            
            if (response?.status == HttpStatusCode.OK) {
                val discoveryInfo = response.body<HostDiscoveryInfo>()
                
                DiscoveredHost(
                    ipAddress = address,
                    port = port,
                    hostName = discoveryInfo.hostName,
                    sessionId = discoveryInfo.sessionId,
                    audioSource = discoveryInfo.audioSource,
                    connectedClients = discoveryInfo.connectedClients,
                    maxClients = discoveryInfo.maxClients,
                    isAcceptingClients = discoveryInfo.isAcceptingClients,
                    supportedTransports = discoveryInfo.transport,
                    quality = discoveryInfo.quality,
                    lastSeen = System.currentTimeMillis()
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.v("No host found at $address:$port - ${e.message}")
            null
        }
    }
    
    /**
     * Get network addresses to scan
     */
    private fun getNetworkAddresses(networkInterface: String?): List<String> {
        return try {
            val addresses = mutableListOf<String>()
            
            // Get all network interfaces
            val interfaces = if (networkInterface != null) {
                listOf(NetworkInterface.getByName(networkInterface))
            } else {
                NetworkInterface.getNetworkInterfaces().toList()
            }
            
            interfaces.filterNotNull().forEach { ni ->
                if (ni.isUp && !ni.isLoopback && !ni.isVirtual) {
                    ni.inetAddresses.toList().forEach { addr ->
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            // Generate addresses in the same subnet
                            val baseIp = addr.hostAddress
                            val network = baseIp.substringBeforeLast('.')
                            
                            // Add addresses in the subnet (skip .0 and .255)
                            for (i in 1..254) {
                                addresses.add("$network.$i")
                            }
                        }
                    }
                }
            }
            
            // Remove duplicates and sort
            addresses.distinct().sorted()
            
        } catch (e: Exception) {
            Timber.e("Failed to get network addresses", e)
            emptyList()
        }
    }
    
    /**
     * Stop ongoing discovery
     */
    fun stopDiscovery() {
        isScanning = false
        
        scope.launch {
            _discoveryEvents.emit(DiscoveryEvent.ScanStopped)
        }
        
        Timber.i("Discovery stopped")
    }
    
    /**
     * Check if discovery is in progress
     */
    fun isDiscoveryInProgress(): Boolean = isScanning
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        stopDiscovery()
        httpClient?.close()
        httpClient = null
        
        Timber.d("Discovery client cleanup completed")
    }
}

/**
 * Discovered host information
 */
data class DiscoveredHost(
    val ipAddress: String,
    val port: Int,
    val hostName: String,
    val sessionId: String,
    val audioSource: String,
    val connectedClients: Int,
    val maxClients: Int,
    val isAcceptingClients: Boolean,
    val supportedTransports: List<String>,
    val quality: io.github.gauravyad69.speakershare.network.api.QualityInfo,
    val lastSeen: Long
) {
    /**
     * Get connection URL for this host
     */
    fun getConnectionUrl(): String = "http://$ipAddress:$port"
    
    /**
     * Check if host supports WebRTC
     */
    fun supportsWebRTC(): Boolean = supportedTransports.contains("WEBRTC")
    
    /**
     * Check if host supports UDP
     */
    fun supportsUDP(): Boolean = supportedTransports.contains("UDP")
    
    /**
     * Get age of discovery in milliseconds
     */
    fun getAge(): Long = System.currentTimeMillis() - lastSeen
}

/**
 * Discovery events
 */
sealed class DiscoveryEvent {
    object ScanStarted : DiscoveryEvent()
    object ScanStopped : DiscoveryEvent()
    data class ScanCompleted(val hostsFound: Int) : DiscoveryEvent()
    data class HostFound(val host: DiscoveredHost) : DiscoveryEvent()
    data class ScanError(val message: String) : DiscoveryEvent()
}