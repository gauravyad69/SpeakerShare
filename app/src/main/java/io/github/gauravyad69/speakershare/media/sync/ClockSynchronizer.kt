package io.github.gauravyad69.speakershare.media.sync

import timber.log.Timber
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Clock Synchronizer for distributed playback
 * 
 * Uses a simplified NTP-like algorithm to sync clocks between host and clients.
 * This allows all devices to have a common time reference for synchronized playback.
 * 
 * Algorithm:
 * 1. Client sends request with local timestamp T1
 * 2. Host receives at T2, responds with T2 and T3 (send time)
 * 3. Client receives at T4
 * 4. Round trip time = (T4 - T1) - (T3 - T2)
 * 5. Clock offset = ((T2 - T1) + (T3 - T4)) / 2
 * 
 * Accuracy: ~10-20ms on local WiFi networks
 */
@Singleton
class ClockSynchronizer @Inject constructor() {
    
    companion object {
        
        // Number of sync samples to average
        const val SYNC_SAMPLES = 5
        
        // How often to resync (ms)
        const val RESYNC_INTERVAL_MS = 30_000L
        
        // Maximum acceptable offset change between syncs
        const val MAX_OFFSET_DRIFT_MS = 100L
        
        // Default port for clock sync
        const val SYNC_PORT = 9091
        
        // Number of drift samples to collect before adjusting offset
        const val DRIFT_SAMPLES_FOR_ADJUSTMENT = 5
        
        // How often to adjust offset based on drift (ms)
        const val DRIFT_ADJUSTMENT_INTERVAL_MS = 5_000L
        
        // Maximum allowed clock offset (prevent unbounded growth)
        const val MAX_CLOCK_OFFSET_MS = 30_000L
        
        // Warmup period after joining session before drift adjustment starts
        const val WARMUP_PERIOD_MS = 3_000L
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
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
    
    private var syncJob: Job? = null
    private var hostServer: Job? = null
    
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
     * Set clock offset manually (used when client syncs via different channel)
     */
    fun setOffset(offset: Long) {
        clockOffset = offset
        _syncState.value = ClockSyncState.Synced(offset, roundTripTime)
        Timber.i("Clock offset set manually: ${offset}ms")
    }
    
    /**
     * Record a drift sample and potentially adjust the clock offset
     * Called by the client when receiving sync pulses from host
     * 
     * @param signedDrift The drift (hostPosition - localPosition). Positive means we're behind, negative means ahead.
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
                // 1. Drift is significant (> 50ms) but not too large (< 2000ms)
                // 2. Either drift samples are consistent (same sign) OR stable with low variance
                // 3. This prevents oscillation when drift alternates between + and -
                val absAvgDrift = kotlin.math.abs(avgDrift)
                val shouldAdjust = absAvgDrift > 50 && absAvgDrift < 2000 && (consistentBias || isStable)
                
                if (shouldAdjust) {
                    // Adjust clock offset to compensate for drift
                    // If avgDrift is positive (we're behind), increase offset
                    // If avgDrift is negative (we're ahead), decrease offset
                    // Use smaller adjustment factor (1/3 instead of 1/2) to reduce oscillation
                    val adjustment = avgDrift / 3 
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
        _syncState.value = ClockSyncState.Synced(0L, 0L)
        
        // Start server to respond to sync requests
        hostServer = scope.launch {
            startSyncServer()
        }
        
        Timber.i("Started as host (time reference)")
    }
    
    /**
     * CLIENT: Sync clock with host
     */
    suspend fun syncWithHost(hostAddress: String): Result<Long> {
        isHost = false
        _syncState.value = ClockSyncState.Syncing
        
        return try {
            val offsets = mutableListOf<Long>()
            val rtts = mutableListOf<Long>()
            
            // Take multiple samples and average
            repeat(SYNC_SAMPLES) { i ->
                val result = performSyncExchange(hostAddress)
                if (result != null) {
                    offsets.add(result.offset)
                    rtts.add(result.rtt)
                    Timber.d("Sync sample $i: offset=${result.offset}ms, rtt=${result.rtt}ms")
                }
                delay(100) // Small delay between samples
            }
            
            if (offsets.isEmpty()) {
                _syncState.value = ClockSyncState.Error("Failed to sync with host")
                return Result.failure(Exception("No successful sync samples"))
            }
            
            // Use median to filter outliers
            offsets.sort()
            rtts.sort()
            
            clockOffset = offsets[offsets.size / 2]
            roundTripTime = rtts[rtts.size / 2]
            
            // Mark session start time for warmup period
            sessionStartTime = System.currentTimeMillis()
            
            _syncState.value = ClockSyncState.Synced(clockOffset, roundTripTime)
            
            // Start periodic resync
            startPeriodicResync(hostAddress)
            
            Timber.i("Clock synced with host: offset=${clockOffset}ms, rtt=${roundTripTime}ms")
            Result.success(clockOffset)
            
        } catch (e: Exception) {
            Timber.e("Failed to sync clock", e)
            _syncState.value = ClockSyncState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }
    
    /**
     * Perform a single sync exchange with the host
     */
    private suspend fun performSyncExchange(hostAddress: String): SyncResult? {
        return try {
            val client = HttpClient()
            
            val t1 = System.currentTimeMillis()
            
            val response = client.get("http://$hostAddress:$SYNC_PORT/sync") {
                parameter("t1", t1)
            }
            
            val t4 = System.currentTimeMillis()
            
            val body = response.bodyAsText()
            val parts = body.split(",")
            
            if (parts.size >= 2) {
                val t2 = parts[0].toLong()
                val t3 = parts[1].toLong()
                
                // Calculate offset and RTT
                val rtt = (t4 - t1) - (t3 - t2)
                val offset = ((t2 - t1) + (t3 - t4)) / 2
                
                client.close()
                SyncResult(offset, rtt)
            } else {
                client.close()
                null
            }
            
        } catch (e: Exception) {
            Timber.e("Sync exchange failed", e)
            null
        }
    }
    
    /**
     * Start the sync server (host only)
     */
    private suspend fun startSyncServer() {
        // Using Ktor server for sync responses
        // This will be integrated with the main HttpApiServer
        Timber.d("Sync server started on port $SYNC_PORT")
    }
    
    /**
     * Start periodic resync to maintain accuracy
     */
    private fun startPeriodicResync(hostAddress: String) {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                delay(RESYNC_INTERVAL_MS)
                
                val result = performSyncExchange(hostAddress)
                if (result != null) {
                    val drift = abs(result.offset - clockOffset)
                    
                    if (drift < MAX_OFFSET_DRIFT_MS) {
                        // Small drift, apply gradual correction
                        clockOffset = (clockOffset + result.offset) / 2
                        roundTripTime = (roundTripTime + result.rtt) / 2
                    } else {
                        // Large drift, full resync
                        Timber.w("Large clock drift detected: ${drift}ms, resyncing")
                        syncWithHost(hostAddress)
                    }
                }
            }
        }
    }
    
    /**
     * Stop clock sync
     */
    fun stop() {
        syncJob?.cancel()
        syncJob = null
        hostServer?.cancel()
        hostServer = null
        clockOffset = 0L
        roundTripTime = 0L
        _syncState.value = ClockSyncState.NotSynced
        Timber.i("Clock sync stopped")
    }
    
    /**
     * Handle incoming sync request (host side)
     * Returns response string: "t2,t3"
     */
    fun handleSyncRequest(t1: Long): String {
        val t2 = System.currentTimeMillis()
        // Small delay for processing
        val t3 = System.currentTimeMillis()
        return "$t2,$t3"
    }
    
    private data class SyncResult(
        val offset: Long,
        val rtt: Long
    )
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
