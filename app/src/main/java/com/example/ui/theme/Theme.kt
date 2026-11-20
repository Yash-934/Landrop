package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val DarkColorScheme = darkColorScheme(
    primary = SignalTeal,
    onPrimary = Color(0xFF00201B),
    primaryContainer = SignalTealContainerDark,
    onPrimaryContainer = Color(0xFF7CFCE4),
    secondary = SignalCyan,
    onSecondary = Color(0xFF00253A),
    secondaryContainer = SignalCyanContainerDark,
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = SignalAmber,
    onTertiary = Color(0xFF2B1800),
    tertiaryContainer = SignalAmberContainerDark,
    onTertiaryContainer = Color(0xFFFFDF94),
    background = ObsidianBg,
    onBackground = ObsidianOnSurface,
    surface = ObsidianSurface,
    onSurface = ObsidianOnSurface,
    surfaceVariant = ObsidianSurfaceHigh,
    onSurfaceVariant = ObsidianOnSurfaceVariant,
    surfaceContainerLowest = ObsidianBg,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurface,
    surfaceContainerHigh = ObsidianSurfaceHigh,
    surfaceContainerHighest = ObsidianSurfaceHighest,
    outline = ObsidianOutline,
    outlineVariant = Color(0xFF1F2B44),
    error = ErrorCoral,
    onError = Color(0xFF33000B),
    errorContainer = ErrorCoralContainerDark,
    onErrorContainer = Color(0xFFFFD9DF),
)

private val LightColorScheme = lightColorScheme(
    primary = SignalTealDark,
    onPrimary = Color.White,
    primaryContainer = SignalTealContainerLight,
    onPrimaryContainer = Color(0xFF003730),
    secondary = SignalCyanDark,
    onSecondary = Color.White,
    secondaryContainer = SignalCyanContainerLight,
    onSecondaryContainer = Color(0xFF075985),
    tertiary = SignalAmberDark,
    onTertiary = Color.White,
    tertiaryContainer = SignalAmberContainerLight,
    onTertiaryContainer = Color(0xFF5C3400),
    background = CeramicBg,
    onBackground = CeramicOnSurface,
    surface = CeramicSurface,
    onSurface = CeramicOnSurface,
    surfaceVariant = CeramicSurfaceHigh,
    onSurfaceVariant = CeramicOnSurfaceVariant,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = CeramicBg,
    surfaceContainer = CeramicSurface,
    surfaceContainerHigh = CeramicSurfaceHigh,
    surfaceContainerHighest = CeramicSurfaceHighest,
    outline = CeramicOutline,
    outlineVariant = Color(0xFFE2E8F0),
    error = ErrorCoralLight,
    onError = Color.White,
    errorContainer = ErrorCoralContainerLight,
    onErrorContainer = Color(0xFF7F1D1D),
)

val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun LanDropTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content,
    )
}
