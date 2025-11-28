package io.github.gauravyad69.speakershare.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.gauravyad69.speakershare.data.model.*
import io.github.gauravyad69.speakershare.data.repository.ClientConnectionRepository
import io.github.gauravyad69.speakershare.data.repository.UserSettingsRepository
import io.github.gauravyad69.speakershare.services.ClientManager
import io.github.gauravyad69.speakershare.services.TransferRequest
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.*

/**
 * ViewModel for Client screen - manages connection to host and audio playback
 */
@HiltViewModel
class ClientViewModel @Inject constructor(
    private val clientManager: ClientManager,
    private val userSettingsRepository: UserSettingsRepository
) : ViewModel() {

    // Current client connection
    val currentConnection: StateFlow<ClientConnection?> = clientManager.currentConnection

    // User settings
    val userSettings: StateFlow<UserSettings?> = userSettingsRepository.getUserSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    // Connection state
    val connectionState: StateFlow<ConnectionStatus> = clientManager.isConnected.map { connected ->
        if (connected) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), ConnectionStatus.DISCONNECTED)

    // Audio settings (local to this client)
    val volume: StateFlow<Float> = clientManager.audioSettings.map { it.volume }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 1.0f)

    val isMuted: StateFlow<Boolean> = clientManager.audioSettings.map { it.isMuted }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    // Audio level for visualization
    val audioLevel: StateFlow<Float> = clientManager.audioLevel

    // Connected host info
    private val _connectedHost = MutableStateFlow<NetworkInfo?>(null)
    val connectedHost: StateFlow<NetworkInfo?> = _connectedHost.asStateFlow()

    // Pending host transfer request
    val pendingTransferRequest: StateFlow<TransferRequest?> = clientManager.pendingTransferRequest
    
    // Callback when user accepts becoming a host
    private var onBecomeHostListener: ((String) -> Unit)? = null

    // UI state
    private val _uiState = MutableStateFlow(ClientUiState())
    val uiState: StateFlow<ClientUiState> = _uiState.asStateFlow()

    // Actions
    fun connectToHost(hostInfo: NetworkInfo) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            try {
                val hostSession = HostSession(
                    sessionId = hostInfo.serviceName,
                    sessionName = hostInfo.serviceName,
                    hostName = hostInfo.serviceName,
                    audioSource = AudioSource.MICROPHONE, // Default
                    quality = AudioQuality(), // Default
                    isActive = true,
                    startTime = System.currentTimeMillis(),
                    connectedClients = emptyList(),
                    networkInfo = hostInfo,
                    maxClients = 50
                )
                
                val clientName = userSettings.value?.displayName ?: "Client"
                
                val result = clientManager.connectToHost(hostSession, clientName)
                
                if (result.isSuccess) {
                    _connectedHost.value = hostInfo
                } else {
                    _uiState.value = _uiState.value.copy(error = result.exceptionOrNull()?.message)
                }
                
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            clientManager.disconnect()
            _connectedHost.value = null
        }
    }

    fun setVolume(volume: Float) {
        viewModelScope.launch {
            clientManager.setVolume(volume)
        }
    }

    fun toggleMute() {
        viewModelScope.launch {
            clientManager.toggleMute()
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    // ========== Host Transfer Methods ==========
    
    /**
     * Set callback for when user needs to become a host.
     * This is called from the UI layer to handle MediaProjection permission.
     */
    fun setOnBecomeHostListener(listener: (String) -> Unit) {
        onBecomeHostListener = listener
        clientManager.setOnBecomeHostCallback(listener)
    }
    
    /**
     * Accept the pending host transfer request
     */
    fun acceptTransferRequest() {
        viewModelScope.launch {
            val result = clientManager.acceptTransferRequest()
            result.onFailure { error ->
                _uiState.value = _uiState.value.copy(error = error.message)
            }
        }
    }
    
    /**
     * Reject the pending host transfer request
     */
    fun rejectTransferRequest() {
        viewModelScope.launch {
            val result = clientManager.rejectTransferRequest()
            result.onFailure { error ->
                _uiState.value = _uiState.value.copy(error = error.message)
            }
        }
    }
}

/**
 * UI state for Client screen
 */
data class ClientUiState(
    val isLoading: Boolean = false,
    val error: String? = null
)
