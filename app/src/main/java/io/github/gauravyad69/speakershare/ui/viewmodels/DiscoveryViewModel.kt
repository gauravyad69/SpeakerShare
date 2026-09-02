package io.github.gauravyad69.speakershare.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.gauravyad69.speakershare.data.model.*
import io.github.gauravyad69.speakershare.data.repository.UserSettingsRepository
import io.github.gauravyad69.speakershare.network.discovery.DiscoveredHost
import io.github.gauravyad69.speakershare.services.NetworkDiscoveryService
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
    private val userSettingsRepository: UserSettingsRepository,
    private val networkDiscoveryService: NetworkDiscoveryService
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
        observeDiscoveredHosts()
    }

    /**
     * Observe discovered hosts from NetworkDiscoveryService
     */
    private fun observeDiscoveredHosts() {
        viewModelScope.launch {
            networkDiscoveryService.discoveredHosts.collect { networkInfoList ->
                // Convert NetworkInfo to DiscoveredHost
                val hosts = networkInfoList.map { networkInfo ->
                    DiscoveredHost(
                        hostId = networkInfo.serviceName,
                        hostName = networkInfo.serviceName,
                        ipAddress = networkInfo.localIpAddress,
                        port = networkInfo.port,
                        serviceName = networkInfo.serviceName,
                        discoveryMethod = networkInfo.discoveryMethod.name,
                        lastSeen = System.currentTimeMillis(),
                        audioSource = "MICROPHONE",
                        quality = "STANDARD",
                        connectedClients = 0,
                        maxClients = 50,
                        isAcceptingClients = true
                    )
                }
                _availableHosts.value = hosts
            }
        }
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
                    DiscoveryMethod.MANUAL_IP -> {
                        // Manual entry is handled directly by the UI dialog
                    }
                    else -> {
                        // Both mDNS and UDP broadcast results arrive via
                        // observeDiscoveredHosts(); no local re-mapping needed
                        // (the old branches fabricated Source/Quality values)
                        networkDiscoveryService.startDiscovery()
                        delay(3000) // Wait for mDNS/UDP results
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

}
