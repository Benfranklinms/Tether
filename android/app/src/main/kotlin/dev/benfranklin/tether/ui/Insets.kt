package dev.benfranklin.tether.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Edge-to-edge for every Tether activity (0.5.3). Tether is always dark, so the status and
 * navigation bar icons are always light, whatever the system theme (the default "auto" style
 * drew dark clock/battery icons on the black app in light system mode).
 */
fun ComponentActivity.tetherEdgeToEdge() = enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
)

/**
 * Content padding for a scrolling screen inside a Scaffold: the top bar (which already sits
 * below the status bar), the gesture/navigation bar at the bottom and any side insets
 * (landscape cutouts), plus the screen's own spacing. Nothing scrolls under a system bar.
 */
@Composable
fun screenPadding(pad: PaddingValues, start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 40.dp): PaddingValues {
    val dir = LocalLayoutDirection.current
    return PaddingValues(
        start = pad.calculateStartPadding(dir) + start,
        top = pad.calculateTopPadding() + top,
        end = pad.calculateEndPadding(dir) + end,
        bottom = pad.calculateBottomPadding() + bottom,
    )
}
