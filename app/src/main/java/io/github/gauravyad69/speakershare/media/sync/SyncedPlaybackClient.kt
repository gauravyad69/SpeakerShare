package io.github.gauravyad69.speakershare.media.sync

import timber.log.Timber
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Client that connects to host's sync server and receives playback commands via WebSocket
 */
@Singleton
class SyncedPlaybackClient @Inject constructor(
    private val clockSynchronizer: ClockSynchronizer
) {
    companion object {
        private const val CONNECTION_TIMEOUT_MS = 5000L
        private const val CLOCK_SYNC_SAMPLES = 5 // Multiple samples for accuracy
        private const val RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocketJob: Job? = null
    private var webSocketSession: WebSocketSession? = null
    private val gson = Gson()
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            gson()
        }
        install(WebSockets) {
            pingInterval = 15_000
        }
        engine {
            requestTimeout = CONNECTION_TIMEOUT_MS
        }
    }
    
    private val clientId = UUID.randomUUID().toString()
    private var hostAddress: String? = null
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()
    
    // Incoming commands from host
    private val _commands = MutableSharedFlow<PlaybackCommand>()
    val commands: SharedFlow<PlaybackCommand> = _commands.asSharedFlow()
    
    // Session info from host
    private val _sessionInfo = MutableStateFlow<JoinResponse?>(null)
    val sessionInfo: StateFlow<JoinResponse?> = _sessionInfo.asStateFlow()
    
    // Reconnection events - emitted when WebSocket reconnects after disconnect
    private val _reconnectionEvents = MutableSharedFlow<Unit>()
    val reconnectionEvents: SharedFlow<Unit> = _reconnectionEvents.asSharedFlow()
    
    /**
     * Connect to a sync host via WebSocket
     */
    suspend fun connectToHost(hostIp: String): Result<JoinResponse> {
        return try {
            hostAddress = hostIp
            val baseUrl = "http://$hostIp:${SyncedPlaybackServer.SYNC_SERVER_PORT}"
            val wsUrl = "ws://$hostIp:${SyncedPlaybackServer.SYNC_SERVER_PORT}"
            
            Timber.i("=== CONNECTING TO HOST VIA WEBSOCKET === at $wsUrl")
            
            // First sync clocks via HTTP (more reliable for initial sync)
            syncClock(baseUrl)
            Timber.i("Clock sync completed, offset = ${clockSynchronizer.getOffset()} ms")
            
            // Connect via WebSocket for real-time commands
            startWebSocketConnection(wsUrl)
            
            // Wait for session info from WebSocket
            var attempts = 0
            while (_sessionInfo.value == null && attempts < 50) { // 5 second timeout
                delay(100)
                attempts++
            }
            
            val sessionInfo = _sessionInfo.value
            if (sessionInfo != null && sessionInfo.success) {
                _isConnected.value = true
                _connectionError.value = null
                Timber.i("Connected to host via WebSocket: ${sessionInfo.sessionId}")
                Result.success(sessionInfo)
            } else {
                Result.failure(Exception("Failed to get session info from host"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to host")
            _connectionError.value = e.message
            Result.failure(e)
        }
    }
    
    /**
     * Disconnect from host
     */
    suspend fun disconnect() {
        try {
            webSocketJob?.cancel()
            webSocketJob = null
            
            webSocketSession?.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnecting"))
            webSocketSession = null
            
            _isConnected.value = false
            _sessionInfo.value = null
            hostAddress = null
            
            Timber.i("Disconnected from host")
        } catch (e: Exception) {
            Timber.e(e, "Error during disconnect")
        }
    }
    
    /**
     * Get synchronized time (host's clock) - now delegated to shared ClockSynchronizer
     */
    fun getSynchronizedTime(): Long {
        return clockSynchronizer.getSynchronizedTime()
    }
    
    /**
     * Start WebSocket connection for real-time command reception
     */
    private fun startWebSocketConnection(wsUrl: String) {
        webSocketJob?.cancel()
        
        webSocketJob = scope.launch {
            var reconnectAttempts = 0
            var wasConnectedBefore = false
            
            while (isActive && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                try {
                    Timber.i("Connecting WebSocket to $wsUrl/sync/ws/$clientId")
                    
                    client.webSocket("$wsUrl/sync/ws/$clientId") {
                        webSocketSession = this
                        
                        // Emit reconnection event if this is a reconnect (not first connection)
                        if (wasConnectedBefore) {
                            Timber.i("WebSocket reconnected - emitting reconnection event")
                            _reconnectionEvents.emit(Unit)
                        }
                        wasConnectedBefore = true
                        
                        reconnectAttempts = 0 // Reset on successful connection
                        Timber.i("WebSocket connected successfully")
                        
                        // Listen for messages
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    handleWebSocketMessage(text)
                                }
                                is Frame.Close -> {
                                    Timber.d("Server closed WebSocket connection")
                                    break
                                }
                                else -> {}
                            }
                        }
                    }
                    
                    webSocketSession = null
                    
                    // If we get here, connection was closed
                    if (isActive && _isConnected.value) {
                        Timber.w("WebSocket connection closed, will reconnect...")
                        reconnectAttempts++
                        delay(RECONNECT_DELAY_MS)
                    }
                    
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e("WebSocket error: ${e.message}")
                    webSocketSession = null
                    reconnectAttempts++
                    
                    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                        _isConnected.value = false
                        _connectionError.value = "Lost connection to host after $reconnectAttempts attempts"
                        break
                    }
                    
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }
    }
    
    /**
     * Handle incoming WebSocket messages
     */
    private suspend fun handleWebSocketMessage(message: String) {
        try {
            val mapType = object : TypeToken<Map<String, Any>>() {}.type
            val data: Map<String, Any> = gson.fromJson(message, mapType)
            
            val type = data["type"] as? String
            
            when (type) {
                "session" -> {
                    // Parse session info
                    val dataField = data["data"]
                    if (dataField != null) {
                        val joinJson = gson.toJson(dataField)
                        val joinResponse = gson.fromJson(joinJson, JoinResponse::class.java)
                        _sessionInfo.value = joinResponse
                        Timber.d("Received session info: ${joinResponse.sessionId}")
                    }
                }
                "pong" -> {
                    // Heartbeat response
                }
                "clock_sync_response" -> {
                    // Deliberately ignored: applying a clock offset derived from a
                    // single exchange hard-sets the offset with jitter directly
                    // becoming offset error. Clock maintenance during playback is
                    // handled by ClockSynchronizer.recordDrift convergence, and the
                    // initial multi-sample sync happens in syncClock().
                }
                else -> {
                    // Try to parse as a sync command
                    val command = parseSyncCommand(data)
                    if (command != null) {
                        Timber.d("Received command via WebSocket: ${data["type"]}")
                        _commands.emit(command)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w("Failed to parse WebSocket message: ${e.message}")
        }
    }
    
    /**
     * Parse a sync command from WebSocket message
     */
    private fun parseSyncCommand(data: Map<String, Any>): PlaybackCommand? {
        val type = data["type"] as? String ?: return null
        val timestamp = (data["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
        val positionMs = (data["positionMs"] as? Number)?.toLong() ?: 0L
        val fileIndex = (data["fileIndex"] as? Number)?.toInt() ?: 0
        val resumePlayback = data["resumePlayback"] as? Boolean ?: false
        val autoPlay = data["autoPlay"] as? Boolean ?: false
        val volume = (data["volume"] as? Number)?.toFloat() ?: 1.0f
        
        return when (type) {
            "play" -> PlaybackCommand.Play(
                timestamp = timestamp,
                positionMs = positionMs,
                fileIndex = fileIndex
            )
            "pause" -> PlaybackCommand.Pause(
                timestamp = timestamp,
                positionMs = positionMs
            )
            "seek" -> PlaybackCommand.Seek(
                timestamp = timestamp,
                positionMs = positionMs,
                resumePlayback = resumePlayback
            )
            "switch" -> PlaybackCommand.SwitchFile(
                timestamp = timestamp,
                fileIndex = fileIndex,
                autoPlay = autoPlay
            )
            "sync" -> PlaybackCommand.SyncPulse(
                timestamp = timestamp,
                positionMs = positionMs
            )
            "stop" -> PlaybackCommand.Stop(
                timestamp = timestamp
            )
            "volume" -> PlaybackCommand.Volume(
                timestamp = timestamp,
                volume = volume
            )
            else -> null
        }
    }
    
    /**
     * Sync clock with host using multiple samples for accuracy
     * Uses HTTP for initial sync (more reliable than WebSocket for this)
     *
     * Uses the minimum-RTT sample's offset - standard NTP practice: the
     * exchange with the lowest round-trip time suffered the least queueing
     * delay, so its offset estimate has the least jitter-induced error.
     * The median is used as a sanity fallback.
     */
    private suspend fun syncClock(baseUrl: String) {
        try {
            data class Sample(val offset: Long, val rtt: Long)
            val samples = mutableListOf<Sample>()
            
            // Take multiple samples for accuracy
            repeat(CLOCK_SYNC_SAMPLES) { i ->
                val result = performSingleClockSync(baseUrl)
                if (result != null) {
                    samples.add(Sample(result.first, result.second))
                    Timber.d("Clock sample $i: offset=${result.first}ms, rtt=${result.second}ms")
                }
                delay(50) // Small delay between samples
            }
            
            if (samples.isEmpty()) {
                Timber.w("No successful clock sync samples")
                return
            }
            
            // Minimum-RTT sample = least jitter-polluted offset estimate
            val bestSample = samples.minBy { it.rtt }
            
            // Median for comparison/logging (sanity check)
            val medianOffset = samples.map { it.offset }.sorted()[samples.size / 2]
            val medianRtt = samples.map { it.rtt }.sorted()[samples.size / 2]
            
            // Use the min-RTT offset; fall back to median if they wildly
            // disagree (indicates an unstable path rather than jitter)
            val chosenOffset = if (kotlin.math.abs(bestSample.offset - medianOffset) > 100) {
                Timber.w("Min-RTT offset ${bestSample.offset}ms disagrees with median $medianOffset ms - using median")
                medianOffset
            } else {
                bestSample.offset
            }
            
            // Update the SHARED ClockSynchronizer so SyncedMediaPlayer uses correct time
            clockSynchronizer.setOffset(chosenOffset)
            
            Timber.i(
                "Clock synced with host: offset=${chosenOffset}ms (min-RTT ${bestSample.rtt}ms, " +
                "median offset=${medianOffset}ms, median rtt=${medianRtt}ms, ${samples.size} samples)"
            )
            
        } catch (e: Exception) {
            Timber.w(e, "Clock sync failed")
        }
    }
    
    /**
     * Perform a single clock sync exchange
     * Returns Pair(offset, rtt) or null on failure
     */
    private suspend fun performSingleClockSync(baseUrl: String): Pair<Long, Long>? {
        return try {
            val t1 = System.currentTimeMillis()
            val response = client.get("$baseUrl/sync/clock") {
                parameter("t1", t1)
            }
            
            if (response.status == HttpStatusCode.OK) {
                val t4 = System.currentTimeMillis()
                val clockResponse = response.body<ClockSyncResponse>()
                
                // Calculate offset using NTP-like algorithm
                val roundTripTime = (t4 - t1) - (clockResponse.t3 - clockResponse.t2)
                val offset = ((clockResponse.t2 - t1) + (clockResponse.t3 - t4)) / 2
                
                Pair(offset, roundTripTime)
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.w("Single clock sync failed: ${e.message}")
            null
        }
    }
    
    /**
     * Request clock sync via WebSocket (for periodic re-sync)
     */
    suspend fun requestClockSyncViaWebSocket() {
        // Intentionally not implemented: a single-exchange sync hard-sets the
        // offset with jitter directly becoming offset error. If periodic
        // resync is ever needed, it must take multiple samples and only
        // apply a bounded correction (see syncClock for the correct pattern).
        Timber.d("requestClockSyncViaWebSocket called - not supported by design")
    }
}
