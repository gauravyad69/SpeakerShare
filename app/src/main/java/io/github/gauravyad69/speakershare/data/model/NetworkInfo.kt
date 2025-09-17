package io.github.gauravyad69.speakershare.data.model

/**
 * Network configuration and discovery information.
 * Lifecycle: Created with session, updated on network changes
 */
data class NetworkInfo(
    val localIpAddress: String,
    val port: Int,                   // Host listening port
    val networkInterface: String,    // Wi-Fi interface name
    val isHotspot: Boolean,         // Device is hotspot
    val discoveryMethod: DiscoveryMethod,
    val serviceName: String         // mDNS service name
)

/**
 * Methods available for host discovery
 */
enum class DiscoveryMethod {
    MDNS,                           // Preferred method
    UDP_BROADCAST,                  // Fallback
    MANUAL_IP                       // User-entered IP
}
