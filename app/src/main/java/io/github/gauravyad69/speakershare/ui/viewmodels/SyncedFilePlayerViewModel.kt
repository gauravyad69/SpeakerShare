package io.github.gauravyad69.speakershare.ui.viewmodels

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.gauravyad69.speakershare.media.sync.*
import io.github.gauravyad69.speakershare.services.NetworkDiscoveryService
import io.github.gauravyad69.speakershare.data.model.NetworkInfo
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Synchronized File Playback
 * 
 * Manages both host and client modes for synced media playback.
 */
@HiltViewModel
class SyncedFilePlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncedPlaybackManager: SyncedPlaybackManager,
    private val fileTransfer: SyncedFileTransfer,
    private val playerFactory: SyncedMediaPlayerFactory,
    private val discoveryService: NetworkDiscoveryService
) : ViewModel() {
    
    companion object {
        private const val TAG = "SyncedFilePlayerVM"
    }
    
    // UI State
    private val _uiState = MutableStateFlow(SyncedPlayerUiState())
    val uiState: StateFlow<SyncedPlayerUiState> = _uiState.asStateFlow()
    
    // Player instance
    private var player: SyncedMediaPlayer? = null
    
    // Selected files
    private val _selectedFiles = MutableStateFlow<List<SyncedMediaFile>>(emptyList())
    val selectedFiles: StateFlow<List<SyncedMediaFile>> = _selectedFiles.asStateFlow()
    
    // Discovered hosts
    private val _discoveredHosts = MutableStateFlow<List<NetworkInfo>>(emptyList())
    val discoveredHosts: StateFlow<List<NetworkInfo>> = _discoveredHosts.asStateFlow()
    
    init {
        // Observe session state
        viewModelScope.launch {
            syncedPlaybackManager.sessionState.collect { state ->
                _uiState.update { 
                    it.copy(
                        sessionState = state,
                        isHost = state is SyncSessionState.HostActive
                    ) 
                }
            }
        }
        
        // Observe playback state
        viewModelScope.launch {
            syncedPlaybackManager.playbackState.collect { playbackState ->
                _uiState.update { 
                    it.copy(
                        isPlaying = playbackState.isPlaying,
                        currentPositionMs = playbackState.positionMs,
                        durationMs = playbackState.durationMs,
                        currentFile = playbackState.currentFile
                    ) 
                }
            }
        }
        
        // Observe file transfer progress
        viewModelScope.launch {
            fileTransfer.transferProgress.collect { progress ->
                _uiState.update { it.copy(transferProgress = progress) }
            }
        }
        
        // Observe discovered hosts
        viewModelScope.launch {
            discoveryService.discoveredHosts.collect { hosts ->
                _discoveredHosts.value = hosts
                _uiState.update { it.copy(isDiscovering = discoveryService.isDiscovering.value) }
            }
        }
        
        // Observe discovery state
        viewModelScope.launch {
            discoveryService.isDiscovering.collect { isDiscovering ->
                _uiState.update { it.copy(isDiscovering = isDiscovering) }
            }
        }
        
        // Observe playback commands (for client mode)
        viewModelScope.launch {
            syncedPlaybackManager.playbackCommands.collect { command ->
                // Host broadcasts, clients receive via network
                // This is handled by the network layer
            }
        }
    }
    
    /**
     * Start discovering hosts on the network
     */
    fun startDiscovery() {
        viewModelScope.launch {
            Log.d(TAG, "Starting host discovery")
            discoveryService.startDiscovery()
        }
    }
    
    /**
     * Stop discovering hosts
     */
    fun stopDiscovery() {
        viewModelScope.launch {
            discoveryService.stopDiscovery()
        }
    }
    
    /**
     * Initialize the player
     */
    fun initializePlayer() {
        if (player == null) {
            player = playerFactory.create(context)
            player?.initialize()
            
            viewModelScope.launch {
                player?.playerState?.collect { playerState ->
                    _uiState.update { 
                        it.copy(
                            isBuffering = playerState.isBuffering,
                            playerError = playerState.error,
                            driftMs = playerState.driftMs
                        ) 
                    }
                }
            }
        }
    }
    
    /**
     * Add files from URIs (host mode)
     */
    fun addFiles(uris: List<Uri>) {
        viewModelScope.launch {
            val files = uris.mapNotNull { uri ->
                fileTransfer.getFileMetadata(context, uri)
            }
            _selectedFiles.value = _selectedFiles.value + files
            
            Log.d(TAG, "Added ${files.size} files")
        }
    }
    
    /**
     * Start hosting a synced playback session
     */
    fun startHostSession() {
        viewModelScope.launch {
            val files = _selectedFiles.value
            if (files.isEmpty()) {
                _uiState.update { it.copy(error = "No files selected") }
                return@launch
            }
            
            _uiState.update { it.copy(isLoading = true) }
            
            val result = syncedPlaybackManager.startHostSession(context, files)
            
            result.onSuccess { sessionId ->
                Log.i(TAG, "Host session started: $sessionId")
                
                // Load first file into player
                files.firstOrNull()?.let { file ->
                    player?.loadMedia(file.uri)
                }
                
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        sessionId = sessionId
                    ) 
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to start host session", error)
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = error.message
                    ) 
                }
            }
        }
    }
    
    /**
     * Join an existing session (client mode)
     */
    fun joinSession(hostAddress: String, sessionId: String, mediaFiles: List<SyncedMediaFile>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            val result = syncedPlaybackManager.joinSession(
                context, hostAddress, sessionId, mediaFiles
            )
            
            result.onSuccess {
                Log.i(TAG, "Joined session $sessionId")
                
                // Load first local file
                val state = syncedPlaybackManager.sessionState.value
                if (state is SyncSessionState.ClientReady) {
                    state.localFiles.firstOrNull()?.localUri?.let { uri ->
                        player?.loadMedia(uri)
                    }
                }
                
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        sessionId = sessionId
                    ) 
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to join session", error)
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = error.message
                    ) 
                }
            }
        }
    }
    
    /**
     * Play/Resume (host only)
     */
    fun play() {
        viewModelScope.launch {
            syncedPlaybackManager.play()
            
            // Also play locally
            val playbackState = syncedPlaybackManager.playbackState.value
            player?.playAtTime(
                playbackState.lastSyncTime,
                playbackState.positionMs
            )
        }
    }
    
    /**
     * Pause (host only)
     */
    fun pause() {
        viewModelScope.launch {
            syncedPlaybackManager.pause()
            player?.pause()
        }
    }
    
    /**
     * Seek to position (host only)
     */
    fun seekTo(positionMs: Long) {
        viewModelScope.launch {
            syncedPlaybackManager.seekTo(positionMs)
            // Player will be updated when command is processed
        }
    }
    
    /**
     * Next track (host only)
     */
    fun nextTrack() {
        viewModelScope.launch {
            val state = syncedPlaybackManager.sessionState.value
            if (state is SyncSessionState.HostActive) {
                val nextIndex = state.currentFileIndex + 1
                if (nextIndex < state.mediaFiles.size) {
                    syncedPlaybackManager.switchFile(nextIndex)
                    player?.loadMedia(state.mediaFiles[nextIndex].uri)
                }
            }
        }
    }
    
    /**
     * Previous track (host only)
     */
    fun previousTrack() {
        viewModelScope.launch {
            val state = syncedPlaybackManager.sessionState.value
            if (state is SyncSessionState.HostActive) {
                val prevIndex = state.currentFileIndex - 1
                if (prevIndex >= 0) {
                    syncedPlaybackManager.switchFile(prevIndex)
                    player?.loadMedia(state.mediaFiles[prevIndex].uri)
                }
            }
        }
    }
    
    /**
     * Handle incoming playback command (client mode)
     */
    fun handleCommand(command: PlaybackCommand) {
        viewModelScope.launch {
            syncedPlaybackManager.handleCommand(command)
            
            // Apply to local player
            when (command) {
                is PlaybackCommand.Play -> {
                    player?.playAtTime(command.timestamp, command.positionMs)
                }
                is PlaybackCommand.Pause -> {
                    player?.pause()
                }
                is PlaybackCommand.Seek -> {
                    player?.seekAtTime(command.timestamp, command.positionMs, command.resumePlayback)
                }
                is PlaybackCommand.SyncPulse -> {
                    player?.syncPosition(command.positionMs, command.timestamp)
                }
                else -> {}
            }
        }
    }
    
    /**
     * Remove file from playlist
     */
    fun removeFile(index: Int) {
        val files = _selectedFiles.value.toMutableList()
        if (index in files.indices) {
            files.removeAt(index)
            _selectedFiles.value = files
        }
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    
    /**
     * Stop session and cleanup
     */
    fun stopSession() {
        syncedPlaybackManager.stopSession()
        player?.release()
        player = null
        _selectedFiles.value = emptyList()
        _uiState.value = SyncedPlayerUiState()
    }
    
    override fun onCleared() {
        super.onCleared()
        player?.release()
        player = null
    }
}

/**
 * UI State for synced file player
 */
data class SyncedPlayerUiState(
    val isHost: Boolean = false,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isDiscovering: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val driftMs: Long = 0L,
    val currentFile: SyncedMediaFile? = null,
    val sessionId: String? = null,
    val sessionState: SyncSessionState = SyncSessionState.Idle,
    val transferProgress: Map<String, TransferProgress> = emptyMap(),
    val error: String? = null,
    val playerError: String? = null
)
