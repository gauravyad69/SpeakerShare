package io.github.gauravyad69.speakershare.services

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import timber.log.Timber
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.gauravyad69.speakershare.data.model.NetworkInfo
import io.github.gauravyad69.speakershare.data.model.DiscoveryMethod
import io.github.gauravyad69.speakershare.data.model.HostMode
import io.github.gauravyad69.speakershare.network.HotspotNetworkHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for network discovery using mDNS and UDP broadcast.
 * Handles both host registration and client discovery.
 */
@Singleton
class NetworkDiscoveryService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hotspotNetworkHelper: HotspotNetworkHelper
) {
    
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Service discovery state
    private val _discoveredHosts = MutableStateFlow<List<NetworkInfo>>(emptyList())
    val discoveredHosts: StateFlow<List<NetworkInfo>> = _discoveredHosts.asStateFlow()
    
    // Filtered views by mode
    val syncHosts: StateFlow<List<NetworkInfo>> = _discoveredHosts
        .map { hosts -> hosts.filter { it.hostMode == HostMode.SYNC } }
        .stateIn(serviceScope, SharingStarted.Eagerly, emptyList())
    
    val streamHosts: StateFlow<List<NetworkInfo>> = _discoveredHosts
        .map { hosts -> hosts.filter { it.hostMode == HostMode.STREAM } }
        .stateIn(serviceScope, SharingStarted.Eagerly, emptyList())
    
    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()
    
    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()
    
    // Internal state
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var udpDiscoveryJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var udpBroadcastJob: Job? = null
    private var registeredService: NsdServiceInfo? = null
    private var discoveryTimeoutJob: Job? = null
    
    companion object {
        private const val SERVICE_TYPE = "_speakershare._tcp"
        private const val SERVICE_NAME = "SpeakerShare Host"
        private const val UDP_DISCOVERY_PORT = 9089
        private const val UDP_BROADCAST_INTERVAL = 3000L // 3 seconds
        private const val DISCOVERY_TIMEOUT = 10000L // 10 seconds
        private const val SERVICE_INFO_KEY_USER = "user"
        private const val SERVICE_INFO_KEY_VERSION = "version"
        private const val SERVICE_INFO_KEY_CLIENTS = "clients"
        private const val SERVICE_INFO_KEY_MAX_CLIENTS = "maxClients"
        private const val SERVICE_INFO_KEY_MODE = "mode" // "sync" or "stream"
    }
    
    /**
     * Register as a host for discovery by clients
     */
    suspend fun registerHost(
        hostName: String,
        port: Int,
        userName: String,
        currentClients: Int = 0,
        maxClients: Int = 50,
        mode: HostMode = HostMode.STREAM
    ): Result<Unit> = withContext(Dispatchers.IO) {
        Timber.d("Registering host: $hostName on port $port")
        
        if (_isRegistered.value) {
            Timber.w("Host already registered, unregistering first")
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
                setAttribute(SERVICE_INFO_KEY_MODE, mode.name.lowercase())

                // Pin the advertised host address to the hotspot (SoftAP) IP when
                // one is active: with several networks attached, mDNS would
                // otherwise advertise whatever address the system picks (e.g. the
                // router subnet), which hotspot clients cannot reach.
                // NOTE: deliberately NOT using setNetwork() - binding the
                // advertiser to the tethering network stalls registration on
                // some devices (onServiceRegistered never fires, so the UDP
                // fallback never starts either). Pinning addresses keeps the
                // service discoverable on all links while still steering
                // clients to the hotspot address.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    hotspotNetworkHelper.getHotspotInterface()?.inetAddresses
                        ?.asSequence()
                        ?.filterIsInstance<java.net.Inet4Address>()
                        ?.filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                        ?.toList()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { addrs ->
                            setHostAddresses(addrs)
                            Timber.i("mDNS service advertising hotspot address(es): ${addrs.map { it.hostAddress }}")
                        }
                }
            }
            
            val listener = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Timber.e("Service registration failed: $errorCode")
                    _isRegistered.value = false
                }
                
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Timber.e("Service unregistration failed: $errorCode")
                }
                
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                    Timber.i("Service registered: ${serviceInfo?.serviceName}")
                    registeredService = serviceInfo
                    _isRegistered.value = true
                }
                
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                    Timber.i("Service unregistered: ${serviceInfo?.serviceName}")
                    registeredService = null
                    _isRegistered.value = false
                    stopUdpBroadcast()
                }
            }
            
            registrationListener = listener
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            
            // Start the UDP broadcast fallback immediately rather than waiting
            // for onServiceRegistered: on some devices the mDNS registration
            // callback is delayed or never fires (e.g. tethering networks),
            // which previously left clients with no discovery path at all.
            startUdpBroadcast(hostName, port, userName, currentClients, maxClients)
            
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to register host")
            _isRegistered.value = false
            Result.failure(e)
        }
    }
    
    /**
     * Unregister host from discovery
     */
    suspend fun unregisterHost(): Result<Unit> = withContext(Dispatchers.IO) {
        Timber.d("Unregistering host")
        
        return@withContext try {
            registrationListener?.let { listener ->
                nsdManager.unregisterService(listener)
                registrationListener = null
            }
            
            stopUdpBroadcast()
            _isRegistered.value = false
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to unregister host")
            Result.failure(e)
        }
    }
    
    /**
     * Start discovering hosts
     */
    suspend fun startDiscovery(): Result<Unit> = withContext(Dispatchers.IO) {
        Timber.d("Starting host discovery")
        
        if (_isDiscovering.value) {
            Timber.w("Discovery already in progress")
            return@withContext Result.success(Unit)
        }
        
        return@withContext try {
            // Clear previous results
            _discoveredHosts.value = emptyList()
            
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String?) {
                    Timber.d("Service discovery started")
                    _isDiscovering.value = true
                }
                
                override fun onServiceFound(service: NsdServiceInfo?) {
                    Timber.d("Service found: ${service?.serviceName}")
                    service?.let { resolveService(it) }
                }
                
                override fun onServiceLost(service: NsdServiceInfo?) {
                    Timber.d("Service lost: ${service?.serviceName}")
                    service?.let { removeDiscoveredHost(it.serviceName) }
                }
                
                override fun onDiscoveryStopped(serviceType: String?) {
                    Timber.d("Service discovery stopped")
                    _isDiscovering.value = false
                }
                
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Timber.e("Discovery start failed: $errorCode")
                    _isDiscovering.value = false
                }
                
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Timber.e("Discovery stop failed: $errorCode")
                }
            }
            
            discoveryListener = listener
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            
            // Start UDP discovery as fallback
            startUdpDiscovery()
            
            // Auto-stop discovery after timeout.
            // Track the job so a restarted discovery cancels the previous
            // timer - otherwise an old timer silently kills the new session.
            discoveryTimeoutJob?.cancel()
            discoveryTimeoutJob = serviceScope.launch {
                delay(DISCOVERY_TIMEOUT)
                stopDiscovery()
            }
            
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to start discovery")
            _isDiscovering.value = false
            Result.failure(e)
        }
    }
    
    /**
     * Stop discovering hosts
     */
    suspend fun stopDiscovery(): Result<Unit> = withContext(Dispatchers.IO) {
        Timber.d("Stopping host discovery")
        
        return@withContext try {
            // Cancel the pending auto-stop timer
            discoveryTimeoutJob?.cancel()
            discoveryTimeoutJob = null
            
            discoveryListener?.let { listener ->
                nsdManager.stopServiceDiscovery(listener)
                discoveryListener = null
            }
            
            stopUdpDiscovery()
            _isDiscovering.value = false
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop discovery")
            Result.failure(e)
        }
    }
    
    /**
     * Resolve discovered service to get full connection details.
     * Resolves are serialized - NsdManager fails concurrent resolve calls with
     * FAILURE_ALREADY_ACTIVE on API levels below 34.
     */
    private val resolveMutex = Mutex()
    
    private fun resolveService(service: NsdServiceInfo) {
        Timber.d("Resolving service: ${service.serviceName}")
        
        serviceScope.launch {
            resolveMutex.withLock {
                val resolved = CompletableDeferred<Unit>()
                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        Timber.e("Resolve failed: $errorCode")
                        resolved.complete(Unit)
                    }
                    
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                        Timber.d("Service resolved: ${serviceInfo?.serviceName}")
                        serviceInfo?.let { addDiscoveredHost(it) }
                        resolved.complete(Unit)
                    }
                }
                
                try {
                    nsdManager.resolveService(service, resolveListener)
                    // Hold the lock until this resolve finishes (or times out)
                    withTimeoutOrNull(10_000L) { resolved.await() }
                } catch (e: Exception) {
                    Timber.e(e, "Resolve request failed for ${service.serviceName}")
                }
            }
        }
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
        
        // Parse host mode (sync or stream)
        val modeStr = serviceInfo.attributes?.get(SERVICE_INFO_KEY_MODE)?.let { 
            String(it) 
        } ?: "stream"
        val hostMode = when (modeStr.lowercase()) {
            "sync" -> HostMode.SYNC
            else -> HostMode.STREAM
        }
        
        val networkInfo = NetworkInfo(
            localIpAddress = serviceInfo.host.hostAddress ?: "",
            port = serviceInfo.port,
            networkInterface = "wlan0", // Default Wi-Fi interface
            isHotspot = false,
            discoveryMethod = DiscoveryMethod.MDNS,
            serviceName = serviceInfo.serviceName,
            hostMode = hostMode
        )
        
        Timber.d("Adding discovered host: ${networkInfo.serviceName} at ${networkInfo.localIpAddress}:${networkInfo.port} (mode=${hostMode.name})")
        
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
        Timber.d("Removing discovered host: $serviceName")
        
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
        // Cancel any previous broadcast before starting a new one (safe when
        // called repeatedly, e.g. re-registering a session)
        udpBroadcastJob?.cancel()
        Timber.d("Starting UDP broadcast")
        
        udpBroadcastJob = serviceScope.launch {
            var socket: DatagramSocket? = null
            
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                
                val message = createBroadcastMessage(hostName, port, userName, currentClients, maxClients)
                val data = message.toByteArray()
                // Limited broadcast (255.255.255.255) exits via the default-route
                // interface (station/cellular); also target the hotspot subnet
                // broadcast so hotspot clients always receive the announcement.
                val targets = hotspotNetworkHelper.getDiscoveryBroadcastTargets()
                Timber.d("UDP broadcast targets: ${targets.map { it.hostAddress }}")
                
                while (udpBroadcastJob?.isActive == true) {
                    try {
                        targets.forEach { target ->
                            val packet = DatagramPacket(data, data.size, target, UDP_DISCOVERY_PORT)
                            socket.send(packet)
                        }
                        Timber.v("UDP broadcast sent to ${targets.size} network(s)")
                    } catch (e: IOException) {
                        // Handle background restriction (EPERM) or other IO errors
                        Timber.w("UDP broadcast failed: ${e.message}")
                    }
                    delay(UDP_BROADCAST_INTERVAL)
                }
                
            } catch (e: Exception) {
                Timber.e(e, "UDP broadcast setup error")
            } finally {
                socket?.close()
            }
        }
    }
    
    /**
     * Stop UDP broadcast
     */
    private fun stopUdpBroadcast() {
        Timber.d("Stopping UDP broadcast")
        udpBroadcastJob?.cancel()
        udpBroadcastJob = null
    }
    
    /**
     * Start UDP discovery listening
     */
    private fun startUdpDiscovery() {
        Timber.d("Starting UDP discovery")
        
        udpDiscoveryJob = serviceScope.launch {
            try {
                // reuseAddress must be set BEFORE binding to be effective
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(UDP_DISCOVERY_PORT))
                }
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                
                while (udpDiscoveryJob?.isActive == true) {
                    udpSocket?.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    Timber.v("UDP discovery received: $message")
                    
                    parseBroadcastMessage(message, packet.address.hostAddress)?.let { networkInfo ->
                        addDiscoveredHostUdp(networkInfo)
                    }
                }
                
            } catch (e: IOException) {
                if (udpDiscoveryJob?.isActive == true) {
                    Timber.e(e, "UDP discovery error")
                }
            } finally {
                udpSocket?.close()
                udpSocket = null
            }
        }
    }
    
    /**
     * Stop UDP discovery listening
     */
    private fun stopUdpDiscovery() {
        Timber.d("Stopping UDP discovery")
        udpDiscoveryJob?.cancel()
        udpDiscoveryJob = null
        udpSocket?.close()
        udpSocket = null
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
            Timber.w(e, "Failed to parse broadcast message: $message")
            null
        }
    }
    
    /**
     * Add discovered host from UDP (with NetworkInfo interface)
     */
    private fun addDiscoveredHostUdp(networkInfo: NetworkInfo) {
        Timber.d("Adding UDP discovered host: ${networkInfo.serviceName} at ${networkInfo.localIpAddress}:${networkInfo.port}")
        
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
     * Get local IP addresses, hotspot (SoftAP) first.
     * The hotspot network is preferred: when the device runs a hotspot while
     * also being connected to a router/cellular, the first address is the one
     * hotspot clients can actually reach.
     */
    suspend fun getLocalIpAddresses(): List<String> = withContext(Dispatchers.IO) {
        hotspotNetworkHelper.getOrderedIpAddresses()
    }

    /**
     * Check if a host is reachable
     */
    suspend fun isHostReachable(ipAddress: String, timeoutMs: Int = 3000): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val address = InetAddress.getByName(ipAddress)
            address.isReachable(timeoutMs)
        } catch (e: Exception) {
            Timber.e(e, "Failed to check host reachability: $ipAddress")
            false
        }
    }
    
    /**
     * Update host client count (for registered hosts)
     */
    suspend fun updateHostClientCount(currentClients: Int) {
        if (_isRegistered.value) {
            registeredService?.let { service ->
                Timber.d("Updating host client count to $currentClients")
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
        Timber.d("Clearing discovered hosts")
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
        Timber.d("Cleaning up NetworkDiscoveryService")
        
        serviceScope.launch {
            unregisterHost()
            stopDiscovery()
        }
        
        serviceScope.cancel()
    }
}
