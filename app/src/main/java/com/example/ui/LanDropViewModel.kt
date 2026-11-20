package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.example.model.ReceivedFileItem
import com.example.model.ServerConfig
import com.example.model.ServerState
import com.example.model.SharedFileItem
import com.example.model.TransferLogEntry
import com.example.server.FileServerService
import com.example.server.ServerController
import kotlinx.coroutines.flow.StateFlow

class LanDropViewModel(application: Application) : AndroidViewModel(application) {

    init {
        ServerController.ensureInitialized(application)
    }

    val serverState: StateFlow<ServerState> = ServerController.serverState
    val config: StateFlow<ServerConfig> = ServerController.config
    val sharedFiles: StateFlow<List<SharedFileItem>> = ServerController.sharedFiles
    val receivedFiles: StateFlow<List<ReceivedFileItem>> = ServerController.receivedFiles
    val transferLogs: StateFlow<List<TransferLogEntry>> = ServerController.transferLogs

    fun startServer() {
        FileServerService.start(getApplication())
    }

    fun stopServer() {
        FileServerService.stop(getApplication())
    }

    fun toggleServer() {
        if (serverState.value.isRunning) {
            stopServer()
        } else {
            startServer()
        }
    }

    fun refreshNetwork() {
        ServerController.refreshNetworkInfo()
    }

    fun addPickedUris(uris: List<Uri>): Int {
        if (uris.isEmpty()) return 0
        return ServerController.addUris(getApplication(), uris)
    }

    fun addQuickText(fileName: String, content: String) {
        if (content.isBlank()) return
        ServerController.addTextNote(fileName, content)
    }

    fun removeSharedFile(id: String) {
        ServerController.removeSharedFile(id)
    }

    fun clearSharedFiles() {
        ServerController.clearSharedFiles()
    }

    fun deleteReceivedFile(id: String) {
        ServerController.deleteReceivedFile(id)
    }

    fun clearTransferLogs() {
        ServerController.clearTransferLogs()
    }

    fun saveConfig(newConfig: ServerConfig) {
        ServerController.updateConfig(newConfig)
    }
}
