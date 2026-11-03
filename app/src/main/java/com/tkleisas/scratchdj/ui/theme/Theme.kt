package com.tkleisas.scratchdj.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DeckAccentA = Color(0xFFFFB300)
val DeckAccentB = Color(0xFF4DD0E1)
val MasterAccent = Color(0xFFE7E9EC)
val ScratchBackground = Color(0xFF090B0E)
val ScratchSurface = Color(0xFF14181E)
val ScratchSurfaceRaised = Color(0xFF1D232B)
val ScratchOutline = Color(0xFF49515C)

private val ScratchColorScheme = darkColorScheme(
    primary = DeckAccentA,
    onPrimary = Color(0xFF1B1300),
    secondary = DeckAccentB,
    onSecondary = Color(0xFF002022),
    background = ScratchBackground,
    onBackground = Color(0xFFE7E9EC),
    surface = ScratchSurface,
    onSurface = Color(0xFFE7E9EC),
    surfaceVariant = ScratchSurfaceRaised,
    onSurfaceVariant = Color(0xFFB4BBC4),
    outline = ScratchOutline,
    error = Color(0xFFFF6B6B),
)

@Composable
fun ScratchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ScratchColorScheme, content = content)
}
