package io.github.gauravyad69.speakershare.media.sync

import android.content.Context
import android.net.Uri
import timber.log.Timber
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
        
        // How far ahead to buffer (ms)
        private const val BUFFER_AHEAD_MS = 5000L
        
        // Acceptable position error before forcing seek (larger value to avoid constant seeking)
        private const val POSITION_TOLERANCE_MS = 500L
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
        
        Timber.d("Player initialized")
    }
    
    /**
     * Load a media file (async - caller should wait for player to be ready)
     */
    fun loadMedia(uri: Uri) {
        player?.let { exo ->
            val mediaItem = MediaItem.fromUri(uri)
            exo.setMediaItem(mediaItem)
            exo.prepare()
            
            Timber.d("Media loading: $uri")
        }
    }
    
    /**
     * Wait for player to be ready after loadMedia
     * Returns true if ready, false if timeout
     */
    suspend fun awaitReady(timeoutMs: Long = 10000L): Boolean {
        val startTime = System.currentTimeMillis()
        var hasSeenBuffering = false
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val state = player?.playbackState
            Timber.d("awaitReady: state=$state, hasSeenBuffering=$hasSeenBuffering, elapsed=${System.currentTimeMillis() - startTime}ms")
            
            if (state == Player.STATE_READY) {
                Timber.d("Player ready after ${System.currentTimeMillis() - startTime}ms")
                return true
            }
            
            if (state == Player.STATE_BUFFERING) {
                hasSeenBuffering = true
            }
            
            // Only fail on IDLE if we've seen buffering (meaning prepare failed)
            // or if we've been waiting long enough
            if (state == Player.STATE_IDLE && hasSeenBuffering) {
                Timber.e("Player went back to IDLE after buffering - prepare failed")
                return false
            }
            
            delay(100)
        }
        Timber.e("Timeout waiting for player to be ready")
        return false
    }
    
    /**
     * Check if player is ready to play
     */
    fun isReady(): Boolean {
        return player?.playbackState == Player.STATE_READY
    }
    
    /**
     * Play starting at the scheduled synchronized time
     * 
     * @param startTime The synchronized time when playback should start
     * @param positionMs The position in the media to start from
     */
    fun playAtTime(startTime: Long, positionMs: Long) {
        val exo = player ?: run {
            Timber.w("playAtTime called but player is null")
            return
        }
        
        scheduledPlayJob?.cancel()
        
        val currentSyncTime = clockSync.getSynchronizedTime()
        val delay = startTime - currentSyncTime
        val duration = exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        
        // Bounds check the position
        val safePositionMs = positionMs.coerceIn(0L, duration.coerceAtLeast(0L))
        
        Timber.d("playAtTime: startTime=$startTime, currentSyncTime=$currentSyncTime, delay=$delay, positionMs=$safePositionMs, duration=$duration, clockOffset=${clockSync.getOffset()}")
        
        // Reject if delay is absurdly large (clock sync broken)
        if (kotlin.math.abs(delay) > 30_000) {
            Timber.w("Rejecting playAtTime - delay too large: ${delay}ms (clock sync broken?)")
            return
        }
        
        // Check if player is in a valid state
        val playerState = exo.playbackState
        if (playerState == Player.STATE_IDLE) {
            Timber.w("Player is IDLE, media not loaded")
            return
        }
        
        if (playerState == Player.STATE_ENDED) {
            Timber.d("Player ended, seeking to beginning")
            exo.seekTo(safePositionMs)
        } else {
            // Seek to position immediately
            exo.seekTo(safePositionMs)
        }
        
        if (delay > 0) {
            // Cap delay to a reasonable maximum (10 seconds)
            val cappedDelay = delay.coerceAtMost(10_000L)
            if (cappedDelay != delay) {
                Timber.w("Capping delay from ${delay}ms to ${cappedDelay}ms")
            }
            
            // Schedule playback
            Timber.d("Scheduling playback in ${cappedDelay}ms at position $safePositionMs")
            
            scheduledPlayJob = scope.launch {
                delay(cappedDelay)
                // Re-check player state after delay
                if (exo.playbackState == Player.STATE_READY || exo.playbackState == Player.STATE_BUFFERING) {
                    exo.play()
                    startPositionTracking(startTime, safePositionMs)
                    _playerState.update { it.copy(isPlaying = true) }
                } else {
                    Timber.w("Player not ready after scheduled delay, state=${exo.playbackState}")
                }
            }
        } else {
            // Start immediately (we're late, adjust position)
            // Guard against overflow: if delay is hugely negative, just use safePositionMs
            val adjustedPosition = if (delay < -60_000) {
                Timber.w("Delay too negative ($delay), using safe position")
                safePositionMs
            } else {
                (safePositionMs + (-delay)).coerceIn(0L, duration.coerceAtLeast(0L))
            }
            
            // Don't start if adjusted position is at or past the end
            if (duration > 0 && duration != Long.MAX_VALUE && adjustedPosition >= duration - 500) {
                Timber.w("Adjusted position $adjustedPosition is at/past end of track ($duration), not starting")
                return
            }
            
            exo.seekTo(adjustedPosition)
            exo.play()
            startPositionTracking(startTime, adjustedPosition)
            _playerState.update { it.copy(isPlaying = true) }
            
            Timber.d("Starting immediately at adjusted position $adjustedPosition")
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
        
        Timber.d("Paused at position $currentPosition")
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
        
        Timber.d("Seeked to $positionMs, resume=$resumePlayback")
    }
    
    /**
     * Sync position with expected position from host
     */
    fun syncPosition(expectedPositionMs: Long, syncTime: Long) {
        val exo = player ?: return
        
        // Don't sync if player is ended - wait for next track
        if (exo.playbackState == Player.STATE_ENDED) {
            Timber.d("Ignoring sync - player ended")
            return
        }
        
        // Don't sync if player is not playing
        if (!exo.isPlaying) {
            Timber.d("Ignoring sync - player not playing")
            return
        }
        
        val currentPosition = exo.currentPosition
        val duration = exo.duration
        
        // Don't sync if expected position is 0 or very small (likely invalid)
        if (expectedPositionMs < 100) {
            Timber.d("Ignoring sync pulse with position $expectedPositionMs (too small)")
            return
        }
        
        // Don't sync if expected position is past the track duration
        if (duration > 0 && expectedPositionMs >= duration) {
            Timber.d("Ignoring sync pulse with position $expectedPositionMs (past duration $duration)")
            return
        }
        
        // Drift = actual player position - expected (host) position
        // Positive drift = we're ahead of host, negative = we're behind host
        val drift = currentPosition - expectedPositionMs
        val absDrift = kotlin.math.abs(drift)
        
        // Record drift for dynamic clock adjustment
        // Only record if:
        // - drift is reasonable (not a clock sync issue)
        // - player is actually playing (currentPosition > 0)
        // - position has advanced beyond initial buffering
        if (absDrift < 5000 && currentPosition > 1000) {
            // ClockSynchronizer expects: positive = we're BEHIND host (need to INCREASE offset to catch up)
            // Our drift: positive = we're AHEAD (current > expected)
            // So we need to pass -drift:
            //   - If we're ahead (drift > 0), pass negative → decrease offset → slow down
            //   - If we're behind (drift < 0), pass positive → increase offset → speed up
            clockSync.recordDrift(-drift, currentPosition)
            Timber.d("Recorded drift: local=$currentPosition, expected=$expectedPositionMs, drift=${drift}ms, sent=${-drift}ms")
        }
        
        if (absDrift > POSITION_TOLERANCE_MS) {
            Timber.w("Position drift detected: ${drift}ms (current=$currentPosition, expected=$expectedPositionMs)")
            
            // Only seek if drift is very large (over 2 seconds)
            if (absDrift > 2000) {
                Timber.i("Large drift, seeking to $expectedPositionMs")
                exo.seekTo(expectedPositionMs)
                _playerState.update { it.copy(currentPositionMs = expectedPositionMs, driftMs = drift) }
            } else {
                // Just track drift for now, don't seek for small differences
                _playerState.update { it.copy(driftMs = drift) }
            }
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
        Timber.d("Player released")
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
            Timber.d("Playback state: $stateStr")
            
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
            Timber.e("Player error: ${error.message}", error)
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
