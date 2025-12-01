package io.github.gauravyad69.speakershare.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.gauravyad69.speakershare.data.model.*
import io.github.gauravyad69.speakershare.data.repository.ClientConnectionRepository
import io.github.gauravyad69.speakershare.data.repository.UserSettingsRepository
import io.github.gauravyad69.speakershare.services.ClientManager
import io.github.gauravyad69.speakershare.services.TransferRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.*
import io.github.gauravyad69.speakershare.network.ScreenStreamClient
import android.graphics.Bitmap

private const val TAG = "ClientViewModel"

/**
 * ViewModel for Client screen - manages connection to host and audio playback
 */
@HiltViewModel
class ClientViewModel @Inject constructor(
    private val clientManager: ClientManager,
    private val userSettingsRepository: UserSettingsRepository,
    private val screenStreamClient: ScreenStreamClient
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
    
    // Screen streaming state
    val isScreenStreaming: StateFlow<Boolean> = screenStreamClient.isStreaming
    val isScreenShareAvailable: StateFlow<Boolean> = screenStreamClient.isScreenShareAvailable
    
    // Current screen frame
    private val _currentScreenFrame = MutableStateFlow<Bitmap?>(null)
    val currentScreenFrame: StateFlow<Bitmap?> = _currentScreenFrame.asStateFlow()
    
    init {
        // Collect screen frames
        viewModelScope.launch {
            screenStreamClient.screenFrameFlow.collect { bitmap ->
                _currentScreenFrame.value = bitmap
            }
        }
    }

    // Actions
    fun connectToHost(hostInfo: NetworkInfo, retryOnFailure: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            // For transfer reconnect, wait initially for new host to start
            if (retryOnFailure) {
                Log.d(TAG, "Waiting 3 seconds for new host to start before connecting...")
                delay(3000L)
            }
            
            val maxRetries = if (retryOnFailure) 10 else 1  // More retries for transfer
            var lastError: Throwable? = null
            
            for (attempt in 1..maxRetries) {
                try {
                    if (attempt > 1) {
                        // Wait before retry with exponential backoff (up to 3 seconds)
                        val delayMs = (1000L * attempt).coerceAtMost(3000L)
                        Log.d(TAG, "Connection attempt $attempt failed, retrying in ${delayMs}ms...")
                        delay(delayMs)
                    }
                    
                    Log.d(TAG, "Connecting to host at ${hostInfo.localIpAddress}:${hostInfo.port} (attempt $attempt/$maxRetries)")
                    
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
                        Log.d(TAG, "Successfully connected to host on attempt $attempt")
                        _connectedHost.value = hostInfo
                        _uiState.value = _uiState.value.copy(isLoading = false)
                        return@launch // Success!
                    } else {
                        lastError = result.exceptionOrNull()
                        Log.w(TAG, "Connection attempt $attempt failed: ${lastError?.message}")
                    }
                    
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Connection attempt $attempt threw exception: ${e.message}")
                }
            }
            
            // All retries failed
            Log.e(TAG, "All $maxRetries connection attempts failed")
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = if (retryOnFailure) 
                    "New host not ready. Please try manually connecting." 
                else 
                    lastError?.message ?: "Failed to connect after $maxRetries attempts"
            )
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
    
    // ========== Screen Streaming Methods ==========
    
    /**
     * Check if screen sharing is available from host
     */
    fun checkScreenShareAvailable() {
        val host = _connectedHost.value ?: return
        viewModelScope.launch {
            screenStreamClient.checkScreenShareAvailable(host.localIpAddress, host.port)
        }
    }
    
    /**
     * Start viewing host's screen
     */
    fun startScreenViewing() {
        val host = _connectedHost.value ?: return
        screenStreamClient.startStreaming(host.localIpAddress, host.port)
    }
    
    /**
     * Stop viewing host's screen
     */
    fun stopScreenViewing() {
        screenStreamClient.stopStreaming()
        _currentScreenFrame.value = null
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
