package io.github.gauravyad69.speakershare.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.gauravyad69.speakershare.data.model.*
import io.github.gauravyad69.speakershare.data.repository.UserSettingsRepository
import io.github.gauravyad69.speakershare.network.discovery.DiscoveredHost
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * ViewModel for Host Discovery functionality.
 * Manages discovering available hosts, connection attempts, and host selection.
 */
@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val userSettingsRepository: UserSettingsRepository
) : ViewModel() {

    // Available hosts
    private val _availableHosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val availableHosts: StateFlow<List<DiscoveredHost>> = _availableHosts.asStateFlow()

    // Discovery state
    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    // Selected host for connection
    private val _selectedHost = MutableStateFlow<DiscoveredHost?>(null)
    val selectedHost: StateFlow<DiscoveredHost?> = _selectedHost.asStateFlow()

    // Discovery method
    private val _discoveryMethod = MutableStateFlow<DiscoveryMethod>(DiscoveryMethod.MDNS)
    val discoveryMethod: StateFlow<DiscoveryMethod> = _discoveryMethod.asStateFlow()

    // Manual host input
    private val _manualHostAddress = MutableStateFlow("")
    val manualHostAddress: StateFlow<String> = _manualHostAddress.asStateFlow()

    // UI state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Connection attempt state
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    // Recently connected hosts (for quick reconnection)
    private val _recentHosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val recentHosts: StateFlow<List<DiscoveredHost>> = _recentHosts.asStateFlow()

    // Discovery refresh timer
    private val _lastDiscoveryTime = MutableStateFlow(0L)
    val lastDiscoveryTime: StateFlow<Long> = _lastDiscoveryTime.asStateFlow()

    init {
        loadRecentHosts()
        startAutoDiscovery()
    }

    /**
     * Load recently connected hosts from storage
     */
    private fun loadRecentHosts() {
        viewModelScope.launch {
            try {
                // TODO: Load from user settings or separate storage
                // For now, start with empty list
                _recentHosts.value = emptyList()
            } catch (e: Exception) {
                _error.value = "Failed to load recent hosts: ${e.message}"
            }
        }
    }

    /**
     * Start automatic host discovery
     */
    fun startDiscovery() {
        viewModelScope.launch {
            _isDiscovering.value = true
            _error.value = null
            
            try {
                when (_discoveryMethod.value) {
                    DiscoveryMethod.MDNS -> {
                        val hosts = performMDNSDiscovery()
                        _availableHosts.value = hosts
                    }
                    DiscoveryMethod.UDP_BROADCAST -> {
                        val hosts = performUDPBroadcastDiscovery()
                        _availableHosts.value = hosts
                    }
                    DiscoveryMethod.MANUAL_IP -> {
                        val hosts = performManualDiscovery()
                        _availableHosts.value = hosts
                    }
                }
                _lastDiscoveryTime.value = System.currentTimeMillis()
            } catch (e: Exception) {
                _error.value = "Discovery failed: ${e.message}"
            } finally {
                _isDiscovering.value = false
            }
        }
    }

    /**
     * Stop host discovery
     */
    fun stopDiscovery() {
        _isDiscovering.value = false
    }

    /**
     * Start automatic discovery with periodic refresh
     */
    private fun startAutoDiscovery() {
        viewModelScope.launch {
            while (true) {
                if (!_isDiscovering.value && _discoveryMethod.value == DiscoveryMethod.MDNS) {
                    startDiscovery()
                }
                delay(10000) // Refresh every 10 seconds
            }
        }
    }

    /**
     * Perform mDNS discovery
     */
    private suspend fun performMDNSDiscovery(): List<DiscoveredHost> {
        // TODO: Implement actual mDNS discovery
        // For now, return mock data
        delay(1000) // Simulate discovery time
        
        return listOf(
            DiscoveredHost(
                hostId = "mock-session-1",
                hostName = "John's Phone",
                ipAddress = "192.168.1.105",
                port = 8080,
                serviceName = "speakershare-mock-session-1",
                discoveryMethod = "MDNS",
                lastSeen = System.currentTimeMillis(),
                audioSource = "MICROPHONE",
                quality = "STANDARD",
                connectedClients = 0,
                maxClients = 0,
                isAcceptingClients = true
            )
        )
    }

    /**
     * Perform UDP broadcast discovery
     */
    private suspend fun performUDPBroadcastDiscovery(): List<DiscoveredHost> {
        // TODO: Implement actual UDP broadcast discovery
        // For now, return mock data
        delay(1500) // Simulate discovery time
        
        return listOf(
            DiscoveredHost(
                hostId = "mock-session-2",
                hostName = "Living Room Speaker",
                ipAddress = "192.168.1.110",
                port = 8080,
                serviceName = "speakershare-mock-session-2",
                discoveryMethod = "UDP_BROADCAST",
                lastSeen = System.currentTimeMillis(),
                audioSource = "SYSTEM_AUDIO",
                quality = "HIGH",
                connectedClients = 2,
                maxClients = 10,
                isAcceptingClients = true
            )
        )
    }

    /**
     * Perform manual discovery by connecting to specific address
     */
    private suspend fun performManualDiscovery(): List<DiscoveredHost> {
        if (_manualHostAddress.value.isBlank()) {
            throw Exception("Please enter a host address")
        }
        
        // TODO: Implement actual manual connection attempt
        // For now, create a mock host based on the address
        delay(2000) // Simulate connection attempt
        
        val parts = _manualHostAddress.value.split(":")
        val address = parts[0]
        val port = parts.getOrElse(1) { "8080" }.toIntOrNull() ?: 8080
        
        // Validate IP address format
        if (!isValidIPAddress(address)) {
            throw Exception("Invalid IP address format")
        }
        
        return listOf(
            DiscoveredHost(
                hostId = "manual-session",
                hostName = "Manual Host",
                ipAddress = address,
                port = port,
                serviceName = "speakershare-manual",
                discoveryMethod = "MANUAL_IP",
                lastSeen = System.currentTimeMillis(),
                audioSource = "MICROPHONE",
                quality = "STANDARD",
                connectedClients = 0,
                maxClients = 0,
                isAcceptingClients = true
            )
        )
    }

    /**
     * Select a host for connection
     */
    fun selectHost(host: DiscoveredHost) {
        _selectedHost.value = host
    }

    /**
     * Clear host selection
     */
    fun clearSelection() {
        _selectedHost.value = null
    }

    /**
     * Set discovery method
     */
    fun setDiscoveryMethod(method: DiscoveryMethod) {
        _discoveryMethod.value = method
        if (method != DiscoveryMethod.MANUAL_IP) {
            startDiscovery()
        }
    }

    /**
     * Set manual host address
     */
    fun setManualHostAddress(address: String) {
        _manualHostAddress.value = address.trim()
    }

    /**
     * Connect to manual host
     */
    fun connectToManualHost() {
        viewModelScope.launch {
            _isConnecting.value = true
            try {
                _discoveryMethod.value = DiscoveryMethod.MANUAL_IP
                val hosts = performManualDiscovery()
                if (hosts.isNotEmpty()) {
                    _availableHosts.value = hosts
                    _selectedHost.value = hosts.first()
                }
            } catch (e: Exception) {
                _error.value = "Failed to connect to manual host: ${e.message}"
            } finally {
                _isConnecting.value = false
            }
        }
    }

    /**
     * Add host to recent hosts list
     */
    fun addToRecentHosts(host: DiscoveredHost) {
        viewModelScope.launch {
            try {
                val currentRecent = _recentHosts.value.toMutableList()
                
                // Remove if already exists
                currentRecent.removeAll { it.hostId == host.hostId }
                
                // Add to front
                currentRecent.add(0, host.copy(lastSeen = System.currentTimeMillis()))
                
                // Keep only last 5
                _recentHosts.value = currentRecent.take(5)
                
                // TODO: Save to persistent storage
                
            } catch (e: Exception) {
                _error.value = "Failed to save recent host: ${e.message}"
            }
        }
    }

    /**
     * Remove host from recent hosts
     */
    fun removeFromRecentHosts(hostId: String) {
        val currentRecent = _recentHosts.value.toMutableList()
        currentRecent.removeAll { it.hostId == hostId }
        _recentHosts.value = currentRecent
    }

    /**
     * Clear all discovered hosts
     */
    fun clearDiscoveredHosts() {
        _availableHosts.value = emptyList()
        _selectedHost.value = null
    }

    /**
     * Refresh discovery
     */
    fun refreshDiscovery() {
        if (!_isDiscovering.value) {
            startDiscovery()
        }
    }

    /**
     * Clear error state
     */
    fun clearError() {
        _error.value = null
    }

    // Helper functions
    private fun isValidIPAddress(ip: String): Boolean {
        return ip.matches(Regex("^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"))
    }
}
