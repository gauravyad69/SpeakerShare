package io.github.gauravyad69.speakershare.media.sync

import timber.log.Timber
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.gson.*
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

/**
 * Client that connects to host's sync server and receives playback commands
 */
@Singleton
class SyncedPlaybackClient @Inject constructor(
    private val clockSynchronizer: ClockSynchronizer
) {
    companion object {
        private const val POLL_INTERVAL_MS = 100L // Poll frequently for low latency
        private const val CONNECTION_TIMEOUT_MS = 5000
        private const val CLOCK_SYNC_SAMPLES = 5 // Multiple samples for accuracy
        private const val MAX_CONSECUTIVE_ERRORS = 10
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null
    private var consecutiveErrors = 0
    
    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            gson()
        }
        engine {
            connectTimeout = CONNECTION_TIMEOUT_MS
            socketTimeout = CONNECTION_TIMEOUT_MS
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
    
    /**
     * Connect to a sync host
     */
    suspend fun connectToHost(hostIp: String): Result<JoinResponse> {
        return try {
            hostAddress = hostIp
            val baseUrl = "http://$hostIp:${SyncedPlaybackServer.SYNC_SERVER_PORT}"
            
            Timber.i("=== CONNECTING TO HOST === at $baseUrl")
            
            // First sync clocks
            syncClock(baseUrl)
            Timber.i("Clock sync completed, offset = ${clockSynchronizer.getOffset()} ms")
            
            // Join the session
            Timber.i("Sending join request to $baseUrl/sync/join")
            val response = client.post("$baseUrl/sync/join") {
                parameter("clientId", clientId)
            }
            Timber.i("Join response status: ${response.status}")
            
            if (response.status == HttpStatusCode.OK) {
                val joinResponse = response.body<JoinResponse>()
                if (joinResponse.success) {
                    _sessionInfo.value = joinResponse
                    _isConnected.value = true
                    _connectionError.value = null
                    
                    // Start polling for commands
                    startPolling(baseUrl)
                    
                    Timber.i("Connected to host: ${joinResponse.sessionId}")
                    Result.success(joinResponse)
                } else {
                    Result.failure(Exception("Failed to join session"))
                }
            } else {
                Result.failure(Exception("Server returned ${response.status}"))
            }
        } catch (e: Exception) {
            Timber.e("Failed to connect to host", e)
            _connectionError.value = e.message
            Result.failure(e)
        }
    }
    
    /**
     * Disconnect from host
     */
    suspend fun disconnect() {
        try {
            pollJob?.cancel()
            pollJob = null
            
            hostAddress?.let { host ->
                val baseUrl = "http://$host:${SyncedPlaybackServer.SYNC_SERVER_PORT}"
                try {
                    client.post("$baseUrl/sync/leave") {
                        parameter("clientId", clientId)
                    }
                } catch (e: Exception) {
                    Timber.w("Failed to notify host of disconnect", e)
                }
            }
            
            _isConnected.value = false
            _sessionInfo.value = null
            hostAddress = null
            
            Timber.i("Disconnected from host")
        } catch (e: Exception) {
            Timber.e("Error during disconnect", e)
        }
    }
    
    /**
     * Get synchronized time (host's clock) - now delegated to shared ClockSynchronizer
     */
    fun getSynchronizedTime(): Long {
        return clockSynchronizer.getSynchronizedTime()
    }
    
    /**
     * Sync clock with host using multiple samples for accuracy
     * Updates the shared ClockSynchronizer so SyncedMediaPlayer uses the correct offset
     */
    private suspend fun syncClock(baseUrl: String) {
        try {
            val offsets = mutableListOf<Long>()
            val rtts = mutableListOf<Long>()
            
            // Take multiple samples for accuracy
            repeat(CLOCK_SYNC_SAMPLES) { i ->
                val result = performSingleClockSync(baseUrl)
                if (result != null) {
                    offsets.add(result.first)
                    rtts.add(result.second)
                    Timber.d("Clock sample $i: offset=${result.first}ms, rtt=${result.second}ms")
                }
                delay(50) // Small delay between samples
            }
            
            if (offsets.isEmpty()) {
                Timber.w("No successful clock sync samples")
                return
            }
            
            // Use median to filter outliers (best accuracy)
            offsets.sort()
            rtts.sort()
            
            val medianOffset = offsets[offsets.size / 2]
            val medianRtt = rtts[rtts.size / 2]
            
            // Update the SHARED ClockSynchronizer so SyncedMediaPlayer uses correct time
            clockSynchronizer.setOffset(medianOffset)
            
            Timber.i("Clock synced with host: offset=${medianOffset}ms, rtt=${medianRtt}ms (${offsets.size} samples)")
            
        } catch (e: Exception) {
            Timber.w("Clock sync failed", e)
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
    
    private fun startPolling(baseUrl: String) {
        pollJob?.cancel()
        consecutiveErrors = 0
        
        pollJob = scope.launch {
            while (isActive && _isConnected.value) {
                try {
                    val response = client.get("$baseUrl/sync/commands") {
                        parameter("clientId", clientId)
                    }
                    
                    if (response.status == HttpStatusCode.OK) {
                        consecutiveErrors = 0 // Reset on success
                        val commandsResponse = response.body<CommandsResponse>()
                        
                        for (cmd in commandsResponse.commands) {
                            val playbackCommand = parseCommand(cmd)
                            if (playbackCommand != null) {
                                Timber.d("Received command: ${cmd.type}")
                                _commands.emit(playbackCommand)
                            }
                        }
                    } else {
                        consecutiveErrors++
                        Timber.w("Polling returned ${response.status}, errors=$consecutiveErrors")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    consecutiveErrors++
                    Timber.w("Polling error ($consecutiveErrors/$MAX_CONSECUTIVE_ERRORS): ${e.message}")
                    
                    // Disconnect if too many consecutive errors (host likely down)
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        Timber.e("Too many consecutive polling errors, disconnecting")
                        _isConnected.value = false
                        _connectionError.value = "Lost connection to host"
                        break
                    }
                }
                
                delay(POLL_INTERVAL_MS)
            }
        }
    }
    
    private fun parseCommand(cmd: SyncCommand): PlaybackCommand? {
        return when (cmd.type) {
            "play" -> PlaybackCommand.Play(
                timestamp = cmd.timestamp,
                positionMs = cmd.positionMs,
                fileIndex = cmd.fileIndex
            )
            "pause" -> PlaybackCommand.Pause(
                timestamp = cmd.timestamp,
                positionMs = cmd.positionMs
            )
            "seek" -> PlaybackCommand.Seek(
                timestamp = cmd.timestamp,
                positionMs = cmd.positionMs,
                resumePlayback = cmd.resumePlayback
            )
            "switch" -> PlaybackCommand.SwitchFile(
                timestamp = cmd.timestamp,
                fileIndex = cmd.fileIndex,
                autoPlay = cmd.autoPlay
            )
            "sync" -> PlaybackCommand.SyncPulse(
                timestamp = cmd.timestamp,
                positionMs = cmd.positionMs
            )
            "stop" -> PlaybackCommand.Stop(
                timestamp = cmd.timestamp
            )
            else -> null
        }
    }
}
