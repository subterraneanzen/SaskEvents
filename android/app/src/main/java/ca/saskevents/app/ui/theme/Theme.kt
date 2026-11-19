package ca.saskevents.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/** One set of named colours per mode. Screens read them through [Palette], which follows the active theme. */
@Immutable
data class SaskColors(
    val paper: Color, val card: Color, val sand: Color, val line: Color,
    val sage: Color, val sageDeep: Color, val sageMist: Color,
    val clay: Color, val clayMist: Color, val olive: Color,
    val ink: Color, val inkSoft: Color, val alert: Color, val alertMist: Color,
    val dark: Boolean,
)

val LightColors = SaskColors(
    paper = Color(0xFFFAF7F2),        // warm off-white background
    card = Color(0xFFFFFDF9),
    sand = Color(0xFFF2EDE4),         // subtle fills
    line = Color(0xFFE4DDD0),         // borders & dividers
    sage = Color(0xFF5F8466),         // primary
    sageDeep = Color(0xFF3F5E46),
    sageMist = Color(0xFFDCE7D9),
    clay = Color(0xFFA86A4A),         // earthy accent
    clayMist = Color(0xFFF3E2D6),
    olive = Color(0xFF8C7B55),
    ink = Color(0xFF2D2A25),
    inkSoft = Color(0xFF6B655B),
    alert = Color(0xFFB0412E),
    alertMist = Color(0xFFF6DED8),
    dark = false,
)

/** Warm charcoal rather than pure black; accents are lightened so text on them stays readable (WCAG AA). */
val DarkColors = SaskColors(
    paper = Color(0xFF191815),
    card = Color(0xFF211F1B),
    sand = Color(0xFF2A2823),
    line = Color(0xFF38352F),
    sage = Color(0xFF8DB596),
    sageDeep = Color(0xFFB7D4BD),     // used for text/links on dark backgrounds and on sageMist
    sageMist = Color(0xFF2A3A2D),
    clay = Color(0xFFDDA27F),
    clayMist = Color(0xFF3B2A20),
    olive = Color(0xFFC4B48C),
    ink = Color(0xFFEDE7DD),
    inkSoft = Color(0xFFA9A296),
    alert = Color(0xFFEF8A75),
    alertMist = Color(0xFF45241D),
    dark = true,
)

val LocalSaskColors = staticCompositionLocalOf { LightColors }

/** Theme-aware palette. Read only from composable code (all screens do). */
object Palette {
    val Paper: Color @Composable @ReadOnlyComposable get() = LocalSaskColors.current.paper
    val Card: Color @Composable @ReadOnlyComposable get() = LocalSaskColors.current.card
    val Sand: Color @Composable @ReadOnlyComposable get() = LocalSaskColors.current.sand
    val Line: Color @Composable @ReadOnlyComposable get() = LocalSaskColors.current.line
    val Sage: Color @Composable @ReadOnlyComposable get() = LocalSaskColors.current.sage
    val SageDeep: Color @Composable @ReadOnlyComposable get() = LocalSaskColors.current.sageDeep
    val SageMist: Color @Composable @ReadOnlyComposable get() = LocalSaskColors.current.sageMist
    val Clay: Color @Composable @ReadOnlyComposable get() = LocalSaskColors.current.clay
    val ClayMist: Color @Composable @ReadOnlyComposable get() = LocalSaskColors.current.clayMist
    val Olive: Color @Composable @ReadOnlyComposable get() = LocalSaskColors.current.olive
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalSaskColors.current.ink
    val InkSoft: Color @Composable @ReadOnlyComposable get() = LocalSaskColors.current.inkSoft
    val Alert: Color @Composable @ReadOnlyComposable get() = LocalSaskColors.current.alert
    val AlertMist: Color @Composable @ReadOnlyComposable get() = LocalSaskColors.current.alertMist
}

private fun scheme(c: SaskColors): ColorScheme {
    val onAccent = if (c.dark) c.paper else Color.White
    val roles = if (c.dark) darkColorScheme() else lightColorScheme()
    return roles.copy(
        primary = c.sage, onPrimary = onAccent,
        primaryContainer = c.sageMist, onPrimaryContainer = c.sageDeep,
        secondary = c.clay, onSecondary = onAccent,
        secondaryContainer = c.clayMist, onSecondaryContainer = if (c.dark) c.clay else Color(0xFF5B341F),
        tertiary = c.olive, onTertiary = onAccent,
        background = c.paper, onBackground = c.ink,
        surface = c.paper, onSurface = c.ink,
        surfaceVariant = c.sand, onSurfaceVariant = c.inkSoft,
        surfaceContainerLowest = c.card, surfaceContainerLow = c.card,
        surfaceContainer = c.sand, surfaceContainerHigh = c.sand,
        surfaceContainerHighest = if (c.dark) Color(0xFF322F29) else Color(0xFFEAE3D8),
        inverseSurface = c.ink, inverseOnSurface = c.paper,
        outline = c.line, outlineVariant = c.line,
        error = c.alert, onError = onAccent, errorContainer = c.alertMist, onErrorContainer = c.alert,
    )
}

private val lightScheme = scheme(LightColors)
private val darkScheme = scheme(DarkColors)

private val base = Typography()
private val typography = Typography(
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.2).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, lineHeight = 20.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.4.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = base.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.2.sp),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(24.dp),
)

/** Theme preference values stored in settings. */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
}

@Composable
fun SaskEventsTheme(mode: String = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(LocalSaskColors provides if (dark) DarkColors else LightColors) {
        MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, typography = typography, shapes = shapes, content = content)
    }
}
