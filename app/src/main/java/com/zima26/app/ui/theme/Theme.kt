package com.zima26.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val VintageColors = darkColorScheme(
    primary = Sepia,
    onPrimary = Carbon,
    primaryContainer = SepiaDark,
    onPrimaryContainer = Paper,
    secondary = Brass,
    onSecondary = Carbon,
    tertiary = PaperDim,
    background = Carbon,
    onBackground = Paper,
    surface = CarbonLight,
    onSurface = Paper,
    surfaceVariant = CarbonLighter,
    onSurfaceVariant = PaperDim,
    surfaceContainerHigh = CarbonLight,
    surfaceContainerHighest = CarbonLighter,
    outline = SepiaDark,
    error = Safelight,
)

/** Tema oscuro vintage, siempre igual (no depende del modo claro/oscuro del sistema). */
@Composable
fun Zima26Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VintageColors,
        typography = VintageTypography,
        content = content
    )
}
