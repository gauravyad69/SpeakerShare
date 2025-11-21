package io.github.gauravyad69.speakershare.network.discovery

data class DiscoveredHost(
    val hostId: String,
    val hostName: String,
    val ipAddress: String,
    val port: Int,
    val serviceName: String,
    val discoveryMethod: String,
    val lastSeen: Long,
    val audioSource: String,
    val quality: String,
    val connectedClients: Int,
    val maxClients: Int,
    val isAcceptingClients: Boolean
)
