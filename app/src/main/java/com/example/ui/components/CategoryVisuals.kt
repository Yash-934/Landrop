package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.FileCategory

fun categoryIcon(category: FileCategory): ImageVector = when (category) {
    FileCategory.IMAGE -> Icons.Rounded.Image
    FileCategory.VIDEO -> Icons.Rounded.VideoFile
    FileCategory.AUDIO -> Icons.Rounded.AudioFile
    FileCategory.DOCUMENT -> Icons.Rounded.Description
    FileCategory.ARCHIVE -> Icons.Rounded.FolderZip
    FileCategory.APP -> Icons.Rounded.Android
    FileCategory.TEXT -> Icons.Rounded.Notes
    FileCategory.OTHER -> Icons.Rounded.InsertDriveFile
}

@Composable
fun categoryTint(category: FileCategory): Color = when (category) {
    FileCategory.IMAGE -> MaterialTheme.colorScheme.primary
    FileCategory.VIDEO -> MaterialTheme.colorScheme.secondary
    FileCategory.AUDIO -> MaterialTheme.colorScheme.tertiary
    FileCategory.DOCUMENT -> MaterialTheme.colorScheme.secondary
    FileCategory.ARCHIVE -> MaterialTheme.colorScheme.tertiary
    FileCategory.APP -> MaterialTheme.colorScheme.primary
    FileCategory.TEXT -> MaterialTheme.colorScheme.primary
    FileCategory.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Energy-efficient status indicator dot with a soft static halo when live.
 * Avoids continuous 60/120fps Compose infinite animations that keep GPU/display warm while idle.
 */
@Composable
fun PulsingStatusDot(
    isLive: Boolean,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

    Canvas(modifier = modifier.size(size)) {
        val minDim = this.size.minDimension
        if (isLive) {
            drawCircle(
                color = activeColor.copy(alpha = 0.24f),
                radius = minDim * 0.48f,
            )
            drawCircle(
                color = activeColor,
                radius = minDim * 0.27f,
            )
        } else {
            drawCircle(
                color = idleColor,
                radius = minDim * 0.30f,
            )
        }
    }
}
