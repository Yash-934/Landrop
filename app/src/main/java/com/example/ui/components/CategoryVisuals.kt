package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
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

@Composable
fun PulsingStatusDot(
    isLive: Boolean,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

    if (!isLive) {
        Canvas(modifier = modifier.size(size)) {
            drawCircle(color = idleColor, radius = this.size.minDimension * 0.34f)
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "signal_pulse")
    val pulseRadius by transition.animateFloat(
        initialValue = 0.32f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse_radius",
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse_alpha",
    )

    Canvas(modifier = modifier.size(size)) {
        val minDim = this.size.minDimension
        drawCircle(
            color = activeColor.copy(alpha = pulseAlpha),
            radius = minDim * pulseRadius,
        )
        drawCircle(
            color = activeColor,
            radius = minDim * 0.28f,
        )
    }
}
