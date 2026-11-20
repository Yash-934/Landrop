package com.example.server

import com.example.model.ServerConfig
import com.example.model.SharedFileItem
import com.example.model.ThermalLevel
import com.example.model.TransferDirection
import com.example.model.TransferLogEntry
import com.example.model.TransferStatus
import com.example.util.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

interface FileStreamProvider {
    fun openInputStream(item: SharedFileItem): InputStream?

    /**
     * Opens a stream positioned at [offsetBytes] using O(1) FileChannel seeking when available.
     * Returns pair of (InputStream, alreadySeeked).
     */
    fun openInputStreamAt(item: SharedFileItem, offsetBytes: Long): Pair<InputStream, Boolean>? {
        val stream = openInputStream(item) ?: return null
        return stream to false
    }
}

interface ServerCallbacks {
    fun onActiveConnectionsChanged(count: Int)
    fun onTransferUpdated(entry: TransferLogEntry)
    fun onDownloadCompleted(fileId: String, bytesSent: Long)
    fun onFileReceived(file: File, originalName: String, mimeType: String, senderIp: String)
}

/**
 * Thermal-optimized embedded HTTP/1.1 file server over ServerSocketChannel + Coroutines.
 * - Uses Linux kernel zero-copy FileChannel.transferTo() (sendfile) to minimize CPU load and heat.
 * - Uses small 8 KB header buffers and L2-friendly 128 KB streaming buffers to avoid GC heat.
 * - Caps concurrent heavy streams (max 2) and applies automatic thermal pacing if device warms up.
 */
class LocalHttpFileServer(
    private val configProvider: () -> ServerConfig,
    private val filesProvider: () -> List<SharedFileItem>,
    private val streamProvider: FileStreamProvider,
    private val receivedDir: File,
    private val callbacks: ServerCallbacks,
    private val thermalLevelProvider: () -> ThermalLevel = { ThermalLevel.COOL },
) {
    companion object {
        private const val HEADER_BUFFER_BYTES = 8 * 1024    // 8 KB for HTTP request headers
        private const val SOCKET_BUFFER_BYTES = 256 * 1024  // 256 KB balanced TCP window
        private const val STREAM_CHUNK_BYTES = 128 * 1024   // 128 KB L2-cache-friendly chunk
        private const val ZERO_COPY_CHUNK_BYTES = 256 * 1024L
        private const val PROGRESS_EMIT_INTERVAL_MS = 800L
        private const val MAX_CONCURRENT_TRANSFERS = 2
    }

    private var serverChannel: ServerSocketChannel? = null
    private var scope: CoroutineScope? = null
    private var acceptJob: Job? = null
    private val activeTransfers = AtomicInteger(0)
    private val transferSemaphore = Semaphore(MAX_CONCURRENT_TRANSFERS, true)

    val isRunning: Boolean
        get() = serverChannel?.isOpen == true

    val boundPort: Int
        get() = serverChannel?.socket()?.localPort ?: -1

    fun start(port: Int) {
        stop()
        val channel = ServerSocketChannel.open()
        channel.configureBlocking(true)
        val socket = channel.socket()
        socket.reuseAddress = true
        try {
            socket.receiveBufferSize = SOCKET_BUFFER_BYTES
        } catch (_: Exception) {
        }
        channel.bind(InetSocketAddress(port), 32)
        serverChannel = channel

        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = serverScope
        acceptJob = serverScope.launch {
            while (isActive && channel.isOpen) {
                try {
                    val clientChannel = channel.accept()
                    serverScope.launch { handleClient(clientChannel) }
                } catch (_: ClosedChannelException) {
                    break
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    // Keep accepting subsequent clients
                }
            }
        }
    }

    fun stop() {
        try {
            serverChannel?.close()
        } catch (_: Exception) {
        }
        serverChannel = null
        acceptJob?.cancel()
        acceptJob = null
        scope?.cancel()
        scope = null
        activeTransfers.set(0)
        callbacks.onActiveConnectionsChanged(0)
    }

    private fun handleClient(clientChannel: SocketChannel) {
        val socket = clientChannel.socket()
        val clientIp = socket.inetAddress?.hostAddress ?: "Unknown"

        try {
            clientChannel.configureBlocking(true)
            socket.soTimeout = 45_000
            try {
                // Keep Nagle enabled (tcpNoDelay = false) to coalesce frames and reduce Wi-Fi radio interrupts
                socket.sendBufferSize = SOCKET_BUFFER_BYTES
                socket.receiveBufferSize = SOCKET_BUFFER_BYTES
            } catch (_: Exception) {
            }

            val input = BufferedInputStream(socket.getInputStream(), HEADER_BUFFER_BYTES)
            val rawOutput = socket.getOutputStream()

            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeSimpleResponse(rawOutput, 400, "Bad Request", "Malformed HTTP request")
                return
            }

            val method = parts[0].uppercase()
            val rawTarget = parts[1]
            val path = rawTarget.substringBefore('?')
            val queryString = rawTarget.substringAfter('?', "")
            val queryParams = parseQueryParams(queryString)

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            val config = configProvider()

            // Handle PIN authentication route
            if (path == "/auth" && method == "GET") {
                val enteredPin = queryParams["pin"].orEmpty()
                if (!config.pinEnabled || enteredPin == config.accessPin) {
                    val headerBlock = buildString {
                        append("HTTP/1.1 302 Found\r\n")
                        append("Location: /\r\n")
                        append("Set-Cookie: landrop_pin=${config.accessPin}; Path=/; SameSite=Lax\r\n")
                        append("Content-Length: 0\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    rawOutput.write(headerBlock.toByteArray(Charsets.UTF_8))
                    rawOutput.flush()
                } else {
                    val html = WebPortalHtmlBuilder.buildPinGateHtml(config.serverName, hasError = true)
                    writeHtmlResponse(rawOutput, 401, "Unauthorized", html)
                }
                return
            }

            // Enforce PIN if enabled
            if (config.pinEnabled && !isAuthorized(headers, queryParams, config.accessPin)) {
                if (method == "POST") {
                    writeSimpleResponse(rawOutput, 401, "Unauthorized", "PIN required")
                } else {
                    val html = WebPortalHtmlBuilder.buildPinGateHtml(config.serverName, hasError = false)
                    writeHtmlResponse(rawOutput, 200, "OK", html)
                }
                return
            }

            when {
                (method == "GET" || method == "HEAD") && (path == "/" || path == "/index.html") -> {
                    val html = WebPortalHtmlBuilder.buildPortalHtml(config, filesProvider())
                    writeHtmlResponse(rawOutput, 200, "OK", html, headOnly = method == "HEAD")
                }

                (method == "GET" || method == "HEAD") && path.startsWith("/download/") -> {
                    val id = path.removePrefix("/download/")
                    runWithTransferPermit {
                        serveFile(
                            fileId = id,
                            headers = headers,
                            clientChannel = clientChannel,
                            output = rawOutput,
                            clientIp = clientIp,
                            inline = false,
                            headOnly = method == "HEAD",
                        )
                    }
                }

                (method == "GET" || method == "HEAD") && path.startsWith("/preview/") -> {
                    val id = path.removePrefix("/preview/")
                    runWithTransferPermit {
                        serveFile(
                            fileId = id,
                            headers = headers,
                            clientChannel = clientChannel,
                            output = rawOutput,
                            clientIp = clientIp,
                            inline = true,
                            headOnly = method == "HEAD",
                        )
                    }
                }

                method == "GET" && path == "/download-all.zip" -> {
                    runWithTransferPermit {
                        serveAllAsZip(rawOutput, clientIp)
                    }
                }

                method == "POST" && path == "/upload" -> {
                    runWithTransferPermit {
                        handleUpload(config, queryParams, headers, input, rawOutput, clientIp)
                    }
                }

                method == "OPTIONS" -> {
                    val response = "HTTP/1.1 204 No Content\r\nAllow: GET, HEAD, POST, OPTIONS\r\nConnection: close\r\n\r\n"
                    rawOutput.write(response.toByteArray(Charsets.UTF_8))
                    rawOutput.flush()
                }

                else -> {
                    writeSimpleResponse(rawOutput, 404, "Not Found", "Resource not found on LanDrop server")
                }
            }
        } catch (_: Exception) {
            // Client disconnected or network interrupted
        } finally {
            try {
                clientChannel.close()
            } catch (_: Exception) {
            }
        }
    }

    private inline fun runWithTransferPermit(block: () -> Unit) {
        transferSemaphore.acquire()
        val active = activeTransfers.incrementAndGet()
        callbacks.onActiveConnectionsChanged(active)
        try {
            block()
        } finally {
            val remaining = activeTransfers.decrementAndGet().coerceAtLeast(0)
            callbacks.onActiveConnectionsChanged(remaining)
            transferSemaphore.release()
        }
    }

    /**
     * Gentle thermal pacing (bytes per second target) ONLY when user enabled Cool Mode
     * or Android's thermal sensor reports the device is warming up.
     */
    private fun currentThermalPacingTargetBps(): Long {
        val thermal = thermalLevelProvider()
        val coolMode = configProvider().coolMode
        return when {
            thermal == ThermalLevel.HOT -> 6L * 1024 * 1024       // ~6 MB/s to let device cool down rapidly
            thermal == ThermalLevel.WARM -> 12L * 1024 * 1024     // ~12 MB/s balanced cool pace
            coolMode -> 14L * 1024 * 1024                         // ~14 MB/s eco-cool mode
            else -> 0L                                            // Full uncapped zero-copy speed
        }
    }

    private fun applyThermalPacingIfNeeded(bytesTransferred: Long, startTimeMs: Long) {
        val targetBps = currentThermalPacingTargetBps()
        if (targetBps <= 0L || bytesTransferred <= 0L) return
        val expectedElapsedMs = (bytesTransferred * 1000L) / targetBps
        val actualElapsedMs = System.currentTimeMillis() - startTimeMs
        val sleepMs = expectedElapsedMs - actualElapsedMs
        if (sleepMs in 4L..250L) {
            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun serveFile(
        fileId: String,
        headers: Map<String, String>,
        clientChannel: SocketChannel,
        output: OutputStream,
        clientIp: String,
        inline: Boolean,
        headOnly: Boolean,
    ) {
        val item = filesProvider().firstOrNull { it.id == fileId }
        if (item == null) {
            writeSimpleResponse(output, 404, "Not Found", "Shared file no longer available")
            return
        }

        val totalLength = item.sizeBytes
        val rangeHeader = headers["range"]
        var startByte = 0L
        var endByte = if (totalLength > 0L) totalLength - 1L else -1L
        var isPartial = false

        if (rangeHeader != null && rangeHeader.startsWith("bytes=") && totalLength > 0L) {
            val spec = rangeHeader.removePrefix("bytes=").substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val startStr = spec.substring(0, dash).trim()
                val endStr = spec.substring(dash + 1).trim()
                if (startStr.isEmpty() && endStr.isNotEmpty()) {
                    val suffix = endStr.toLongOrNull() ?: 0L
                    startByte = (totalLength - suffix).coerceAtLeast(0L)
                    endByte = totalLength - 1L
                    isPartial = true
                } else {
                    val parsedStart = startStr.toLongOrNull()
                    if (parsedStart != null) {
                        startByte = parsedStart
                        endByte = endStr.toLongOrNull()?.coerceAtMost(totalLength - 1L) ?: (totalLength - 1L)
                        isPartial = true
                    }
                }
            }

            if (isPartial && (startByte < 0L || startByte >= totalLength || startByte > endByte)) {
                val resp = buildString {
                    append("HTTP/1.1 416 Range Not Satisfiable\r\n")
                    append("Content-Range: bytes */$totalLength\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(resp.toByteArray(Charsets.UTF_8))
                output.flush()
                return
            }
        }

        val contentLength = if (totalLength >= 0L) (endByte - startByte + 1L).coerceAtLeast(0L) else -1L
        val dispositionType = if (inline) "inline" else "attachment"
        val encodedName = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
        val asciiFallback = item.name.replace(Regex("[^\\x20-\\x7E]"), "_").replace("\"", "_")

        val statusLine = if (isPartial) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
        val headerBlock = buildString {
            append("$statusLine\r\n")
            append("Content-Type: ${item.mimeType}\r\n")
            append("Content-Disposition: $dispositionType; filename=\"$asciiFallback\"; filename*=UTF-8''$encodedName\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (contentLength >= 0L) {
                append("Content-Length: $contentLength\r\n")
            }
            if (isPartial && totalLength > 0L) {
                append("Content-Range: bytes $startByte-$endByte/$totalLength\r\n")
            }
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }

        if (headOnly) {
            output.write(headerBlock.toByteArray(Charsets.UTF_8))
            output.flush()
            return
        }

        val streamPair = openStreamForItem(item, startByte)
        if (streamPair == null) {
            writeSimpleResponse(output, 404, "Not Found", "Unable to read source file")
            return
        }
        val (rawStream, alreadySeeked) = streamPair

        val transferId = UUID.randomUUID().toString()
        val trackedTotal = if (contentLength > 0L) contentLength else totalLength.coerceAtLeast(0L)
        val shouldTrack = !inline || startByte == 0L
        val startTimeMs = System.currentTimeMillis()
        if (shouldTrack) {
            callbacks.onTransferUpdated(
                TransferLogEntry(
                    id = transferId,
                    fileName = item.name,
                    clientIp = clientIp,
                    direction = TransferDirection.DOWNLOAD,
                    bytesTransferred = 0L,
                    totalBytes = trackedTotal,
                    status = TransferStatus.IN_PROGRESS,
                    speedBytesPerSec = 0L,
                )
            )
        }

        var sent = 0L
        try {
            rawStream.use { inputStream ->
                writeChannelFully(clientChannel, headerBlock.toByteArray(Charsets.UTF_8))

                var remaining = if (contentLength >= 0L) contentLength else Long.MAX_VALUE
                var lastEmitTime = startTimeMs
                var usedZeroCopy = false

                // Kernel zero-copy sendfile() path for real FileInputStream / ParcelFileDescriptor files
                if (inputStream is FileInputStream && contentLength > 0L) {
                    val fileChannel = inputStream.channel
                    var filePos = startByte
                    try {
                        while (remaining > 0L) {
                            val chunk = minOf(ZERO_COPY_CHUNK_BYTES, remaining)
                            val transferred = fileChannel.transferTo(filePos, chunk, clientChannel)
                            if (transferred <= 0L) {
                                if (sent == 0L) break // Non-seekable pipe; fall back cleanly to stream copy
                                break
                            }
                            usedZeroCopy = true
                            filePos += transferred
                            sent += transferred
                            remaining -= transferred

                            applyThermalPacingIfNeeded(sent, startTimeMs)

                            val now = System.currentTimeMillis()
                            if (shouldTrack && now - lastEmitTime >= PROGRESS_EMIT_INTERVAL_MS) {
                                lastEmitTime = now
                                val elapsedMs = (now - startTimeMs).coerceAtLeast(1L)
                                callbacks.onTransferUpdated(
                                    TransferLogEntry(
                                        id = transferId,
                                        fileName = item.name,
                                        clientIp = clientIp,
                                        direction = TransferDirection.DOWNLOAD,
                                        bytesTransferred = sent,
                                        totalBytes = trackedTotal,
                                        status = TransferStatus.IN_PROGRESS,
                                        speedBytesPerSec = (sent * 1000L) / elapsedMs,
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) {
                        if (usedZeroCopy) throw RuntimeException("Client closed during zero-copy transfer")
                        // Otherwise fall through to regular stream copy below
                    }
                }

                // Fallback stream copy (used for text notes or virtual non-seekable streams)
                if (!usedZeroCopy && remaining > 0L) {
                    if (startByte > 0L && !alreadySeeked) {
                        skipFully(inputStream, startByte)
                    }
                    val buffer = ByteArray(STREAM_CHUNK_BYTES)
                    while (remaining > 0L) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = inputStream.read(buffer, 0, toRead)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        sent += read
                        if (contentLength >= 0L) remaining -= read

                        applyThermalPacingIfNeeded(sent, startTimeMs)

                        val now = System.currentTimeMillis()
                        if (shouldTrack && now - lastEmitTime >= PROGRESS_EMIT_INTERVAL_MS) {
                            lastEmitTime = now
                            val elapsedMs = (now - startTimeMs).coerceAtLeast(1L)
                            callbacks.onTransferUpdated(
                                TransferLogEntry(
                                    id = transferId,
                                    fileName = item.name,
                                    clientIp = clientIp,
                                    direction = TransferDirection.DOWNLOAD,
                                    bytesTransferred = sent,
                                    totalBytes = trackedTotal,
                                    status = TransferStatus.IN_PROGRESS,
                                    speedBytesPerSec = (sent * 1000L) / elapsedMs,
                                )
                            )
                        }
                    }
                    output.flush()
                }
            }

            val totalElapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
            val avgSpeed = (sent * 1000L) / totalElapsedMs
            if (shouldTrack) {
                callbacks.onTransferUpdated(
                    TransferLogEntry(
                        id = transferId,
                        fileName = item.name,
                        clientIp = clientIp,
                        direction = TransferDirection.DOWNLOAD,
                        bytesTransferred = sent,
                        totalBytes = maxOf(trackedTotal, sent),
                        status = TransferStatus.COMPLETED,
                        speedBytesPerSec = avgSpeed,
                    )
                )
            }
            if (!inline) {
                callbacks.onDownloadCompleted(item.id, sent)
            }
        } catch (_: Exception) {
            if (shouldTrack) {
                callbacks.onTransferUpdated(
                    TransferLogEntry(
                        id = transferId,
                        fileName = item.name,
                        clientIp = clientIp,
                        direction = TransferDirection.DOWNLOAD,
                        bytesTransferred = sent,
                        totalBytes = trackedTotal,
                        status = TransferStatus.FAILED,
                    )
                )
            }
        }
    }

    private fun serveAllAsZip(output: OutputStream, clientIp: String) {
        val files = filesProvider()
        if (files.isEmpty()) {
            writeSimpleResponse(output, 404, "Not Found", "No files to archive")
            return
        }

        val headerBlock = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/zip\r\n")
            append("Content-Disposition: attachment; filename=\"LanDrop_Files.zip\"\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headerBlock.toByteArray(Charsets.UTF_8))

        val transferId = UUID.randomUUID().toString()
        val estTotal = files.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        val startTimeMs = System.currentTimeMillis()
        callbacks.onTransferUpdated(
            TransferLogEntry(
                id = transferId,
                fileName = "LanDrop_Files.zip (${files.size} files)",
                clientIp = clientIp,
                direction = TransferDirection.DOWNLOAD,
                bytesTransferred = 0L,
                totalBytes = estTotal,
                status = TransferStatus.IN_PROGRESS,
            )
        )

        var totalWritten = 0L
        try {
            val usedNames = mutableSetOf<String>()
            val bufferedOut = BufferedOutputStream(output, STREAM_CHUNK_BYTES)
            ZipOutputStream(bufferedOut).use { zip ->
                // Level 0 (no compression) avoids CPU heat and streams cleanly
                zip.setLevel(Deflater.NO_COMPRESSION)
                val buffer = ByteArray(STREAM_CHUNK_BYTES)
                for (item in files) {
                    val stream = openStreamForItem(item, 0L)?.first ?: continue
                    var entryName = FormatUtils.sanitizeFileName(item.name)
                    var counter = 1
                    while (!usedNames.add(entryName)) {
                        val base = entryName.substringBeforeLast('.', entryName)
                        val ext = entryName.substringAfterLast('.', "")
                        entryName = if (ext.isNotEmpty() && ext != base) "${base}_$counter.$ext" else "${base}_$counter"
                        counter++
                    }
                    zip.putNextEntry(ZipEntry(entryName))
                    stream.use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            zip.write(buffer, 0, read)
                            totalWritten += read
                            applyThermalPacingIfNeeded(totalWritten, startTimeMs)
                        }
                    }
                    zip.closeEntry()
                }
                zip.finish()
            }
            val elapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
            callbacks.onTransferUpdated(
                TransferLogEntry(
                    id = transferId,
                    fileName = "LanDrop_Files.zip (${files.size} files)",
                    clientIp = clientIp,
                    direction = TransferDirection.DOWNLOAD,
                    bytesTransferred = totalWritten,
                    totalBytes = maxOf(estTotal, totalWritten),
                    status = TransferStatus.COMPLETED,
                    speedBytesPerSec = (totalWritten * 1000L) / elapsedMs,
                )
            )
            callbacks.onDownloadCompleted("zip-bundle", totalWritten)
        } catch (_: Exception) {
            callbacks.onTransferUpdated(
                TransferLogEntry(
                    id = transferId,
                    fileName = "LanDrop_Files.zip",
                    clientIp = clientIp,
                    direction = TransferDirection.DOWNLOAD,
                    bytesTransferred = totalWritten,
                    totalBytes = estTotal,
                    status = TransferStatus.FAILED,
                )
            )
        }
    }

    private fun handleUpload(
        config: ServerConfig,
        queryParams: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        clientIp: String,
    ) {
        if (!config.allowBrowserUploads) {
            writeSimpleResponse(output, 403, "Forbidden", "Browser uploads are disabled on this device")
            return
        }

        val contentLength = headers["content-length"]?.toLongOrNull()
        if (contentLength == null || contentLength < 0L) {
            writeSimpleResponse(output, 411, "Length Required", "Content-Length header is required")
            return
        }

        val rawName = queryParams["name"] ?: "upload_${System.currentTimeMillis()}.bin"
        val cleanName = FormatUtils.sanitizeFileName(rawName)
        val mimeType = headers["content-type"]?.substringBefore(';')?.trim()
            ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: FormatUtils.guessMimeType(cleanName)

        if (!receivedDir.exists()) receivedDir.mkdirs()
        val targetFile = uniqueTargetFile(receivedDir, cleanName)

        val transferId = UUID.randomUUID().toString()
        val startTimeMs = System.currentTimeMillis()
        callbacks.onTransferUpdated(
            TransferLogEntry(
                id = transferId,
                fileName = targetFile.name,
                clientIp = clientIp,
                direction = TransferDirection.UPLOAD,
                bytesTransferred = 0L,
                totalBytes = contentLength,
                status = TransferStatus.IN_PROGRESS,
            )
        )

        var received = 0L
        try {
            BufferedOutputStream(FileOutputStream(targetFile), STREAM_CHUNK_BYTES).use { fos ->
                val buffer = ByteArray(STREAM_CHUNK_BYTES)
                var remaining = contentLength
                var lastEmit = startTimeMs
                while (remaining > 0L) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    fos.write(buffer, 0, read)
                    received += read
                    remaining -= read

                    applyThermalPacingIfNeeded(received, startTimeMs)

                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= PROGRESS_EMIT_INTERVAL_MS) {
                        lastEmit = now
                        val elapsedMs = (now - startTimeMs).coerceAtLeast(1L)
                        callbacks.onTransferUpdated(
                            TransferLogEntry(
                                id = transferId,
                                fileName = targetFile.name,
                                clientIp = clientIp,
                                direction = TransferDirection.UPLOAD,
                                bytesTransferred = received,
                                totalBytes = contentLength,
                                status = TransferStatus.IN_PROGRESS,
                                speedBytesPerSec = (received * 1000L) / elapsedMs,
                            )
                        )
                    }
                }
                fos.flush()
            }

            if (received == contentLength) {
                val totalElapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
                callbacks.onTransferUpdated(
                    TransferLogEntry(
                        id = transferId,
                        fileName = targetFile.name,
                        clientIp = clientIp,
                        direction = TransferDirection.UPLOAD,
                        bytesTransferred = received,
                        totalBytes = contentLength,
                        status = TransferStatus.COMPLETED,
                        speedBytesPerSec = (received * 1000L) / totalElapsedMs,
                    )
                )
                callbacks.onFileReceived(targetFile, targetFile.name, mimeType, clientIp)
                writeSimpleResponse(output, 200, "OK", """{"status":"ok","name":"${targetFile.name}"}""", "application/json")
            } else {
                targetFile.delete()
                callbacks.onTransferUpdated(
                    TransferLogEntry(
                        id = transferId,
                        fileName = targetFile.name,
                        clientIp = clientIp,
                        direction = TransferDirection.UPLOAD,
                        bytesTransferred = received,
                        totalBytes = contentLength,
                        status = TransferStatus.FAILED,
                    )
                )
                writeSimpleResponse(output, 400, "Bad Request", "Incomplete upload received")
            }
        } catch (_: Exception) {
            targetFile.delete()
            callbacks.onTransferUpdated(
                TransferLogEntry(
                    id = transferId,
                    fileName = targetFile.name,
                    clientIp = clientIp,
                    direction = TransferDirection.UPLOAD,
                    bytesTransferred = received,
                    totalBytes = contentLength,
                    status = TransferStatus.FAILED,
                )
            )
            writeSimpleResponse(output, 500, "Internal Server Error", "Failed to save uploaded file")
        }
    }

    private fun writeChannelFully(channel: SocketChannel, bytes: ByteArray) {
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) {
            channel.write(buf)
        }
    }

    private fun openStreamForItem(item: SharedFileItem, offsetBytes: Long): Pair<InputStream, Boolean>? {
        if (item.textContent != null) {
            val bytes = item.textContent.toByteArray(Charsets.UTF_8)
            val safeOffset = offsetBytes.coerceIn(0L, bytes.size.toLong()).toInt()
            return ByteArrayInputStream(bytes, safeOffset, bytes.size - safeOffset) to true
        }
        return streamProvider.openInputStreamAt(item, offsetBytes)
    }

    private fun uniqueTargetFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_($index)$ext")
            index++
        }
        return candidate
    }

    private fun isAuthorized(
        headers: Map<String, String>,
        queryParams: Map<String, String>,
        expectedPin: String,
    ): Boolean {
        if (queryParams["pin"] == expectedPin) return true
        val cookieHeader = headers["cookie"] ?: return false
        return cookieHeader.split(';').any { pair ->
            val kv = pair.trim().split('=', limit = 2)
            kv.size == 2 && kv[0].trim() == "landrop_pin" && kv[1].trim() == expectedPin
        }
    }

    private fun writeHtmlResponse(
        output: OutputStream,
        code: Int,
        reason: String,
        html: String,
        headOnly: Boolean = false,
    ) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
        if (!headOnly) {
            output.write(bytes)
        }
        output.flush()
    }

    private fun writeSimpleResponse(
        output: OutputStream,
        code: Int,
        reason: String,
        body: String,
        contentType: String = "text/plain; charset=utf-8",
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun readAsciiLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString(Charsets.UTF_8.name())
            }
            if (b == '\n'.code) {
                val bytes = baos.toByteArray()
                val len = if (prev == '\r'.code && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.UTF_8)
            }
            baos.write(b)
            prev = b
            if (baos.size() > 16_384) break
        }
        return baos.toString(Charsets.UTF_8.name())
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                result[key] = value
            }
        }
        return result
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val skipBuf = ByteArray(32 * 1024)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val read = input.read(skipBuf, 0, minOf(skipBuf.size.toLong(), remaining).toInt())
                if (read <= 0) break
                remaining -= read
            }
        }
    }
}
