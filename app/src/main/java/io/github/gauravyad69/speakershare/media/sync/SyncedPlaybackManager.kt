package io.github.gauravyad69.speakershare.media.sync

import android.content.Context
import android.net.Uri
import android.util.Log
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
    private val fileTransfer: SyncedFileTransfer
) {
    companion object {
        private const val TAG = "SyncedPlaybackManager"
        
        // Time to wait for clients to be ready before starting playback
        const val READY_WAIT_TIME_MS = 500L
        
        // How often to send sync pulses during playback
        const val SYNC_INTERVAL_MS = 1000L
        
        // Maximum acceptable drift before forcing resync
        const val MAX_DRIFT_MS = 50L
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
    
    private var syncJob: Job? = null
    
    /**
     * HOST: Start a synced playback session with media files
     */
    suspend fun startHostSession(
        context: Context,
        mediaFiles: List<SyncedMediaFile>
    ): Result<String> {
        return try {
            Log.d(TAG, "Starting host session with ${mediaFiles.size} files")
            
            // Generate session ID
            val sessionId = generateSessionId()
            
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
            
            // Start clock sync
            clockSync.startAsHost()
            
            // Start sync pulse job
            startSyncPulseJob()
            
            Log.i(TAG, "Host session started: $sessionId")
            Result.success(sessionId)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start host session", e)
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
            Log.d(TAG, "Joining session $sessionId from host $hostAddress")
            
            // Sync clock with host
            clockSync.syncWithHost(hostAddress)
            
            // Check if we have the required files locally
            val localFiles = mutableListOf<SyncedMediaFile>()
            val missingFiles = mutableListOf<SyncedMediaFile>()
            
            for (file in mediaFiles) {
                val localUri = fileTransfer.findLocalFile(context, file)
                if (localUri != null) {
                    localFiles.add(file.copy(localUri = localUri))
                } else {
                    missingFiles.add(file)
                }
            }
            
            _sessionState.value = SyncSessionState.ClientJoining(
                sessionId = sessionId,
                hostAddress = hostAddress,
                totalFiles = mediaFiles.size,
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
                        return Result.failure(Exception("Failed to download: ${file.name}"))
                    }
                }
            }
            
            // All files ready
            _sessionState.value = SyncSessionState.ClientReady(
                sessionId = sessionId,
                hostAddress = hostAddress,
                localFiles = localFiles
            )
            
            // Notify host we're ready
            notifyReady(sessionId)
            
            Log.i(TAG, "Joined session $sessionId successfully")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to join session", e)
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
            Log.w(TAG, "Cannot play: not in host mode")
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
        
        Log.d(TAG, "Play command sent: start at $startTime, position $startPosition")
    }
    
    /**
     * HOST: Pause playback
     */
    suspend fun pause() {
        if (_sessionState.value !is SyncSessionState.HostActive) {
            Log.w(TAG, "Cannot pause: not in host mode")
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
        
        Log.d(TAG, "Pause command sent at position $currentPosition")
    }
    
    /**
     * HOST: Seek to position
     */
    suspend fun seekTo(positionMs: Long) {
        val state = _sessionState.value
        if (state !is SyncSessionState.HostActive) {
            Log.w(TAG, "Cannot seek: not in host mode")
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
        
        Log.d(TAG, "Seek command sent to position $positionMs")
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
        
        Log.d(TAG, "Switch file command sent: index $index")
    }
    
    /**
     * CLIENT: Handle incoming playback command
     */
    suspend fun handleCommand(command: PlaybackCommand) {
        val state = _sessionState.value
        if (state !is SyncSessionState.ClientReady) {
            Log.w(TAG, "Cannot handle command: not in client mode")
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
                    _playbackState.update {
                        it.copy(
                            currentFile = localFiles[command.fileIndex],
                            positionMs = 0L,
                            isPlaying = false
                        )
                    }
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
     * Calculate current playback position based on last sync time
     */
    fun calculateCurrentPosition(): Long {
        val state = _playbackState.value
        return if (state.isPlaying && state.lastSyncTime > 0) {
            val elapsed = clockSync.getSynchronizedTime() - state.lastSyncTime
            (state.positionMs + elapsed).coerceAtMost(state.durationMs)
        } else {
            state.positionMs
        }
    }
    
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
        clockSync.stop()
        _sessionState.value = SyncSessionState.Idle
        _playbackState.value = SyncedPlaybackState()
        _clientReadiness.value = emptyMap()
        Log.i(TAG, "Session stopped")
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
        val localPosition = calculateCurrentPosition()
        val drift = kotlin.math.abs(localPosition - pulse.positionMs)
        
        if (drift > MAX_DRIFT_MS) {
            Log.w(TAG, "Drift detected: ${drift}ms, resyncing")
            _playbackState.update {
                it.copy(
                    positionMs = pulse.positionMs,
                    lastSyncTime = pulse.timestamp
                )
            }
        }
    }
    
    private suspend fun notifyReady(sessionId: String) {
        // This would send a message to the host via the network layer
        Log.d(TAG, "Notifying host that client is ready for session $sessionId")
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
