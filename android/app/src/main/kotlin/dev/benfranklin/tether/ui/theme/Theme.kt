package dev.benfranklin.tether.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring as springSpec
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.benfranklin.tether.R

/** Pure Mono palette: black, greys and white only. State is shown with weight and fill, never hue. */
object Mono {
    val Bg = Color(0xFF09090A)
    val Surface = Color(0xFF141416)
    val SurfaceHigh = Color(0xFF1C1C1F)
    val Line = Color(0xFF2A2A2E)
    val Text = Color(0xFFF2F2F2)
    val Muted = Color(0xFF8A8A8E)
    val Accent = Color(0xFFE8E8E8)
}

private val scheme = darkColorScheme(
    primary = Mono.Accent,
    onPrimary = Mono.Bg,
    primaryContainer = Mono.SurfaceHigh,
    onPrimaryContainer = Mono.Text,
    secondary = Mono.Accent,
    onSecondary = Mono.Bg,
    secondaryContainer = Mono.SurfaceHigh,
    onSecondaryContainer = Mono.Text,
    tertiary = Mono.Accent,
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

/** JetBrains Mono (SIL OFL, bundled) for numbers, codes and commands. */
val MonoCode = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

/** Manrope (SIL OFL, bundled) for text; numbers and codes use JetBrains Mono. */
private val sans = FontFamily(
    Font(R.font.manrope_light, FontWeight.Light),
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_semibold, FontWeight.Bold),
)

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
    labelSmall = TextStyle(fontFamily = MonoCode, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

/** True when the user turned animations off (Settings > Accessibility > Remove animations). */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * iOS-like spring physics. Every animated value in the app goes through these so motion is
 * consistent, and collapses to an instant snap when the user asked for reduced motion.
 */
object Motion {
    /** Default UI spring: quick, slightly soft (≈ SwiftUI .spring(response: 0.4, dampingFraction: 0.82)). */
    fun <T> spring(reduce: Boolean, stiffness: Float = Spring.StiffnessMediumLow, damping: Float = DAMPING): FiniteAnimationSpec<T> =
        if (reduce) snap() else springSpec(dampingRatio = damping, stiffness = stiffness)

    /** Bouncier spring for small things that "land" (digits, check marks). */
    fun <T> bouncy(reduce: Boolean): FiniteAnimationSpec<T> =
        if (reduce) snap() else springSpec(dampingRatio = BOUNCY_DAMPING, stiffness = Spring.StiffnessMedium)

    /** Appear: spring scale + fade; with reduce motion just a short crossfade (no movement). */
    fun enter(reduce: Boolean, from: Float = ENTER_SCALE): androidx.compose.animation.EnterTransition =
        if (reduce) {
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(REDUCED_FADE_MS))
        } else {
            androidx.compose.animation.fadeIn(springSpec(stiffness = Spring.StiffnessMediumLow)) +
                androidx.compose.animation.scaleIn(springSpec(dampingRatio = BOUNCY_DAMPING, stiffness = Spring.StiffnessMediumLow), from)
        }

    fun exit(reduce: Boolean, to: Float = ENTER_SCALE): androidx.compose.animation.ExitTransition =
        if (reduce) {
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(REDUCED_FADE_MS))
        } else {
            androidx.compose.animation.fadeOut(springSpec(stiffness = Spring.StiffnessMedium)) +
                androidx.compose.animation.scaleOut(springSpec(stiffness = Spring.StiffnessMedium), to)
        }

    /** Fade-only spec for alpha when reduce motion is on (a snap would make things pop). */
    fun <T> fade(reduce: Boolean): FiniteAnimationSpec<T> =
        if (reduce) androidx.compose.animation.core.tween(REDUCED_FADE_MS) else springSpec(stiffness = Spring.StiffnessMediumLow)

    const val REDUCED_FADE_MS = 150
    private const val ENTER_SCALE = 0.92f
    private const val DAMPING = 0.82f
    private const val BOUNCY_DAMPING = 0.62f
}

@Composable
fun TetherTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reduce = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    CompositionLocalProvider(LocalReduceMotion provides reduce) {
        MaterialTheme(colorScheme = scheme, typography = TetherType) {
            // Text outside a Surface would otherwise default to black on our black background.
            CompositionLocalProvider(LocalContentColor provides Mono.Text, content = content)
        }
    }
}
