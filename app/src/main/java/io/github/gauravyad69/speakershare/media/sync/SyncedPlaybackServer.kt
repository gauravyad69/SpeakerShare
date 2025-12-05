package io.github.gauravyad69.speakershare.media.sync

import android.content.Context
import android.net.Uri
import timber.log.Timber
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.google.gson.Gson

/**
 * HTTP Server for synced playback command distribution
 * 
 * Host broadcasts playback commands via this server.
 * Clients poll /commands endpoint to receive them.
 */
@Singleton
class SyncedPlaybackServer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val SYNC_SERVER_PORT = 8765
        private const val MAX_COMMAND_QUEUE_SIZE = 100 // Prevent memory issues
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: NettyApplicationEngine? = null
    private val gson = Gson()
    
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    
    // Queue of commands to broadcast to clients (per-client queues to avoid missed commands)
    private val clientCommandQueues = mutableMapOf<String, MutableList<SyncCommand>>()
    private val commandQueueLock = Any()
    
    // WebSocket sessions for instant push
    private val webSocketSessions = ConcurrentHashMap<String, WebSocketSession>()
    
    // Current session info
    private var sessionInfo: SessionInfo? = null
    
    // Map of file hash to URI for serving files
    private val fileUriMap = mutableMapOf<String, Uri>()
    
    // Connected clients tracking
    private val _connectedClients = MutableStateFlow<Set<String>>(emptySet())
    val connectedClients: StateFlow<Set<String>> = _connectedClients.asStateFlow()

    /**
     * Register files for serving
     */
    fun registerFiles(files: List<SyncedMediaFile>) {
        fileUriMap.clear()
        files.forEach { file ->
            fileUriMap[file.contentHash] = file.uri
        }
        Timber.i("Registered ${files.size} files for serving")
    }

    /**
     * Start the sync server as host
     */
    fun startServer(session: SessionInfo): Boolean {
        if (_isRunning.value) {
            Timber.w("Server already running")
            return true
        }
        
        return try {
            sessionInfo = session
            
            // Clear all client command queues
            synchronized(commandQueueLock) {
                clientCommandQueues.clear()
            }
            
            Timber.i("Starting sync server on port $SYNC_SERVER_PORT for session ${session.sessionId}")
            
            server = embeddedServer(Netty, port = SYNC_SERVER_PORT, host = "0.0.0.0") {
                install(ContentNegotiation) {
                    gson {
                        setPrettyPrinting()
                    }
                }
                install(CORS) {
                    anyHost()
                    allowMethod(HttpMethod.Get)
                    allowMethod(HttpMethod.Post)
                    allowHeader(HttpHeaders.ContentType)
                }
                install(WebSockets) {
                    pingPeriod = Duration.ofSeconds(15)
                    timeout = Duration.ofSeconds(30)
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }
                
                routing {
                    configureSyncRoutes()
                    configureWebSocketRoutes()
                }
            }
            
            server?.start(wait = false)
            _isRunning.value = true
            
            Timber.i("Sync server started on port $SYNC_SERVER_PORT")
            true
            
        } catch (e: Exception) {
            Timber.e("Failed to start sync server", e)
            false
        }
    }
    
    /**
     * Stop the sync server
     */
    fun stopServer() {
        scope.launch {
            try {
                // Close all WebSocket sessions
                webSocketSessions.values.forEach { session ->
                    try {
                        session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server stopping"))
                    } catch (e: Exception) {
                        Timber.w("Error closing WebSocket session", e)
                    }
                }
                webSocketSessions.clear()
                
                server?.stop(500, 1000)
                server = null
                _isRunning.value = false
                sessionInfo = null
                _connectedClients.value = emptySet()
                
                // Clear all command queues
                synchronized(commandQueueLock) {
                    clientCommandQueues.clear()
                }
                
                Timber.i("Sync server stopped")
            } catch (e: Exception) {
                Timber.e("Error stopping sync server", e)
            }
        }
    }
    
    /**
     * Broadcast a command to all connected clients
     */
    fun broadcastCommand(command: PlaybackCommand) {
        val syncCommand = when (command) {
            is PlaybackCommand.Play -> SyncCommand(
                type = "play",
                timestamp = command.timestamp,
                positionMs = command.positionMs,
                fileIndex = command.fileIndex
            )
            is PlaybackCommand.Pause -> SyncCommand(
                type = "pause",
                timestamp = command.timestamp,
                positionMs = command.positionMs
            )
            is PlaybackCommand.Seek -> SyncCommand(
                type = "seek",
                timestamp = command.timestamp,
                positionMs = command.positionMs,
                resumePlayback = command.resumePlayback
            )
            is PlaybackCommand.SwitchFile -> SyncCommand(
                type = "switch",
                timestamp = command.timestamp,
                fileIndex = command.fileIndex,
                autoPlay = command.autoPlay
            )
            is PlaybackCommand.SyncPulse -> SyncCommand(
                type = "sync",
                timestamp = command.timestamp,
                positionMs = command.positionMs
            )
            is PlaybackCommand.Stop -> SyncCommand(
                type = "stop",
                timestamp = command.timestamp
            )
            is PlaybackCommand.Volume -> SyncCommand(
                type = "volume",
                timestamp = command.timestamp,
                volume = command.volume
            )
        }
        
        // Add command to each connected client's queue (for HTTP polling fallback)
        synchronized(commandQueueLock) {
            for (clientId in _connectedClients.value) {
                val queue = clientCommandQueues.getOrPut(clientId) { mutableListOf() }
                queue.add(syncCommand)
                
                // Trim queue if too large (keep most recent)
                while (queue.size > MAX_COMMAND_QUEUE_SIZE) {
                    queue.removeAt(0)
                }
            }
        }
        
        // Push immediately to all WebSocket clients
        val json = gson.toJson(syncCommand)
        scope.launch {
            val deadSessions = mutableListOf<String>()
            webSocketSessions.forEach { (clientId, session) ->
                try {
                    session.send(Frame.Text(json))
                } catch (e: Exception) {
                    Timber.w("Failed to send to WebSocket client $clientId: ${e.message}")
                    deadSessions.add(clientId)
                }
            }
            // Clean up dead sessions
            deadSessions.forEach { clientId ->
                webSocketSessions.remove(clientId)
                Timber.d("Removed dead WebSocket session: $clientId")
            }
        }
        
        Timber.d("Broadcast command: ${syncCommand.type} to ${_connectedClients.value.size} HTTP + ${webSocketSessions.size} WS clients")
    }
    
    /**
     * Update session info (e.g., when file changes)
     */
    fun updateSession(session: SessionInfo) {
        sessionInfo = session
    }
    
    /**
     * Update playback state in session info (for late-joining clients)
     */
    fun updatePlaybackState(isPlaying: Boolean, positionMs: Long, fileIndex: Int? = null) {
        sessionInfo = sessionInfo?.copy(
            isPlaying = isPlaying,
            currentPositionMs = positionMs,
            currentFileIndex = fileIndex ?: sessionInfo?.currentFileIndex ?: 0
        )
        Timber.d("Updated session playback state: isPlaying=$isPlaying, positionMs=$positionMs")
    }
    
    private fun Routing.configureSyncRoutes() {
        route("/sync") {
            // Get session info
            get("/session") {
                val session = sessionInfo
                if (session != null) {
                    call.respond(HttpStatusCode.OK, session)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "No active session"))
                }
            }
            
            // Clock synchronization
            get("/clock") {
                val t1 = call.request.queryParameters["t1"]?.toLongOrNull() ?: 0L
                val t2 = System.currentTimeMillis()
                val t3 = System.currentTimeMillis()
                
                call.respond(HttpStatusCode.OK, ClockSyncResponse(t1, t2, t3))
            }
            
            // Get pending commands (per-client queue)
            get("/commands") {
                val clientId = call.request.queryParameters["clientId"] ?: "unknown"
                
                // Track client
                _connectedClients.value = _connectedClients.value + clientId
                
                // Get and clear this client's command queue
                val commands = synchronized(commandQueueLock) {
                    val queue = clientCommandQueues[clientId] ?: emptyList()
                    clientCommandQueues[clientId] = mutableListOf()
                    queue.toList()
                }
                
                call.respond(HttpStatusCode.OK, CommandsResponse(commands))
            }
            
            // Client registration
            post("/join") {
                val clientId = call.request.queryParameters["clientId"] ?: "client-${System.currentTimeMillis()}"
                _connectedClients.value = _connectedClients.value + clientId
                
                // Initialize empty command queue for this client
                synchronized(commandQueueLock) {
                    clientCommandQueues[clientId] = mutableListOf()
                }
                
                val session = sessionInfo
                if (session != null) {
                    call.respond(HttpStatusCode.OK, JoinResponse(
                        success = true,
                        sessionId = session.sessionId,
                        files = session.files,
                        currentFileIndex = session.currentFileIndex,
                        currentPositionMs = session.currentPositionMs,
                        isPlaying = session.isPlaying
                    ))
                } else {
                    call.respond(HttpStatusCode.NotFound, JoinResponse(
                        success = false,
                        sessionId = "",
                        files = emptyList(),
                        currentFileIndex = 0,
                        currentPositionMs = 0L,
                        isPlaying = false
                    ))
                }
            }
            
            // Client leaving
            post("/leave") {
                val clientId = call.request.queryParameters["clientId"] ?: ""
                _connectedClients.value = _connectedClients.value - clientId
                
                // Clean up this client's command queue
                synchronized(commandQueueLock) {
                    clientCommandQueues.remove(clientId)
                }
                
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }
        }
        
        // File serving routes
        route("/file") {
            get("/{hash}") {
                val hash = call.parameters["hash"]
                if (hash == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing hash parameter"))
                    return@get
                }
                
                val uri = fileUriMap[hash]
                if (uri == null) {
                    Timber.w("File not found for hash: $hash")
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "File not found"))
                    return@get
                }
                
                try {
                    Timber.i("Serving file with hash: $hash, uri: $uri")
                    val appContext = this@SyncedPlaybackServer.context
                    val inputStream = appContext.contentResolver.openInputStream(uri)
                    if (inputStream == null) {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Cannot read file"))
                        return@get
                    }
                    
                    val bytes = inputStream.readBytes()
                    inputStream.close()
                    
                    // Try to determine content type from file info
                    val fileInfo = sessionInfo?.files?.find { it.hash == hash }
                    val contentType = fileInfo?.mimeType?.let { mimeType -> 
                        ContentType.parse(mimeType) 
                    } ?: ContentType.Application.OctetStream
                    
                    call.respondBytes(bytes, contentType)
                    Timber.i("Served file: ${bytes.size} bytes")
                } catch (e: Exception) {
                    Timber.e("Error serving file", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }
        }
    }
    
    /**
     * Configure WebSocket routes for real-time sync command push
     */
    private fun Routing.configureWebSocketRoutes() {
        webSocket("/sync/ws/{clientId}") {
            val clientId = call.parameters["clientId"] ?: "ws-${System.currentTimeMillis()}"
            
            Timber.i("WebSocket client connected: $clientId")
            webSocketSessions[clientId] = this
            _connectedClients.value = _connectedClients.value + clientId
            
            try {
                // Send current session state immediately
                sessionInfo?.let { session ->
                    val joinInfo = JoinResponse(
                        success = true,
                        sessionId = session.sessionId,
                        files = session.files,
                        currentFileIndex = session.currentFileIndex,
                        currentPositionMs = session.currentPositionMs,
                        isPlaying = session.isPlaying
                    )
                    send(Frame.Text(gson.toJson(mapOf("type" to "session", "data" to joinInfo))))
                }
                
                // Listen for messages from client (clock sync, etc.)
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            handleWebSocketMessage(clientId, text)
                        }
                        is Frame.Close -> {
                            Timber.d("WebSocket client $clientId sent close frame")
                            break
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Timber.w("WebSocket error for client $clientId: ${e.message}")
            } finally {
                webSocketSessions.remove(clientId)
                _connectedClients.value = _connectedClients.value - clientId
                Timber.i("WebSocket client disconnected: $clientId")
            }
        }
        
        // WebSocket file transfer - faster than HTTP with progress streaming
        webSocket("/file/ws/{hash}") {
            val hash = call.parameters["hash"]
            if (hash == null) {
                send(Frame.Text(gson.toJson(mapOf("type" to "error", "message" to "Missing hash"))))
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing hash"))
                return@webSocket
            }
            
            val uri = fileUriMap[hash]
            if (uri == null) {
                Timber.w("WebSocket file transfer: File not found for hash: $hash")
                send(Frame.Text(gson.toJson(mapOf("type" to "error", "message" to "File not found"))))
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "File not found"))
                return@webSocket
            }
            
            try {
                Timber.i("WebSocket file transfer started: hash=$hash")
                val appContext = this@SyncedPlaybackServer.context
                val inputStream = appContext.contentResolver.openInputStream(uri)
                
                if (inputStream == null) {
                    send(Frame.Text(gson.toJson(mapOf("type" to "error", "message" to "Cannot read file"))))
                    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Cannot read file"))
                    return@webSocket
                }
                
                val fileInfo = sessionInfo?.files?.find { it.hash == hash }
                val totalSize = fileInfo?.sizeBytes ?: inputStream.available().toLong()
                val mimeType = fileInfo?.mimeType ?: "application/octet-stream"
                
                // Send file metadata first
                send(Frame.Text(gson.toJson(mapOf(
                    "type" to "file_start",
                    "hash" to hash,
                    "size" to totalSize,
                    "mimeType" to mimeType
                ))))
                
                // Wait for client acknowledgment to support resume
                var startOffset = 0L
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val msg = gson.fromJson(frame.readText(), Map::class.java)
                        if (msg["type"] == "resume") {
                            startOffset = (msg["offset"] as? Number)?.toLong() ?: 0L
                            Timber.d("Client requesting resume from offset: $startOffset")
                        }
                        break
                    }
                }
                
                // Skip to offset if resuming
                if (startOffset > 0) {
                    inputStream.skip(startOffset)
                }
                
                // Stream file in chunks as binary frames
                val chunkSize = 64 * 1024 // 64KB chunks
                val buffer = ByteArray(chunkSize)
                var bytesSent = startOffset
                var bytesRead: Int
                
                inputStream.use { stream ->
                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        // Send binary data
                        val chunk = if (bytesRead == chunkSize) buffer else buffer.copyOf(bytesRead)
                        send(Frame.Binary(true, chunk))
                        
                        bytesSent += bytesRead
                        
                        // Send progress update every 256KB
                        if (bytesSent % (256 * 1024) < chunkSize) {
                            send(Frame.Text(gson.toJson(mapOf(
                                "type" to "progress",
                                "sent" to bytesSent,
                                "total" to totalSize
                            ))))
                        }
                    }
                }
                
                // Send completion
                send(Frame.Text(gson.toJson(mapOf(
                    "type" to "file_complete",
                    "hash" to hash,
                    "size" to bytesSent
                ))))
                
                Timber.i("WebSocket file transfer complete: hash=$hash, bytes=$bytesSent")
                
            } catch (e: Exception) {
                Timber.e("WebSocket file transfer error", e)
                send(Frame.Text(gson.toJson(mapOf("type" to "error", "message" to e.message))))
            }
        }
    }
    
    /**
     * Handle incoming WebSocket messages from clients
     */
    private suspend fun WebSocketSession.handleWebSocketMessage(clientId: String, message: String) {
        try {
            val request = gson.fromJson(message, Map::class.java)
            when (request["type"]) {
                "clock_sync" -> {
                    // Respond with clock sync
                    val t1 = (request["t1"] as? Number)?.toLong() ?: 0L
                    val t2 = System.currentTimeMillis()
                    val t3 = System.currentTimeMillis()
                    send(Frame.Text(gson.toJson(mapOf(
                        "type" to "clock_sync_response",
                        "t1" to t1,
                        "t2" to t2,
                        "t3" to t3
                    ))))
                }
                "ping" -> {
                    send(Frame.Text(gson.toJson(mapOf("type" to "pong"))))
                }
                else -> {
                    Timber.d("Unknown WebSocket message type from $clientId: ${request["type"]}")
                }
            }
        } catch (e: Exception) {
            Timber.w("Failed to parse WebSocket message from $clientId: ${e.message}")
        }
    }
}

/**
 * Session info sent to clients
 */
data class SessionInfo(
    val sessionId: String,
    val hostName: String,
    val files: List<SyncFileInfo>,
    val currentFileIndex: Int,
    val currentPositionMs: Long,
    val isPlaying: Boolean
)

/**
 * File info for sync
 */
data class SyncFileInfo(
    val name: String,
    val hash: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val mimeType: String
)

/**
 * Sync command sent over HTTP
 */
data class SyncCommand(
    val type: String,
    val timestamp: Long,
    val positionMs: Long = 0L,
    val fileIndex: Int = 0,
    val resumePlayback: Boolean = false,
    val autoPlay: Boolean = false,
    val volume: Float = 1.0f
)

/**
 * Clock sync response
 */
data class ClockSyncResponse(
    val t1: Long,
    val t2: Long,
    val t3: Long
)

/**
 * Commands response
 */
data class CommandsResponse(
    val commands: List<SyncCommand>
)

/**
 * Join session response
 */
data class JoinResponse(
    val success: Boolean,
    val sessionId: String,
    val files: List<SyncFileInfo>,
    val currentFileIndex: Int,
    val currentPositionMs: Long,
    val isPlaying: Boolean
)
