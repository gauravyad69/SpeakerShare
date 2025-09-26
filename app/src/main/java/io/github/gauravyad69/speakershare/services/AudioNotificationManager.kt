package io.github.gauravyad69.speakershare.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.gauravyad69.speakershare.R
import io.github.gauravyad69.speakershare.data.model.HostSession
import io.github.gauravyad69.speakershare.MainActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for creating and updating notifications for the audio broadcasting service
 */
@Singleton
class AudioNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val REQUEST_CODE_MAIN = 100
        private const val REQUEST_CODE_STOP = 101
        private const val REQUEST_CODE_PAUSE = 102
        private const val REQUEST_CODE_RESUME = 103
        private const val REQUEST_CODE_MUTE = 104
    }

    /**
     * Creates a notification for active broadcasting session
     */
    fun createBroadcastingNotification(
        session: HostSession,
        connectedClients: Int,
        isAudioMuted: Boolean,
        startTime: Long,
        isPaused: Boolean = false
    ): Notification {
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_MAIN,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = AudioForegroundService.stopBroadcast(context)
        val stopPendingIntent = PendingIntent.getService(
            context,
            REQUEST_CODE_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, AudioForegroundService.CHANNEL_ID)
            .setContentTitle(getNotificationTitle(session, isPaused))
            .setContentText(getNotificationText(connectedClients, session.maxClients, isAudioMuted, isPaused))
            .setSmallIcon(getNotificationIcon(isPaused, isAudioMuted))
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_stop,
                "Stop",
                stopPendingIntent
            )

        // Add pause/resume action
        if (isPaused) {
            val resumeIntent = AudioForegroundService.resumeBroadcast(context)
            val resumePendingIntent = PendingIntent.getService(
                context,
                REQUEST_CODE_RESUME,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_play_arrow, "Resume", resumePendingIntent)
        } else {
            val pauseIntent = AudioForegroundService.pauseBroadcast(context)
            val pausePendingIntent = PendingIntent.getService(
                context,
                REQUEST_CODE_PAUSE,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_pause, "Pause", pausePendingIntent)
        }

        // Add mute/unmute action
        val muteIntent = AudioForegroundService.toggleMute(context)
        val mutePendingIntent = PendingIntent.getService(
            context,
            REQUEST_CODE_MUTE,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val muteActionIcon = if (isAudioMuted) R.drawable.ic_volume_up else R.drawable.ic_volume_off
        val muteActionText = if (isAudioMuted) "Unmute" else "Mute"
        builder.addAction(muteActionIcon, muteActionText, mutePendingIntent)

        // Enhanced notification for Android 7.0+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            addExpandedContent(builder, session, connectedClients, startTime, isPaused, isAudioMuted)
        }

        return builder.build()
    }

    /**
     * Creates a notification for connection status (connecting, reconnecting, etc.)
     */
    fun createConnectionNotification(
        status: String,
        hostName: String? = null,
        progress: Int = -1
    ): Notification {
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_MAIN,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, AudioForegroundService.CHANNEL_ID)
            .setContentTitle("SpeakerShare")
            .setContentText(getConnectionStatusText(status, hostName))
            .setSmallIcon(R.drawable.ic_sync)
            .setContentIntent(mainPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Add progress bar for connection attempts
        if (progress >= 0) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true) // Indeterminate progress
        }

        return builder.build()
    }

    /**
     * Creates a notification for client connection status
     */
    fun createClientNotification(
        isConnected: Boolean,
        hostName: String,
        qualityInfo: String? = null,
        error: String? = null
    ): Notification {
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_MAIN,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isConnected) "Connected to $hostName" else "Disconnected"
        val text = when {
            error != null -> "Error: $error"
            qualityInfo != null && isConnected -> "Quality: $qualityInfo"
            isConnected -> "Audio streaming active"
            else -> "Connection lost"
        }

        val icon = when {
            error != null -> R.drawable.ic_error
            isConnected -> R.drawable.ic_cast_connected
            else -> R.drawable.ic_cast_disconnected
        }

        return NotificationCompat.Builder(context, AudioForegroundService.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(mainPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(!isConnected) // Auto-dismiss disconnection notifications
            .build()
    }

    /**
     * Creates an error notification
     */
    fun createErrorNotification(
        error: String,
        isRetrying: Boolean = false
    ): Notification {
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_MAIN,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isRetrying) "Connection Issue" else "Broadcast Error"
        val text = if (isRetrying) "Retrying... $error" else error

        return NotificationCompat.Builder(context, AudioForegroundService.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_error)
            .setContentIntent(mainPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setColor(context.getColor(android.R.color.holo_red_dark))
            .build()
    }

    /**
     * Creates a notification for discovery/scanning status
     */
    fun createDiscoveryNotification(
        isScanning: Boolean,
        hostsFound: Int = 0
    ): Notification {
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_MAIN,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isScanning) "Scanning for Hosts" else "Discovery Complete"
        val text = if (isScanning) {
            "Looking for available broadcasts..."
        } else {
            "$hostsFound host${if (hostsFound == 1) "" else "s"} found"
        }

        val builder = NotificationCompat.Builder(context, AudioForegroundService.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_search)
            .setContentIntent(mainPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(!isScanning)

        if (isScanning) {
            builder.setProgress(0, 0, true) // Indeterminate progress
        }

        return builder.build()
    }

    private fun getNotificationTitle(session: HostSession, isPaused: Boolean): String {
        return when {
            isPaused -> "⏸ ${session.sessionName} (Paused)"
            else -> "🎵 ${session.sessionName}"
        }
    }

    private fun getNotificationText(
        connectedClients: Int,
        maxClients: Int,
        isAudioMuted: Boolean,
        isPaused: Boolean
    ): String {
        val clientText = if (maxClients > 0) {
            "$connectedClients/$maxClients clients"
        } else {
            "$connectedClients client${if (connectedClients == 1) "" else "s"}"
        }

        val statusParts = mutableListOf<String>()
        
        if (isPaused) {
            statusParts.add("Paused")
        } else {
            statusParts.add("Broadcasting")
        }
        
        if (isAudioMuted) {
            statusParts.add("Muted")
        }
        
        return "$clientText • ${statusParts.joinToString(" • ")}"
    }

    private fun getNotificationIcon(isPaused: Boolean, isAudioMuted: Boolean): Int {
        return when {
            isPaused -> R.drawable.ic_pause_circle
            isAudioMuted -> R.drawable.ic_volume_off
            else -> R.drawable.ic_cast_connected
        }
    }

    private fun getConnectionStatusText(status: String, hostName: String?): String {
        return when (status) {
            "CONNECTING" -> "Connecting to ${hostName ?: "host"}..."
            "RECONNECTING" -> "Reconnecting to ${hostName ?: "host"}..."
            "AUTHENTICATING" -> "Authenticating..."
            "DISCOVERING" -> "Finding hosts..."
            "STARTING" -> "Starting broadcast..."
            "STOPPING" -> "Stopping broadcast..."
            else -> status
        }
    }

    private fun addExpandedContent(
        builder: NotificationCompat.Builder,
        session: HostSession,
        connectedClients: Int,
        startTime: Long,
        isPaused: Boolean,
        isAudioMuted: Boolean
    ) {
        val duration = formatDuration(System.currentTimeMillis() - startTime)
        
        val expandedText = buildString {
            appendLine("Session: ${session.sessionName}")
            appendLine("Host: ${session.hostName}")
            appendLine("Source: ${formatAudioSource(session.audioSource)}")
            
            if (session.maxClients > 0) {
                appendLine("Clients: $connectedClients/${session.maxClients}")
            } else {
                appendLine("Clients: $connectedClients")
            }
            
            appendLine("Duration: $duration")
            
            val statusList = mutableListOf<String>()
            if (isPaused) statusList.add("Paused")
            if (isAudioMuted) statusList.add("Muted")
            if (session.requiresPassword) statusList.add("Password Protected")
            
            if (statusList.isNotEmpty()) {
                appendLine("Status: ${statusList.joinToString(", ")}")
            }
        }.trim()

        builder.setStyle(
            NotificationCompat.BigTextStyle()
                .bigText(expandedText)
                .setBigContentTitle(getNotificationTitle(session, isPaused))
        )
    }

    private fun formatAudioSource(source: String): String {
        return when (source.uppercase()) {
            "MICROPHONE" -> "Microphone"
            "SYSTEM" -> "System Audio"
            "LINE_IN" -> "Line Input"
            "BLUETOOTH" -> "Bluetooth"
            else -> source
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = durationMs / (1000 * 60 * 60)
        
        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
            else -> String.format("%d:%02d", minutes, seconds)
        }
    }
}