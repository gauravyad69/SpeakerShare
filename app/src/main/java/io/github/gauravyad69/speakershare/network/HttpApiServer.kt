package io.github.gauravyad69.speakershare.network

import timber.log.Timber
import io.github.gauravyad69.speakershare.data.model.*
import io.github.gauravyad69.speakershare.network.api.*
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP API Server using Ktor
 * Provides REST endpoints for client discovery and connection management
 */
@Singleton
class HttpApiServer @Inject constructor(
    private val hostApiHandler: HostApiHandler,
    private val screenCaptureService: io.github.gauravyad69.speakershare.screen.ScreenCaptureService
) {
    companion object {
        private const val DEFAULT_PORT = 8080
        private const val API_BASE_PATH = "/api/v1"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: NettyApplicationEngine? = null
    private var isRunning = false
    
    // Events
    private val _serverEvents = MutableSharedFlow<HttpServerEvent>()
    val serverEvents: SharedFlow<HttpServerEvent> = _serverEvents.asSharedFlow()
    
    /**
     * Start HTTP server
     */
    fun startServer(port: Int = DEFAULT_PORT): Boolean {
        if (isRunning) {
            Timber.w("Server already running")
            return true
        }
        
        return try {
            server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
                configureServer()
            }
            
            server?.start(wait = false)
            isRunning = true
            
            scope.launch {
                _serverEvents.emit(HttpServerEvent.ServerStarted(port))
            }
            
            Timber.i("HTTP server started on port $port")
            true
            
        } catch (e: Exception) {
            Timber.e("Failed to start HTTP server", e)
            
            scope.launch {
                _serverEvents.emit(HttpServerEvent.ServerError("Failed to start server: ${e.message}"))
            }
            false
        }
    }
    
    /**
     * Stop HTTP server
     */
    fun stopServer() {
        if (!isRunning) {
            return
        }
        
        scope.launch {
            try {
                server?.stop(1000, 2000)
                server = null
                isRunning = false
                
                _serverEvents.emit(HttpServerEvent.ServerStopped)
                Timber.i("HTTP server stopped")
                
            } catch (e: Exception) {
                Timber.e("Error stopping HTTP server", e)
            }
        }
    }
    
    /**
     * Configure Ktor server
     */
    private fun Application.configureServer() {
        // Install content negotiation for JSON
        install(ContentNegotiation) {
            gson {
                setPrettyPrinting()
                disableHtmlEscaping()
            }
        }
        
        // Install CORS for local network access
        install(CORS) {
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Patch)
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.AccessControlAllowOrigin)
            allowHeader(HttpHeaders.AccessControlAllowHeaders)
            allowCredentials = false
            anyHost() // Allow all hosts for local network
        }
        
        // Configure routing
        routing {
            route(API_BASE_PATH) {
                configureDiscoveryRoutes()
                configureClientRoutes()
                configureHostRoutes()
                configureSessionRoutes()
                configureSyncRoutes()
            }
            
            // Screen streaming routes (outside API_BASE_PATH for simpler URLs)
            configureScreenRoutes()
            
            // Health check endpoint
            get("/health") {
                call.respond(HttpStatusCode.OK, mapOf("status" to "healthy"))
            }
        }
    }
    
    /**
     * Configure discovery endpoints
     */
    private fun Route.configureDiscoveryRoutes() {
        route("/discovery") {
            get("/info") {
                try {
                    val discoveryInfo = hostApiHandler.getDiscoveryInfo()
                    call.respond(HttpStatusCode.OK, discoveryInfo)
                    
                    scope.launch {
                        _serverEvents.emit(HttpServerEvent.DiscoveryRequested)
                    }
                    
                } catch (e: Exception) {
                    Timber.e("Error handling discovery request", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Internal server error", "DISCOVERY_ERROR")
                    )
                }
            }
        }
    }
    
    /**
     * Configure client management endpoints
     */
    private fun Route.configureClientRoutes() {
        route("/clients") {
            // Connect client
            post("/connect") {
                try {
                    val request = call.receive<ClientConnectRequest>()
                    
                    // Extract client IP from request
                    val clientIp = call.request.local.remoteHost
                    Timber.d("Client connect request from IP: $clientIp")
                    
                    val response = hostApiHandler.connectClientWithIp(request, clientIp)
                    
                    val statusCode = when (response.status) {
                        "success", "ACCEPTED" -> HttpStatusCode.OK
                        "rejected", "REJECTED" -> HttpStatusCode.Forbidden
                        "error" -> HttpStatusCode.BadRequest
                        else -> HttpStatusCode.OK // Default to OK for ACCEPTED status
                    }
                    
                    call.respond(statusCode, response)
                    
                    scope.launch {
                        _serverEvents.emit(HttpServerEvent.ClientConnectAttempt(request.clientId, response.status))
                    }
                    
                } catch (e: Exception) {
                    Timber.e("Error handling client connect", e)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid request", "CONNECT_ERROR")
                    )
                }
            }
            
            // Disconnect client
            post("/{clientId}/disconnect") {
                try {
                    val clientId = call.parameters["clientId"]
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Missing client ID", "MISSING_CLIENT_ID")
                        )
                    
                    val response = hostApiHandler.disconnectClient(clientId)
                    
                    val statusCode = when (response.status) {
                        "success" -> HttpStatusCode.OK
                        "not_found" -> HttpStatusCode.NotFound
                        else -> HttpStatusCode.InternalServerError
                    }
                    
                    call.respond(statusCode, response)
                    
                    scope.launch {
                        _serverEvents.emit(HttpServerEvent.ClientDisconnected(clientId))
                    }
                    
                } catch (e: Exception) {
                    Timber.e("Error handling client disconnect", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Internal server error", "DISCONNECT_ERROR")
                    )
                }
            }
            
            // Kick client
            post("/{clientId}/kick") {
                try {
                    val clientId = call.parameters["clientId"]
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Missing client ID", "MISSING_CLIENT_ID")
                        )
                    
                    val response = hostApiHandler.kickClient(clientId)
                    
                    val statusCode = when (response.status) {
                        "success" -> HttpStatusCode.OK
                        "not_found" -> HttpStatusCode.NotFound
                        else -> HttpStatusCode.InternalServerError
                    }
                    
                    call.respond(statusCode, response)
                    
                    scope.launch {
                        _serverEvents.emit(HttpServerEvent.ClientKicked(clientId))
                    }
                    
                } catch (e: Exception) {
                    Timber.e("Error handling client kick", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Internal server error", "KICK_ERROR")
                    )
                }
            }
            
            // Get client list
            get {
                try {
                    val clientList = hostApiHandler.getClientList()
                    call.respond(HttpStatusCode.OK, clientList)
                    
                } catch (e: Exception) {
                    Timber.e("Error getting client list", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Internal server error", "CLIENT_LIST_ERROR")
                    )
                }
            }
        }
    }
    
    /**
     * Configure host management endpoints
     */
    private fun Route.configureHostRoutes() {
        route("/host") {
            // Update host settings
            put("/settings") {
                try {
                    val settings = call.receive<HostSettingsRequest>()
                    val response = hostApiHandler.updateHostSettings(settings)
                    
                    val statusCode = when (response.status) {
                        "success" -> HttpStatusCode.OK
                        "invalid" -> HttpStatusCode.BadRequest
                        else -> HttpStatusCode.InternalServerError
                    }
                    
                    call.respond(statusCode, response)
                    
                    scope.launch {
                        _serverEvents.emit(HttpServerEvent.HostSettingsUpdated)
                    }
                    
                } catch (e: Exception) {
                    Timber.e("Error updating host settings", e)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid settings", "SETTINGS_ERROR")
                    )
                }
            }
        }
    }
    
    /**
     * Configure session endpoints
     */
    private fun Route.configureSessionRoutes() {
        route("/session") {
            // Get session status
            get("/status") {
                try {
                    val status = hostApiHandler.getSessionStatus()
                    call.respond(HttpStatusCode.OK, status)
                    
                } catch (e: Exception) {
                    Timber.e("Error getting session status", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Internal server error", "STATUS_ERROR")
                    )
                }
            }
        }
    }
    
    /**
     * Configure synchronized playback endpoints (clock sync, file transfer, commands)
     */
    private fun Route.configureSyncRoutes() {
        route("/sync") {
            // Clock synchronization endpoint
            get("/clock") {
                try {
                    val t1 = call.request.queryParameters["t1"]?.toLongOrNull() ?: 0L
                    val t2 = System.currentTimeMillis()
                    val t3 = System.currentTimeMillis()
                    
                    call.respond(HttpStatusCode.OK, mapOf(
                        "t1" to t1,
                        "t2" to t2,
                        "t3" to t3
                    ))
                } catch (e: Exception) {
                    Timber.e("Clock sync error", e)
                    call.respond(HttpStatusCode.InternalServerError, 
                        ErrorResponse("Clock sync failed", "CLOCK_SYNC_ERROR"))
                }
            }
            
            // Get current synced playback session info
            get("/session") {
                try {
                    // TODO: Get from SyncedPlaybackManager
                    call.respond(HttpStatusCode.OK, mapOf(
                        "sessionId" to "",
                        "isActive" to false,
                        "files" to emptyList<String>()
                    ))
                } catch (e: Exception) {
                    Timber.e("Session info error", e)
                    call.respond(HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to get session info", "SESSION_ERROR"))
                }
            }
            
            // Playback command endpoint (WebSocket would be better, but HTTP works for now)
            post("/command") {
                try {
                    val command = call.receive<SyncPlaybackCommandRequest>()
                    
                    // Broadcast to all clients
                    scope.launch {
                        _serverEvents.emit(HttpServerEvent.SyncCommandReceived(command))
                    }
                    
                    call.respond(HttpStatusCode.OK, mapOf("status" to "sent"))
                } catch (e: Exception) {
                    Timber.e("Command error", e)
                    call.respond(HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid command", "COMMAND_ERROR"))
                }
            }
            
            // Get pending commands (polling for clients)
            get("/commands") {
                try {
                    // TODO: Return queued commands for client
                    call.respond(HttpStatusCode.OK, emptyList<Any>())
                } catch (e: Exception) {
                    Timber.e("Commands error", e)
                    call.respond(HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to get commands", "COMMANDS_ERROR"))
                }
            }
        }
        
        // File serving for synced playback
        route("/files") {
            // Get file list for session
            get {
                try {
                    // TODO: Get from SyncedPlaybackManager
                    call.respond(HttpStatusCode.OK, emptyList<Any>())
                } catch (e: Exception) {
                    Timber.e("File list error", e)
                    call.respond(HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to get file list", "FILE_LIST_ERROR"))
                }
            }
            
            // Download file by hash (for clients to cache)
            get("/{hash}") {
                try {
                    val hash = call.parameters["hash"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest,
                            ErrorResponse("Missing file hash", "MISSING_HASH"))
                    
                    // TODO: Serve file from SyncedFileTransfer
                    call.respond(HttpStatusCode.NotFound,
                        ErrorResponse("File not found", "FILE_NOT_FOUND"))
                } catch (e: Exception) {
                    Timber.e("File download error", e)
                    call.respond(HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to serve file", "FILE_ERROR"))
                }
            }
        }
    }
    
    /**
     * Configure screen streaming endpoints
     */
    private fun Routing.configureScreenRoutes() {
        route("/api/screen") {
            // Check if screen sharing is available
            get("/status") {
                val isCapturing = screenCaptureService.captureState.value.isCapturing
                call.respond(HttpStatusCode.OK, mapOf(
                    "available" to isCapturing,
                    "width" to screenCaptureService.captureState.value.width,
                    "height" to screenCaptureService.captureState.value.height,
                    "fps" to screenCaptureService.captureState.value.fps
                ))
            }
            
            // Get single frame (polling approach - simpler and more reliable)
            get("/frame") {
                if (!screenCaptureService.captureState.value.isCapturing) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf(
                        "error" to "Screen sharing not active"
                    ))
                    return@get
                }
                
                // Get the latest frame
                val frame = screenCaptureService.getLatestFrame()
                if (frame != null) {
                    call.respondBytes(frame, ContentType.Image.JPEG)
                } else {
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            
            // Stream screen frames (SSE-style for continuous streaming)
            get("/stream") {
                if (!screenCaptureService.captureState.value.isCapturing) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf(
                        "error" to "Screen sharing not active"
                    ))
                    return@get
                }
                
                call.respondOutputStream(ContentType.Application.OctetStream) {
                    try {
                        // Collect and send frames
                        screenCaptureService.screenFrameFlow.collect { frameBytes ->
                            try {
                                // Send frame length (4 bytes, big-endian)
                                val lengthBytes = ByteArray(4)
                                lengthBytes[0] = ((frameBytes.size shr 24) and 0xFF).toByte()
                                lengthBytes[1] = ((frameBytes.size shr 16) and 0xFF).toByte()
                                lengthBytes[2] = ((frameBytes.size shr 8) and 0xFF).toByte()
                                lengthBytes[3] = (frameBytes.size and 0xFF).toByte()
                                
                                write(lengthBytes)
                                write(frameBytes)
                                flush()
                            } catch (e: Exception) {
                                Timber.d("Client disconnected from screen stream")
                                throw e
                            }
                        }
                    } catch (e: Exception) {
                        Timber.d("Screen stream ended: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * Check if server is running
     */
    fun isServerRunning(): Boolean = isRunning
    
    /**
     * Get server port
     */
    fun getServerPort(): Int? = server?.environment?.connectors?.firstOrNull()?.port
    
    /**
     * Get server info
     */
    fun getServerInfo(): HttpServerInfo {
        return HttpServerInfo(
            isRunning = isRunning,
            port = getServerPort() ?: 0,
            endpoints = listOf(
                "$API_BASE_PATH/discovery/info",
                "$API_BASE_PATH/clients/connect",
                "$API_BASE_PATH/clients/{clientId}/disconnect",
                "$API_BASE_PATH/clients/{clientId}/kick",
                "$API_BASE_PATH/clients",
                "$API_BASE_PATH/host/settings",
                "$API_BASE_PATH/session/status"
            )
        )
    }
}

/**
 * Error response data class
 */
data class ErrorResponse(
    val message: String,
    val errorCode: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * HTTP Server info data class
 */
data class HttpServerInfo(
    val isRunning: Boolean,
    val port: Int,
    val endpoints: List<String>
)

/**
 * HTTP Server events
 */
sealed class HttpServerEvent {
    data class ServerStarted(val port: Int) : HttpServerEvent()
    object ServerStopped : HttpServerEvent()
    object DiscoveryRequested : HttpServerEvent()
    data class ClientConnectAttempt(val clientId: String, val status: String) : HttpServerEvent()
    data class ClientDisconnected(val clientId: String) : HttpServerEvent()
    data class ClientKicked(val clientId: String) : HttpServerEvent()
    object HostSettingsUpdated : HttpServerEvent()
    data class ServerError(val message: String) : HttpServerEvent()
    data class SyncCommandReceived(val command: SyncPlaybackCommandRequest) : HttpServerEvent()
}

/**
 * Sync playback command request
 */
data class SyncPlaybackCommandRequest(
    val type: String, // "play", "pause", "seek", "sync", "switch"
    val timestamp: Long,
    val positionMs: Long = 0L,
    val fileIndex: Int = 0,
    val resumePlayback: Boolean = false
)