package io.github.gauravyad69.speakershare.media.sync

import android.util.Log
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
        private const val TAG = "ClockSynchronizer"
        
        // Number of sync samples to average
        const val SYNC_SAMPLES = 5
        
        // How often to resync (ms)
        const val RESYNC_INTERVAL_MS = 30_000L
        
        // Maximum acceptable offset change between syncs
        const val MAX_OFFSET_DRIFT_MS = 100L
        
        // Default port for clock sync
        const val SYNC_PORT = 9091
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Current clock offset (add to local time to get synchronized time)
    private var clockOffset: Long = 0L
    
    // Network round trip time estimate
    private var roundTripTime: Long = 0L
    
    // Whether we're the host (host has offset = 0)
    private var isHost: Boolean = false
    
    // Sync state
    private val _syncState = MutableStateFlow<ClockSyncState>(ClockSyncState.NotSynced)
    val syncState: StateFlow<ClockSyncState> = _syncState.asStateFlow()
    
    private var syncJob: Job? = null
    private var hostServer: Job? = null
    
    /**
     * Get the synchronized time across all devices
     */
    fun getSynchronizedTime(): Long {
        return System.currentTimeMillis() + clockOffset
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
        _syncState.value = ClockSyncState.Synced(0L, 0L)
        
        // Start server to respond to sync requests
        hostServer = scope.launch {
            startSyncServer()
        }
        
        Log.i(TAG, "Started as host (time reference)")
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
                    Log.d(TAG, "Sync sample $i: offset=${result.offset}ms, rtt=${result.rtt}ms")
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
            
            _syncState.value = ClockSyncState.Synced(clockOffset, roundTripTime)
            
            // Start periodic resync
            startPeriodicResync(hostAddress)
            
            Log.i(TAG, "Clock synced with host: offset=${clockOffset}ms, rtt=${roundTripTime}ms")
            Result.success(clockOffset)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync clock", e)
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
            Log.e(TAG, "Sync exchange failed", e)
            null
        }
    }
    
    /**
     * Start the sync server (host only)
     */
    private suspend fun startSyncServer() {
        // Using Ktor server for sync responses
        // This will be integrated with the main HttpApiServer
        Log.d(TAG, "Sync server started on port $SYNC_PORT")
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
                        Log.w(TAG, "Large clock drift detected: ${drift}ms, resyncing")
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
        Log.i(TAG, "Clock sync stopped")
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
