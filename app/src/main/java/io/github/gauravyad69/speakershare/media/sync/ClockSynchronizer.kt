package io.github.gauravyad69.speakershare.media.sync

import timber.log.Timber
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Clock Synchronizer for distributed playback
 *
 * Maintains the offset between this device's clock and the host's clock so
 * that all devices share a common time reference for synchronized playback.
 *
 * The offset is established once at connect time by SyncedPlaybackClient
 * (NTP-style exchange over HTTP, min-RTT sample selection) and then kept
 * convergent during playback by recordDrift() feedback.
 */
@Singleton
class ClockSynchronizer @Inject constructor() {
    
    companion object {
        // Number of drift samples to collect before adjusting offset
        // With ~1 sync pulse per second, 5 samples = 5 seconds of data
        const val DRIFT_SAMPLES_FOR_ADJUSTMENT = 5
        
        // How often to adjust offset based on drift (ms)
        // Check every 5 seconds - fast enough to correct, slow enough to avoid chasing noise
        const val DRIFT_ADJUSTMENT_INTERVAL_MS = 5_000L
        
        // Maximum allowed clock offset (prevent unbounded growth)
        const val MAX_CLOCK_OFFSET_MS = 30_000L
        
        // Warmup period after joining session before drift adjustment starts
        const val WARMUP_PERIOD_MS = 5_000L
    }
    
    // Current clock offset (add to local time to get synchronized time)
    private var clockOffset: Long = 0L
    
    // Network round trip time estimate
    private var roundTripTime: Long = 0L
    
    // Whether we're the host (host has offset = 0)
    private var isHost: Boolean = false
    
    // Drift samples for dynamic adjustment
    private val driftSamples = mutableListOf<Long>()
    private var lastDriftAdjustmentTime: Long = 0L
    
    // Session start time for warmup period
    private var sessionStartTime: Long = 0L
    
    // Sync state
    private val _syncState = MutableStateFlow<ClockSyncState>(ClockSyncState.NotSynced)
    val syncState: StateFlow<ClockSyncState> = _syncState.asStateFlow()
    
    // Current average drift for UI display
    private val _currentDrift = MutableStateFlow(0L)
    val currentDrift: StateFlow<Long> = _currentDrift.asStateFlow()
    
    /**
     * Get the synchronized time across all devices
     */
    fun getSynchronizedTime(): Long {
        return System.currentTimeMillis() + clockOffset
    }
    
    /**
     * Get the current clock offset
     */
    fun getOffset(): Long {
        return clockOffset
    }
    
    /**
     * Set clock offset manually (used by SyncedPlaybackClient after the
     * NTP-style exchange with the host)
     */
    fun setOffset(offset: Long) {
        clockOffset = offset
        // Mark session start time for warmup period
        sessionStartTime = System.currentTimeMillis()
        _syncState.value = ClockSyncState.Synced(offset, roundTripTime)
        Timber.i("Clock offset set: ${offset}ms")
    }
    
    /**
     * Record a drift sample and potentially adjust the clock offset.
     * Called by the client when receiving sync pulses from host.
     *
     * Sign convention (matches the live caller SyncedMediaPlayer.syncPosition):
     *   signedDrift = localPosition - expectedPosition
     *   Positive  = client is AHEAD of the host
     *   Negative  = client is BEHIND the host
     *
     * Convergence logic:
     *   expectedPos = hostPos + (getSynchronizedTime() - hostTimestamp)
     *   where getSynchronizedTime() = localNow + clockOffset
     *   - Client BEHIND (negative drift): lower the offset so expectedPos
     *     comes DOWN toward the local position.
     *   - Client AHEAD (positive drift): raise the offset so expectedPos
     *     goes UP toward the local position.
     *   adjustment = avgDrift / 4 therefore converges in both directions.
     *
     * @param localPosition The local player position (used to detect if player is actually playing)
     */
    fun recordDrift(signedDrift: Long, localPosition: Long = -1L) {
        if (isHost) return // Host doesn't need drift adjustment
        
        val now = System.currentTimeMillis()
        
        // Skip drift recording during warmup period (first 3 seconds after session start)
        if (sessionStartTime > 0 && now - sessionStartTime < WARMUP_PERIOD_MS) {
            Timber.d("Ignoring drift sample during warmup period (${now - sessionStartTime}ms elapsed)")
            return
        }
        
        // If local position is 0 and drift is large, player probably isn't playing
        // Don't use this sample for adjustment
        if (localPosition == 0L && kotlin.math.abs(signedDrift) > 1000) {
            Timber.d("Ignoring drift sample - player not playing (localPos=0, drift=${signedDrift}ms)")
            return
        }
        
        // Reject drift samples when local position is negative (invalid state)
        if (localPosition < 0) {
            Timber.d("Ignoring drift sample - invalid local position: $localPosition")
            return
        }
        
        // Reject huge drift samples - they indicate clock sync is broken, not playback drift
        // Normal drift should be in the range of -5000ms to +5000ms (5 seconds)
        if (kotlin.math.abs(signedDrift) > 10000) {
            Timber.w("Rejecting huge drift sample: ${signedDrift}ms - clock sync may be broken")
            return
        }
        
        synchronized(driftSamples) {
            driftSamples.add(signedDrift)
            
            // Keep only recent samples
            while (driftSamples.size > DRIFT_SAMPLES_FOR_ADJUSTMENT * 2) {
                driftSamples.removeAt(0)
            }
            
            // Update current drift for UI
            _currentDrift.value = kotlin.math.abs(signedDrift)
            
            // Check if it's time to adjust
            if (now - lastDriftAdjustmentTime >= DRIFT_ADJUSTMENT_INTERVAL_MS 
                && driftSamples.size >= DRIFT_SAMPLES_FOR_ADJUSTMENT) {
                
                // Calculate average drift from recent samples
                val recentSamples = driftSamples.takeLast(DRIFT_SAMPLES_FOR_ADJUSTMENT)
                val avgDrift = recentSamples.sum() / recentSamples.size
                
                // Check if drift samples are consistent (same sign) to avoid oscillation
                val allPositive = recentSamples.all { it > 0 }
                val allNegative = recentSamples.all { it < 0 }
                val consistentBias = allPositive || allNegative
                
                // Also check if drift values are converging (not oscillating wildly)
                val maxDrift = recentSamples.maxOrNull() ?: 0L
                val minDrift = recentSamples.minOrNull() ?: 0L
                val range = maxDrift - minDrift
                val isStable = range < 500 // Within 500ms range considered stable
                
                // Only adjust if:
                // 1. Drift is significant (> 100ms) but not too large (< 2000ms)
                // 2. Drift samples are consistent (same sign) AND stable
                // 3. This prevents oscillation and only adjusts for real clock drift
                val absAvgDrift = kotlin.math.abs(avgDrift)
                val shouldAdjust = absAvgDrift > 100 && absAvgDrift < 2000 && consistentBias && isStable
                
                if (shouldAdjust) {
                    // adjustment = avgDrift / 4 converges in both directions
                    // (see recordDrift KDoc for the full derivation).
                    val adjustment = avgDrift / 4
                    val newOffset = clockOffset + adjustment
                    
                    // Bound the offset to prevent unbounded growth
                    clockOffset = newOffset.coerceIn(-MAX_CLOCK_OFFSET_MS, MAX_CLOCK_OFFSET_MS)
                    
                    if (clockOffset != newOffset) {
                        Timber.w("Clock offset bounded: wanted ${newOffset}ms, clamped to ${clockOffset}ms")
                    } else {
                        Timber.i("Dynamic clock adjustment: avgDrift=${avgDrift}ms, adjustment=${adjustment}ms, newOffset=${clockOffset}ms")
                    }
                    _syncState.value = ClockSyncState.Synced(clockOffset, roundTripTime)
                    
                    // Only clear samples after successful adjustment
                    driftSamples.clear()
                } else if (absAvgDrift > 5) {
                    Timber.d("Skipping adjustment: avgDrift=${avgDrift}ms, range=${range}ms, consistentBias=$consistentBias, isStable=$isStable")
                }
                
                lastDriftAdjustmentTime = now
            }
        }
    }
    
    /**
     * Get the current measured drift
     */
    fun getCurrentDrift(): Long = _currentDrift.value
    
    /**
     * Discard accumulated drift samples without changing the offset.
     * Call after intentional seeks / track switches: the next few seconds of
     * position data are unrepresentative of clock error, and feeding them
     * into convergence poisons the offset for minutes afterward.
     */
    fun resetDriftSamples() {
        if (isHost) return
        synchronized(driftSamples) {
            if (driftSamples.isNotEmpty()) {
                driftSamples.clear()
                Timber.d("Drift samples reset (intentional seek or track switch)")
            }
        }
    }
    
    /**
     * Get estimated network latency (one-way)
     */
    fun getNetworkLatency(): Long {
        return roundTripTime / 2
    }
    
    /**
     * HOST: Start as the time reference
     * Host's synchronized time = local time (offset = 0)
     */
    fun startAsHost() {
        isHost = true
        clockOffset = 0L
        roundTripTime = 0L
        _syncState.value = ClockSyncState.Synced(0L, 0L)
        Timber.i("Started as host (time reference)")
    }
    
    /**
     * Stop clock sync
     */
    fun stop() {
        clockOffset = 0L
        roundTripTime = 0L
        isHost = false
        synchronized(driftSamples) {
            driftSamples.clear()
        }
        lastDriftAdjustmentTime = 0L
        sessionStartTime = 0L
        _syncState.value = ClockSyncState.NotSynced
        Timber.i("Clock sync stopped")
    }
}

/**
 * Clock sync states
 */
sealed class ClockSyncState {
    data object NotSynced : ClockSyncState()
    data object Syncing : ClockSyncState()
    data class Synced(val offsetMs: Long, val rttMs: Long) : ClockSyncState()
    data class Error(val message: String) : ClockSyncState()
}
