package com.example.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.SettingsRepository
import com.example.model.FileCategory
import com.example.model.ReceivedFileItem
import com.example.model.ServerConfig
import com.example.model.ServerState
import com.example.model.SharedFileItem
import com.example.model.TransferLogEntry
import com.example.util.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Process-scoped coordinator managing the local HTTP server, shared file queue,
 * received files directory, live transfer logs, and Wi-Fi network monitoring.
 */
object ServerController {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null
    private var settingsRepository: SettingsRepository? = null
    private var httpServer: LocalHttpFileServer? = null
    private var networkCallbackRegistered = false

    private val _config = MutableStateFlow(ServerConfig())
    val config: StateFlow<ServerConfig> = _config.asStateFlow()

    private val _serverState = MutableStateFlow(ServerState())
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _sharedFiles = MutableStateFlow<List<SharedFileItem>>(emptyList())
    val sharedFiles: StateFlow<List<SharedFileItem>> = _sharedFiles.asStateFlow()

    private val _receivedFiles = MutableStateFlow<List<ReceivedFileItem>>(emptyList())
    val receivedFiles: StateFlow<List<ReceivedFileItem>> = _receivedFiles.asStateFlow()

    private val _transferLogs = MutableStateFlow<List<TransferLogEntry>>(emptyList())
    val transferLogs: StateFlow<List<TransferLogEntry>> = _transferLogs.asStateFlow()

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val repo = SettingsRepository(app)
        settingsRepository = repo

        refreshNetworkInfo()
        registerNetworkMonitor(app)
        reloadReceivedFiles(app)

        appScope.launch {
            repo.configFlow.collect { newConfig ->
                val previous = _config.value
                _config.value = newConfig
                _serverState.update { state ->
                    if (!state.isRunning) state.copy(port = newConfig.port) else state
                }
                if (_serverState.value.isRunning && previous.port != newConfig.port) {
                    restartServerLocked()
                }
            }
        }
    }

    fun getReceivedDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "received")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun refreshNetworkInfo() {
        val info = NetworkUtils.detectLocalNetwork()
        _serverState.update { it.copy(networkInfo = info) }
    }

    @Synchronized
    fun startServer(): Result<Int> {
        val ctx = appContext ?: return Result.failure(IllegalStateException("App not initialized"))
        refreshNetworkInfo()
        val desiredPort = _config.value.port

        val server = buildHttpServer(ctx)
        return try {
            server.start(desiredPort)
            httpServer = server
            _serverState.update {
                it.copy(
                    isRunning = true,
                    port = server.boundPort,
                    lastError = null,
                )
            }
            Result.success(server.boundPort)
        } catch (e: Exception) {
            // Fallback to a nearby free port if the requested port is busy
            try {
                val fallbackPort = if (desiredPort < 65000) desiredPort + 1 else 8081
                server.start(fallbackPort)
                httpServer = server
                _serverState.update {
                    it.copy(
                        isRunning = true,
                        port = server.boundPort,
                        lastError = null,
                    )
                }
                Result.success(server.boundPort)
            } catch (e2: Exception) {
                _serverState.update {
                    it.copy(
                        isRunning = false,
                        lastError = e2.message ?: "Could not bind local server port",
                    )
                }
                Result.failure(e2)
            }
        }
    }

    @Synchronized
    fun stopServer() {
        httpServer?.stop()
        httpServer = null
        _serverState.update {
            it.copy(
                isRunning = false,
                activeConnections = 0,
            )
        }
    }

    @Synchronized
    private fun restartServerLocked() {
        stopServer()
        startServer()
    }

    fun addUris(context: Context, uris: List<Uri>): Int {
        ensureInitialized(context)
        val resolver = context.contentResolver
        val existingUris = _sharedFiles.value.mapNotNull { it.uri }.toSet()
        val newItems = mutableListOf<SharedFileItem>()

        for (uri in uris) {
            if (uri in existingUris) continue
            try {
                resolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Temporary URI read grants from Photo Picker / SAF still work for this session
            }

            var displayName = "shared_file_${System.currentTimeMillis()}"
            var sizeBytes = -1L

            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIdx >= 0) {
                                cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                            }
                            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                                sizeBytes = cursor.getLong(sizeIdx)
                            }
                        }
                    }
            } catch (_: Exception) {
            }

            if (sizeBytes < 0L) {
                try {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        if (afd.length >= 0L) sizeBytes = afd.length
                    }
                } catch (_: Exception) {
                }
            }

            val mimeType = resolver.getType(uri)?.takeIf { it.isNotBlank() }
                ?: FormatUtils.guessMimeType(displayName)
            val category = FormatUtils.categorizeFile(displayName, mimeType)

            newItems.add(
                SharedFileItem(
                    id = UUID.randomUUID().toString().replace("-", "").take(12),
                    name = displayName,
                    sizeBytes = sizeBytes.coerceAtLeast(0L),
                    mimeType = mimeType,
                    category = category,
                    uri = uri,
                )
            )
        }

        if (newItems.isNotEmpty()) {
            _sharedFiles.update { newItems + it }
        }
        return newItems.size
    }

    fun addTextNote(rawFileName: String, content: String) {
        val base = rawFileName.trim().ifBlank { "note_${System.currentTimeMillis()}" }
        val finalName = if (base.contains('.')) base else "$base.txt"
        val bytes = content.toByteArray(Charsets.UTF_8)
        val item = SharedFileItem(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            name = FormatUtils.sanitizeFileName(finalName),
            sizeBytes = bytes.size.toLong(),
            mimeType = "text/plain; charset=utf-8",
            category = FileCategory.TEXT,
            textContent = content,
        )
        _sharedFiles.update { listOf(item) + it }
    }

    fun removeSharedFile(id: String) {
        _sharedFiles.update { list -> list.filterNot { it.id == id } }
    }

    fun clearSharedFiles() {
        _sharedFiles.value = emptyList()
    }

    fun deleteReceivedFile(id: String) {
        val target = _receivedFiles.value.firstOrNull { it.id == id } ?: return
        target.file.delete()
        _receivedFiles.update { list -> list.filterNot { it.id == id } }
    }

    fun clearTransferLogs() {
        _transferLogs.value = emptyList()
    }

    fun updateConfig(newConfig: ServerConfig) {
        appScope.launch {
            settingsRepository?.saveConfig(newConfig)
        }
    }

    private fun reloadReceivedFiles(context: Context) {
        val dir = getReceivedDir(context)
        val files = dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                val mime = FormatUtils.guessMimeType(file.name)
                ReceivedFileItem(
                    id = file.absolutePath.hashCode().toString(),
                    name = file.name,
                    sizeBytes = file.length(),
                    mimeType = mime,
                    category = FormatUtils.categorizeFile(file.name, mime),
                    file = file,
                    senderIp = "Local Wi-Fi",
                    receivedAtMillis = file.lastModified(),
                )
            }
            .orEmpty()
        _receivedFiles.value = files
    }

    private fun buildHttpServer(context: Context): LocalHttpFileServer {
        val resolver = context.contentResolver
        return LocalHttpFileServer(
            configProvider = { _config.value },
            filesProvider = { _sharedFiles.value },
            streamProvider = object : FileStreamProvider {
                override fun openInputStream(item: SharedFileItem): InputStream? {
                    val uri = item.uri ?: return null
                    return try {
                        resolver.openInputStream(uri)
                    } catch (_: Exception) {
                        null
                    }
                }
            },
            receivedDir = getReceivedDir(context),
            callbacks = object : ServerCallbacks {
                override fun onActiveConnectionsChanged(count: Int) {
                    _serverState.update { it.copy(activeConnections = count) }
                }

                override fun onTransferUpdated(entry: TransferLogEntry) {
                    _transferLogs.update { current ->
                        val index = current.indexOfFirst { it.id == entry.id }
                        if (index >= 0) {
                            current.toMutableList().also { it[index] = entry }
                        } else {
                            (listOf(entry) + current).take(50)
                        }
                    }
                }

                override fun onDownloadCompleted(fileId: String, bytesSent: Long) {
                    _serverState.update {
                        it.copy(
                            totalDownloadsServed = it.totalDownloadsServed + 1,
                            totalBytesServed = it.totalBytesServed + bytesSent,
                        )
                    }
                    _sharedFiles.update { list ->
                        list.map { item ->
                            if (item.id == fileId) item.copy(downloadCount = item.downloadCount + 1)
                            else item
                        }
                    }
                }

                override fun onFileReceived(
                    file: File,
                    originalName: String,
                    mimeType: String,
                    senderIp: String,
                ) {
                    val item = ReceivedFileItem(
                        id = UUID.randomUUID().toString().take(12),
                        name = originalName,
                        sizeBytes = file.length(),
                        mimeType = mimeType,
                        category = FormatUtils.categorizeFile(originalName, mimeType),
                        file = file,
                        senderIp = senderIp,
                        receivedAtMillis = System.currentTimeMillis(),
                    )
                    _receivedFiles.update { listOf(item) + it }
                }
            },
        )
    }

    private fun registerNetworkMonitor(context: Context) {
        if (networkCallbackRegistered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = refreshNetworkInfo()
                    override fun onLost(network: Network) = refreshNetworkInfo()
                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: android.net.LinkProperties,
                    ) = refreshNetworkInfo()
                },
            )
            networkCallbackRegistered = true
        } catch (_: Exception) {
        }
    }
}
