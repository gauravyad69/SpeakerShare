package io.github.gauravyad69.speakershare.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.gauravyad69.speakershare.data.model.*
import io.github.gauravyad69.speakershare.data.repository.ClientConnectionRepository
import io.github.gauravyad69.speakershare.data.repository.UserSettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.*

/**
 * ViewModel for Client screen - manages connection to host and audio playback
 */
@HiltViewModel
class ClientViewModel @Inject constructor(
    private val clientConnectionRepository: ClientConnectionRepository,
    private val userSettingsRepository: UserSettingsRepository
) : ViewModel() {

    // Current client connection
    private val _currentConnection = MutableStateFlow<ClientConnection?>(null)
    val currentConnection: StateFlow<ClientConnection?> = _currentConnection.asStateFlow()

    // User settings
    val userSettings: StateFlow<UserSettings?> = userSettingsRepository.getUserSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionState: StateFlow<ConnectionStatus> = _connectionState.asStateFlow()

    // Audio settings (local to this client)
    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    // Connected host info
    private val _connectedHost = MutableStateFlow<NetworkInfo?>(null)
    val connectedHost: StateFlow<NetworkInfo?> = _connectedHost.asStateFlow()

    // UI state
    private val _uiState = MutableStateFlow(ClientUiState())
    val uiState: StateFlow<ClientUiState> = _uiState.asStateFlow()

    // Actions
    fun connectToHost(hostInfo: NetworkInfo) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            _connectionState.value = ConnectionStatus.CONNECTING
            
            try {
                val clientConnection = ClientConnection(
                    clientId = generateClientId(),
                    clientName = getClientName(),
                    ipAddress = hostInfo.localIpAddress,
                    connectionTime = System.currentTimeMillis(),
                    status = ConnectionStatus.CONNECTING,
                    audioSettings = ClientAudioSettings(
                        volume = _volume.value,
                        isMuted = _isMuted.value
                    ),
                    networkMetrics = NetworkMetrics(
                        latency = 0,
                        packetLoss = 0.0f,
                        bandwidth = 0
                    )
                )
                
                // Save connection
                clientConnectionRepository.addClient(
                    clientId = clientConnection.clientId,
                    clientName = clientConnection.clientName,
                    ipAddress = clientConnection.ipAddress,
                    audioSettings = clientConnection.audioSettings
                )
                _currentConnection.value = clientConnection
                _connectedHost.value = hostInfo
                _connectionState.value = ConnectionStatus.CONNECTED
                
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
                _connectionState.value = ConnectionStatus.ERROR
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            _currentConnection.value?.let { connection ->
                clientConnectionRepository.updateClientStatus(
                    clientId = connection.clientId,
                    status = ConnectionStatus.DISCONNECTED
                )
            }
            
            _currentConnection.value = null
            _connectedHost.value = null
            _connectionState.value = ConnectionStatus.DISCONNECTED
        }
    }

    fun setVolume(volume: Float) {
        viewModelScope.launch {
            _volume.value = volume.coerceIn(0.0f, 1.0f)
            
            // Update connection with new audio settings
            _currentConnection.value?.let { connection ->
                val updatedSettings = connection.audioSettings.copy(volume = _volume.value)
                clientConnectionRepository.updateClientAudioSettings(
                    clientId = connection.clientId,
                    settings = updatedSettings
                )
                _currentConnection.value = connection.copy(audioSettings = updatedSettings)
            }
        }
    }

    fun toggleMute() {
        viewModelScope.launch {
            _isMuted.value = !_isMuted.value
            
            // Update connection with new audio settings
            _currentConnection.value?.let { connection ->
                val updatedSettings = connection.audioSettings.copy(isMuted = _isMuted.value)
                clientConnectionRepository.updateClientAudioSettings(
                    clientId = connection.clientId,
                    settings = updatedSettings
                )
                _currentConnection.value = connection.copy(audioSettings = updatedSettings)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun generateClientId(): String = UUID.randomUUID().toString()
    
    private fun getClientName(): String = userSettings.value?.displayName ?: "Client"
}

/**
 * UI state for Client screen
 */
data class ClientUiState(
    val isLoading: Boolean = false,
    val error: String? = null
)
