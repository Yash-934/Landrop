package com.example.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.model.ReceivedFileItem
import com.example.ui.components.PulsingStatusDot
import com.example.ui.components.QrCodeDialog
import com.example.ui.components.QuickTextDialog
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ShareScreen
import com.example.ui.screens.TransfersScreen
import kotlinx.coroutines.launch

private enum class AppTab(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String,
) {
    SHARE(R.string.tab_share, Icons.Rounded.WifiTethering, Icons.Outlined.WifiTethering, "nav_share"),
    TRANSFERS(R.string.tab_transfers, Icons.Rounded.SwapHoriz, Icons.Outlined.SwapHoriz, "nav_transfers"),
    SETTINGS(R.string.tab_settings, Icons.Rounded.Settings, Icons.Outlined.Settings, "nav_settings"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanDropApp(
    viewModel: LanDropViewModel = viewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val sharedFiles by viewModel.sharedFiles.collectAsStateWithLifecycle()
    val receivedFiles by viewModel.receivedFiles.collectAsStateWithLifecycle()
    val transferLogs by viewModel.transferLogs.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(AppTab.SHARE) }
    var qrUrlToShow by rememberSaveable { mutableStateOf<String?>(null) }
    var showQuickTextDialog by rememberSaveable { mutableStateOf(false) }

    // Return to primary Share tab when pressing Back on secondary tabs
    BackHandler(enabled = currentTab != AppTab.SHARE) {
        currentTab = AppTab.SHARE
    }

    fun showMessage(msg: String) {
        coroutineScope.launch {
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Zero-permission SAF document picker for ANY file type
    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            val added = viewModel.addPickedUris(uris)
            if (added > 0) {
                showMessage(context.getString(R.string.msg_files_added, added))
            }
        },
    )

    // Zero-permission Android Photo Picker for quick gallery selection
    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = { uris ->
            val added = viewModel.addPickedUris(uris)
            if (added > 0) {
                showMessage(context.getString(R.string.msg_files_added, added))
            }
        },
    )

    // Runtime notification permission on Android 13+ before launching foreground server
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {
            // Start server regardless so user is never blocked even if notifications are declined
            viewModel.startServer()
        },
    )

    fun handleServerToggle() {
        if (serverState.isRunning) {
            viewModel.stopServer()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        viewModel.startServer()
    }

    fun copyToClipboard(url: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("LanDrop URL", url))
        showMessage(context.getString(R.string.msg_url_copied))
    }

    fun shareUrl(url: String) {
        val text = if (config.pinEnabled) {
            "Download my shared files over Wi-Fi: $url (PIN: ${config.accessPin})"
        } else {
            "Download my shared files over Wi-Fi: $url"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share Local Server Link"))
    }

    fun openReceivedFile(item: ReceivedFileItem) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                item.file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open ${item.name}"))
        } catch (_: Exception) {
            showMessage("No app found to open ${item.name}")
        }
    }

    fun shareReceivedFile(item: ReceivedFileItem) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                item.file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share ${item.name}"))
        } catch (_: Exception) {
            showMessage("Could not share ${item.name}")
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            if (serverState.isRunning) {
                                Spacer(Modifier.width(8.dp))
                                PulsingStatusDot(isLive = true, size = 12.dp)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
            },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        AppTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    TabIconWithBadge(
                                        tab = tab,
                                        selected = selected,
                                        receivedCount = receivedFiles.size,
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag(tab.tag),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        AppTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    TabIconWithBadge(
                                        tab = tab,
                                        selected = selected,
                                        receivedCount = receivedFiles.size,
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag(tab.tag),
                            )
                        }
                    }
                }

                Box(
                    contentAlignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 680.dp),
                    ) {
                        when (currentTab) {
                            AppTab.SHARE -> ShareScreen(
                                serverState = serverState,
                                config = config,
                                sharedFiles = sharedFiles,
                                onToggleServer = ::handleServerToggle,
                                onRefreshNetwork = { viewModel.refreshNetwork() },
                                onCopyUrl = ::copyToClipboard,
                                onShareUrl = ::shareUrl,
                                onShowQr = { qrUrlToShow = it },
                                onPickAnyFiles = { documentPicker.launch(arrayOf("*/*")) },
                                onPickMedia = {
                                    mediaPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                },
                                onAddQuickText = { showQuickTextDialog = true },
                                onRemoveFile = { viewModel.removeSharedFile(it) },
                                onClearAllFiles = { viewModel.clearSharedFiles() },
                            )

                            AppTab.TRANSFERS -> TransfersScreen(
                                receivedFiles = receivedFiles,
                                transferLogs = transferLogs,
                                onOpenReceived = ::openReceivedFile,
                                onShareReceived = ::shareReceivedFile,
                                onDeleteReceived = { viewModel.deleteReceivedFile(it) },
                                onClearLogs = { viewModel.clearTransferLogs() },
                            )

                            AppTab.SETTINGS -> SettingsScreen(
                                config = config,
                                onSaveConfig = { updated ->
                                    viewModel.saveConfig(updated)
                                    showMessage(context.getString(R.string.settings_saved_toast))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    qrUrlToShow?.let { url ->
        QrCodeDialog(
            url = url,
            pin = if (config.pinEnabled) config.accessPin else null,
            onCopyUrl = { copyToClipboard(url) },
            onDismiss = { qrUrlToShow = null },
        )
    }

    if (showQuickTextDialog) {
        QuickTextDialog(
            onAdd = { name, content ->
                viewModel.addQuickText(name, content)
                showMessage("Added $name to server")
            },
            onDismiss = { showQuickTextDialog = false },
        )
    }
}

@Composable
private fun TabIconWithBadge(
    tab: AppTab,
    selected: Boolean,
    receivedCount: Int,
) {
    val icon = if (selected) tab.selectedIcon else tab.unselectedIcon
    if (tab == AppTab.TRANSFERS && receivedCount > 0) {
        BadgedBox(
            badge = {
                Badge {
                    Text(if (receivedCount > 99) "99+" else receivedCount.toString())
                }
            }
        ) {
            Icon(imageVector = icon, contentDescription = stringResource(tab.labelRes))
        }
    } else {
        Icon(imageVector = icon, contentDescription = stringResource(tab.labelRes))
    }
}
