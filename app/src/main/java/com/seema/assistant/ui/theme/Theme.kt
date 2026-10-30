package com.seema.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val SeemaColorScheme = darkColorScheme(
    primary = JarvisCyan,
    onPrimary = DeepSpace,
    secondary = TextSecondary,
    onSecondary = DeepSpace,
    tertiary = SpeakingGreen,
    onTertiary = DeepSpace,
    background = DeepSpace,
    onBackground = JarvisCyan,
    surface = PanelSurface,
    onSurface = TextSecondary,
    surfaceVariant = PanelSurface,
    onSurfaceVariant = TextSecondary,
    outline = JarvisCyan.copy(alpha = 0.4f),
    error = AlertRed,
    onError = DeepSpace
)

@Composable
fun SeemaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SeemaColorScheme,
        typography = SeemaTypography,
        content = content
    )
}
