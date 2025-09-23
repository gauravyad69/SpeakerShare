package io.github.gauravyad69.speakershare.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.gauravyad69.speakershare.data.model.*
import io.github.gauravyad69.speakershare.data.repository.*
import io.github.gauravyad69.speakershare.network.api.HostApiHandler
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Host mode functionality.
 * Manages host session, connected clients, audio controls, and broadcasting state.
 */
@HiltViewModel
class HostViewModel @Inject constructor(
    private val hostSessionRepository: HostSessionRepository,
    private val clientConnectionRepository: ClientConnectionRepository,
    private val audioStreamRepository: AudioStreamRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val hostApiHandler: HostApiHandler
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
                clientConnectionRepository.getConnectedClients().collect { clients ->
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
     * Start hosting session
     */
    fun startHosting() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Create network info
                val networkInfo = NetworkInfo(
                    localIpAddress = "192.168.1.100", // This would come from network detection
                    port = 8080,
                    networkInterface = "wlan0",
                    isHotspot = false,
                    discoveryMethod = DiscoveryMethod.MDNS,
                    serviceName = "SpeakerShare-${getDeviceName()}"
                )
                
                // Create session using repository
                val result = hostSessionRepository.createSession(
                    hostName = getDeviceName(),
                    audioSource = _audioSource.value,
                    quality = AudioQuality(),
                    networkInfo = networkInfo
                )
                
                result.onSuccess { session ->
                    _hostSession.value = session
                    // Start broadcasting
                    hostSessionRepository.startBroadcasting()
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
                    // Update session as inactive
                    val updatedSession = session.copy(
                        isActive = false,
                        lastUpdated = System.currentTimeMillis()
                    )
                    hostSessionRepository.updateSession(updatedSession)
                    
                    // Disconnect all clients
                    disconnectAllClients()
                    
                    // Stop audio stream
                    stopAudioStream()
                    
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
                val newMuteState = !_isMuted.value
                _isMuted.value = newMuteState
                
                _audioStream.value?.let { stream ->
                    val updatedStream = stream.copy(
                        isMuted = newMuteState,
                        lastUpdated = System.currentTimeMillis()
                    )
                    audioStreamRepository.updateStream(updatedStream)
                }
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
                
                _audioStream.value?.let { stream ->
                    val updatedStream = stream.copy(
                        volume = clampedVolume,
                        lastUpdated = System.currentTimeMillis()
                    )
                    audioStreamRepository.updateStream(updatedStream)
                }
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
                _audioSource.value = source
                
                _hostSession.value?.let { session ->
                    val updatedSession = session.copy(
                        audioSource = source,
                        lastUpdated = System.currentTimeMillis()
                    )
                    hostSessionRepository.updateSession(updatedSession)
                }
            } catch (e: Exception) {
                _error.value = "Failed to switch audio source: ${e.message}"
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
                
                _hostSession.value?.let { session ->
                    val updatedSession = session.copy(
                        isAcceptingClients = newAcceptanceState,
                        lastUpdated = System.currentTimeMillis()
                    )
                    hostSessionRepository.updateSession(updatedSession)
                }
            } catch (e: Exception) {
                _error.value = "Failed to toggle client acceptance: ${e.message}"
            }
        }
    }

    /**
     * Kick a specific client
     */
    fun kickClient(clientId: String) {
        viewModelScope.launch {
            try {
                // Use the API handler to disconnect the client
                hostApiHandler.disconnectClient(clientId)
                
                // Remove from local repository
                clientConnectionRepository.removeConnection(clientId)
                
            } catch (e: Exception) {
                _error.value = "Failed to kick client: ${e.message}"
            }
        }
    }

    /**
     * Disconnect all clients
     */
    private fun disconnectAllClients() {
        viewModelScope.launch {
            try {
                _connectedClients.value.forEach { client ->
                    hostApiHandler.disconnectClient(client.id)
                }
                clientConnectionRepository.clearAllConnections()
            } catch (e: Exception) {
                _error.value = "Failed to disconnect all clients: ${e.message}"
            }
        }
    }

    /**
     * Create audio stream
     */
    private fun createAudioStream() {
        viewModelScope.launch {
            try {
                val stream = AudioStream(
                    streamId = generateStreamId(),
                    sessionId = _hostSession.value?.sessionId ?: "",
                    format = AudioFormat.AAC,
                    quality = AudioQuality.STANDARD,
                    sampleRate = 44100,
                    bitrate = 128000,
                    channels = 2,
                    volume = _volume.value,
                    isMuted = _isMuted.value,
                    isActive = true,
                    createdAt = System.currentTimeMillis(),
                    lastUpdated = System.currentTimeMillis()
                )
                
                audioStreamRepository.createStream(stream)
                _audioStream.value = stream
            } catch (e: Exception) {
                _error.value = "Failed to create audio stream: ${e.message}"
            }
        }
    }

    /**
     * Stop audio stream
     */
    private fun stopAudioStream() {
        viewModelScope.launch {
            try {
                _audioStream.value?.let { stream ->
                    val stoppedStream = stream.copy(
                        isActive = false,
                        lastUpdated = System.currentTimeMillis()
                    )
                    audioStreamRepository.updateStream(stoppedStream)
                }
            } catch (e: Exception) {
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

    // Helper functions
    private fun generateSessionId(): String = java.util.UUID.randomUUID().toString()
    private fun generateStreamId(): String = java.util.UUID.randomUUID().toString()
    private fun getDeviceName(): String = android.os.Build.MODEL
}
