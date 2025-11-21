package io.github.gauravyad69.speakershare.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.gauravyad69.speakershare.data.model.ClientConnection
import io.github.gauravyad69.speakershare.data.model.HostSession
import io.github.gauravyad69.speakershare.services.AudioForegroundService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing connected clients in host mode
 * Provides client list management, session monitoring, and client control operations
 */
@HiltViewModel
class ClientsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioService: AudioForegroundService
) : ViewModel() {

    /**
     * UI State for the Clients Screen
     */
    data class ClientsUiState(
        val clients: List<ClientConnection> = emptyList(),
        val sessionName: String = "",
        val maxClients: Int = 0, // 0 = unlimited
        val isSessionActive: Boolean = false,
        val totalBandwidth: Float = 0f,
        val isLoading: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(ClientsUiState())
    val uiState: StateFlow<ClientsUiState> = _uiState.asStateFlow()

    private var monitoringStarted = false

    /**
     * Start monitoring the session and connected clients
     */
    fun startMonitoring() {
        if (monitoringStarted) return
        
        viewModelScope.launch {
            // Monitor session changes
            audioService.currentSession.collect { session ->
                updateUIStateFromSession(session)
            }
        }
        
        monitoringStarted = true
    }

    /**
     * Refresh client list manually
     */
    fun refreshClients() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                // Force refresh the current session data
                val currentSession = audioService.getCurrentSessionInfo()
                updateUIStateFromSession(currentSession)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to refresh clients: ${e.message}",
                    isLoading = false
                )
            }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /**
     * Stop the current broadcasting session
     */
    fun stopSession() {
        viewModelScope.launch {
            try {
                // Send stop broadcast intent to the service
                val stopIntent = AudioForegroundService.stopBroadcast(context)
                context.startService(stopIntent)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to stop session: ${e.message}"
                )
            }
        }
    }

    /**
     * Kick a single client by ID
     */
    fun kickClient(clientId: String) {
        viewModelScope.launch {
            try {
                audioService.kickClient(clientId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to kick client: ${e.message}"
                )
            }
        }
    }

    /**
     * Kick multiple clients by their IDs
     */
    fun kickClients(clientIds: List<String>) {
        viewModelScope.launch {
            try {
                clientIds.forEach { clientId ->
                    audioService.kickClient(clientId)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to kick clients: ${e.message}"
                )
            }
        }
    }

    /**
     * Kick all connected clients
     */
    fun kickAllClients() {
        viewModelScope.launch {
            try {
                val clientIds = _uiState.value.clients.map { it.clientId }
                clientIds.forEach { clientId ->
                    audioService.kickClient(clientId)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to kick all clients: ${e.message}"
                )
            }
        }
    }

    /**
     * Mute a specific client
     */
    fun muteClient(clientId: String) {
        viewModelScope.launch {
            try {
                audioService.muteClient(clientId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to mute client: ${e.message}"
                )
            }
        }
    }

    /**
     * Unmute a specific client
     */
    fun unmuteClient(clientId: String) {
        viewModelScope.launch {
            try {
                audioService.unmuteClient(clientId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to unmute client: ${e.message}"
                )
            }
        }
    }

    /**
     * Mute all connected clients
     */
    fun muteAllClients() {
        viewModelScope.launch {
            try {
                val clientIds = _uiState.value.clients.map { it.clientId }
                clientIds.forEach { clientId ->
                    audioService.muteClient(clientId)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to mute all clients: ${e.message}"
                )
            }
        }
    }

    /**
     * Unmute all connected clients
     */
    fun unmuteAllClients() {
        viewModelScope.launch {
            try {
                val clientIds = _uiState.value.clients.map { it.clientId }
                clientIds.forEach { clientId ->
                    audioService.unmuteClient(clientId)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to unmute all clients: ${e.message}"
                )
            }
        }
    }

    /**
     * Clear the current error state
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Update UI state from session data
     */
    private fun updateUIStateFromSession(session: HostSession?) {
        _uiState.value = _uiState.value.copy(
            sessionName = session?.hostName ?: "No Session",
            maxClients = session?.maxClients ?: 0,
            isSessionActive = session?.isActive ?: false,
            clients = session?.connectedClients ?: emptyList(),
            totalBandwidth = calculateTotalBandwidth(session?.connectedClients ?: emptyList())
        )
    }

    /**
     * Calculate total bandwidth usage from all clients
     */
    private fun calculateTotalBandwidth(clients: List<ClientConnection>): Float {
        return clients.sumOf { it.networkMetrics.bandwidth }.toFloat() / (1024 * 1024) // Convert to MB/s
    }
}