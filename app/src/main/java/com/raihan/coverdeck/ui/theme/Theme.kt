package com.raihan.coverdeck.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * CoverDeck is dark-only on purpose: the cover screen is an OLED panel that is mostly
 * glanced at in the dark, and a light theme would both wash out and cost battery.
 */
object DeckColors {
    val Background = Color(0xFF090C12)
    val Surface = Color(0xFF131922)
    val SurfaceRaised = Color(0xFF1B2331)
    val SurfaceActive = Color(0xFF16273F)
    val Outline = Color(0xFF27303D)
    val OutlineActive = Color(0xFF3E7BD1)

    val Accent = Color(0xFF5B9DFF)
    val AccentBright = Color(0xFF8FBCFF)
    val AccentDim = Color(0xFF2C4A73)
    val Cyan = Color(0xFF62D8F5)

    val TextPrimary = Color(0xFFE8EDF5)
    val TextSecondary = Color(0xFF95A2B5)
    val TextTertiary = Color(0xFF5F6B7C)

    val Danger = Color(0xFFFF6B6B)
    val Warning = Color(0xFFFFC062)
    val Success = Color(0xFF5BE3A7)
}

private val scheme = darkColorScheme(
    primary = DeckColors.Accent,
    onPrimary = Color(0xFF04101F),
    primaryContainer = DeckColors.SurfaceActive,
    onPrimaryContainer = DeckColors.AccentBright,
    secondary = DeckColors.Cyan,
    onSecondary = Color(0xFF04101F),
    background = DeckColors.Background,
    onBackground = DeckColors.TextPrimary,
    surface = DeckColors.Surface,
    onSurface = DeckColors.TextPrimary,
    surfaceVariant = DeckColors.SurfaceRaised,
    onSurfaceVariant = DeckColors.TextSecondary,
    outline = DeckColors.Outline,
    error = DeckColors.Danger,
)

/** Tightened down a notch from stock Material: the cover screen is ~350 dp wide. */
private val deckTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun CoverDeckTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = scheme, typography = deckTypography, content = content)
}
