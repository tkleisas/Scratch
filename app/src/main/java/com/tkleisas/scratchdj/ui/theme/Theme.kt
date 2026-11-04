package com.tkleisas.scratchdj.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

enum class ScratchThemeName(val label: String) {
    Classic("Classic"),
    HighContrast("High Contrast"),
    Midnight("Midnight"),
    Ember("Ember"),
}

data class ScratchPalette(
    val accentA: Color,
    val accentB: Color,
    val masterAccent: Color,
    val background: Color,
    val backgroundAlt: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val outline: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val onAccent: Color,
    val onSecondary: Color,
    val error: Color,
)

private val ClassicPalette = ScratchPalette(
    accentA = Color(0xFFFFB300),
    accentB = Color(0xFF4DD0E1),
    masterAccent = Color(0xFFE7E9EC),
    background = Color(0xFF090B0E),
    backgroundAlt = Color(0xFF11151B),
    surface = Color(0xFF14181E),
    surfaceRaised = Color(0xFF1D232B),
    outline = Color(0xFF49515C),
    onBackground = Color(0xFFE7E9EC),
    onSurface = Color(0xFFE7E9EC),
    onSurfaceVariant = Color(0xFFB4BBC4),
    onAccent = Color(0xFF1B1300),
    onSecondary = Color(0xFF002022),
    error = Color(0xFFFF6B6B),
)

private val HighContrastPalette = ScratchPalette(
    accentA = Color(0xFFFFE600),
    accentB = Color(0xFF00E5FF),
    masterAccent = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    backgroundAlt = Color(0xFF050505),
    surface = Color(0xFF070707),
    surfaceRaised = Color(0xFF121212),
    outline = Color(0xFFB8B8B8),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFE0E0E0),
    onAccent = Color(0xFF000000),
    onSecondary = Color(0xFF000000),
    error = Color(0xFFFF5252),
)

private val MidnightPalette = ScratchPalette(
    accentA = Color(0xFFFF7AB6),
    accentB = Color(0xFF6DA8FF),
    masterAccent = Color(0xFFEDEFFF),
    background = Color(0xFF050812),
    backgroundAlt = Color(0xFF0A1020),
    surface = Color(0xFF0C1220),
    surfaceRaised = Color(0xFF151E33),
    outline = Color(0xFF56627E),
    onBackground = Color(0xFFF1F4FF),
    onSurface = Color(0xFFF1F4FF),
    onSurfaceVariant = Color(0xFFB8C2DA),
    onAccent = Color(0xFF260A1A),
    onSecondary = Color(0xFF041225),
    error = Color(0xFFFF6B8A),
)

private val EmberPalette = ScratchPalette(
    accentA = Color(0xFFFF7A45),
    accentB = Color(0xFF7BE0C3),
    masterAccent = Color(0xFFFFE9D6),
    background = Color(0xFF0C0705),
    backgroundAlt = Color(0xFF150C08),
    surface = Color(0xFF18100C),
    surfaceRaised = Color(0xFF261811),
    outline = Color(0xFF8A6A56),
    onBackground = Color(0xFFFFF3EA),
    onSurface = Color(0xFFFFF3EA),
    onSurfaceVariant = Color(0xFFD8BDA9),
    onAccent = Color(0xFF1A0800),
    onSecondary = Color(0xFF002018),
    error = Color(0xFFFF5C5C),
)

fun paletteFor(theme: ScratchThemeName): ScratchPalette = when (theme) {
    ScratchThemeName.Classic -> ClassicPalette
    ScratchThemeName.HighContrast -> HighContrastPalette
    ScratchThemeName.Midnight -> MidnightPalette
    ScratchThemeName.Ember -> EmberPalette
}

private val currentPalette = mutableStateOf(ClassicPalette)

val DeckAccentA: Color get() = currentPalette.value.accentA
val DeckAccentB: Color get() = currentPalette.value.accentB
val MasterAccent: Color get() = currentPalette.value.masterAccent
val ScratchBackground: Color get() = currentPalette.value.background
val ScratchBackgroundAlt: Color get() = currentPalette.value.backgroundAlt
val ScratchSurface: Color get() = currentPalette.value.surface
val ScratchSurfaceRaised: Color get() = currentPalette.value.surfaceRaised
val ScratchOutline: Color get() = currentPalette.value.outline
val ScratchOnAccent: Color get() = currentPalette.value.onAccent

private fun ScratchPalette.toColorScheme() = darkColorScheme(
    primary = accentA,
    onPrimary = onAccent,
    secondary = accentB,
    onSecondary = onSecondary,
    background = background,
    onBackground = onBackground,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceRaised,
    onSurfaceVariant = onSurfaceVariant,
    outline = outline,
    error = error,
)

@Composable
fun ScratchTheme(
    theme: ScratchThemeName = ScratchThemeName.Classic,
    content: @Composable () -> Unit,
) {
    val palette = paletteFor(theme)
    currentPalette.value = palette
    val colorScheme = remember(palette) { palette.toColorScheme() }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
