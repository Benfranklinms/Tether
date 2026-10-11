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
import androidx.compose.material3.lightColorScheme
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.benfranklin.tether.R
import dev.benfranklin.tether.data.appearance

/**
 * The colours every screen uses, read from the active theme (0.5.5; was the fixed Pure Mono set).
 * Plain getters over snapshot state, so they work in composition and in draw lambdas alike.
 * Old names keep their roles: Surface = SURF, SurfaceHigh = SURF3, Line = LINE3, Muted = T2.
 */
@Suppress("MemberNameEqualsClassName")
object Mono {
    private val p: TetherPalette get() = ThemeState.palette
    val Bg: Color get() = p.bg
    val Surface: Color get() = p.surface
    val Surface2: Color get() = p.surface2
    val SurfaceHigh: Color get() = p.surface3
    val Surface4: Color get() = p.surface4
    val Line: Color get() = p.line3
    val Line2: Color get() = p.line2
    val LineFaint: Color get() = p.line
    val Text: Color get() = p.text
    val Muted: Color get() = p.t2
    val Faint: Color get() = p.t3
    val Fainter: Color get() = p.t4
    /** ACC: play button, primary pills, progress bars, badges, switches, selection. */
    val Accent: Color get() = p.accent
    /** Text and icons on an [Accent] fill. */
    val OnAccent: Color get() = p.bg
    val isLight: Boolean get() = p.light
}

/** Material colours derived from a palette, so Material components follow the theme too. */
fun colorSchemeOf(p: TetherPalette): androidx.compose.material3.ColorScheme {
    val scrim = Color(0xCC000000)
    return if (p.light) {
        lightColorScheme(
            primary = p.accent, onPrimary = p.bg, primaryContainer = p.surface3, onPrimaryContainer = p.text,
            secondary = p.accent, onSecondary = p.bg, secondaryContainer = p.surface3, onSecondaryContainer = p.text,
            tertiary = p.accent, background = p.bg, onBackground = p.text, surface = p.bg, onSurface = p.text,
            surfaceVariant = p.surface, onSurfaceVariant = p.t2, surfaceContainer = p.surface, surfaceContainerHigh = p.surface3,
            surfaceContainerHighest = p.surface3, surfaceContainerLow = p.surface, surfaceContainerLowest = p.bg,
            outline = p.line3, outlineVariant = p.line2, error = p.text, onError = p.bg, errorContainer = p.surface3,
            onErrorContainer = p.text, inverseSurface = p.text, inverseOnSurface = p.bg, scrim = scrim,
        )
    } else {
        darkColorScheme(
            primary = p.accent, onPrimary = p.bg, primaryContainer = p.surface3, onPrimaryContainer = p.text,
            secondary = p.accent, onSecondary = p.bg, secondaryContainer = p.surface3, onSecondaryContainer = p.text,
            tertiary = p.accent, background = p.bg, onBackground = p.text, surface = p.bg, onSurface = p.text,
            surfaceVariant = p.surface, onSurfaceVariant = p.t2, surfaceContainer = p.surface, surfaceContainerHigh = p.surface3,
            surfaceContainerHighest = p.surface3, surfaceContainerLow = p.surface, surfaceContainerLowest = p.bg,
            outline = p.line3, outlineVariant = p.line2, error = p.text, onError = p.bg, errorContainer = p.surface3,
            onErrorContainer = p.text, inverseSurface = p.text, inverseOnSurface = p.bg, scrim = scrim,
        )
    }
}

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
    Font(R.font.manrope_bold, FontWeight.Bold),
)

/** 0.5.5 type scale (smaller; "the text is so big"): rows 13-15, meta 11.5-12.5, titles 22. */
val TetherType = Typography(
    displaySmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Light, fontSize = 28.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.2).sp),
    headlineMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 28.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = (-0.1).sp),
    titleMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    titleSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    bodyLarge = TextStyle(fontFamily = sans, fontSize = 14.5.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = sans, fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = MonoCode, fontSize = 11.sp, letterSpacing = 0.4.sp),
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

/**
 * The app theme (0.5.5): the user's palette (Settings › Appearance; follows the system light/dark
 * when asked), text size, a short colour cross-fade on a switch, and status / navigation bar
 * icons dark on light themes. [appearance] null = read the saved choice (tests pass their own).
 */
@Composable
fun TetherTheme(appearance: Appearance? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reduce = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val chosen = appearance ?: savedAppearance()
    val target = chosen.resolve(isSystemInDarkTheme()).palette
    val p = animatePalette(target, reduce)
    // Forward write: everything below reads the palette after this point in the same frame.
    if (ThemeState.palette != p) ThemeState.palette = p
    SystemBarIcons(p.light)
    val density = LocalDensity.current
    val scaled = remember(density, chosen.fontScale) { Density(density.density, density.fontScale * chosen.fontScale) }
    CompositionLocalProvider(LocalReduceMotion provides reduce, LocalPalette provides p, LocalDensity provides scaled) {
        MaterialTheme(colorScheme = colorSchemeOf(p), typography = TetherType) {
            // Text outside a Surface would otherwise default to black on a dark background.
            CompositionLocalProvider(LocalContentColor provides p.text, content = content)
        }
    }
}

/** The active palette for code that prefers a CompositionLocal (same value as [Mono]). */
val LocalPalette = compositionLocalOf { Themes.MONO.palette }

@Composable
private fun savedAppearance(): Appearance {
    val app = LocalContext.current.applicationContext as? dev.benfranklin.tether.TetherApp ?: return Appearance()
    val s by app.runtime.settings.settings.collectAsState()
    return s.appearance
}

@Composable
private fun animatePalette(t: TetherPalette, reduce: Boolean): TetherPalette {
    val spec = tween<Color>(if (reduce) Motion.REDUCED_FADE_MS else THEME_FADE_MS)
    @Composable fun a(c: Color) = animateColorAsState(c, spec, label = "theme").value
    return TetherPalette(
        t.light, a(t.bg), a(t.surface), a(t.surface2), a(t.surface3), a(t.surface4), a(t.line), a(t.line2), a(t.line3),
        a(t.text), a(t.accent), a(t.t2), a(t.t3), a(t.t4),
    )
}

private const val THEME_FADE_MS = 320

/** Status and navigation bar icons: dark on a light theme, light on a dark one. */
@Composable
private fun SystemBarIcons(light: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val c = androidx.core.view.WindowCompat.getInsetsController(window, view)
        c.isAppearanceLightStatusBars = light
        c.isAppearanceLightNavigationBars = light
    }
}
