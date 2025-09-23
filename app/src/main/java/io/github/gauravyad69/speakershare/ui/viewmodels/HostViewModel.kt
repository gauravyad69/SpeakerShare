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
                    // Stop broadcasting using repository
                    hostSessionRepository.stopBroadcasting()

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

                // TODO: Apply mute/unmute to actual audio stream
                // This would interact with the audio service/manager

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
                _audioSource.value = source

                // Use repository method to update audio source
                hostSessionRepository.updateAudioSource(source)

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
                // Use the correct repository method
                clientConnectionRepository.kickClient(clientId)

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

    // Helper functions
    private fun generateSessionId(): String = java.util.UUID.randomUUID().toString()
    private fun getDeviceName(): String = android.os.Build.MODEL
}