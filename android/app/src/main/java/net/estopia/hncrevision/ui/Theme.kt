package net.estopia.hncrevision.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF173D37)
private val Paper = Color(0xFFF5F5EF)
private val Line = Color(0xFFDCE2D9)
private val Muted = Color(0xFF6D7C75)
private val Accent = Color(0xFFD5E9A7)

/** Site colours that Material's scheme has no slot for. */
@Immutable
data class HncColors(
    val tagBackground: Color,
    val tagText: Color,
    val muted: Color,
    val line: Color,
    val correctBackground: Color,
    val correctBorder: Color,
    val wrongBackground: Color,
    val wrongBorder: Color,
    val progress: Color,
    val progressTrack: Color,
    val selectedOption: Color,
    val note: Color,
    val card: Color,
)

private val LightExtra = HncColors(
    tagBackground = Color(0xFFEDF3E6),
    tagText = Color(0xFF486342),
    muted = Muted,
    line = Line,
    correctBackground = Color(0xFFEDF6E7),
    correctBorder = Color(0xFF578344),
    wrongBackground = Color(0xFFFFF0EB),
    wrongBorder = Color(0xFFAA5143),
    progress = Color(0xFF87A867),
    progressTrack = Color(0xFFE0E5D9),
    selectedOption = Color(0xFFF0F4E7),
    note = Color(0xFF785139),
    card = Color.White,
)

private val DarkExtra = HncColors(
    tagBackground = Color(0xFF24493F),
    tagText = Color(0xFFCFE3B5),
    muted = Color(0xFFA9B8B0),
    line = Color(0xFF2E5049),
    correctBackground = Color(0xFF1F4430),
    correctBorder = Color(0xFF8CC271),
    wrongBackground = Color(0xFF4A2A24),
    wrongBorder = Color(0xFFE59180),
    progress = Color(0xFF9CBD7C),
    progressTrack = Color(0xFF2B4A43),
    selectedOption = Color(0xFF22463E),
    note = Color(0xFFE2BC9B),
    card = Color(0xFF173D37),
)

private fun lightScheme(dyslexia: Boolean) = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Accent,
    onPrimaryContainer = Ink,
    secondary = Color(0xFF486342),
    onSecondary = Color.White,
    secondaryContainer = Accent,
    onSecondaryContainer = Ink,
    tertiary = Color(0xFF578344),
    background = if (dyslexia) Color(0xFFF7F1E3) else Paper,
    onBackground = Ink,
    surface = if (dyslexia) Color(0xFFFFFAF0) else Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFECEFE6),
    onSurfaceVariant = Muted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F2EA),
    surfaceContainer = Color(0xFFECEFE6),
    surfaceContainerHigh = Color(0xFFE8ECE2),
    surfaceContainerHighest = Color(0xFFE2E7DC),
    outline = Color(0xFF8A9A92),
    outlineVariant = Line,
    error = Color(0xFFAA5143),
    surfaceTint = Color.Transparent,
)

private fun darkScheme(dyslexia: Boolean) = darkColorScheme(
    primary = Color(0xFFEEF2EA),
    onPrimary = Ink,
    primaryContainer = Accent,
    onPrimaryContainer = Ink,
    secondary = Accent,
    onSecondary = Ink,
    secondaryContainer = Accent,
    onSecondaryContainer = Ink,
    tertiary = Color(0xFF9CBD7C),
    background = if (dyslexia) Color(0xFF1A211C) else Color(0xFF0F1F1C),
    onBackground = Color(0xFFEEF2EA),
    surface = Color(0xFF173D37),
    onSurface = Color(0xFFEEF2EA),
    surfaceVariant = Color(0xFF1E3A34),
    onSurfaceVariant = Color(0xFFA9B8B0),
    surfaceContainerLowest = Color(0xFF0B1714),
    surfaceContainerLow = Color(0xFF132925),
    surfaceContainer = Color(0xFF16302B),
    surfaceContainerHigh = Color(0xFF1B3832),
    surfaceContainerHighest = Color(0xFF22423B),
    outline = Color(0xFF6F8A82),
    outlineVariant = Color(0xFF2E5049),
    error = Color(0xFFE59180),
    surfaceTint = Color.Transparent,
)

val LocalHncColors = staticCompositionLocalOf { LightExtra }

/** Sizes are in sp so they follow the system font scale. */
private fun baseTypography(): Typography {
    val t = Typography()
    return t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 31.sp, letterSpacing = (-0.3).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(fontSize = 17.sp, lineHeight = 27.sp),
        bodyMedium = t.bodyMedium.copy(lineHeight = 22.sp),
        labelSmall = t.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
    )
}

/** Dyslexia reading mode: plain sans, ~0.05em letter spacing and ~1.6 line spacing everywhere. */
private fun TextStyle.dyslexic() = copy(
    fontFamily = FontFamily.SansSerif,
    letterSpacing = 0.05.em,
    lineHeight = 1.6.em,
)

private fun dyslexiaTypography(t: Typography) = Typography(
    displayLarge = t.displayLarge.dyslexic(), displayMedium = t.displayMedium.dyslexic(), displaySmall = t.displaySmall.dyslexic(),
    headlineLarge = t.headlineLarge.dyslexic(), headlineMedium = t.headlineMedium.dyslexic(), headlineSmall = t.headlineSmall.dyslexic(),
    titleLarge = t.titleLarge.dyslexic(), titleMedium = t.titleMedium.dyslexic(), titleSmall = t.titleSmall.dyslexic(),
    bodyLarge = t.bodyLarge.dyslexic(), bodyMedium = t.bodyMedium.dyslexic(), bodySmall = t.bodySmall.dyslexic(),
    labelLarge = t.labelLarge.dyslexic(), labelMedium = t.labelMedium.dyslexic(), labelSmall = t.labelSmall.dyslexic(),
)

@Composable
fun HncTheme(dyslexia: Boolean, dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (dark) darkScheme(dyslexia) else lightScheme(dyslexia)
    val extra = when {
        dark -> DarkExtra
        dyslexia -> LightExtra.copy(card = Color(0xFFFFFAF0))
        else -> LightExtra
    }
    val typography = baseTypography().let { if (dyslexia) dyslexiaTypography(it) else it }
    CompositionLocalProvider(LocalHncColors provides extra) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
