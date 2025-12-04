package io.github.gauravyad69.speakershare.ui.viewmodels

import android.content.Context
import timber.log.Timber
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.gauravyad69.speakershare.data.model.*
import io.github.gauravyad69.speakershare.data.repository.*
import io.github.gauravyad69.speakershare.network.api.HostApiHandler
import io.github.gauravyad69.speakershare.services.AudioForegroundService
import io.github.gauravyad69.speakershare.services.AudioStreamManager
import io.github.gauravyad69.speakershare.services.HostService
import io.github.gauravyad69.speakershare.services.NetworkDiscoveryService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Host mode functionality.
 * Manages host session, connected clients, audio controls, and broadcasting state.
 */
@HiltViewModel
class HostViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hostSessionRepository: HostSessionRepository,
    private val clientConnectionRepository: ClientConnectionRepository,
    private val audioStreamRepository: AudioStreamRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val hostApiHandler: HostApiHandler,
    private val hostService: HostService,
    private val networkDiscoveryService: NetworkDiscoveryService,
    private val audioStreamManager: AudioStreamManager
) : ViewModel() {

    // Host session state
    private val _hostSession = MutableStateFlow<HostSession?>(null)
    val hostSession: StateFlow<HostSession?> = _hostSession.asStateFlow()

    // Connected clients
    private val _connectedClients = MutableStateFlow<List<ClientConnection>>(emptyList())
    val connectedClients: StateFlow<List<ClientConnection>> = _connectedClients.asStateFlow()

    // Audio stream state
    private val _audioStream = MutableStateFlow<AudioStream?>(null)
    val audioStream: StateFlow<AudioStream?> = _audioStream.asStateFlow()

    // Broadcasting state
    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    // Audio source
    private val _audioSource = MutableStateFlow(AudioSource.MICROPHONE)
    val audioSource: StateFlow<AudioSource> = _audioSource.asStateFlow()

    // Audio controls
    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    // UI state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Client acceptance state
    private val _isAcceptingClients = MutableStateFlow(true)
    val isAcceptingClients: StateFlow<Boolean> = _isAcceptingClients.asStateFlow()

    init {
        loadHostSession()
        observeConnectedClients()
        observeAudioStream()
        observeTransferEvents()
    }

    /**
     * Load existing host session or create new one
     */
    private fun loadHostSession() {
        viewModelScope.launch {
            try {
                hostSessionRepository.getCurrentSession().collect { session ->
                    _hostSession.value = session
                    session?.let {
                        _isBroadcasting.value = it.isActive
                        _audioSource.value = it.audioSource
                    }
                }
            } catch (e: Exception) {
                _error.value = "Failed to load host session: ${e.message}"
            }
        }
    }

    /**
     * Observe connected clients
     */
    private fun observeConnectedClients() {
        viewModelScope.launch {
            try {
                // Use hostService.connectedClients which is updated when UDP clients connect
                hostService.connectedClients.collect { clients ->
                    _connectedClients.value = clients
                }
            } catch (e: Exception) {
                _error.value = "Failed to load connected clients: ${e.message}"
            }
        }
    }

    /**
     * Observe audio stream state
     */
    private fun observeAudioStream() {
        viewModelScope.launch {
            try {
                audioStreamRepository.getCurrentStream().collect { stream ->
                    _audioStream.value = stream
                }
            } catch (e: Exception) {
                _error.value = "Failed to load audio stream: ${e.message}"
            }
        }
    }

    /**
     * Observe transfer events from HostService to update transfer status
     */
    private fun observeTransferEvents() {
        viewModelScope.launch {
            hostService.transferEvents.collect { event ->
                when (event) {
                    is io.github.gauravyad69.speakershare.services.TransferEvent.Accepted -> {
                        // Transfer was accepted - handle it in the ViewModel
                        handleTransferAccepted(event.clientId, event.clientAddress, event.newServerPort)
                    }
                    is io.github.gauravyad69.speakershare.services.TransferEvent.Rejected -> {
                        handleTransferRejected(event.clientId)
                    }
                    null -> { /* No event */ }
                }
            }
        }
    }

    /**
     * Start hosting session
     */
    fun startHosting(hostName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Use HostService to start hosting
                val result = hostService.startHosting(
                    hostName = hostName,
                    audioSource = _audioSource.value
                )
                
                result.onSuccess { session ->
                    _hostSession.value = session
                    _isBroadcasting.value = true
                }.onFailure { error ->
                    _error.value = "Failed to create session: ${error.message}"
                }

            } catch (e: Exception) {
                _error.value = "Failed to start hosting: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Stop hosting session
     */
    fun stopHosting() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _hostSession.value?.let { session ->
                    // Stop hosting using HostService
                    hostService.stopHosting()

                    _isBroadcasting.value = false
                }
            } catch (e: Exception) {
                _error.value = "Failed to stop hosting: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Toggle mute state
     */
    fun toggleMute() {
        viewModelScope.launch {
            try {
                // Toggle mute directly in AudioStreamManager
                val newMuteState = audioStreamManager.toggleMute()
                _isMuted.value = newMuteState

                // Also notify the foreground service for notification update
                val muteIntent = AudioForegroundService.toggleMute(context)
                context.startService(muteIntent)

            } catch (e: Exception) {
                _error.value = "Failed to toggle mute: ${e.message}"
            }
        }
    }

    /**
     * Set volume level
     */
    fun setVolume(volume: Float) {
        viewModelScope.launch {
            try {
                val clampedVolume = volume.coerceIn(0f, 1f)
                _volume.value = clampedVolume

                // TODO: Apply volume change to actual audio stream
                // This would interact with the audio service/manager

            } catch (e: Exception) {
                _error.value = "Failed to set volume: ${e.message}"
            }
        }
    }

    /**
     * Switch audio source
     */
    fun switchAudioSource(source: AudioSource) {
        viewModelScope.launch {
            try {
                Timber.d("Switching audio source to $source")
                
                // Actually switch the audio source via HostService
                val result = hostService.switchAudioSource(source)
                
                if (result.isSuccess) {
                    _audioSource.value = source
                    // Also update repository state
                    hostSessionRepository.updateAudioSource(source)
                    Timber.d("Audio source switched successfully to $source")
                } else {
                    _error.value = "Failed to switch audio source: ${result.exceptionOrNull()?.message}"
                    Timber.e("Failed to switch audio source", result.exceptionOrNull())
                }

            } catch (e: Exception) {
                _error.value = "Failed to switch audio source: ${e.message}"
                Timber.e("Failed to switch audio source", e)
            }
        }
    }
    
    /**
     * Update audio source state without calling service (used when foreground service handles the switch)
     */
    fun updateAudioSourceState(source: AudioSource) {
        viewModelScope.launch {
            _audioSource.value = source
            hostSessionRepository.updateAudioSource(source)
            Timber.d("Audio source state updated to $source")
        }
    }

    /**
     * Initialize MediaProjection using the result from the permission activity.
     * This delegates to HostService which will forward to the AudioCaptureService.
     */
    fun initializeMediaProjection(resultCode: Int, data: Intent) {
        viewModelScope.launch {
            try {
                val result = hostService.initializeMediaProjection(resultCode, data)
                if (result.isSuccess) {
                    Timber.d("MediaProjection initialized successfully")
                } else {
                    _error.value = "Failed to initialize MediaProjection: ${result.exceptionOrNull()?.message}"
                    Timber.e("Failed to initialize MediaProjection", result.exceptionOrNull())
                }
            } catch (e: Exception) {
                _error.value = "Failed to initialize MediaProjection: ${e.message}"
                Timber.e("Failed to initialize MediaProjection", e)
            }
        }
    }

    /**
     * Toggle client acceptance
     */
    fun toggleClientAcceptance() {
        viewModelScope.launch {
            try {
                val newAcceptanceState = !_isAcceptingClients.value
                _isAcceptingClients.value = newAcceptanceState

                // TODO: Implement client acceptance toggle
                // This would need to be added to the HostSessionRepository interface

            } catch (e: Exception) {
                _error.value = "Failed to toggle client acceptance: ${e.message}"
            }
        }
    }

    /**
     * Kick client by ID
     */
    fun kickClient(clientId: String) {
        viewModelScope.launch {
            try {
                // First kick via HostService (which sends UDP kick message)
                val result = hostService.kickClient(clientId, "Kicked by host")
                if (result.isSuccess) {
                    // Also update local repository state
                    clientConnectionRepository.kickClient(clientId)
                } else {
                    _error.value = "Failed to kick client: ${result.exceptionOrNull()?.message}"
                }

            } catch (e: Exception) {
                _error.value = "Failed to kick client: ${e.message}"
            }
        }
    }

    /**
     * Disconnect all clients
     */
    private suspend fun disconnectAllClients() {
        try {
            // TODO: Implement disconnect all clients
            // This would need to be added to repository interfaces

        } catch (e: Exception) {
            _error.value = "Failed to disconnect clients: ${e.message}"
        }
    }

    /**
     * Create audio stream
     */
    private fun createAudioStream() {
        viewModelScope.launch {
            val settings = userSettingsRepository.getCurrentSettings()
            val quality = settings.defaultQuality
            val source = settings.defaultAudioSource

            val result = audioStreamRepository.createStream(
                sessionId = _hostSession.value?.sessionId ?: "",
                source = source,
                transport = StreamTransport.WEBRTC, // Default to WebRTC
                quality = quality
            )

            result.onSuccess { stream ->
                // Note: HostSession doesn't have audioStream property
                // The stream is managed separately by the repository
                _isBroadcasting.value = true
            }.onFailure { e ->
                _error.value = "Failed to create audio stream: ${e.message}"
            }
        }
    }



    /**
     * Stop audio stream
     */
    private fun stopAudioStream() {
        viewModelScope.launch {
            val result = audioStreamRepository.stopStream()
            result.onSuccess {
                _isBroadcasting.value = false
            }.onFailure { e ->
                _error.value = "Failed to stop audio stream: ${e.message}"
            }
        }
    }

    /**
     * Clear error state
     */
    fun clearError() {
        _error.value = null
    }
    
    // Host transfer state
    private val _pendingTransferClientId = MutableStateFlow<String?>(null)
    val pendingTransferClientId: StateFlow<String?> = _pendingTransferClientId.asStateFlow()
    
    private val _transferStatus = MutableStateFlow<TransferStatus>(TransferStatus.Idle)
    val transferStatus: StateFlow<TransferStatus> = _transferStatus.asStateFlow()
    
    /**
     * Request to transfer host role to a specific client
     */
    fun requestTransferHost(clientId: String) {
        viewModelScope.launch {
            try {
                _pendingTransferClientId.value = clientId
                _transferStatus.value = TransferStatus.Requesting
                
                val result = hostService.requestTransferHost(clientId)
                result.onSuccess {
                    _transferStatus.value = TransferStatus.WaitingForResponse
                    // Wait for TransferAccepted or TransferRejected event from UdpAudioServer
                }.onFailure { error ->
                    _pendingTransferClientId.value = null
                    _transferStatus.value = TransferStatus.Failed(error.message ?: "Unknown error")
                    _error.value = "Failed to send transfer request"
                }
            } catch (e: Exception) {
                _pendingTransferClientId.value = null
                _transferStatus.value = TransferStatus.Failed(e.message ?: "Unknown error")
                _error.value = "Failed to request host transfer: ${e.message}"
            }
        }
    }
    
    /**
     * Handle transfer accepted by client - called when UdpServerEvent.TransferAccepted is received
     */
    fun handleTransferAccepted(clientId: String, newHostIp: String, newHostPort: Int) {
        viewModelScope.launch {
            _transferStatus.value = TransferStatus.Completing
            
            // Get the client name for display
            val client = _connectedClients.value.find { it.clientId == clientId }
            val newHostName = client?.clientName ?: "New Host"
            
            val result = hostService.handleTransferAccepted(clientId, newHostIp, newHostPort)
            result.onSuccess {
                _pendingTransferClientId.value = null
                // Signal that we should become a client of the new host
                // Use HTTP API port (8080) not the UDP audio port
                val httpApiPort = 8080
                _transferStatus.value = TransferStatus.BecomeClient(newHostIp, httpApiPort, newHostName)
            }.onFailure { error ->
                _transferStatus.value = TransferStatus.Failed(error.message ?: "Transfer completion failed")
                _error.value = "Failed to complete transfer: ${error.message}"
            }
        }
    }
    
    /**
     * Handle transfer rejected by client - called when UdpServerEvent.TransferRejected is received
     */
    fun handleTransferRejected(clientId: String) {
        hostService.handleTransferRejected(clientId)
        _pendingTransferClientId.value = null
        _transferStatus.value = TransferStatus.Rejected
        _error.value = "Client rejected host transfer request"
    }
    
    /**
     * Cancel pending transfer request
     */
    fun cancelTransferRequest() {
        _pendingTransferClientId.value = null
        _transferStatus.value = TransferStatus.Idle
    }

    // Combined UI State
    val uiState: StateFlow<HostUiState> = combine(
        isBroadcasting,
        connectedClients,
        audioSource,
        isMuted,
        isLoading
    ) { args ->
        val isBroadcasting = args[0] as Boolean
        @Suppress("UNCHECKED_CAST")
        val connectedClients = args[1] as List<ClientConnection>
        val audioSource = args[2] as AudioSource
        val isMuted = args[3] as Boolean
        val isLoading = args[4] as Boolean

        HostUiState(
            isHosting = isBroadcasting,
            connectedClients = connectedClients,
            audioSource = audioSource,
            isMuted = isMuted,
            isLoading = isLoading,
            error = _error.value,
            networkInfo = _hostSession.value?.networkInfo
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HostUiState()
    )

    // Helper functions
    private fun generateSessionId(): String = java.util.UUID.randomUUID().toString()
    private fun getDeviceName(): String = android.os.Build.MODEL
}

data class HostUiState(
    val isHosting: Boolean = false,
    val connectedClients: List<ClientConnection> = emptyList(),
    val audioSource: AudioSource = AudioSource.MICROPHONE,
    val isMuted: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val networkInfo: NetworkInfo? = null
)

/**
 * Represents the status of a host transfer operation
 */
sealed class TransferStatus {
    data object Idle : TransferStatus()
    data object Requesting : TransferStatus()
    data object WaitingForResponse : TransferStatus()
    data object Completing : TransferStatus()
    data object Completed : TransferStatus()
    data object Rejected : TransferStatus()
    data class Failed(val reason: String) : TransferStatus()
    
    /** Old host should now become a client connecting to the new host */
    data class BecomeClient(val newHostIp: String, val newHostPort: Int, val newHostName: String) : TransferStatus()
}