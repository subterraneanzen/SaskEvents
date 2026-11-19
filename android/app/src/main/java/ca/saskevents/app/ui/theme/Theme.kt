package ca.saskevents.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

object Palette {
    val Paper = Color(0xFFFAF7F2)        // warm off-white background
    val Card = Color(0xFFFFFDF9)
    val Sand = Color(0xFFF2EDE4)          // subtle fills
    val Line = Color(0xFFE4DDD0)          // borders & dividers
    val Sage = Color(0xFF5F8466)          // primary
    val SageDeep = Color(0xFF3F5E46)
    val SageMist = Color(0xFFDCE7D9)
    val Clay = Color(0xFFA86A4A)          // earthy accent
    val ClayMist = Color(0xFFF3E2D6)
    val Olive = Color(0xFF8C7B55)
    val Ink = Color(0xFF2D2A25)
    val InkSoft = Color(0xFF6B655B)
    val Alert = Color(0xFFB0412E)
    val AlertMist = Color(0xFFF6DED8)
}

private val colors = lightColorScheme(
    primary = Palette.Sage, onPrimary = Color.White,
    primaryContainer = Palette.SageMist, onPrimaryContainer = Palette.SageDeep,
    secondary = Palette.Clay, onSecondary = Color.White,
    secondaryContainer = Palette.ClayMist, onSecondaryContainer = Color(0xFF5B341F),
    tertiary = Palette.Olive, onTertiary = Color.White,
    background = Palette.Paper, onBackground = Palette.Ink,
    surface = Palette.Paper, onSurface = Palette.Ink,
    surfaceVariant = Palette.Sand, onSurfaceVariant = Palette.InkSoft,
    surfaceContainerLowest = Palette.Card, surfaceContainerLow = Palette.Card,
    surfaceContainer = Palette.Sand, surfaceContainerHigh = Palette.Sand, surfaceContainerHighest = Color(0xFFEAE3D8),
    outline = Palette.Line, outlineVariant = Palette.Line,
    error = Palette.Alert, errorContainer = Palette.AlertMist,
)

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

@Composable
fun SaskEventsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
