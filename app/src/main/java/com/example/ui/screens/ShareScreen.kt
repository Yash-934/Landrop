package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.ServerConfig
import com.example.model.ServerState
import com.example.model.SharedFileItem
import com.example.ui.components.PulsingStatusDot
import com.example.ui.components.categoryIcon
import com.example.ui.components.categoryTint
import com.example.ui.theme.MonoFontFamily
import com.example.util.FormatUtils

@Composable
fun ShareScreen(
    serverState: ServerState,
    config: ServerConfig,
    sharedFiles: List<SharedFileItem>,
    onToggleServer: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onCopyUrl: (String) -> Unit,
    onShareUrl: (String) -> Unit,
    onShowQr: (String) -> Unit,
    onPickAnyFiles: () -> Unit,
    onPickMedia: () -> Unit,
    onAddQuickText: () -> Unit,
    onRemoveFile: (String) -> Unit,
    onClearAllFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalSharedBytes = sharedFiles.sumOf { it.sizeBytes.coerceAtLeast(0L) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        item(key = "hero_server_card") {
            ServerHeroCard(
                serverState = serverState,
                config = config,
                fileCount = sharedFiles.size,
                onToggleServer = onToggleServer,
                onRefreshNetwork = onRefreshNetwork,
                onCopyUrl = onCopyUrl,
                onShareUrl = onShareUrl,
                onShowQr = onShowQr,
            )
        }

        item(key = "quick_add_row") {
            QuickAddRow(
                onPickAnyFiles = onPickAnyFiles,
                onPickMedia = onPickMedia,
                onAddQuickText = onAddQuickText,
            )
        }

        item(key = "shared_files_header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.section_shared_files),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = if (sharedFiles.isEmpty()) {
                            "0 files queued"
                        } else {
                            "${sharedFiles.size} file(s) • ${FormatUtils.formatBytes(totalSharedBytes)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (sharedFiles.isNotEmpty()) {
                    TextButton(
                        onClick = onClearAllFiles,
                        modifier = Modifier.testTag("clear_all_files_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_clear_all))
                    }
                }
            }
        }

        if (sharedFiles.isEmpty()) {
            item(key = "empty_state") {
                EmptySharedFilesCard(onPickAnyFiles = onPickAnyFiles)
            }
        } else {
            items(items = sharedFiles, key = { it.id }) { item ->
                SharedFileRowCard(
                    item = item,
                    onRemove = { onRemoveFile(item.id) },
                )
            }
        }

        item(key = "bottom_spacer") {
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ServerHeroCard(
    serverState: ServerState,
    config: ServerConfig,
    fileCount: Int,
    onToggleServer: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onCopyUrl: (String) -> Unit,
    onShareUrl: (String) -> Unit,
    onShowQr: (String) -> Unit,
) {
    val isLive = serverState.isRunning
    val primaryGlow = MaterialTheme.colorScheme.primary
    val secondaryGlow = MaterialTheme.colorScheme.secondary
    val serverUrl = serverState.serverUrl

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = if (isLive) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        tonalElevation = if (isLive) 4.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("server_hero_card"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    if (isLive) {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    primaryGlow.copy(alpha = 0.16f),
                                    secondaryGlow.copy(alpha = 0.05f),
                                    primaryGlow.copy(alpha = 0f),
                                ),
                                center = Offset(size.width * 0.85f, size.height * 0.12f),
                                radius = size.maxDimension * 0.85f,
                            )
                        )
                    }
                }
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Status + Wi-Fi indicator row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (isLive) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            PulsingStatusDot(isLive = isLive, size = 14.dp)
                            Text(
                                text = stringResource(
                                    if (isLive) R.string.server_status_online else R.string.server_status_offline
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isLive) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Icon(
                                    imageVector = if (serverState.networkInfo.isConnected) {
                                        Icons.Rounded.Wifi
                                    } else {
                                        Icons.Rounded.WifiOff
                                    },
                                    contentDescription = null,
                                    tint = if (serverState.networkInfo.isConnected) {
                                        MaterialTheme.colorScheme.secondary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = serverState.networkInfo.interfaceLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }

                        IconButton(
                            onClick = onRefreshNetwork,
                            modifier = Modifier.testTag("refresh_network_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Refresh local IP address",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                // URL Address Console Box
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = if (isLive && serverUrl != null) {
                                stringResource(R.string.server_open_browser_hint)
                            } else if (!serverState.networkInfo.isConnected) {
                                stringResource(R.string.server_no_wifi_warning)
                            } else {
                                stringResource(R.string.server_not_started_hint)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Text(
                            text = when {
                                isLive && serverUrl != null -> serverUrl
                                serverUrl != null -> serverUrl
                                else -> "http://0.0.0.0:${serverState.port}"
                            },
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (isLive && serverUrl != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("server_url_text"),
                        )

                        if (config.pinEnabled) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 2.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "PIN Protected • Code: ${config.accessPin}",
                                    fontFamily = MonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }

                // Error banner if server failed to bind
                serverState.lastError?.let { errorMsg ->
                    Text(
                        text = errorMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // Live URL Action buttons when server is active
                AnimatedVisibility(
                    visible = isLive && serverUrl != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    val activeUrl = serverUrl.orEmpty()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FilledTonalButton(
                            onClick = { onCopyUrl(activeUrl) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("copy_url_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = stringResource(R.string.action_copy_url),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        FilledTonalButton(
                            onClick = { onShowQr(activeUrl) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("show_qr_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.QrCode2,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = stringResource(R.string.action_show_qr),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        OutlinedButton(
                            onClick = { onShareUrl(activeUrl) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier.testTag("share_url_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = stringResource(R.string.action_share_url),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                // Primary Power Toggle CTA
                Button(
                    onClick = onToggleServer,
                    shape = RoundedCornerShape(14.dp),
                    colors = if (isLive) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    },
                    contentPadding = PaddingValues(vertical = 15.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_toggle_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(if (isLive) R.string.server_stop else R.string.server_start),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Compact live telemetry footer
                if (isLive) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                    ) {
                        TelemetryChip(label = "Files", value = "$fileCount")
                        TelemetryChip(label = "Downloads", value = "${serverState.totalDownloadsServed}")
                        TelemetryChip(
                            label = "Sent",
                            value = FormatUtils.formatBytes(serverState.totalBytesServed),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontFamily = MonoFontFamily,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuickAddRow(
    onPickAnyFiles: () -> Unit,
    onPickMedia: () -> Unit,
    onAddQuickText: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        QuickActionTile(
            icon = Icons.Rounded.FolderOpen,
            label = stringResource(R.string.action_pick_files),
            accentColor = MaterialTheme.colorScheme.primary,
            onClick = onPickAnyFiles,
            modifier = Modifier
                .weight(1f)
                .testTag("pick_any_files_button"),
        )
        QuickActionTile(
            icon = Icons.Rounded.PhotoLibrary,
            label = stringResource(R.string.action_pick_media),
            accentColor = MaterialTheme.colorScheme.secondary,
            onClick = onPickMedia,
            modifier = Modifier
                .weight(1f)
                .testTag("pick_media_button"),
        )
        QuickActionTile(
            icon = Icons.Rounded.Notes,
            label = stringResource(R.string.action_add_text),
            accentColor = MaterialTheme.colorScheme.tertiary,
            onClick = onAddQuickText,
            modifier = Modifier
                .weight(1f)
                .testTag("add_quick_text_button"),
        )
    }
}

@Composable
private fun QuickActionTile(
    icon: ImageVector,
    label: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(alpha = 0.14f)),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptySharedFilesCard(onPickAnyFiles: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(20.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_empty_vault_1795194564453),
                contentDescription = "Wireless Wi-Fi file sharing illustration",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(172.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.empty_files_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.empty_files_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = onPickAnyFiles,
                modifier = Modifier.testTag("empty_pick_files_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.AddCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Select Files to Share")
            }
        }
    }
}

@Composable
private fun SharedFileRowCard(
    item: SharedFileItem,
    onRemove: () -> Unit,
) {
    val tint = categoryTint(item.category)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("shared_file_card_${item.id}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.14f)),
            ) {
                Icon(
                    imageVector = categoryIcon(item.category),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = FormatUtils.formatBytes(item.sizeBytes),
                        fontFamily = MonoFontFamily,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.downloadCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DownloadDone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "${item.downloadCount}×",
                                fontFamily = MonoFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.testTag("remove_file_button_${item.id}"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove ${item.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
