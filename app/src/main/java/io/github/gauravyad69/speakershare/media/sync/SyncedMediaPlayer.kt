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
import io.github.gauravyad69.speakershare.data.repository.SettingsRepository
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
    private val clockSync: ClockSynchronizer,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        
        // How far ahead to buffer (ms)
        private const val BUFFER_AHEAD_MS = 5000L
        
        // Acceptable position error before forcing seek (relaxed to reduce oscillation)
        // With WebSocket, we get low jitter - only correct large drifts
        private const val POSITION_TOLERANCE_MS = 250L
        
        // Minimum time between corrective seeks (increased to let playback settle)
        private const val MIN_SEEK_INTERVAL_MS = 3000L
        
        // Threshold for immediate seek (very large drift)
        private const val LARGE_DRIFT_THRESHOLD_MS = 500L
        
        // Seek-ahead compensation disabled - WebSocket is fast enough
        // Adding compensation was causing overshoot/oscillation
        private const val SEEK_AHEAD_COMPENSATION_MS = 0L
        
        // Grace period after intentional seek during which sync pulses are relaxed
        // This prevents race conditions when host seeks and stale sync pulses arrive
        private const val SEEK_GRACE_PERIOD_MS = 2000L
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
    
    // Last corrective seek time (for rate-limiting seeks)
    private var lastCorrectiveSeekTime: Long = 0L
    
    // Last intentional seek time (for ignoring stale sync pulses after seeking)
    // This prevents race conditions when host seeks and stale sync pulses arrive
    private var lastIntentionalSeekTime: Long = 0L
    private var lastIntentionalSeekPosition: Long = 0L
    
    // Last reconnection time (for ignoring stale sync pulses after reconnecting)
    // Similar to seek grace period - first sync pulse after reconnect may have stale data
    private var lastReconnectionTime: Long = 0L
    
    /**
     * Mark that a reconnection happened - ignore stale sync pulses briefly
     * Call this when WebSocket reconnects to prevent stale position data from causing seeks
     */
    fun markReconnection() {
        lastReconnectionTime = System.currentTimeMillis()
        Timber.d("Marked reconnection at $lastReconnectionTime - will ignore stale sync pulses")
    }
    
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
        
        // Mark as intentional seek (from host Play command)
        lastIntentionalSeekTime = System.currentTimeMillis()
        lastIntentionalSeekPosition = safePositionMs
        
        if (playerState == Player.STATE_ENDED) {
            Timber.d("Player ended, seeking to beginning (intentional)")
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
            // We're late or exactly on time
            // Only adjust position if we're significantly late (> 50ms)
            val adjustedPosition = if (delay < -50) {
                // Guard against overflow: if delay is hugely negative, just use safePositionMs
                if (delay < -60_000) {
                    Timber.w("Delay too negative ($delay), using safe position")
                    safePositionMs
                } else {
                    (safePositionMs + (-delay)).coerceIn(0L, duration.coerceAtLeast(0L))
                }
            } else {
                // Within 50ms of target time, just use the requested position
                safePositionMs
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
     * Set playback volume
     * @param volume Volume level from 0.0 (mute) to 1.0 (full)
     */
    fun setVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        player?.volume = clampedVolume
        _playerState.update { it.copy(volume = clampedVolume) }
        Timber.d("Volume set to $clampedVolume")
    }
    
    /**
     * Get current volume
     */
    fun getVolume(): Float {
        return player?.volume ?: 1f
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
            // Mark this as an intentional seek (from host command)
            // This prevents sync pulses from fighting with intentional seeks
            lastIntentionalSeekTime = System.currentTimeMillis()
            lastIntentionalSeekPosition = positionMs
            
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
        
        Timber.d("Seeked to $positionMs (intentional), resume=$resumePlayback")
    }
    
    /**
     * Sync position with expected position from host
     * @param hostPositionMs The media position on the host when it sent the sync pulse
     * @param hostTimestamp The synchronized timestamp when the host sent the pulse
     */
    fun syncPosition(hostPositionMs: Long, hostTimestamp: Long) {
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
        
        // Check if we're within grace period after an intentional seek
        // This prevents stale sync pulses from fighting with intentional seeks (race condition)
        val now = System.currentTimeMillis()
        val timeSinceIntentionalSeek = now - lastIntentionalSeekTime
        if (timeSinceIntentionalSeek < SEEK_GRACE_PERIOD_MS) {
            // During grace period, only accept sync pulses that are close to our intentional seek position
            // This allows valid sync pulses through while rejecting stale ones
            val driftFromIntentional = kotlin.math.abs(hostPositionMs - lastIntentionalSeekPosition)
            if (driftFromIntentional > LARGE_DRIFT_THRESHOLD_MS) {
                Timber.d("Ignoring sync pulse during seek grace period (${timeSinceIntentionalSeek}ms since seek), hostPos=$hostPositionMs differs from intentional=$lastIntentionalSeekPosition by ${driftFromIntentional}ms")
                return
            }
            Timber.d("Accepting sync pulse during seek grace period - hostPos=$hostPositionMs close to intentional=$lastIntentionalSeekPosition")
        }
        
        // Check if we're within grace period after a reconnection
        // First few sync pulses after reconnect may have stale position data
        val timeSinceReconnection = now - lastReconnectionTime
        if (timeSinceReconnection < SEEK_GRACE_PERIOD_MS) {
            // During reconnection grace period, accept all sync pulses but don't do corrective seeks
            // This allows clock drift recording while preventing stale seeks
            Timber.d("In reconnection grace period (${timeSinceReconnection}ms since reconnect) - will record drift but skip corrective seeks")
        }
        
        val currentPosition = exo.currentPosition
        val duration = exo.duration
        
        // Calculate the expected position NOW by accounting for time elapsed since the host sent the pulse
        // The host was at hostPositionMs at hostTimestamp. Since then, (currentSyncTime - hostTimestamp) ms have elapsed.
        val currentSyncTime = clockSync.getSynchronizedTime()
        val elapsedSinceHostSent = currentSyncTime - hostTimestamp
        val expectedPositionMs = hostPositionMs + elapsedSinceHostSent
        
        Timber.d("Sync calculation: hostPos=$hostPositionMs, hostTime=$hostTimestamp, currentSyncTime=$currentSyncTime, elapsed=$elapsedSinceHostSent, expectedPos=$expectedPositionMs")
        
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
        
        // Drift = actual player position - expected position
        // Positive drift = we're ahead of host, negative = we're behind host
        val drift = currentPosition - expectedPositionMs
        val absDrift = kotlin.math.abs(drift)
        
        // Record drift for dynamic clock adjustment
        // Only record if:
        // - drift is reasonable (not a clock sync issue)
        // - player is actually playing (currentPosition > 0)
        // - position has advanced beyond initial buffering
        if (absDrift < 5000 && currentPosition > 1000) {
            // Drift direction:
            //   drift > 0: client AHEAD of expected → need to DECREASE getSynchronizedTime() → DECREASE offset
            //   drift < 0: client BEHIND expected → need to INCREASE getSynchronizedTime() → INCREASE offset  
            // 
            // ClockSynchronizer applies: newOffset = offset + (avgDrift / 3)
            //   positive avgDrift → increase offset → increase getSynchronizedTime() → increase expectedPos
            //   negative avgDrift → decrease offset → decrease getSynchronizedTime() → decrease expectedPos
            //
            // So we need to pass -drift:
            //   drift > 0 (ahead) → pass negative → decrease offset → decrease expectedPos → client catches up
            //   drift < 0 (behind) → pass positive → increase offset → increase expectedPos → wait, that makes it worse!
            //
            // Actually, let's trace through more carefully with the new calculation:
            //   expectedPos = hostPos + (getSynchronizedTime() - hostTimestamp)
            //   If we INCREASE offset → getSynchronizedTime goes UP → expectedPos goes UP
            //   If client is BEHIND (drift < 0, localPos < expectedPos):
            //     We want expectedPos to go DOWN to match localPos
            //     So we need offset to DECREASE
            //     So we should pass NEGATIVE avgDrift
            //   Therefore, pass drift directly (negative when behind → negative adjustment)
            clockSync.recordDrift(drift, currentPosition)
            Timber.d("Recorded drift: local=$currentPosition, expected=$expectedPositionMs, drift=${drift}ms")
        }
        
        // Get sync thresholds from settings (user-configurable)
        val positionTolerance = settingsRepository.getSyncPositionTolerance().toLong()
        val minSeekInterval = settingsRepository.getSyncMinSeekInterval().toLong()
        
        if (absDrift > positionTolerance) {
            Timber.w("Position drift detected: ${drift}ms (current=$currentPosition, expected=$expectedPositionMs, tolerance=$positionTolerance)")
            
            // Skip corrective seeks during reconnection grace period
            // Clock drift is still recorded above, but we don't want to seek based on potentially stale data
            if (timeSinceReconnection < SEEK_GRACE_PERIOD_MS) {
                Timber.d("Skipping corrective seek during reconnection grace period (${timeSinceReconnection}ms)")
                _playerState.update { it.copy(driftMs = drift) }
                return
            }
            
            val timeSinceLastSeek = now - lastCorrectiveSeekTime
            
            // Determine if we should seek:
            // - Large drift (>500ms): seek immediately
            // - Moderate drift: only seek if enough time has passed since last seek
            val shouldSeek = when {
                absDrift > LARGE_DRIFT_THRESHOLD_MS -> true  // Large drift - always seek
                timeSinceLastSeek > minSeekInterval -> true  // Enough time passed
                else -> false
            }
            
            if (shouldSeek) {
                // Apply seek-ahead compensation when behind (drift < 0)
                // This accounts for seek latency - by the time seek completes, we need to be at expectedPos
                val compensatedPosition = if (drift < 0) {
                    // We're behind, seek ahead to compensate for seek latency
                    (expectedPositionMs + SEEK_AHEAD_COMPENSATION_MS).coerceAtMost(duration)
                } else {
                    // We're ahead, just seek to expected position
                    expectedPositionMs
                }
                
                Timber.i("Corrective seek: drift=${drift}ms, expectedPos=$expectedPositionMs, compensatedPos=$compensatedPosition, timeSinceLastSeek=${timeSinceLastSeek}ms")
                exo.seekTo(compensatedPosition)
                lastCorrectiveSeekTime = now
                _playerState.update { it.copy(currentPositionMs = compensatedPosition, driftMs = 0) }
            } else {
                // Just track drift for now
                Timber.d("Drift $drift ms but skipping seek (last seek ${timeSinceLastSeek}ms ago)")
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
                val actualPosition = player?.currentPosition ?: 0L
                
                // Just update the position, not the drift
                // Drift is calculated accurately in syncPosition() from host sync pulses
                _playerState.update { 
                    it.copy(
                        currentPositionMs = actualPosition
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
    val error: String? = null,
    val volume: Float = 1.0f
)

/**
 * Factory for creating SyncedMediaPlayer instances
 */
class SyncedMediaPlayerFactory @Inject constructor(
    private val clockSync: ClockSynchronizer,
    private val settingsRepository: SettingsRepository
) {
    fun create(context: Context): SyncedMediaPlayer {
        return SyncedMediaPlayer(context, clockSync, settingsRepository)
    }
}
