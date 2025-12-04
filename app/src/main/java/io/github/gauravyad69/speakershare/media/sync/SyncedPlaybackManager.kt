package io.github.gauravyad69.speakershare.media.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import timber.log.Timber
import io.github.gauravyad69.speakershare.services.NetworkDiscoveryService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synchronized Playback Manager
 * 
 * Enables near-zero latency playback across devices by:
 * 1. Sharing file metadata (hash/size) so clients can cache the same file
 * 2. Broadcasting playback commands with timestamps
 * 3. Using clock synchronization to ensure all devices play at the same position
 * 
 * Key difference from streaming:
 * - Streaming: Host captures audio → encodes → sends → client decodes → plays (100-500ms latency)
 * - Synced File: All devices have the file locally, just need to sync play position (10-20ms latency)
 */
@Singleton
class SyncedPlaybackManager @Inject constructor(
    private val clockSync: ClockSynchronizer,
    private val fileTransfer: SyncedFileTransfer,
    private val discoveryService: NetworkDiscoveryService,
    private val syncServer: SyncedPlaybackServer,
    private val syncClient: SyncedPlaybackClient
) {
    companion object {
        
        // Time to wait for clients to be ready before starting playback
        const val READY_WAIT_TIME_MS = 500L
        
        // How often to send sync pulses during playback
        const val SYNC_INTERVAL_MS = 1000L
        
        // Maximum acceptable drift before forcing resync (larger value to avoid constant seeking)
        const val MAX_DRIFT_MS = 500L
        
        // Port for synced playback HTTP server
        const val SYNC_HTTP_PORT = 8765
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Current session state
    private val _sessionState = MutableStateFlow<SyncSessionState>(SyncSessionState.Idle)
    val sessionState: StateFlow<SyncSessionState> = _sessionState.asStateFlow()
    
    // Playback state
    private val _playbackState = MutableStateFlow(SyncedPlaybackState())
    val playbackState: StateFlow<SyncedPlaybackState> = _playbackState.asStateFlow()
    
    // Connected clients and their readiness
    private val _clientReadiness = MutableStateFlow<Map<String, ClientReadiness>>(emptyMap())
    val clientReadiness: StateFlow<Map<String, ClientReadiness>> = _clientReadiness.asStateFlow()
    
    // Outgoing commands for broadcast
    private val _playbackCommands = MutableSharedFlow<PlaybackCommand>()
    val playbackCommands: SharedFlow<PlaybackCommand> = _playbackCommands.asSharedFlow()
    
    // Incoming commands (for clients)
    private val _incomingCommands = MutableSharedFlow<PlaybackCommand>()
    
    // Incoming commands from network (clients can observe this)
    val incomingCommands: SharedFlow<PlaybackCommand> = syncClient.commands
    
    private var syncJob: Job? = null
    private var commandBroadcastJob: Job? = null
    
    init {
        // Observe outgoing commands and broadcast via server
        scope.launch {
            playbackCommands.collect { command ->
                syncServer.broadcastCommand(command)
            }
        }
    }

    /**
     * HOST: Start a synced playback session with media files
     */
    suspend fun startHostSession(
        context: Context,
        mediaFiles: List<SyncedMediaFile>
    ): Result<String> {
        return try {
            Timber.i("=== STARTING HOST SESSION === with ${mediaFiles.size} files")
            
            // Generate session ID
            val sessionId = generateSessionId()
            Timber.i("Generated session ID: $sessionId")
            
            // Calculate file hashes for verification
            val filesWithHash = mediaFiles.map { file ->
                val hash = fileTransfer.calculateFileHash(context, file.uri)
                file.copy(contentHash = hash)
            }
            
            // Update session state
            _sessionState.value = SyncSessionState.HostActive(
                sessionId = sessionId,
                mediaFiles = filesWithHash,
                currentFileIndex = 0
            )
            
            _playbackState.value = SyncedPlaybackState(
                currentFile = filesWithHash.firstOrNull(),
                isPlaying = false,
                positionMs = 0L,
                durationMs = filesWithHash.firstOrNull()?.durationMs ?: 0L
            )
            
            // Start HTTP server for command broadcasting
            val sessionInfo = SessionInfo(
                sessionId = sessionId,
                hostName = Build.MODEL,
                files = filesWithHash.map { file ->
                    SyncFileInfo(
                        name = file.name,
                        hash = file.contentHash,
                        sizeBytes = file.sizeBytes,
                        durationMs = file.durationMs,
                        mimeType = file.mimeType
                    )
                },
                currentFileIndex = 0,
                currentPositionMs = 0L,
                isPlaying = false
            )
            
            // Register files for serving to clients
            syncServer.registerFiles(filesWithHash)
            
            syncServer.startServer(sessionInfo)
            Timber.i("Sync server started on port $SYNC_HTTP_PORT")
            
            // Start clock sync
            clockSync.startAsHost()
            
            // Register for network discovery so clients can find us
            val deviceName = "SyncedPlay-${Build.MODEL}"
            discoveryService.registerHost(
                hostName = deviceName,
                port = SYNC_HTTP_PORT,
                userName = sessionId
            )
            
            // Start sync pulse job
            startSyncPulseJob()
            
            Timber.i("Host session started: $sessionId")
            Result.success(sessionId)
            
        } catch (e: Exception) {
            Timber.e("Failed to start host session", e)
            Result.failure(e)
        }
    }
    
    /**
     * CLIENT: Join a synced playback session
     */
    suspend fun joinSession(
        context: Context,
        hostAddress: String,
        sessionId: String,
        mediaFiles: List<SyncedMediaFile>
    ): Result<Unit> {
        return try {
            Timber.d("Joining session $sessionId from host $hostAddress")
            
            // Connect to host's sync server
            val joinResult = syncClient.connectToHost(hostAddress)
            
            joinResult.onFailure { error ->
                _sessionState.value = SyncSessionState.Error("Failed to connect: ${error.message}")
                return Result.failure(error)
            }
            
            val joinResponse = joinResult.getOrThrow()
            
            // Convert server file info to SyncedMediaFile
            val serverFiles = joinResponse.files.map { info ->
                SyncedMediaFile(
                    uri = Uri.EMPTY, // Will be set when we find/download locally
                    name = info.name,
                    mimeType = info.mimeType,
                    sizeBytes = info.sizeBytes,
                    durationMs = info.durationMs,
                    contentHash = info.hash
                )
            }
            
            // Check if we have the required files locally
            val localFiles = mutableListOf<SyncedMediaFile>()
            val missingFiles = mutableListOf<SyncedMediaFile>()
            
            for (file in serverFiles) {
                val localUri = fileTransfer.findLocalFile(context, file)
                if (localUri != null) {
                    localFiles.add(file.copy(localUri = localUri))
                } else {
                    missingFiles.add(file)
                }
            }
            
            _sessionState.value = SyncSessionState.ClientJoining(
                sessionId = joinResponse.sessionId,
                hostAddress = hostAddress,
                totalFiles = serverFiles.size,
                cachedFiles = localFiles.size,
                downloadingFiles = missingFiles
            )
            
            // Download missing files if needed
            if (missingFiles.isNotEmpty()) {
                for (file in missingFiles) {
                    val downloaded = fileTransfer.downloadFile(context, hostAddress, file)
                    if (downloaded != null) {
                        localFiles.add(file.copy(localUri = downloaded))
                    } else {
                        // If download fails, the client won't have the file to play
                        // For now, we'll allow joining without all files
                        Timber.w("Failed to download: ${file.name}, continuing anyway")
                    }
                }
            }
            
            // All files ready (or as many as we could get)
            _sessionState.value = SyncSessionState.ClientReady(
                sessionId = joinResponse.sessionId,
                hostAddress = hostAddress,
                localFiles = localFiles
            )
            
            // Always set the current file from the host's current file index
            val currentFile = localFiles.getOrNull(joinResponse.currentFileIndex)
            
            // Update playback state based on host's current state
            if (joinResponse.isPlaying) {
                val currentTime = syncClient.getSynchronizedTime()
                _playbackState.value = SyncedPlaybackState(
                    currentFile = currentFile,
                    isPlaying = true,
                    positionMs = joinResponse.currentPositionMs,
                    durationMs = currentFile?.durationMs ?: 0L,
                    lastSyncTime = currentTime
                )
                Timber.i("Host is already playing at position ${joinResponse.currentPositionMs}ms")
            } else {
                // Host is not playing, but still set the current file for UI
                _playbackState.value = SyncedPlaybackState(
                    currentFile = currentFile,
                    isPlaying = false,
                    positionMs = joinResponse.currentPositionMs,
                    durationMs = currentFile?.durationMs ?: 0L,
                    lastSyncTime = 0L
                )
                Timber.i("Host is not playing, current file: ${currentFile?.name}")
            }
            
            Timber.i("Joined session ${joinResponse.sessionId} successfully")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e("Failed to join session", e)
            _sessionState.value = SyncSessionState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }
    
    /**
     * HOST: Play/Resume playback at synchronized time
     */
    suspend fun play() {
        val state = _sessionState.value
        if (state !is SyncSessionState.HostActive) {
            Timber.w("Cannot play: not in host mode")
            return
        }
        
        // Calculate when playback should start (give clients time to prepare)
        val startTime = clockSync.getSynchronizedTime() + READY_WAIT_TIME_MS
        val startPosition = _playbackState.value.positionMs
        
        val command = PlaybackCommand.Play(
            timestamp = startTime,
            positionMs = startPosition,
            fileIndex = state.currentFileIndex
        )
        
        _playbackCommands.emit(command)
        
        // Schedule local playback
        val delay = startTime - clockSync.getSynchronizedTime()
        if (delay > 0) {
            delay(delay)
        }
        
        _playbackState.update { it.copy(isPlaying = true, lastSyncTime = startTime) }
        
        // Update server session info for late-joining clients
        syncServer.updatePlaybackState(
            isPlaying = true,
            positionMs = startPosition,
            fileIndex = state.currentFileIndex
        )
        
        Timber.d("Play command sent: start at $startTime, position $startPosition")
    }
    
    /**
     * HOST: Pause playback
     */
    suspend fun pause() {
        if (_sessionState.value !is SyncSessionState.HostActive) {
            Timber.w("Cannot pause: not in host mode")
            return
        }
        
        val currentPosition = calculateCurrentPosition()
        val command = PlaybackCommand.Pause(
            timestamp = clockSync.getSynchronizedTime(),
            positionMs = currentPosition
        )
        
        _playbackCommands.emit(command)
        _playbackState.update { 
            it.copy(isPlaying = false, positionMs = currentPosition) 
        }
        
        // Update server session info for late-joining clients
        syncServer.updatePlaybackState(
            isPlaying = false,
            positionMs = currentPosition
        )
        
        Timber.d("Pause command sent at position $currentPosition")
    }
    
    /**
     * HOST: Seek to position
     */
    suspend fun seekTo(positionMs: Long) {
        val state = _sessionState.value
        if (state !is SyncSessionState.HostActive) {
            Timber.w("Cannot seek: not in host mode")
            return
        }
        
        val wasPlaying = _playbackState.value.isPlaying
        
        // If playing, schedule the seek to happen at a synchronized time
        val seekTime = clockSync.getSynchronizedTime() + READY_WAIT_TIME_MS
        
        val command = PlaybackCommand.Seek(
            timestamp = seekTime,
            positionMs = positionMs,
            resumePlayback = wasPlaying
        )
        
        _playbackCommands.emit(command)
        
        // Apply locally
        val delay = seekTime - clockSync.getSynchronizedTime()
        if (delay > 0) {
            delay(delay)
        }
        
        _playbackState.update { 
            it.copy(
                positionMs = positionMs,
                lastSyncTime = if (wasPlaying) seekTime else it.lastSyncTime
            ) 
        }
        
        // Update server session info for late-joining clients
        syncServer.updatePlaybackState(
            isPlaying = wasPlaying,
            positionMs = positionMs
        )
        
        Timber.d("Seek command sent to position $positionMs")
    }
    
    /**
     * HOST: Switch to next/previous file
     */
    suspend fun switchFile(index: Int) {
        val state = _sessionState.value
        if (state !is SyncSessionState.HostActive) return
        
        if (index < 0 || index >= state.mediaFiles.size) return
        
        val newFile = state.mediaFiles[index]
        val switchTime = clockSync.getSynchronizedTime() + READY_WAIT_TIME_MS
        
        val command = PlaybackCommand.SwitchFile(
            timestamp = switchTime,
            fileIndex = index,
            autoPlay = _playbackState.value.isPlaying
        )
        
        _playbackCommands.emit(command)
        
        // Update state
        _sessionState.value = state.copy(currentFileIndex = index)
        _playbackState.update {
            it.copy(
                currentFile = newFile,
                positionMs = 0L,
                durationMs = newFile.durationMs,
                isPlaying = false
            )
        }
        
        Timber.d("Switch file command sent: index $index")
    }
    
    /**
     * CLIENT: Handle incoming playback command
     */
    suspend fun handleCommand(command: PlaybackCommand) {
        val state = _sessionState.value
        if (state !is SyncSessionState.ClientReady) {
            Timber.w("Cannot handle command: not in client mode")
            return
        }
        
        when (command) {
            is PlaybackCommand.Play -> {
                val delay = command.timestamp - clockSync.getSynchronizedTime()
                if (delay > 0) {
                    delay(delay)
                }
                _playbackState.update {
                    it.copy(
                        isPlaying = true,
                        positionMs = command.positionMs,
                        lastSyncTime = command.timestamp
                    )
                }
            }
            
            is PlaybackCommand.Pause -> {
                _playbackState.update {
                    it.copy(isPlaying = false, positionMs = command.positionMs)
                }
            }
            
            is PlaybackCommand.Seek -> {
                val delay = command.timestamp - clockSync.getSynchronizedTime()
                if (delay > 0) {
                    delay(delay)
                }
                _playbackState.update {
                    it.copy(
                        positionMs = command.positionMs,
                        isPlaying = command.resumePlayback,
                        lastSyncTime = if (command.resumePlayback) command.timestamp else it.lastSyncTime
                    )
                }
            }
            
            is PlaybackCommand.SwitchFile -> {
                val localFiles = state.localFiles
                if (command.fileIndex < localFiles.size) {
                    val newFile = localFiles[command.fileIndex]
                    _playbackState.update {
                        it.copy(
                            currentFile = newFile,
                            positionMs = 0L,
                            durationMs = newFile.durationMs,  // Reset duration to new file's duration
                            isPlaying = false,
                            lastSyncTime = 0L  // Reset sync time for fresh start
                        )
                    }
                    Timber.d("Switched to file ${command.fileIndex}: ${newFile.name}, duration=${newFile.durationMs}ms")
                }
            }
            
            is PlaybackCommand.SyncPulse -> {
                handleSyncPulse(command)
            }
            
            is PlaybackCommand.Stop -> {
                _playbackState.update {
                    it.copy(isPlaying = false, positionMs = 0L)
                }
            }
        }
    }
    
    /**
     * Update the manager's playback position from the actual player
     * This should be called by the host's ViewModel periodically
     */
    fun updatePlaybackPosition(positionMs: Long, durationMs: Long) {
        val currentTime = clockSync.getSynchronizedTime()
        _playbackState.update {
            it.copy(
                positionMs = positionMs,
                durationMs = durationMs,
                lastSyncTime = currentTime
            )
        }
        
        // Keep server session info updated for late-joining clients
        val isPlaying = _playbackState.value.isPlaying
        syncServer.updatePlaybackState(
            isPlaying = isPlaying,
            positionMs = positionMs
        )
    }
    
    /**
     * Calculate current playback position based on last sync time
     */
    fun calculateCurrentPosition(): Long {
        val state = _playbackState.value
        return if (state.isPlaying && state.lastSyncTime > 0) {
            val syncTime = clockSync.getSynchronizedTime()
            val elapsed = syncTime - state.lastSyncTime
            val calculatedPos = (state.positionMs + elapsed).coerceAtLeast(0).coerceAtMost(state.durationMs.coerceAtLeast(1))
            
            // Debug logging for position calculation
            if (elapsed < 0 || calculatedPos == 0L) {
                Timber.w("calculateCurrentPosition: syncTime=$syncTime, lastSyncTime=${state.lastSyncTime}, elapsed=$elapsed, positionMs=${state.positionMs}, durationMs=${state.durationMs}, result=$calculatedPos, clockOffset=${clockSync.getOffset()}")
            }
            calculatedPos
        } else {
            state.positionMs
        }
    }
    
    /**
     * Get synchronized time from the clock synchronizer
     */
    fun getSynchronizedTime(): Long = clockSync.getSynchronizedTime()
    
    /**
     * Get current clock offset in milliseconds
     */
    fun getClockOffset(): Long = clockSync.getOffset()
    
    /**
     * Get current measured drift in milliseconds
     */
    fun getCurrentDrift(): Long = clockSync.getCurrentDrift()
    
    /**
     * Get connected clients count (host only)
     */
    fun getConnectedClientsCount(): Int = syncServer.connectedClients.value.size
    
    /**
     * Get position that client should seek to (with network compensation)
     */
    fun getClientTargetPosition(networkLatencyMs: Long): Long {
        val currentPos = calculateCurrentPosition()
        // Client should be slightly ahead to compensate for any remaining latency
        return currentPos + (networkLatencyMs / 2)
    }
    
    /**
     * Stop the session
     */
    fun stopSession() {
        syncJob?.cancel()
        syncJob = null
        commandBroadcastJob?.cancel()
        commandBroadcastJob = null
        clockSync.stop()
        
        // Stop server (if host) or disconnect client
        syncServer.stopServer()
        scope.launch {
            syncClient.disconnect()
        }
        
        // Unregister from discovery
        scope.launch {
            discoveryService.unregisterHost()
        }
        
        _sessionState.value = SyncSessionState.Idle
        _playbackState.value = SyncedPlaybackState()
        _clientReadiness.value = emptyMap()
        Timber.i("Session stopped")
    }
    
    private fun startSyncPulseJob() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                
                if (_playbackState.value.isPlaying) {
                    val currentPos = calculateCurrentPosition()
                    val pulse = PlaybackCommand.SyncPulse(
                        timestamp = clockSync.getSynchronizedTime(),
                        positionMs = currentPos
                    )
                    _playbackCommands.emit(pulse)
                }
            }
        }
    }
    
    private suspend fun handleSyncPulse(pulse: PlaybackCommand.SyncPulse) {
        val playbackState = _playbackState.value
        
        // Only process sync pulse if we think we should be playing
        if (!playbackState.isPlaying) {
            Timber.d("Sync pulse ignored - not playing")
            return
        }
        
        // Calculate what our position SHOULD be based on elapsed time since last sync
        val expectedLocalPosition = calculateCurrentPosition()
        val duration = playbackState.durationMs
        val signedDrift = pulse.positionMs - expectedLocalPosition // Positive = we're behind host
        val drift = kotlin.math.abs(signedDrift)
        
        // NOTE: Drift recording moved to SyncedMediaPlayer.syncPosition() which has
        // access to the actual ExoPlayer position, not the calculated position.
        
        // Always update our state to match host's position to prevent drift accumulation
        _playbackState.update {
            it.copy(
                positionMs = pulse.positionMs,
                lastSyncTime = pulse.timestamp
            )
        }
        
        if (drift > MAX_DRIFT_MS) {
            Timber.d("Sync pulse: calculated drift=${drift}ms (calculated=$expectedLocalPosition, host=${pulse.positionMs})")
        } else {
            Timber.d("Sync pulse: drift=${drift}ms (within tolerance)")
        }
    }
    
    private suspend fun notifyReady(sessionId: String) {
        // This would send a message to the host via the network layer
        Timber.d("Notifying host that client is ready for session $sessionId")
    }
    
    private fun generateSessionId(): String {
        return "sync-${System.currentTimeMillis()}-${(1000..9999).random()}"
    }
}

/**
 * Session states
 */
sealed class SyncSessionState {
    data object Idle : SyncSessionState()
    
    data class HostActive(
        val sessionId: String,
        val mediaFiles: List<SyncedMediaFile>,
        val currentFileIndex: Int
    ) : SyncSessionState()
    
    data class ClientJoining(
        val sessionId: String,
        val hostAddress: String,
        val totalFiles: Int,
        val cachedFiles: Int,
        val downloadingFiles: List<SyncedMediaFile>
    ) : SyncSessionState()
    
    data class ClientReady(
        val sessionId: String,
        val hostAddress: String,
        val localFiles: List<SyncedMediaFile>
    ) : SyncSessionState()
    
    data class Error(val message: String) : SyncSessionState()
}

/**
 * Synced playback state
 */
data class SyncedPlaybackState(
    val currentFile: SyncedMediaFile? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lastSyncTime: Long = 0L
)

/**
 * Media file metadata for sync
 */
data class SyncedMediaFile(
    val uri: Uri,
    val localUri: Uri? = null,  // For clients: local cached copy
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val contentHash: String = "",  // SHA-256 for verification
    val isVideo: Boolean = false
)

/**
 * Client readiness status
 */
data class ClientReadiness(
    val clientId: String,
    val hasFile: Boolean,
    val downloadProgress: Float = 0f,
    val isReady: Boolean = false,
    val clockOffset: Long = 0L
)

/**
 * Playback commands for synchronization
 */
sealed class PlaybackCommand {
    abstract val timestamp: Long
    
    data class Play(
        override val timestamp: Long,
        val positionMs: Long,
        val fileIndex: Int
    ) : PlaybackCommand()
    
    data class Pause(
        override val timestamp: Long,
        val positionMs: Long
    ) : PlaybackCommand()
    
    data class Seek(
        override val timestamp: Long,
        val positionMs: Long,
        val resumePlayback: Boolean
    ) : PlaybackCommand()
    
    data class SwitchFile(
        override val timestamp: Long,
        val fileIndex: Int,
        val autoPlay: Boolean
    ) : PlaybackCommand()
    
    data class SyncPulse(
        override val timestamp: Long,
        val positionMs: Long
    ) : PlaybackCommand()
    
    data class Stop(
        override val timestamp: Long
    ) : PlaybackCommand()
}
