package io.github.gauravyad69.speakershare.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prefers the hotspot (SoftAP) network for address selection and discovery.
 *
 * The host device may be attached to several networks at once (router Wi-Fi,
 * cellular, ethernet) while also running a Wi-Fi hotspot. Two problems arise
 * when that happens:
 *
 *  1. "The first interface IP" is whatever order the kernel enumerates -
 *     could be cellular (rmnet) or the router subnet, which clients on the
 *     hotspot cannot reach.
 *  2. A limited broadcast (255.255.255.255) leaves via the default-route
 *     interface (station/cellular), so hotspot clients never see it.
 *
 * Detection strategy:
 *  - Primary: ConnectivityManager reports the SoftAP interface as a Wi-Fi
 *    network WITHOUT the INTERNET capability (tethered/local networks are
 *    registered by the system), while the station Wi-Fi has INTERNET.
 *  - Fallback: well-known SoftAP interface names (ap0, softap0, swlan0).
 *  - Fallback: any wlan or ap interface that is NOT a station interface.
 */
@Singleton
class HotspotNetworkHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /** Interface names commonly used for the Wi-Fi SoftAP interface */
        private val KNOWN_HOTSPOT_INTERFACES = listOf("ap0", "softap0", "swlan0")
    }

    /**
     * Interface names carrying an active station (client) Wi-Fi connection,
     * i.e. Wi-Fi networks with internet capability.
     */
    fun getStationInterfaceNames(): Set<String> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return emptySet()
            val station = mutableSetOf<String>()
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    cm.getLinkProperties(network)?.interfaceName?.let { station.add(it) }
                }
            }
            station
        } catch (e: Exception) {
            Timber.w(e, "Failed to query station interfaces")
            emptySet()
        }
    }

    /**
     * The hotspot (SoftAP) as an android Network object, for APIs that
     * operate per-network (e.g. NsdManager.registerService on API 33+).
     * Null when no hotspot is active.
     */
    fun getHotspotNetwork(): android.net.Network? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    return network
                }
            }
            null
        } catch (e: Exception) {
            Timber.w(e, "Failed to get hotspot Network")
            null
        }
    }

    /**
     * The active hotspot (SoftAP) interface, or null when this device is not
     * running a hotspot (e.g. it is a client connected to one).
     */
    fun getHotspotInterface(): NetworkInterface? {
        // 1. Primary: Wi-Fi network without internet capability = tethered SoftAP
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    ) {
                        val name = cm.getLinkProperties(network)?.interfaceName ?: continue
                        val iface = findInterfaceByName(name)
                        if (iface != null) return iface
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to query hotspot interface via ConnectivityManager")
        }

        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .toList()
            val station = getStationInterfaceNames()

            // 2. Well-known SoftAP interface names
            interfaces.firstOrNull { it.name in KNOWN_HOTSPOT_INTERFACES }
                // 3. A Wi-Fi interface that is not the station interface
                ?: interfaces.firstOrNull { iface ->
                    (iface.name.startsWith("wlan") || iface.name.startsWith("ap")) &&
                        iface.name !in station
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to enumerate network interfaces")
            null
        }
    }

    /**
     * All local IPv4 site-local addresses, ordered so that the hotspot
     * interface comes first, then station Wi-Fi, then everything else
     * (cellular/ethernet last). Callers taking the first element always
     * advertise a hotspot-reachable address when a hotspot exists.
     */
    suspend fun getOrderedIpAddresses(): List<String> = withContext(Dispatchers.IO) {
        try {
            val hotspotName = getHotspotInterface()?.name
            val station = getStationInterfaceNames()

            val interfaces = NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { !it.isLoopback && it.isUp }
                .sortedWith(
                    compareByDescending<NetworkInterface> { it.name == hotspotName }
                        .thenByDescending { it.name in station }
                )
                .toList()

            interfaces.flatMap { iface ->
                iface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                    .mapNotNull { it.hostAddress }
                    .toList()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get local IP addresses")
            emptyList()
        }
    }

    /**
     * The single best address to advertise: the hotspot (SoftAP) address when
     * a hotspot is active, otherwise the first local address.
     */
    suspend fun getPreferredIpAddress(): String? = getOrderedIpAddresses().firstOrNull()

    /**
     * Subnet broadcast address of the hotspot network (e.g. 10.11.46.255),
     * or null when no hotspot is active. Sending to this address guarantees
     * the packet leaves via the hotspot interface, unlike 255.255.255.255
     * which follows the default route.
     */
    fun getHotspotBroadcastAddress(): InetAddress? {
        return try {
            getHotspotInterface()?.interfaceAddresses
                ?.firstOrNull { it.broadcast != null }
                ?.broadcast
        } catch (e: Exception) {
            Timber.w(e, "Failed to get hotspot broadcast address")
            null
        }
    }

    /**
     * Broadcast targets for discovery, hotspot subnet first:
     * limited broadcast + hotspot subnet broadcast (deduplicated).
     */
    fun getDiscoveryBroadcastTargets(): List<InetAddress> {
        val targets = mutableListOf<InetAddress>()
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
            getHotspotBroadcastAddress()?.let { bc ->
                if (targets.none { it == bc }) targets.add(bc)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to build broadcast targets")
        }
        return targets
    }

    /**
     * Every IPv4 address on this device (any interface). Used for
     * self-connection checks where any local address must match.
     */
    fun getAllLocalIpv4Addresses(): Set<String> {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .mapNotNull { it.hostAddress }
                .toSet()
        } catch (e: Exception) {
            Timber.w(e, "Failed to get all local IPv4 addresses")
            emptySet()
        }
    }

    private fun findInterfaceByName(name: String): NetworkInterface? =
        try {
            NetworkInterface.getByName(name)
        } catch (e: Exception) {
            null
        }
}
