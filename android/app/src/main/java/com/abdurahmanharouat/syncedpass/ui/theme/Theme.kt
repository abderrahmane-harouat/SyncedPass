package com.abdurahmanharouat.syncedpass.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * SyncedPass design tokens. No Material: colors, type, spacing and shapes are
 * defined here and read through [SyncedPassTheme].
 *
 * Based on the Proton Authenticator style guide (android-mcp reference):
 * warm stone-gray pages, white rounded cards, warm-gray text (never black),
 * one accent family for interactive emphasis. The accent is gold, taken from
 * the SyncedPass padlock icon, instead of Proton's purple.
 */

@Immutable
data class SyncedPassColors(
    val pageTop: Color,
    val pageBottom: Color,
    val card: Color,
    val cardBorder: Color,
    val bottomBar: Color,
    val text: Color,
    val textWeak: Color,
    val textPlaceholder: Color,
    /** Links, text buttons, focus. */
    val accent: Color,
    /** Primary button and FAB: vertical gradient. */
    val primaryTop: Color,
    val primaryBottom: Color,
    /** Label on the gold primary gradient (dark, for contrast). */
    val onPrimary: Color,
    val primaryGlow: Color,
    val inputFill: Color,
    val inputBorder: Color,
    val iconTile: Color,
    val warning: Color,
    val error: Color,
    /** Confirmations, like the checkmark after changing the master password. */
    val success: Color,
    val illustration: Color,
)

val LightColors = SyncedPassColors(
    pageTop = Color(0xFFF5F5F4), pageBottom = Color(0xFFFAFAF9),
    card = Color.White, cardBorder = Color(0xFFECECEC),
    bottomBar = Color(0xF7E9E9E6),
    text = Color(0xFF44403C), textWeak = Color(0xFF78716C), textPlaceholder = Color(0xFF9E9894),
    accent = Color(0xFFA16A00),
    primaryTop = Color(0xFFFCD34D), primaryBottom = Color(0xFFE5A100),
    onPrimary = Color(0xFF3B2A05), primaryGlow = Color(0x66E5A100),
    inputFill = Color(0x05000000), inputBorder = Color(0x0F000000),
    iconTile = Color(0xFFF7F0E1),
    warning = Color(0xFFFFB879), error = Color(0xFFCC2D4F), success = Color(0xFF2F9E5A),
    illustration = Color(0xFFA6A6A6),
)

val DarkColors = SyncedPassColors(
    pageTop = Color(0xFF2D2A28), pageBottom = Color(0xFF161514),
    card = Color(0xFF373535), cardBorder = Color(0x1FFFFFFF),
    bottomBar = Color(0xF7252525),
    text = Color.White, textWeak = Color(0xFFDFDFDF), textPlaceholder = Color(0x66FFFFFF),
    accent = Color(0xFFFCD34D),
    primaryTop = Color(0xFFFCD34D), primaryBottom = Color(0xFFE5A100),
    onPrimary = Color(0xFF3B2A05), primaryGlow = Color(0x55E5A100),
    inputFill = Color(0x7D000000), inputBorder = Color(0x1FFFFFFF),
    iconTile = Color(0xFF2B2620),
    warning = Color(0xFFFFB879), error = Color(0xFFF08FA4), success = Color(0xFF4ADE80),
    illustration = Color(0xFF6E6A67),
)

/** Type scale from the style guide (sp size / line height, weight). */
@Immutable
data class SyncedPassType(
    val title: TextStyle = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    val subtitle: TextStyle = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    val headline: TextStyle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    val body: TextStyle = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    val body1: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    val body1Medium: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    val body2: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 18.sp),
    val label: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    val caption: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 14.sp),
    val button: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    val mono: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontFamily = FontFamily.Monospace),
)

/** Spacing scale: 4, 8, 12, 16, 24, 32, 64 dp. 16 is the screen margin. */
object Spacing {
    val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp; val huge = 64.dp
}

/** Radius tokens: 8 is the most used, cards 12–16, buttons fully rounded. */
object Radius {
    val small = 8.dp; val mediumSmall = 12.dp; val medium = 16.dp; val large = 24.dp
}

private val LocalColors = staticCompositionLocalOf { LightColors }
private val LocalType = staticCompositionLocalOf { SyncedPassType() }

object SyncedPassTheme {
    val colors: SyncedPassColors @Composable get() = LocalColors.current
    val type: SyncedPassType @Composable get() = LocalType.current
}

@Composable
fun SyncedPassTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalType provides SyncedPassType(),
        LocalTextSelectionColors provides TextSelectionColors(colors.primaryBottom, colors.primaryTop.copy(alpha = 0.4f)),
        content = content,
    )
}
