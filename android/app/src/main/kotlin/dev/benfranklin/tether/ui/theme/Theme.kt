package dev.benfranklin.tether.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Pure Mono palette. No red or orange anywhere: problems are shown in plain text weight. */
object Mono {
    val Bg = Color(0xFF09090A)
    val Surface = Color(0xFF141416)
    val SurfaceHigh = Color(0xFF1C1C1F)
    val Line = Color(0xFF2A2A2E)
    val Text = Color(0xFFF2F2F2)
    val Muted = Color(0xFF8A8A8E)
    val Accent = Color(0xFFE8E8E8)
    val Sage = Color(0xFF9FB8A3)
}

private val scheme = darkColorScheme(
    primary = Mono.Accent,
    onPrimary = Mono.Bg,
    primaryContainer = Mono.SurfaceHigh,
    onPrimaryContainer = Mono.Text,
    secondary = Mono.Sage,
    onSecondary = Mono.Bg,
    secondaryContainer = Mono.SurfaceHigh,
    onSecondaryContainer = Mono.Text,
    tertiary = Mono.Sage,
    background = Mono.Bg,
    onBackground = Mono.Text,
    surface = Mono.Bg,
    onSurface = Mono.Text,
    surfaceVariant = Mono.Surface,
    onSurfaceVariant = Mono.Muted,
    surfaceContainer = Mono.Surface,
    surfaceContainerHigh = Mono.SurfaceHigh,
    surfaceContainerHighest = Mono.SurfaceHigh,
    surfaceContainerLow = Mono.Surface,
    surfaceContainerLowest = Mono.Bg,
    outline = Mono.Line,
    outlineVariant = Mono.Line,
    error = Mono.Text,
    onError = Mono.Bg,
    errorContainer = Mono.SurfaceHigh,
    onErrorContainer = Mono.Text,
    inverseSurface = Mono.Text,
    inverseOnSurface = Mono.Bg,
    scrim = Color(0xCC000000),
)

private val sans = FontFamily.SansSerif

val TetherType = Typography(
    displaySmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Light, fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 24.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = sans, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

@Composable
fun TetherTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = TetherType, content = content)
}
