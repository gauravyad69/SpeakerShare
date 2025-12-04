package io.github.gauravyad69.speakershare.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import timber.log.Timber
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Client-side service for receiving screen frames from the host.
 * Uses HTTP MJPEG-like streaming for receiving JPEG frames.
 */
@Singleton
class ScreenStreamClient @Inject constructor() {
    
    companion object {
        private const val SCREEN_STREAM_PATH = "/api/screen/stream"
        private const val CONNECTION_TIMEOUT = 2000 // Reduced for lower latency
        private const val READ_TIMEOUT = 3000 // Reduced for lower latency
        private const val HTTP_API_PORT = 8080 // The HTTP API always runs on this port
        private const val FRAME_DELAY_MS = 33L // ~30 FPS polling (was 66ms/15fps)
    }
    
    // Screen frame flow (decoded Bitmaps)
    private val _screenFrameFlow = MutableSharedFlow<Bitmap>(
        replay = 1,
        extraBufferCapacity = 2,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val screenFrameFlow: SharedFlow<Bitmap> = _screenFrameFlow.asSharedFlow()
    
    // Streaming state
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    
    // Screen sharing availability from host
    private val _isScreenShareAvailable = MutableStateFlow(false)
    val isScreenShareAvailable: StateFlow<Boolean> = _isScreenShareAvailable.asStateFlow()
    
    private var streamJob: Job? = null
    private val streamScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Start receiving screen frames from host using polling
     * Note: Uses HTTP_API_PORT (8080) for the HTTP API, ignoring the passed port
     */
    fun startStreaming(hostIp: String, hostPort: Int) {
        if (_isStreaming.value) {
            Timber.w("Already streaming")
            return
        }
        
        streamJob = streamScope.launch {
            _isStreaming.value = true
            _isScreenShareAvailable.value = true
            Timber.d("Starting screen frame polling from $hostIp:$HTTP_API_PORT")
            
            try {
                pollScreenFrames(hostIp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e("Screen polling error", e)
            } finally {
                _isStreaming.value = false
            }
        }
    }
    
    /**
     * Stop receiving screen stream
     */
    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        _isStreaming.value = false
        _isScreenShareAvailable.value = false
        Timber.d("Screen stream stopped")
    }
    
    /**
     * Check if host is sharing screen
     * Note: Uses HTTP_API_PORT (8080) for the HTTP API, ignoring the passed port
     */
    suspend fun checkScreenShareAvailable(hostIp: String, hostPort: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$hostIp:$HTTP_API_PORT/api/screen/status")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECTION_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.requestMethod = "GET"
                
                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    // Handle both formats: "available": true and "available":true
                    val available = response.contains("\"available\":") && response.contains("true")
                    Timber.d("Screen share available: $available (response: $response)")
                    _isScreenShareAvailable.value = available
                    available
                } else {
                    _isScreenShareAvailable.value = false
                    false
                }
            } catch (e: Exception) {
                Timber.d("Screen share not available: ${e.message}")
                _isScreenShareAvailable.value = false
                false
            }
        }
    }
    
    /**
     * Poll screen frames via HTTP (simpler and more reliable than streaming)
     */
    private suspend fun pollScreenFrames(hostIp: String) {
        val frameUrl = "http://$hostIp:$HTTP_API_PORT/api/screen/frame"
        var frameCount = 0
        var consecutiveErrors = 0
        val maxConsecutiveErrors = 10
        
        // Reuse buffer for better performance
        val bitmapOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565 // Use 16-bit for faster decoding
            inSampleSize = 1
        }
        
        while (coroutineContext.isActive) {
            try {
                val url = URL(frameUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECTION_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.requestMethod = "GET"
                connection.setRequestProperty("Connection", "keep-alive") // Reuse connection
                
                val responseCode = connection.responseCode
                when (responseCode) {
                    200 -> {
                        // Read JPEG frame with buffered input
                        val inputStream = BufferedInputStream(connection.inputStream, 32768)
                        val bytes = inputStream.readBytes()
                        inputStream.close()
                        connection.disconnect()
                        
                        if (bytes.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bitmapOptions)
                            if (bitmap != null) {
                                _screenFrameFlow.emit(bitmap)
                                frameCount++
                                consecutiveErrors = 0
                                if (frameCount % 30 == 0) {
                                    Timber.d("Received $frameCount screen frames")
                                }
                            }
                        }
                    }
                    204 -> {
                        // No content - no frame available yet
                        Timber.d("No frame available yet")
                    }
                    503 -> {
                        // Screen sharing stopped
                        Timber.d("Screen sharing stopped by host")
                        _isScreenShareAvailable.value = false
                        return
                    }
                    else -> {
                        Timber.w("Unexpected response: $responseCode")
                        consecutiveErrors++
                    }
                }
                
                connection.disconnect()
                
                // Check if too many errors
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    Timber.e("Too many consecutive errors, stopping")
                    _isScreenShareAvailable.value = false
                    return
                }
                
                // Poll at ~30 FPS for smoother playback
                delay(FRAME_DELAY_MS)
                
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e("Error polling frame: ${e.message}")
                consecutiveErrors++
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    Timber.e("Too many errors, stopping screen polling")
                    _isScreenShareAvailable.value = false
                    return
                }
                delay(500) // Back off on error
            }
        }
    }
    
    /**
     * Clean up resources
     */
    fun release() {
        stopStreaming()
        streamScope.cancel()
    }
}
