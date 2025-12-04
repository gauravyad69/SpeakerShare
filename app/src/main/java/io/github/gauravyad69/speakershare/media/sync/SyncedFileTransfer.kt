package io.github.gauravyad69.speakershare.media.sync

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import timber.log.Timber
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synced File Transfer
 * 
 * Handles file sharing between host and clients for synchronized playback.
 * 
 * Workflow:
 * 1. Host shares file metadata (name, size, hash)
 * 2. Client checks if file exists locally (by hash)
 * 3. If not found, client downloads from host
 * 4. File is cached for future sessions
 */
@Singleton
class SyncedFileTransfer @Inject constructor() {
    
    companion object {
        private const val CACHE_DIR = "synced_media"
        private const val CHUNK_SIZE = 64 * 1024 // 64KB chunks
        const val FILE_SERVE_PORT = 9092
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Transfer progress for UI
    private val _transferProgress = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    val transferProgress: StateFlow<Map<String, TransferProgress>> = _transferProgress.asStateFlow()
    
    /**
     * Calculate SHA-256 hash of a file for verification
     */
    suspend fun calculateFileHash(context: Context, uri: Uri): String {
        return withContext(Dispatchers.IO) {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext ""
                
                inputStream.use { stream ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    var bytesRead: Int
                    
                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                
                // Convert to hex string
                digest.digest().joinToString("") { "%02x".format(it) }
                
            } catch (e: Exception) {
                Timber.e("Failed to calculate file hash", e)
                ""
            }
        }
    }
    
    /**
     * Get file metadata from Uri
     */
    suspend fun getFileMetadata(context: Context, uri: Uri): SyncedMediaFile? {
        return withContext(Dispatchers.IO) {
            try {
                val cursor = context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null
                )
                
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                        
                        val name = if (nameIndex >= 0) it.getString(nameIndex) else "Unknown"
                        val size = if (sizeIndex >= 0) it.getLong(sizeIndex) else 0L
                        val mimeType = context.contentResolver.getType(uri) ?: ""
                        val isVideo = mimeType.startsWith("video/")
                        
                        // Extract duration using MediaMetadataRetriever
                        val durationMs = extractDuration(context, uri)
                        Timber.d("File metadata: name=$name, size=$size, duration=${durationMs}ms")
                        
                        SyncedMediaFile(
                            uri = uri,
                            name = name,
                            mimeType = mimeType,
                            sizeBytes = size,
                            durationMs = durationMs,
                            isVideo = isVideo
                        )
                    } else null
                }
            } catch (e: Exception) {
                Timber.e("Failed to get file metadata", e)
                null
            }
        }
    }
    
    /**
     * Extract media duration using MediaMetadataRetriever
     */
    private fun extractDuration(context: Context, uri: Uri): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Timber.w("Failed to extract duration for $uri: ${e.message}")
            0L
        }
    }
    
    /**
     * Find a local file matching the given metadata
     */
    suspend fun findLocalFile(context: Context, file: SyncedMediaFile): Uri? {
        return withContext(Dispatchers.IO) {
            try {
                // Check cache directory first
                val cacheDir = File(context.cacheDir, CACHE_DIR)
                if (cacheDir.exists()) {
                    val cachedFile = File(cacheDir, file.contentHash)
                    if (cachedFile.exists() && cachedFile.length() == file.sizeBytes) {
                        // Verify hash
                        val localHash = calculateLocalFileHash(cachedFile)
                        if (localHash == file.contentHash) {
                            Timber.d("Found cached file: ${file.name}")
                            return@withContext Uri.fromFile(cachedFile)
                        }
                    }
                }
                
                // TODO: Could also search device media library for matching file
                null
                
            } catch (e: Exception) {
                Timber.e("Error finding local file", e)
                null
            }
        }
    }
    
    /**
     * Download file from host
     */
    suspend fun downloadFile(
        context: Context,
        hostAddress: String,
        file: SyncedMediaFile
    ): Uri? {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("Downloading file: ${file.name} from $hostAddress")
                
                // Update progress
                updateProgress(file.contentHash, TransferProgress(
                    fileName = file.name,
                    totalBytes = file.sizeBytes,
                    downloadedBytes = 0,
                    status = TransferStatus.DOWNLOADING
                ))
                
                val client = HttpClient {
                    install(HttpTimeout) {
                        requestTimeoutMillis = 5 * 60 * 1000 // 5 minutes
                        connectTimeoutMillis = 10_000
                    }
                }
                
                // Create cache directory
                val cacheDir = File(context.cacheDir, CACHE_DIR)
                cacheDir.mkdirs()
                
                // Download to temp file first
                val tempFile = File(cacheDir, "${file.contentHash}.tmp")
                val finalFile = File(cacheDir, file.contentHash)
                
                val response = client.get("http://$hostAddress:${SyncedPlaybackServer.SYNC_SERVER_PORT}/file/${file.contentHash}") {
                    onDownload { bytesSentTotal, contentLength ->
                        updateProgress(file.contentHash, TransferProgress(
                            fileName = file.name,
                            totalBytes = contentLength,
                            downloadedBytes = bytesSentTotal,
                            status = TransferStatus.DOWNLOADING
                        ))
                    }
                }
                
                if (response.status == HttpStatusCode.OK) {
                    // Write to file
                    FileOutputStream(tempFile).use { output ->
                        val channel = response.bodyAsChannel()
                        val buffer = ByteArray(CHUNK_SIZE)
                        
                        while (!channel.isClosedForRead) {
                            val bytesRead = channel.readAvailable(buffer, 0, buffer.size)
                            if (bytesRead > 0) {
                                output.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                    
                    // Verify hash
                    val downloadedHash = calculateLocalFileHash(tempFile)
                    if (downloadedHash == file.contentHash) {
                        tempFile.renameTo(finalFile)
                        
                        updateProgress(file.contentHash, TransferProgress(
                            fileName = file.name,
                            totalBytes = file.sizeBytes,
                            downloadedBytes = file.sizeBytes,
                            status = TransferStatus.COMPLETED
                        ))
                        
                        Timber.i("Downloaded and verified: ${file.name}")
                        client.close()
                        return@withContext Uri.fromFile(finalFile)
                    } else {
                        Timber.e("Hash mismatch after download")
                        tempFile.delete()
                        updateProgress(file.contentHash, TransferProgress(
                            fileName = file.name,
                            totalBytes = file.sizeBytes,
                            downloadedBytes = 0,
                            status = TransferStatus.FAILED,
                            error = "Hash verification failed"
                        ))
                    }
                } else {
                    Timber.e("Download failed: ${response.status}")
                    updateProgress(file.contentHash, TransferProgress(
                        fileName = file.name,
                        totalBytes = file.sizeBytes,
                        downloadedBytes = 0,
                        status = TransferStatus.FAILED,
                        error = "HTTP ${response.status}"
                    ))
                }
                
                client.close()
                null
                
            } catch (e: Exception) {
                Timber.e("Download failed", e)
                updateProgress(file.contentHash, TransferProgress(
                    fileName = file.name,
                    totalBytes = file.sizeBytes,
                    downloadedBytes = 0,
                    status = TransferStatus.FAILED,
                    error = e.message
                ))
                null
            }
        }
    }
    
    /**
     * Serve a file for download (host side)
     * Returns the file bytes for the given hash
     */
    suspend fun serveFile(context: Context, contentHash: String, files: List<SyncedMediaFile>): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val file = files.find { it.contentHash == contentHash }
                    ?: return@withContext null
                
                context.contentResolver.openInputStream(file.uri)?.use { stream ->
                    stream.readBytes()
                }
                
            } catch (e: Exception) {
                Timber.e("Failed to serve file", e)
                null
            }
        }
    }
    
    /**
     * Clear cached files
     */
    fun clearCache(context: Context) {
        try {
            val cacheDir = File(context.cacheDir, CACHE_DIR)
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
                Timber.i("Cache cleared")
            }
        } catch (e: Exception) {
            Timber.e("Failed to clear cache", e)
        }
    }
    
    /**
     * Get cache size in bytes
     */
    fun getCacheSize(context: Context): Long {
        return try {
            val cacheDir = File(context.cacheDir, CACHE_DIR)
            if (cacheDir.exists()) {
                cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            } else 0L
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun calculateLocalFileHash(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(CHUNK_SIZE)
                var bytesRead: Int
                
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
    
    private fun updateProgress(fileHash: String, progress: TransferProgress) {
        _transferProgress.update { current ->
            current + (fileHash to progress)
        }
    }
}

/**
 * Transfer progress for a single file
 */
data class TransferProgress(
    val fileName: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: TransferStatus,
    val error: String? = null
) {
    val progressPercent: Float
        get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
}

/**
 * Transfer status
 */
enum class TransferStatus {
    PENDING,
    DOWNLOADING,
    VERIFYING,
    COMPLETED,
    FAILED
}
