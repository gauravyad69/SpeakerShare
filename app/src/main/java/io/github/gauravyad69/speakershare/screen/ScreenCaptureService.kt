package io.github.gauravyad69.speakershare.screen

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * ScreenCaptureService handles screen capture using MediaProjection.
 * Captures screen frames and provides them as JPEG byte arrays for streaming.
 * 
 * Key Features:
 * - Screen mirroring using VirtualDisplay
 * - Configurable frame rate and quality
 * - Efficient JPEG encoding
 * - Background capture support
 */
@Singleton
class ScreenCaptureService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val VIRTUAL_DISPLAY_NAME = "SpeakerShareScreen"
        private const val DEFAULT_FPS = 24 // Higher FPS for smoother playback
        private const val DEFAULT_QUALITY = 50 // Lower quality for faster encoding/transfer
        private const val SCALE_FACTOR = 0.5f // Scale down for bandwidth
    }

    // Screen capture state
    data class ScreenCaptureState(
        val isCapturing: Boolean = false,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Int = DEFAULT_FPS
    )

    private val _captureState = MutableStateFlow(ScreenCaptureState())
    val captureState: StateFlow<ScreenCaptureState> = _captureState.asStateFlow()

    // Screen frame stream (JPEG bytes)
    private val _screenFrameFlow = MutableSharedFlow<ByteArray>(
        replay = 1,  // Keep latest frame for polling
        extraBufferCapacity = 3,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val screenFrameFlow: SharedFlow<ByteArray> = _screenFrameFlow.asSharedFlow()
    
    // Latest frame for polling
    @Volatile
    private var latestFrame: ByteArray? = null
    
    /**
     * Get the latest captured frame (for HTTP polling)
     */
    fun getLatestFrame(): ByteArray? = latestFrame

    // MediaProjection components
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var captureJob: Job? = null

    // Capture scope
    private val captureScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Screen dimensions
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    init {
        initScreenDimensions()
    }

    private fun initScreenDimensions() {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        screenWidth = (metrics.widthPixels * SCALE_FACTOR).toInt()
        screenHeight = (metrics.heightPixels * SCALE_FACTOR).toInt()
        screenDensity = metrics.densityDpi
        
        Log.d(TAG, "Screen dimensions: ${screenWidth}x${screenHeight}, density: $screenDensity")
    }

    /**
     * Initialize MediaProjection from permission result
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun initializeMediaProjection(resultCode: Int, data: Intent): Result<Unit> {
        return try {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            
            if (mediaProjection == null) {
                return Result.failure(IllegalStateException("Failed to get MediaProjection"))
            }
            
            Log.d(TAG, "MediaProjection initialized for screen capture")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection", e)
            Result.failure(e)
        }
    }

    // MediaProjection callback (required on Android 14+)
    private var mediaProjectionCallback: MediaProjection.Callback? = null

    /**
     * Set MediaProjection from external source (shared with AudioCaptureService)
     */
    fun setMediaProjection(projection: MediaProjection?) {
        mediaProjection = projection
        Log.d(TAG, "MediaProjection set externally: ${projection != null}")
    }

    /**
     * Start screen capture
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun startCapture(fps: Int = DEFAULT_FPS, quality: Int = DEFAULT_QUALITY): Result<Unit> {
        return try {
            if (_captureState.value.isCapturing) {
                Log.w(TAG, "Screen capture already running")
                return Result.success(Unit)
            }

            val projection = mediaProjection
            if (projection == null) {
                Log.e(TAG, "MediaProjection not initialized")
                return Result.failure(IllegalStateException("MediaProjection not initialized"))
            }

            // Start handler thread for image callbacks
            handlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
            handler = Handler(handlerThread!!.looper)

            // Register MediaProjection callback (required on Android 14+)
            // Must be done BEFORE createVirtualDisplay
            mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped via callback")
                    stopCapture()
                }
            }
            projection.registerCallback(mediaProjectionCallback!!, handler)
            Log.d(TAG, "MediaProjection callback registered")

            // Create ImageReader
            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )

            // Create VirtualDisplay
            virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                handler
            )

            // Start capture loop
            captureJob = captureScope.launch {
                captureLoop(fps, quality)
            }

            _captureState.value = ScreenCaptureState(
                isCapturing = true,
                width = screenWidth,
                height = screenHeight,
                fps = fps
            )

            Log.d(TAG, "Screen capture started: ${screenWidth}x${screenHeight} @ ${fps}fps")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen capture", e)
            stopCapture()
            Result.failure(e)
        }
    }

    /**
     * Screen capture loop - captures frames at specified FPS
     */
    private suspend fun captureLoop(fps: Int, quality: Int) {
        val frameDelayMs = 1000L / fps
        var frameCount = 0

        while (coroutineContext.isActive) {
            try {
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    try {
                        val buffer = image.planes[0].buffer
                        val pixelStride = image.planes[0].pixelStride
                        val rowStride = image.planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * screenWidth

                        // Create bitmap
                        val bitmap = Bitmap.createBitmap(
                            screenWidth + rowPadding / pixelStride,
                            screenHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)

                        // Crop to actual screen size if needed
                        val croppedBitmap = if (rowPadding > 0) {
                            Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                        } else {
                            bitmap
                        }

                        // Compress to JPEG
                        val outputStream = ByteArrayOutputStream()
                        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                        val jpegBytes = outputStream.toByteArray()

                        // Store latest frame for polling
                        latestFrame = jpegBytes
                        
                        // Emit frame for streaming
                        _screenFrameFlow.emit(jpegBytes)

                        // Cleanup
                        if (croppedBitmap !== bitmap) {
                            croppedBitmap.recycle()
                        }
                        bitmap.recycle()
                        
                        frameCount++
                        if (frameCount % 30 == 0) {
                            Log.d(TAG, "Screen captured $frameCount frames, last ${jpegBytes.size} bytes")
                        }
                    } finally {
                        image.close()
                    }
                }

                delay(frameDelayMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing screen frame", e)
                delay(100) // Brief pause on error
            }
        }
    }

    /**
     * Stop screen capture
     */
    fun stopCapture(releaseProjection: Boolean = false): Result<Unit> {
        return try {
            captureJob?.cancel()
            captureJob = null

            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            handlerThread?.quitSafely()
            handlerThread = null
            handler = null

            // Unregister MediaProjection callback
            mediaProjectionCallback?.let { callback ->
                mediaProjection?.unregisterCallback(callback)
            }
            mediaProjectionCallback = null

            if (releaseProjection) {
                mediaProjection?.stop()
                mediaProjection = null
            }

            _captureState.value = ScreenCaptureState(isCapturing = false)
            Log.d(TAG, "Screen capture stopped")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping screen capture", e)
            Result.failure(e)
        }
    }

    /**
     * Get current MediaProjection
     */
    fun getMediaProjection(): MediaProjection? = mediaProjection

    /**
     * Check if MediaProjection is initialized
     */
    fun isMediaProjectionInitialized(): Boolean = mediaProjection != null

    /**
     * Clean up resources
     */
    fun release() {
        stopCapture(releaseProjection = true)
        captureScope.cancel()
    }
}
