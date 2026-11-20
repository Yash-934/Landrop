package com.example

import com.example.model.FileCategory
import com.example.model.ServerConfig
import com.example.model.SharedFileItem
import com.example.model.ThermalLevel
import com.example.model.TransferLogEntry
import com.example.server.FileStreamProvider
import com.example.server.LocalHttpFileServer
import com.example.server.ServerCallbacks
import com.example.util.QrCodeGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class LocalHttpServerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun qrCodeGeneratorProducesValidMatrixForLocalUrl() {
        val matrix = QrCodeGenerator.encode("http://192.168.1.42:8080")
        assertNotNull(matrix)
        val size = matrix!!.size
        assertEquals(25, size) // Version 2 (25x25)
        // Verify top-left 7x7 finder pattern outer border
        for (i in 0 until 7) {
            assertTrue(matrix[0][i])
            assertTrue(matrix[6][i])
            assertTrue(matrix[i][0])
            assertTrue(matrix[i][6])
        }
    }

    @Test
    fun localHttpServerServesPortalDownloadsRangesAndUploads() {
        val samplePayload = "Hello from LanDrop local Wi-Fi server! 0123456789"
        val sampleBytes = samplePayload.toByteArray(Charsets.UTF_8)
        val sharedItem = SharedFileItem(
            id = "testfile1",
            name = "hello.txt",
            sizeBytes = sampleBytes.size.toLong(),
            mimeType = "text/plain; charset=utf-8",
            category = FileCategory.TEXT,
            textContent = samplePayload,
        )

        var uploadedFile: File? = null
        val receivedDir = tempFolder.newFolder("received")
        val server = LocalHttpFileServer(
            configProvider = { ServerConfig(serverName = "UnitTestPhone", port = 0, pinEnabled = false, allowBrowserUploads = true) },
            filesProvider = { listOf(sharedItem) },
            streamProvider = object : FileStreamProvider {
                override fun openInputStream(item: SharedFileItem): InputStream? =
                    ByteArrayInputStream(sampleBytes)
            },
            receivedDir = receivedDir,
            callbacks = object : ServerCallbacks {
                override fun onActiveConnectionsChanged(count: Int) {}
                override fun onTransferUpdated(entry: TransferLogEntry) {}
                override fun onDownloadCompleted(fileId: String, bytesSent: Long) {}
                override fun onFileReceived(file: File, originalName: String, mimeType: String, senderIp: String) {
                    uploadedFile = file
                }
            },
        )

        server.start(0) // Bind ephemeral port
        val port = server.boundPort
        try {
            // 1. Test GET / portal HTML
            val portalConn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            assertEquals(200, portalConn.responseCode)
            val html = portalConn.inputStream.bufferedReader().readText()
            assertTrue(html.contains("UnitTestPhone"))
            assertTrue(html.contains("hello.txt"))
            portalConn.disconnect()

            // 2. Test GET /download/testfile1 full download
            val dlConn = URL("http://127.0.0.1:$port/download/testfile1").openConnection() as HttpURLConnection
            assertEquals(200, dlConn.responseCode)
            val downloaded = dlConn.inputStream.bufferedReader().readText()
            assertEquals(samplePayload, downloaded)
            dlConn.disconnect()

            // 3. Test HTTP Byte Range (206 Partial Content)
            val rangeConn = URL("http://127.0.0.1:$port/download/testfile1").openConnection() as HttpURLConnection
            rangeConn.setRequestProperty("Range", "bytes=0-4")
            assertEquals(206, rangeConn.responseCode)
            val partial = rangeConn.inputStream.bufferedReader().readText()
            assertEquals("Hello", partial)
            rangeConn.disconnect()

            // 4. Test POST /upload?name=from_pc.txt
            val uploadConn = URL("http://127.0.0.1:$port/upload?name=from_pc.txt").openConnection() as HttpURLConnection
            uploadConn.requestMethod = "POST"
            uploadConn.doOutput = true
            val uploadData = "Sent from laptop browser".toByteArray(Charsets.UTF_8)
            uploadConn.setFixedLengthStreamingMode(uploadData.size)
            uploadConn.setRequestProperty("Content-Type", "text/plain")
            uploadConn.outputStream.use { it.write(uploadData) }
            assertEquals(200, uploadConn.responseCode)
            uploadConn.disconnect()

            assertNotNull(uploadedFile)
            assertEquals("Sent from laptop browser", uploadedFile!!.readText())
        } finally {
            server.stop()
        }
    }

    @Test
    fun localHttpServerZeroCopyFileChannelFullAndRangeDownload() {
        // 600 KB deterministic binary payload (> 256 KB ZERO_COPY_CHUNK_BYTES to exercise multi-chunk transferTo)
        val payload = ByteArray(600 * 1024) { index -> ((index * 31 + 7) and 0xFF).toByte() }
        val sourceFile = tempFolder.newFile("sample_video.bin")
        sourceFile.writeBytes(payload)

        val sharedItem = SharedFileItem(
            id = "binfile1",
            name = "sample_video.bin",
            sizeBytes = payload.size.toLong(),
            mimeType = "application/octet-stream",
            category = FileCategory.OTHER,
        )

        var completedBytes = 0L
        val receivedDir = tempFolder.newFolder("received_zc")
        val server = LocalHttpFileServer(
            configProvider = { ServerConfig(serverName = "ZeroCopyPhone", port = 0, coolMode = false) },
            filesProvider = { listOf(sharedItem) },
            streamProvider = object : FileStreamProvider {
                override fun openInputStream(item: SharedFileItem): InputStream =
                    FileInputStream(sourceFile)
            },
            receivedDir = receivedDir,
            callbacks = object : ServerCallbacks {
                override fun onActiveConnectionsChanged(count: Int) {}
                override fun onTransferUpdated(entry: TransferLogEntry) {}
                override fun onDownloadCompleted(fileId: String, bytesSent: Long) {
                    completedBytes = bytesSent
                }
                override fun onFileReceived(file: File, originalName: String, mimeType: String, senderIp: String) {}
            },
            thermalLevelProvider = { ThermalLevel.COOL },
        )

        server.start(0)
        val port = server.boundPort
        try {
            // 1. Full zero-copy download
            val fullConn = URL("http://127.0.0.1:$port/download/binfile1").openConnection() as HttpURLConnection
            assertEquals(200, fullConn.responseCode)
            val fullBytes = fullConn.inputStream.use { it.readBytes() }
            fullConn.disconnect()
            assertArrayEquals(payload, fullBytes)
            assertEquals(payload.size.toLong(), completedBytes)

            // 2. Multi-chunk 206 byte range zero-copy download
            val start = 100_000
            val end = 399_999
            val rangeConn = URL("http://127.0.0.1:$port/download/binfile1").openConnection() as HttpURLConnection
            rangeConn.setRequestProperty("Range", "bytes=$start-$end")
            assertEquals(206, rangeConn.responseCode)
            val rangeBytes = rangeConn.inputStream.use { it.readBytes() }
            rangeConn.disconnect()
            assertArrayEquals(payload.copyOfRange(start, end + 1), rangeBytes)
        } finally {
            server.stop()
        }
    }
}
