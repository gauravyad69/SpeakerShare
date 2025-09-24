package io.github.gauravyad69.speakershare.services

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.gauravyad69.speakershare.data.model.NetworkInfo
import io.github.gauravyad69.speakershare.data.model.DiscoveryMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for network discovery using mDNS and UDP broadcast.
 * Handles both host registration and client discovery.
 */
@Singleton
class NetworkDiscoveryService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Service discovery state
    private val _discoveredHosts = MutableStateFlow<List<NetworkInfo>>(emptyList())
    val discoveredHosts: StateFlow<List<NetworkInfo>> = _discoveredHosts.asStateFlow()
    
    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()
    
    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()
    
    // Internal state
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var udpDiscoveryJob: Job? = null
    private var udpBroadcastJob: Job? = null
    private var registeredService: NsdServiceInfo? = null
    
    companion object {
        private const val TAG = "NetworkDiscoveryService"
        private const val SERVICE_TYPE = "_speakershare._tcp"
        private const val SERVICE_NAME = "SpeakerShare Host"
        private const val UDP_DISCOVERY_PORT = 9089
        private const val UDP_BROADCAST_INTERVAL = 3000L // 3 seconds
        private const val DISCOVERY_TIMEOUT = 10000L // 10 seconds
        private const val SERVICE_INFO_KEY_USER = "user"
        private const val SERVICE_INFO_KEY_VERSION = "version"
        private const val SERVICE_INFO_KEY_CLIENTS = "clients"
        private const val SERVICE_INFO_KEY_MAX_CLIENTS = "maxClients"
    }
    
    /**
     * Register as a host for discovery by clients
     */
    suspend fun registerHost(
        hostName: String,
        port: Int,
        userName: String,
        currentClients: Int = 0,
        maxClients: Int = 50
    ): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Registering host: $hostName on port $port")
        
        if (_isRegistered.value) {
            Log.w(TAG, "Host already registered, unregistering first")
            unregisterHost()
        }
        
        return@withContext try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = hostName
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute(SERVICE_INFO_KEY_USER, userName)
                setAttribute(SERVICE_INFO_KEY_VERSION, "1.0")
                setAttribute(SERVICE_INFO_KEY_CLIENTS, currentClients.toString())
                setAttribute(SERVICE_INFO_KEY_MAX_CLIENTS, maxClients.toString())
            }
            
            val listener = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.e(TAG, "Service registration failed: $errorCode")
                    _isRegistered.value = false
                }
                
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.e(TAG, "Service unregistration failed: $errorCode")
                }
                
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                    Log.i(TAG, "Service registered: ${serviceInfo?.serviceName}")
                    registeredService = serviceInfo
                    _isRegistered.value = true
                    
                    // Start UDP broadcast for fallback discovery
                    startUdpBroadcast(hostName, port, userName, currentClients, maxClients)
                }
                
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                    Log.i(TAG, "Service unregistered: ${serviceInfo?.serviceName}")
                    registeredService = null
                    _isRegistered.value = false
                    stopUdpBroadcast()
                }
            }
            
            registrationListener = listener
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register host", e)
            _isRegistered.value = false
            Result.failure(e)
        }
    }
    
    /**
     * Unregister host from discovery
     */
    suspend fun unregisterHost(): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Unregistering host")
        
        return@withContext try {
            registrationListener?.let { listener ->
                nsdManager.unregisterService(listener)
                registrationListener = null
            }
            
            stopUdpBroadcast()
            _isRegistered.value = false
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister host", e)
            Result.failure(e)
        }
    }
    
    /**
     * Start discovering hosts
     */
    suspend fun startDiscovery(): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting host discovery")
        
        if (_isDiscovering.value) {
            Log.w(TAG, "Discovery already in progress")
            return@withContext Result.success(Unit)
        }
        
        return@withContext try {
            // Clear previous results
            _discoveredHosts.value = emptyList()
            
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String?) {
                    Log.d(TAG, "Service discovery started")
                    _isDiscovering.value = true
                }
                
                override fun onServiceFound(service: NsdServiceInfo?) {
                    Log.d(TAG, "Service found: ${service?.serviceName}")
                    service?.let { resolveService(it) }
                }
                
                override fun onServiceLost(service: NsdServiceInfo?) {
                    Log.d(TAG, "Service lost: ${service?.serviceName}")
                    service?.let { removeDiscoveredHost(it.serviceName) }
                }
                
                override fun onDiscoveryStopped(serviceType: String?) {
                    Log.d(TAG, "Service discovery stopped")
                    _isDiscovering.value = false
                }
                
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.e(TAG, "Discovery start failed: $errorCode")
                    _isDiscovering.value = false
                }
                
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.e(TAG, "Discovery stop failed: $errorCode")
                }
            }
            
            discoveryListener = listener
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            
            // Start UDP discovery as fallback
            startUdpDiscovery()
            
            // Auto-stop discovery after timeout
            serviceScope.launch {
                delay(DISCOVERY_TIMEOUT)
                stopDiscovery()
            }
            
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            _isDiscovering.value = false
            Result.failure(e)
        }
    }
    
    /**
     * Stop discovering hosts
     */
    suspend fun stopDiscovery(): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Stopping host discovery")
        
        return@withContext try {
            discoveryListener?.let { listener ->
                nsdManager.stopServiceDiscovery(listener)
                discoveryListener = null
            }
            
            stopUdpDiscovery()
            _isDiscovering.value = false
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop discovery", e)
            Result.failure(e)
        }
    }
    
    /**
     * Resolve discovered service to get full connection details
     */
    private fun resolveService(service: NsdServiceInfo) {
        Log.d(TAG, "Resolving service: ${service.serviceName}")
        
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode")
            }
            
            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "Service resolved: ${serviceInfo?.serviceName}")
                serviceInfo?.let { addDiscoveredHost(it) }
            }
        }
        
        nsdManager.resolveService(service, resolveListener)
    }
    
    /**
     * Add a discovered host to the list
     */
    private fun addDiscoveredHost(serviceInfo: NsdServiceInfo) {
        val userName = serviceInfo.attributes?.get(SERVICE_INFO_KEY_USER)?.let { 
            String(it) 
        } ?: "Unknown User"
        
        val currentClients = serviceInfo.attributes?.get(SERVICE_INFO_KEY_CLIENTS)?.let { 
            String(it).toIntOrNull() 
        } ?: 0
        
        val maxClients = serviceInfo.attributes?.get(SERVICE_INFO_KEY_MAX_CLIENTS)?.let { 
            String(it).toIntOrNull() 
        } ?: 50
        
        val networkInfo = NetworkInfo(
            localIpAddress = serviceInfo.host.hostAddress ?: "",
            port = serviceInfo.port,
            networkInterface = "wlan0", // Default Wi-Fi interface
            isHotspot = false,
            discoveryMethod = DiscoveryMethod.MDNS,
            serviceName = serviceInfo.serviceName
        )
        
        Log.d(TAG, "Adding discovered host: ${networkInfo.serviceName} at ${networkInfo.localIpAddress}:${networkInfo.port}")
        
        val currentHosts = _discoveredHosts.value.toMutableList()
        
        // Remove any existing entry with the same name or IP
        currentHosts.removeAll { 
            it.serviceName == networkInfo.serviceName || it.localIpAddress == networkInfo.localIpAddress 
        }
        
        // Add the new entry
        currentHosts.add(networkInfo)
        _discoveredHosts.value = currentHosts
    }
    
    /**
     * Remove a host from the discovered list
     */
    private fun removeDiscoveredHost(serviceName: String) {
        Log.d(TAG, "Removing discovered host: $serviceName")
        
        val currentHosts = _discoveredHosts.value.toMutableList()
        currentHosts.removeAll { it.serviceName == serviceName }
        _discoveredHosts.value = currentHosts
    }
    
    /**
     * Start UDP broadcast for fallback discovery
     */
    private fun startUdpBroadcast(
        hostName: String, 
        port: Int, 
        userName: String, 
        currentClients: Int, 
        maxClients: Int
    ) {
        Log.d(TAG, "Starting UDP broadcast")
        
        udpBroadcastJob = serviceScope.launch {
            var socket: DatagramSocket? = null
            
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                
                val message = createBroadcastMessage(hostName, port, userName, currentClients, maxClients)
                val data = message.toByteArray()
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(data, data.size, broadcastAddress, UDP_DISCOVERY_PORT)
                
                while (udpBroadcastJob?.isActive == true) {
                    socket.send(packet)
                    Log.v(TAG, "UDP broadcast sent")
                    delay(UDP_BROADCAST_INTERVAL)
                }
                
            } catch (e: IOException) {
                Log.e(TAG, "UDP broadcast error", e)
            } finally {
                socket?.close()
            }
        }
    }
    
    /**
     * Stop UDP broadcast
     */
    private fun stopUdpBroadcast() {
        Log.d(TAG, "Stopping UDP broadcast")
        udpBroadcastJob?.cancel()
        udpBroadcastJob = null
    }
    
    /**
     * Start UDP discovery listening
     */
    private fun startUdpDiscovery() {
        Log.d(TAG, "Starting UDP discovery")
        
        udpDiscoveryJob = serviceScope.launch {
            var socket: DatagramSocket? = null
            
            try {
                socket = DatagramSocket(UDP_DISCOVERY_PORT)
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                
                while (udpDiscoveryJob?.isActive == true) {
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    Log.v(TAG, "UDP discovery received: $message")
                    
                    parseBroadcastMessage(message, packet.address.hostAddress)?.let { networkInfo ->
                        addDiscoveredHostUdp(networkInfo)
                    }
                }
                
            } catch (e: IOException) {
                Log.e(TAG, "UDP discovery error", e)
            } finally {
                socket?.close()
            }
        }
    }
    
    /**
     * Stop UDP discovery listening
     */
    private fun stopUdpDiscovery() {
        Log.d(TAG, "Stopping UDP discovery")
        udpDiscoveryJob?.cancel()
        udpDiscoveryJob = null
    }
    
    /**
     * Create UDP broadcast message
     */
    private fun createBroadcastMessage(
        hostName: String,
        port: Int,
        userName: String,
        currentClients: Int,
        maxClients: Int
    ): String {
        return "SPEAKERSHARE_HOST|$hostName|$port|$userName|$currentClients|$maxClients"
    }
    
    /**
     * Parse UDP broadcast message
     */
    private fun parseBroadcastMessage(message: String, ipAddress: String?): NetworkInfo? {
        return try {
            val parts = message.split("|")
            if (parts.size >= 6 && parts[0] == "SPEAKERSHARE_HOST") {
                NetworkInfo(
                    localIpAddress = ipAddress ?: "",
                    port = parts[2].toInt(),
                    networkInterface = "wlan0", // Default interface
                    isHotspot = false,
                    discoveryMethod = DiscoveryMethod.UDP_BROADCAST,
                    serviceName = parts[1]
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse broadcast message: $message", e)
            null
        }
    }
    
    /**
     * Add discovered host from UDP (with NetworkInfo interface)
     */
    private fun addDiscoveredHostUdp(networkInfo: NetworkInfo) {
        Log.d(TAG, "Adding UDP discovered host: ${networkInfo.serviceName} at ${networkInfo.localIpAddress}:${networkInfo.port}")
        
        val currentHosts = _discoveredHosts.value.toMutableList()
        
        // Remove any existing entry with the same name or IP
        currentHosts.removeAll { 
            it.serviceName == networkInfo.serviceName || it.localIpAddress == networkInfo.localIpAddress 
        }
        
        // Add the new entry
        currentHosts.add(networkInfo)
        _discoveredHosts.value = currentHosts
    }
    
    /**
     * Get local IP addresses
     */
    suspend fun getLocalIpAddresses(): List<String> = withContext(Dispatchers.IO) {
        val addresses = mutableListOf<String>()
        
        try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { !it.isLoopback && it.isUp }
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                .forEach { addresses.add(it.hostAddress) }
                
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP addresses", e)
        }
        
        return@withContext addresses
    }
    
    /**
     * Check if a host is reachable
     */
    suspend fun isHostReachable(ipAddress: String, timeoutMs: Int = 3000): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val address = InetAddress.getByName(ipAddress)
            address.isReachable(timeoutMs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check host reachability: $ipAddress", e)
            false
        }
    }
    
    /**
     * Update host client count (for registered hosts)
     */
    suspend fun updateHostClientCount(currentClients: Int) {
        if (_isRegistered.value) {
            registeredService?.let { service ->
                Log.d(TAG, "Updating host client count to $currentClients")
                service.setAttribute(SERVICE_INFO_KEY_CLIENTS, currentClients.toString())
                // Note: mDNS doesn't support updating attributes directly,
                // we'd need to re-register the service, but UDP broadcast will reflect changes
            }
        }
    }
    
    /**
     * Clear discovered hosts
     */
    fun clearDiscoveredHosts() {
        Log.d(TAG, "Clearing discovered hosts")
        _discoveredHosts.value = emptyList()
    }
    
    /**
     * Get current discovered hosts count
     */
    fun getDiscoveredHostCount(): Int {
        return _discoveredHosts.value.size
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up NetworkDiscoveryService")
        
        serviceScope.launch {
            unregisterHost()
            stopDiscovery()
        }
        
        serviceScope.cancel()
    }
}
