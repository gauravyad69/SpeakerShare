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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

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
    
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    
    // Queue of commands to broadcast to clients (per-client queues to avoid missed commands)
    private val clientCommandQueues = mutableMapOf<String, MutableList<SyncCommand>>()
    private val commandQueueLock = Any()
    
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
                
                routing {
                    configureSyncRoutes()
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
        }
        
        // Add command to each connected client's queue
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
        
        Timber.d("Broadcast command: ${syncCommand.type} to ${_connectedClients.value.size} clients")
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
    val autoPlay: Boolean = false
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
