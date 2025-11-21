package io.github.gauravyad69.speakershare.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.util.Log
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.gauravyad69.speakershare.R
import io.github.gauravyad69.speakershare.data.model.*
import io.github.gauravyad69.speakershare.network.HttpApiServer
import io.github.gauravyad69.speakershare.network.UdpAudioServer
import io.github.gauravyad69.speakershare.network.WebRTCManager
import io.github.gauravyad69.speakershare.audio.AudioCaptureService
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/**
 * Foreground service for managing audio broadcasting in the background.
 * Handles service lifecycle, notifications, and coordinates between different components.
 */
@AndroidEntryPoint
class AudioForegroundService : Service() {

    companion object {
        private const val TAG = "AudioForegroundService"
        private const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "audio_broadcast_channel"
        const val ACTION_START_BROADCAST = "START_BROADCAST"
        const val ACTION_STOP_BROADCAST = "STOP_BROADCAST"
        const val ACTION_PAUSE_BROADCAST = "PAUSE_BROADCAST"
        const val ACTION_RESUME_BROADCAST = "RESUME_BROADCAST"
        const val ACTION_TOGGLE_MUTE = "TOGGLE_MUTE"
        
        private const val EXTRA_SESSION_NAME = "session_name"
        private const val EXTRA_AUDIO_SOURCE = "audio_source"
        private const val EXTRA_MAX_CLIENTS = "max_clients"
        private const val EXTRA_REQUIRE_PASSWORD = "require_password"
        private const val EXTRA_PASSWORD = "password"

        fun startBroadcast(
            context: Context,
            sessionName: String,
            audioSource: String = "MICROPHONE",
            maxClients: Int = 0,
            requirePassword: Boolean = false,
            password: String? = null
        ): Intent {
            return Intent(context, AudioForegroundService::class.java).apply {
                action = ACTION_START_BROADCAST
                putExtra(EXTRA_SESSION_NAME, sessionName)
                putExtra(EXTRA_AUDIO_SOURCE, audioSource)
                putExtra(EXTRA_MAX_CLIENTS, maxClients)
                putExtra(EXTRA_REQUIRE_PASSWORD, requirePassword)
                putExtra(EXTRA_PASSWORD, password)
            }
        }

        fun stopBroadcast(context: Context): Intent {
            return Intent(context, AudioForegroundService::class.java).apply {
                action = ACTION_STOP_BROADCAST
            }
        }

        fun pauseBroadcast(context: Context): Intent {
            return Intent(context, AudioForegroundService::class.java).apply {
                action = ACTION_PAUSE_BROADCAST
            }
        }

        fun resumeBroadcast(context: Context): Intent {
            return Intent(context, AudioForegroundService::class.java).apply {
                action = ACTION_RESUME_BROADCAST
            }
        }

        fun toggleMute(context: Context): Intent {
            return Intent(context, AudioForegroundService::class.java).apply {
                action = ACTION_TOGGLE_MUTE
            }
        }
    }

    @Inject
    lateinit var httpApiServer: HttpApiServer

    @Inject
    lateinit var hostApiHandler: io.github.gauravyad69.speakershare.network.api.HostApiHandler

    @Inject
    lateinit var udpAudioServer: UdpAudioServer

    @Inject
    lateinit var webRTCManager: WebRTCManager

    @Inject
    lateinit var audioCaptureService: AudioCaptureService

    @Inject
    lateinit var notificationManager: AudioNotificationManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val binder = AudioServiceBinder()

    // Service state
    private val _serviceState = MutableStateFlow(ServiceState.STOPPED)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _currentSession = MutableStateFlow<HostSession?>(null)
    val currentSession: StateFlow<HostSession?> = _currentSession.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<String>>(emptyList())
    val connectedClients: StateFlow<List<String>> = _connectedClients.asStateFlow()

    private val _isAudioMuted = MutableStateFlow(false)
    val isAudioMuted: StateFlow<Boolean> = _isAudioMuted.asStateFlow()

    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error.asSharedFlow()

    private var startTime: Long = 0
    private var currentNotification: Notification? = null

    enum class ServiceState {
        STOPPED,
        STARTING,
        RUNNING,
        PAUSED,
        STOPPING,
        ERROR
    }

    inner class AudioServiceBinder : Binder() {
        fun getService(): AudioForegroundService = this@AudioForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // TODO: Add error monitoring when services expose error flows
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            serviceScope.launch {
                handleAction(action, intent)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceScope.launch {
            stopBroadcasting()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun handleAction(action: String, intent: Intent) {
        when (action) {
            ACTION_START_BROADCAST -> {
                val sessionName = intent.getStringExtra(EXTRA_SESSION_NAME) ?: "SpeakerShare Session"
                val audioSource = intent.getStringExtra(EXTRA_AUDIO_SOURCE) ?: "MICROPHONE"
                val maxClients = intent.getIntExtra(EXTRA_MAX_CLIENTS, 0)
                val requirePassword = intent.getBooleanExtra(EXTRA_REQUIRE_PASSWORD, false)
                val password = intent.getStringExtra(EXTRA_PASSWORD)

                startBroadcasting(sessionName, audioSource, maxClients, requirePassword, password)
            }
            ACTION_STOP_BROADCAST -> stopBroadcasting()
            ACTION_PAUSE_BROADCAST -> pauseBroadcasting()
            ACTION_RESUME_BROADCAST -> resumeBroadcasting()
            ACTION_TOGGLE_MUTE -> toggleMute()
        }
    }

    private suspend fun startBroadcasting(
        sessionName: String,
        audioSource: String,
        maxClients: Int,
        requirePassword: Boolean,
        password: String?
    ) {
        if (_serviceState.value != ServiceState.STOPPED) {
            return
        }

        try {
            _serviceState.value = ServiceState.STARTING
            startTime = System.currentTimeMillis()

            // Check permissions
            if (!hasRequiredPermissions()) {
                throw SecurityException("Required permissions not granted")
            }

            // Create session
            val session = HostSession(
                sessionId = java.util.UUID.randomUUID().toString(),
                sessionName = sessionName,
                hostName = android.os.Build.MODEL,
                audioSource = AudioSource.valueOf(audioSource),
                quality = AudioQuality(), // Use default quality settings
                isActive = true,
                startTime = startTime,
                connectedClients = emptyList(),
                networkInfo = NetworkInfo(
                    localIpAddress = getLocalIpAddress(),
                    port = 8080,
                    networkInterface = "wlan0",
                    isHotspot = false,
                    discoveryMethod = DiscoveryMethod.MDNS,
                    serviceName = "speakershare-${sessionName.hashCode()}"
                ),
                maxClients = maxClients,
                requiresPassword = requirePassword,
                password = password
            )
            _currentSession.value = session

            // Start foreground service with notification
            val notification = notificationManager.createBroadcastingNotification(
                session = session,
                connectedClients = 0,
                isAudioMuted = _isAudioMuted.value,
                startTime = startTime
            )
            currentNotification = notification
            startForeground(NOTIFICATION_ID, notification)

            // Convert string to AudioSource enum
            val audioSourceEnum = when (audioSource) {
                "MICROPHONE" -> io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE
                "SYSTEM_AUDIO", "SYSTEM" -> io.github.gauravyad69.speakershare.data.model.AudioSource.SYSTEM_AUDIO
                else -> io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE
            }
            
            // Start servers
            startHttpServer(session)
            startUdpServer(session)
            startWebRTCManager(session)

            // Start audio capture
            audioCaptureService.startCapture(audioSourceEnum)

            _serviceState.value = ServiceState.RUNNING
            
        } catch (e: Exception) {
            _serviceState.value = ServiceState.ERROR
            _error.emit("Failed to start broadcast: ${e.message}")
            stopSelf()
        }
    }

    private suspend fun stopBroadcasting() {
        if (_serviceState.value == ServiceState.STOPPED || _serviceState.value == ServiceState.STOPPING) {
            return
        }

        try {
            _serviceState.value = ServiceState.STOPPING

            // Stop audio capture first
            audioCaptureService.stopCapture()

            // Stop servers
            httpApiServer.stopServer()
            udpAudioServer.stopServer()
            webRTCManager.stopBroadcasting()

            // Clear state
            _currentSession.value = null
            _connectedClients.value = emptyList()
            _isAudioMuted.value = false

            // Remove from foreground
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

            _serviceState.value = ServiceState.STOPPED

        } catch (e: Exception) {
            _error.emit("Error stopping broadcast: ${e.message}")
        } finally {
            stopSelf()
        }
    }

    private suspend fun pauseBroadcasting() {
        if (_serviceState.value != ServiceState.RUNNING) {
            return
        }

        try {
            // Pause audio capture
            audioCaptureService.stopCapture()
            
            // Keep servers running but mark session as paused
            _currentSession.value = _currentSession.value?.copy(isActive = false)
            
            _serviceState.value = ServiceState.PAUSED
            updateNotificationIfRunning()
            
        } catch (e: Exception) {
            _error.emit("Error pausing broadcast: ${e.message}")
        }
    }

    private suspend fun resumeBroadcasting() {
        if (_serviceState.value != ServiceState.PAUSED) {
            return
        }

        try {
            // Resume audio capture with the current session's audio source
            val currentSession = _currentSession.value
            if (currentSession != null) {
                audioCaptureService.startCapture(currentSession.audioSource)
            }
            
            // Mark session as active
            _currentSession.value = _currentSession.value?.copy(isActive = true)
            
            _serviceState.value = ServiceState.RUNNING
            updateNotificationIfRunning()
            
        } catch (e: Exception) {
            _error.emit("Error resuming broadcast: ${e.message}")
        }
    }

    private suspend fun toggleMute() {
        if (_serviceState.value == ServiceState.RUNNING) {
            val wasMuted = _isAudioMuted.value
            _isAudioMuted.value = !wasMuted
            
            // TODO: Implement actual mute/unmute functionality
            // This could be done by pausing/resuming audio capture or setting volume to 0
            // For now, we just track the mute state
            
            updateNotificationIfRunning()
        }
    }

    private suspend fun startHttpServer(session: HostSession) {
        httpApiServer.startServer(8080)
    }

    private suspend fun startUdpServer(session: HostSession) {
        udpAudioServer.startServer(
            sessionId = session.sessionId,
            hostName = session.hostName,
            audioPort = 9090,
            discoveryPort = 9089
        )
    }

    private suspend fun startWebRTCManager(session: HostSession) {
        webRTCManager.startBroadcasting()
    }

    private fun hasRequiredPermissions(): Boolean {
        val requiredPermissions = arrayOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_MULTICAST_STATE
        )
        
        return requiredPermissions.all { permission ->
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun handleError(error: String) {
        serviceScope.launch {
            _serviceState.value = ServiceState.ERROR
            
            // Update notification to show error
            currentNotification = notificationManager.createErrorNotification(error)
            currentNotification?.let { notification ->
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
            
            // Auto-stop after showing error
            delay(5000)
            stopBroadcasting()
        }
    }

    private fun updateNotificationIfRunning() {
        if (_serviceState.value in listOf(ServiceState.RUNNING, ServiceState.PAUSED)) {
            _currentSession.value?.let { session ->
                currentNotification = notificationManager.createBroadcastingNotification(
                    session = session,
                    connectedClients = _connectedClients.value.size,
                    isAudioMuted = _isAudioMuted.value,
                    startTime = startTime,
                    isPaused = _serviceState.value == ServiceState.PAUSED
                )
                currentNotification?.let { notification ->
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Broadcasting",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for audio broadcasting sessions"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    // Public API for bound clients
    fun getCurrentSessionInfo(): HostSession? = _currentSession.value

    fun getConnectedClientsCount(): Int = _connectedClients.value.size

    fun getServiceUptime(): Long = if (startTime > 0) System.currentTimeMillis() - startTime else 0

    suspend fun kickClient(clientId: String) {
        hostApiHandler.kickClient(clientId)
    }

    suspend fun muteClient(clientId: String) {
        // Implementation depends on the client protocol
        // This would send a mute command to the specific client
    }

    suspend fun unmuteClient(clientId: String) {
        // Implementation depends on the client protocol
        // This would send an unmute command to the specific client
    }

    suspend fun kickAllClients() {
        _connectedClients.value.forEach { clientId ->
            kickClient(clientId)
        }
    }

    fun getServiceState(): ServiceState = _serviceState.value

    suspend fun updateSessionSettings(
        sessionName: String? = null,
        maxClients: Int? = null,
        requirePassword: Boolean? = null,
        password: String? = null
    ) {
        _currentSession.value?.let { session ->
            val updatedSession = session.copy(
                sessionName = sessionName ?: session.sessionName,
                maxClients = maxClients ?: session.maxClients,
                requiresPassword = requirePassword ?: session.requiresPassword,
                password = password ?: session.password
            )
            _currentSession.value = updatedSession
            
            // Update notification if running
            updateNotificationIfRunning()
        }
    }

    /**
     * Get the local IP address for network information
     */
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addresses = intf.inetAddresses
                for (address in addresses) {
                    if (!address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        val hostAddress = address.hostAddress
                        if (hostAddress != null && hostAddress.indexOf(':') < 0) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP address", e)
        }
        return "192.168.1.100" // Fallback IP
    }
}