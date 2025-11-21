package io.github.gauravyad69.speakershare.network

import android.util.Log
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
    private val hostApiHandler: HostApiHandler
) {
    companion object {
        private const val TAG = "HttpApiServer"
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
            Log.w(TAG, "Server already running")
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
            
            Log.i(TAG, "HTTP server started on port $port")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
            
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
                Log.i(TAG, "HTTP server stopped")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping HTTP server", e)
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
            }
            
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
                    Log.e(TAG, "Error handling discovery request", e)
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
                    val response = hostApiHandler.connectClient(request)
                    
                    val statusCode = when (response.status) {
                        "success" -> HttpStatusCode.OK
                        "rejected" -> HttpStatusCode.Forbidden
                        "error" -> HttpStatusCode.BadRequest
                        else -> HttpStatusCode.InternalServerError
                    }
                    
                    call.respond(statusCode, response)
                    
                    scope.launch {
                        _serverEvents.emit(HttpServerEvent.ClientConnectAttempt(request.clientId, response.status))
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling client connect", e)
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
                    Log.e(TAG, "Error handling client disconnect", e)
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
                    Log.e(TAG, "Error handling client kick", e)
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
                    Log.e(TAG, "Error getting client list", e)
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
                    Log.e(TAG, "Error updating host settings", e)
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
                    Log.e(TAG, "Error getting session status", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Internal server error", "STATUS_ERROR")
                    )
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
}