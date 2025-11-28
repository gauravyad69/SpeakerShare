package io.github.gauravyad69.speakershare.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.gauravyad69.speakershare.MainActivity
import io.github.gauravyad69.speakershare.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/**
 * Foreground service for managing audio playback in client mode.
 * Keeps the network connection alive when app is in background.
 */
@AndroidEntryPoint
class ClientForegroundService : Service() {

    companion object {
        private const val TAG = "ClientForegroundService"
        private const val NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "audio_playback_channel"
        const val ACTION_START_PLAYBACK = "START_PLAYBACK"
        const val ACTION_STOP_PLAYBACK = "STOP_PLAYBACK"
        
        private const val EXTRA_HOST_NAME = "host_name"
        private const val EXTRA_HOST_IP = "host_ip"

        fun startPlayback(context: Context, hostName: String, hostIp: String): Intent {
            return Intent(context, ClientForegroundService::class.java).apply {
                action = ACTION_START_PLAYBACK
                putExtra(EXTRA_HOST_NAME, hostName)
                putExtra(EXTRA_HOST_IP, hostIp)
            }
        }

        fun stopPlayback(context: Context): Intent {
            return Intent(context, ClientForegroundService::class.java).apply {
                action = ACTION_STOP_PLAYBACK
            }
        }
        
        // Static flag to check if service is running
        @Volatile
        var isRunning = false
            private set
    }

    @Inject
    lateinit var clientManager: ClientManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val binder = ClientServiceBinder()
    private var wakeLock: PowerManager.WakeLock? = null

    // Service state
    private val _serviceState = MutableStateFlow(ServiceState.STOPPED)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private var hostName: String = "Unknown Host"
    private var hostIp: String = ""
    private var startTime: Long = 0

    enum class ServiceState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    inner class ClientServiceBinder : Binder() {
        fun getService(): ClientForegroundService = this@ClientForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        intent?.action?.let { action ->
            serviceScope.launch {
                handleAction(action, intent)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        isRunning = false
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun handleAction(action: String, intent: Intent) {
        when (action) {
            ACTION_START_PLAYBACK -> {
                hostName = intent.getStringExtra(EXTRA_HOST_NAME) ?: "Unknown Host"
                hostIp = intent.getStringExtra(EXTRA_HOST_IP) ?: ""
                startPlaybackService()
            }
            ACTION_STOP_PLAYBACK -> stopPlaybackService()
        }
    }

    private suspend fun startPlaybackService() {
        if (_serviceState.value != ServiceState.STOPPED) {
            Log.d(TAG, "Service already running, updating notification")
            updateNotification()
            return
        }

        try {
            _serviceState.value = ServiceState.STARTING
            startTime = System.currentTimeMillis()

            // Acquire wake lock to keep network alive
            acquireWakeLock()

            // Start foreground service with notification
            val notification = createPlaybackNotification()
            startForeground(NOTIFICATION_ID, notification)

            _serviceState.value = ServiceState.RUNNING
            Log.d(TAG, "Client foreground service started for host: $hostName")

            // Monitor connection state
            serviceScope.launch {
                clientManager.isConnected.collect { connected ->
                    if (!connected && _serviceState.value == ServiceState.RUNNING) {
                        Log.d(TAG, "Connection lost, stopping service")
                        stopPlaybackService()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback service", e)
            _serviceState.value = ServiceState.STOPPED
            stopSelf()
        }
    }

    private suspend fun stopPlaybackService() {
        if (_serviceState.value == ServiceState.STOPPED || _serviceState.value == ServiceState.STOPPING) {
            return
        }

        _serviceState.value = ServiceState.STOPPING
        Log.d(TAG, "Stopping client foreground service")

        releaseWakeLock()
        
        _serviceState.value = ServiceState.STOPPED
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SpeakerShare::ClientPlayback"
            ).apply {
                acquire(10 * 60 * 60 * 1000L) // 10 hours max
            }
            Log.d(TAG, "Wake lock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when audio is playing from a host"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createPlaybackNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 0, stopPlayback(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Playing Audio")
            .setContentText("Connected to $hostName")
            .setSmallIcon(R.drawable.ic_headphones)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Disconnect", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val notification = createPlaybackNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
