package io.github.gauravyad69.speakershare.ui.viewmodels

import android.content.Context
import android.net.Uri
import timber.log.Timber
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.gauravyad69.speakershare.media.sync.*
import io.github.gauravyad69.speakershare.services.NetworkDiscoveryService
import io.github.gauravyad69.speakershare.data.model.NetworkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
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
    }
    
    // UI State
    private val _uiState = MutableStateFlow(SyncedPlayerUiState())
    val uiState: StateFlow<SyncedPlayerUiState> = _uiState.asStateFlow()
    
    // Player instance
    private var player: SyncedMediaPlayer? = null
    
    // Flag to prevent duplicate track-end handling
    private var handlingTrackEnd = false
    
    // Job for tracking host position
    private var hostPositionTrackingJob: Job? = null
    
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
        
        // Periodically update sync stats
        viewModelScope.launch {
            while (true) {
                delay(500) // Update stats every 500ms
                _uiState.update { state ->
                    state.copy(
                        clockOffsetMs = syncedPlaybackManager.getClockOffset(),
                        connectedClientsCount = syncedPlaybackManager.getConnectedClientsCount(),
                        driftMs = syncedPlaybackManager.getCurrentDrift()
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
        
        // Observe discovered SYNC hosts only (filter out streaming hosts)
        viewModelScope.launch {
            discoveryService.syncHosts.collect { hosts ->
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
        
        // Observe incoming commands from host (for client mode)
        viewModelScope.launch {
            syncedPlaybackManager.incomingCommands.collect { command ->
                Timber.d("Received command from host: $command")
                handleCommand(command)
            }
        }
        
        // Observe reconnection events (for client mode) - apply grace period after reconnect
        viewModelScope.launch {
            syncedPlaybackManager.reconnectionEvents.collect {
                Timber.i("WebSocket reconnected - marking reconnection on player")
                player?.markReconnection()
            }
        }
    }
    
    /**
     * Start discovering hosts on the network
     */
    fun startDiscovery() {
        viewModelScope.launch {
            Timber.d("Starting host discovery")
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
                            driftMs = playerState.driftMs,
                            currentPositionMs = playerState.currentPositionMs,
                            durationMs = playerState.durationMs
                        ) 
                    }
                    
                    // If we're the host, update the manager's position so sync pulses are accurate
                    if (_uiState.value.isHost && playerState.isPlaying) {
                        syncedPlaybackManager.updatePlaybackPosition(
                            playerState.currentPositionMs,
                            playerState.durationMs
                        )
                        // Reset track-end flag when playing
                        handlingTrackEnd = false
                    }
                    
                    // Auto-advance to next track when current track ends (host only)
                    if (_uiState.value.isHost && playerState.isEnded && !handlingTrackEnd) {
                        handlingTrackEnd = true
                        Timber.i("Track ended, auto-advancing to next track")
                        nextTrack()
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
            
            Timber.d("Added ${files.size} files")
        }
    }
    
    /**
     * Start tracking host position for accurate sync pulses
     * This runs on main thread to safely access ExoPlayer
     */
    private fun startHostPositionTracking() {
        hostPositionTrackingJob?.cancel()
        hostPositionTrackingJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                // Update position every 200ms for accuracy (more frequent than sync pulses)
                val position = player?.getCurrentPosition() ?: 0L
                syncedPlaybackManager.lastActualPosition = position
                delay(200)
            }
        }
    }
    
    /**
     * Stop host position tracking
     */
    private fun stopHostPositionTracking() {
        hostPositionTrackingJob?.cancel()
        hostPositionTrackingJob = null
        syncedPlaybackManager.lastActualPosition = -1L
    }
    
    /**
     * Start hosting a synced playback session
     */
    fun startHostSession() {
        Timber.i("=== startHostSession CALLED ===")
        viewModelScope.launch {
            val files = _selectedFiles.value
            Timber.i("Files count: ${files.size}")
            if (files.isEmpty()) {
                _uiState.update { it.copy(error = "No files selected") }
                return@launch
            }
            
            _uiState.update { it.copy(isLoading = true) }
            
            // Start position tracking for accurate sync pulses
            // This runs on main thread and updates position periodically
            startHostPositionTracking()
            
            Timber.i("Calling syncedPlaybackManager.startHostSession...")
            val result = syncedPlaybackManager.startHostSession(context, files)
            Timber.i("syncedPlaybackManager.startHostSession returned: $result")
            
            result.onSuccess { sessionId ->
                Timber.i("Host session started: $sessionId")
                
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
                Timber.e("Failed to start host session", error)
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
            
            // Ensure player is initialized (may have been released after previous session)
            if (player == null) {
                Timber.i("Player was null, re-initializing for rejoin")
                initializePlayer()
            }
            
            val result = syncedPlaybackManager.joinSession(
                context, hostAddress, sessionId, mediaFiles
            )
            
            result.onSuccess {
                Timber.i("Joined session $sessionId")
                
                // Load the current file from playback state (which now has currentFile set)
                val playbackState = syncedPlaybackManager.playbackState.value
                val state = syncedPlaybackManager.sessionState.value
                Timber.i("Session state after join: $state")
                Timber.i("Playback state after join: currentFile=${playbackState.currentFile?.name}, isPlaying=${playbackState.isPlaying}")
                
                if (state is SyncSessionState.ClientReady) {
                    Timber.i("ClientReady state, localFiles: ${state.localFiles.size}")
                    
                    // Find the matching local file with localUri
                    // playbackState.currentFile is from host and has no localUri
                    // We need to match it with our local downloaded file
                    val hostFile = playbackState.currentFile
                    val fileToLoad = if (hostFile != null) {
                        // Find matching file by name or contentHash in our local files
                        state.localFiles.find { it.name == hostFile.name || it.contentHash == hostFile.contentHash }
                            ?: state.localFiles.firstOrNull().also {
                                Timber.w("Could not match host file '${hostFile.name}' to local file, using first")
                            }
                    } else {
                        state.localFiles.firstOrNull()
                    }
                    
                    fileToLoad?.let { file ->
                        Timber.i("Loading file: ${file.name}, localUri: ${file.localUri}")
                        
                        file.localUri?.let { uri ->
                            Timber.i("Loading media into player: $uri")
                            player?.loadMedia(uri)
                        } ?: Timber.w("No localUri for file '${file.name}'! (Local files may not be downloaded)")
                    } ?: Timber.w("No files available!")
                } else {
                    Timber.w("Not in ClientReady state, state is: ${state::class.simpleName}")
                }
                
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        sessionId = sessionId
                    ) 
                }
                
                // If host is already playing, wait for player to be ready then start playback
                if (playbackState.isPlaying) {
                    Timber.i("Host is already playing at ${playbackState.positionMs}ms, waiting for player ready")
                    val isReady = player?.awaitReady(10000L) ?: false
                    if (isReady) {
                        Timber.i("Player ready, starting playback")
                        val syncTime = syncedPlaybackManager.getSynchronizedTime()
                        // Estimate current position based on time elapsed
                        val timeSinceLastSync = syncTime - playbackState.lastSyncTime
                        val estimatedPosition = playbackState.positionMs + timeSinceLastSync
                        Timber.i("Starting at estimated position: $estimatedPosition (original: ${playbackState.positionMs}, elapsed: ${timeSinceLastSync}ms)")
                        player?.playAtTime(syncTime + 100L, estimatedPosition)
                    } else {
                        Timber.e("Player failed to become ready, not starting playback")
                    }
                }
            }.onFailure { error ->
                Timber.e("Failed to join session", error)
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
            val wasPlaying = _uiState.value.isPlaying
            syncedPlaybackManager.seekTo(positionMs)
            // Apply seek to local player immediately for host
            if (_uiState.value.isHost) {
                val syncTime = syncedPlaybackManager.getSynchronizedTime()
                player?.seekAtTime(syncTime + 200L, positionMs, wasPlaying)
            }
        }
    }
    
    /**
     * Set volume (host only - broadcasts to all clients)
     */
    fun setVolume(volume: Float) {
        viewModelScope.launch {
            val clampedVolume = volume.coerceIn(0f, 1f)
            // Apply locally
            player?.setVolume(clampedVolume)
            _uiState.update { it.copy(volume = clampedVolume) }
            // Broadcast to clients if host
            if (_uiState.value.isHost) {
                syncedPlaybackManager.setVolume(clampedVolume)
            }
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
                    // Capture wasPlaying BEFORE switchFile changes the state
                    val wasPlaying = syncedPlaybackManager.playbackState.value.isPlaying
                    Timber.i("Switching to next track: $nextIndex, wasPlaying=$wasPlaying")
                    
                    // Reset track-end flag for new track
                    handlingTrackEnd = false
                    
                    syncedPlaybackManager.switchFile(nextIndex)
                    player?.loadMedia(state.mediaFiles[nextIndex].uri)
                    
                    // Wait for player to be ready and auto-play if was playing
                    if (wasPlaying) {
                        val isReady = player?.awaitReady(5000L) ?: false
                        if (isReady) {
                            Timber.i("Player ready after track switch, resuming playback")
                            syncedPlaybackManager.play()
                            val playbackState = syncedPlaybackManager.playbackState.value
                            player?.playAtTime(playbackState.lastSyncTime, 0L)
                        }
                    }
                } else {
                    Timber.i("Reached end of playlist, no more tracks")
                    // Reset flag since there's no next track
                    handlingTrackEnd = false
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
                    // Capture wasPlaying BEFORE switchFile changes the state
                    val wasPlaying = syncedPlaybackManager.playbackState.value.isPlaying
                    Timber.i("Switching to previous track: $prevIndex, wasPlaying=$wasPlaying")
                    
                    syncedPlaybackManager.switchFile(prevIndex)
                    player?.loadMedia(state.mediaFiles[prevIndex].uri)
                    
                    // Wait for player to be ready and auto-play if was playing
                    if (wasPlaying) {
                        val isReady = player?.awaitReady(5000L) ?: false
                        if (isReady) {
                            Timber.i("Player ready after track switch, resuming playback")
                            syncedPlaybackManager.play()
                            val playbackState = syncedPlaybackManager.playbackState.value
                            player?.playAtTime(playbackState.lastSyncTime, 0L)
                        }
                    }
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
            Timber.i("Handling command: ${command::class.simpleName}, player=${player != null}")
            when (command) {
                is PlaybackCommand.Play -> {
                    Timber.i("Calling player.playAtTime(${command.timestamp}, ${command.positionMs})")
                    player?.playAtTime(command.timestamp, command.positionMs)
                }
                is PlaybackCommand.Pause -> {
                    player?.pause()
                }
                is PlaybackCommand.Seek -> {
                    player?.seekAtTime(command.timestamp, command.positionMs, command.resumePlayback)
                }
                is PlaybackCommand.SyncPulse -> {
                    // Only sync if we're actually playing
                    if (_uiState.value.isPlaying) {
                        player?.syncPosition(command.positionMs, command.timestamp)
                    }
                }
                is PlaybackCommand.SwitchFile -> {
                    Timber.i("Switching to file index ${command.fileIndex}")
                    val state = syncedPlaybackManager.sessionState.value
                    if (state is SyncSessionState.ClientReady && command.fileIndex < state.localFiles.size) {
                        val newFile = state.localFiles[command.fileIndex]
                        Timber.i("Loading new file: ${newFile.name}")
                        newFile.localUri?.let { uri ->
                            player?.loadMedia(uri)
                            // Wait for player to be ready, then auto-play if requested
                            if (command.autoPlay) {
                                val isReady = player?.awaitReady(5000L) ?: false
                                if (isReady) {
                                    Timber.i("Player ready after switch, auto-playing")
                                    val syncTime = syncedPlaybackManager.getSynchronizedTime()
                                    player?.playAtTime(syncTime + 100L, 0L)
                                }
                            }
                        }
                    }
                }
                is PlaybackCommand.Stop -> {
                    player?.pause()
                    // Seek to beginning
                    val syncTime = syncedPlaybackManager.getSynchronizedTime()
                    player?.seekAtTime(syncTime, 0L, false)
                }
                is PlaybackCommand.Volume -> {
                    player?.setVolume(command.volume)
                    _uiState.update { it.copy(volume = command.volume) }
                }
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
        stopHostPositionTracking()
        syncedPlaybackManager.stopSession()
        player?.release()
        player = null
        _selectedFiles.value = emptyList()
        _uiState.value = SyncedPlayerUiState()
    }
    
    override fun onCleared() {
        super.onCleared()
        // Full cleanup when ViewModel is destroyed (leaving screen)
        stopHostPositionTracking()
        syncedPlaybackManager.stopSession()
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
    val volume: Float = 1.0f,
    val currentFile: SyncedMediaFile? = null,
    val sessionId: String? = null,
    val sessionState: SyncSessionState = SyncSessionState.Idle,
    val transferProgress: Map<String, TransferProgress> = emptyMap(),
    val error: String? = null,
    val playerError: String? = null,
    // Sync stats
    val clockOffsetMs: Long = 0L,
    val lastRttMs: Long = 0L,
    val connectedClientsCount: Int = 0,
    val syncPulseCount: Long = 0L
)
