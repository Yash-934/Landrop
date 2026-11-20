package com.example.model

import android.net.Uri
import java.io.File

enum class FileCategory {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,
    APP,
    TEXT,
    OTHER,
}

data class SharedFileItem(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val category: FileCategory,
    val uri: Uri? = null,
    val textContent: String? = null,
    val addedAtMillis: Long = System.currentTimeMillis(),
    val downloadCount: Int = 0,
)

data class ReceivedFileItem(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val category: FileCategory,
    val file: File,
    val senderIp: String,
    val receivedAtMillis: Long = System.currentTimeMillis(),
)

enum class TransferDirection {
    DOWNLOAD,
    UPLOAD,
}

enum class TransferStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED,
}

data class TransferLogEntry(
    val id: String,
    val fileName: String,
    val clientIp: String,
    val direction: TransferDirection,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val status: TransferStatus,
    val timestampMillis: Long = System.currentTimeMillis(),
) {
    val progress: Float
        get() = if (totalBytes > 0L) {
            (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else if (status == TransferStatus.COMPLETED) {
            1f
        } else {
            0f
        }
}

data class ServerConfig(
    val serverName: String = "LanDrop Server",
    val port: Int = 8080,
    val pinEnabled: Boolean = false,
    val accessPin: String = "2468",
    val allowBrowserUploads: Boolean = true,
)

data class NetworkInfo(
    val ipAddress: String? = null,
    val interfaceLabel: String = "Offline",
    val isConnected: Boolean = false,
)

data class ServerState(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val networkInfo: NetworkInfo = NetworkInfo(),
    val activeConnections: Int = 0,
    val totalDownloadsServed: Int = 0,
    val totalBytesServed: Long = 0L,
    val lastError: String? = null,
) {
    val serverUrl: String?
        get() = networkInfo.ipAddress?.let { "http://$it:$port" }
}
