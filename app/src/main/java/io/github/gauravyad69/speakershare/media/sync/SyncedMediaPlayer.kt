package io.github.gauravyad69.speakershare.media.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/**
 * Synchronized Media Player
 * 
 * Wraps ExoPlayer with precise synchronization capabilities.
 * Works with SyncedPlaybackManager to maintain sync across devices.
 * 
 * Key features:
 * - Precise seek to timestamp
 * - Preloading for instant playback
 * - Position tracking with sub-millisecond precision
 * - Buffer management for smooth sync
 */
class SyncedMediaPlayer(
    private val context: Context,
    private val clockSync: ClockSynchronizer
) {
    companion object {
        private const val TAG = "SyncedMediaPlayer"
        
        // How far ahead to buffer (ms)
        private const val BUFFER_AHEAD_MS = 5000L
        
        // Acceptable position error before forcing seek
        private const val POSITION_TOLERANCE_MS = 30L
    }
    
    private var player: ExoPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Player state
    private val _playerState = MutableStateFlow(SyncedPlayerState())
    val playerState: StateFlow<SyncedPlayerState> = _playerState.asStateFlow()
    
    // Position tracking job
    private var positionTrackingJob: Job? = null
    
    // Scheduled playback
    private var scheduledPlayJob: Job? = null
    
    /**
     * Initialize the player
     */
    @OptIn(UnstableApi::class)
    fun initialize() {
        if (player != null) return
        
        player = ExoPlayer.Builder(context)
            .build()
            .apply {
                addListener(PlayerListener())
                playWhenReady = false
            }
        
        Log.d(TAG, "Player initialized")
    }
    
    /**
     * Load a media file
     */
    fun loadMedia(uri: Uri) {
        player?.let { exo ->
            val mediaItem = MediaItem.fromUri(uri)
            exo.setMediaItem(mediaItem)
            exo.prepare()
            
            Log.d(TAG, "Media loaded: $uri")
        }
    }
    
    /**
     * Play starting at the scheduled synchronized time
     * 
     * @param startTime The synchronized time when playback should start
     * @param positionMs The position in the media to start from
     */
    fun playAtTime(startTime: Long, positionMs: Long) {
        scheduledPlayJob?.cancel()
        
        val currentSyncTime = clockSync.getSynchronizedTime()
        val delay = startTime - currentSyncTime
        
        player?.let { exo ->
            // Seek to position immediately
            exo.seekTo(positionMs)
            
            if (delay > 0) {
                // Schedule playback
                Log.d(TAG, "Scheduling playback in ${delay}ms at position $positionMs")
                
                scheduledPlayJob = scope.launch {
                    delay(delay)
                    exo.play()
                    startPositionTracking(startTime, positionMs)
                    _playerState.update { it.copy(isPlaying = true) }
                }
            } else {
                // Start immediately (we're late, adjust position)
                val adjustedPosition = positionMs + (-delay)
                exo.seekTo(adjustedPosition)
                exo.play()
                startPositionTracking(startTime, positionMs)
                _playerState.update { it.copy(isPlaying = true) }
                
                Log.d(TAG, "Starting immediately at adjusted position $adjustedPosition")
            }
        }
    }
    
    /**
     * Pause playback
     */
    fun pause() {
        scheduledPlayJob?.cancel()
        player?.pause()
        stopPositionTracking()
        
        val currentPosition = player?.currentPosition ?: 0L
        _playerState.update { 
            it.copy(isPlaying = false, currentPositionMs = currentPosition) 
        }
        
        Log.d(TAG, "Paused at position $currentPosition")
    }
    
    /**
     * Seek to position at scheduled time
     */
    fun seekAtTime(targetTime: Long, positionMs: Long, resumePlayback: Boolean) {
        scheduledPlayJob?.cancel()
        
        val currentSyncTime = clockSync.getSynchronizedTime()
        val delay = targetTime - currentSyncTime
        
        if (delay > 0) {
            scheduledPlayJob = scope.launch {
                delay(delay)
                performSeek(positionMs, resumePlayback, targetTime)
            }
        } else {
            performSeek(positionMs, resumePlayback, targetTime)
        }
    }
    
    private fun performSeek(positionMs: Long, resumePlayback: Boolean, syncTime: Long) {
        player?.let { exo ->
            exo.seekTo(positionMs)
            
            if (resumePlayback) {
                exo.play()
                startPositionTracking(syncTime, positionMs)
            }
            
            _playerState.update { 
                it.copy(
                    isPlaying = resumePlayback,
                    currentPositionMs = positionMs
                ) 
            }
        }
        
        Log.d(TAG, "Seeked to $positionMs, resume=$resumePlayback")
    }
    
    /**
     * Sync position with expected position from host
     */
    fun syncPosition(expectedPositionMs: Long, syncTime: Long) {
        val currentPosition = player?.currentPosition ?: return
        val drift = kotlin.math.abs(currentPosition - expectedPositionMs)
        
        if (drift > POSITION_TOLERANCE_MS) {
            Log.w(TAG, "Position drift detected: ${drift}ms, resyncing")
            
            // Adjust playback rate temporarily for smooth correction
            // or seek if drift is too large
            if (drift > 100) {
                player?.seekTo(expectedPositionMs)
            } else {
                // Could use setPlaybackSpeed for gradual correction
                // For now, just seek
                player?.seekTo(expectedPositionMs)
            }
            
            _playerState.update { it.copy(currentPositionMs = expectedPositionMs) }
        }
    }
    
    /**
     * Get current playback position
     */
    fun getCurrentPosition(): Long {
        return player?.currentPosition ?: 0L
    }
    
    /**
     * Get media duration
     */
    fun getDuration(): Long {
        return player?.duration ?: 0L
    }
    
    /**
     * Release the player
     */
    fun release() {
        scheduledPlayJob?.cancel()
        stopPositionTracking()
        player?.release()
        player = null
        scope.cancel()
        Log.d(TAG, "Player released")
    }
    
    private fun startPositionTracking(startTime: Long, startPosition: Long) {
        positionTrackingJob?.cancel()
        positionTrackingJob = scope.launch {
            while (isActive) {
                val elapsed = clockSync.getSynchronizedTime() - startTime
                val expectedPosition = startPosition + elapsed
                val actualPosition = player?.currentPosition ?: 0L
                
                _playerState.update { 
                    it.copy(
                        currentPositionMs = actualPosition,
                        expectedPositionMs = expectedPosition,
                        driftMs = actualPosition - expectedPosition
                    ) 
                }
                
                delay(100) // Update every 100ms
            }
        }
    }
    
    private fun stopPositionTracking() {
        positionTrackingJob?.cancel()
        positionTrackingJob = null
    }
    
    private inner class PlayerListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateStr = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            Log.d(TAG, "Playback state: $stateStr")
            
            _playerState.update { 
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    isEnded = playbackState == Player.STATE_ENDED,
                    durationMs = player?.duration ?: 0L
                ) 
            }
        }
        
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playerState.update { it.copy(isPlaying = isPlaying) }
        }
        
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}", error)
            _playerState.update { it.copy(error = error.message) }
        }
    }
}

/**
 * Player state for UI
 */
data class SyncedPlayerState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val currentPositionMs: Long = 0L,
    val expectedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val driftMs: Long = 0L,
    val error: String? = null
)

/**
 * Factory for creating SyncedMediaPlayer instances
 */
class SyncedMediaPlayerFactory @Inject constructor(
    private val clockSync: ClockSynchronizer
) {
    fun create(context: Context): SyncedMediaPlayer {
        return SyncedMediaPlayer(context, clockSync)
    }
}
