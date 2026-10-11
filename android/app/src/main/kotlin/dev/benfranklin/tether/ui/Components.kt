package dev.benfranklin.tether.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.Motion

/**
 * The Tether mark: two rings. Apart and breathing while searching; they spring together and
 * interlock when [linked]. All motion is spring-driven and stops when reduce-motion is on.
 */
@Composable
fun TetherMark(size: Dp, linked: Boolean, pulse: Boolean, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "pulse")
    val phase by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Reverse), label = "phase",
    )
    val apart by animateFloatAsState(if (linked) 0f else 1f, Motion.spring(reduce, damping = 0.6f), label = "apart")
    val fill by animateFloatAsState(if (linked) 1f else 0f, Motion.spring(reduce), label = "fill")
    val breathe = if (pulse && !linked && !reduce) phase else 0.5f
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension * 0.25f
        val w = this.size.minDimension * 0.055f
        val cy = this.size.height / 2
        val cx = this.size.width / 2
        // Interlocked: centres 0.9r apart. Apart: 1.9r..2.3r while breathing.
        val dx = r * (0.45f + apart * (0.5f + 0.2f * breathe))
        drawCircle(Mono.Text, r, Offset(cx - dx, cy), style = Stroke(w))
        drawCircle(Mono.Text.copy(alpha = 0.35f + 0.65f * fill), r, Offset(cx + dx, cy), style = Stroke(w))
        if (fill > 0f) drawCircle(Mono.Text, w * 1.6f * fill, Offset(cx, cy), style = Fill)
    }
}

private const val PULSE_MS = 1400

/** Light, system-standard haptic ticks. Silent when the user disabled touch feedback. */
/**
 * A spring's value clamped to 0…1 (0.5.3 crash fix). Springs with damping < 1 overshoot, so a
 * "lift" animating 1 → 0 dips just below zero; used as a corner radius, elevation or alpha that
 * dip threw "Corner size in Px can't be negative" and closed the app on release of a hold.
 */
fun Float.clamp01(): Float = coerceIn(0f, 1f)

object Haptics {
    fun confirm(v: View) {
        v.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }

    fun reject(v: View) {
        v.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
        )
    }

    fun tick(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}

/** iOS-style press feedback: content scales down slightly on a spring while pressed. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val reduce = LocalReduceMotion.current
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, Motion.spring(reduce, stiffness = 1400f), label = "press")
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

private const val PRESSED_SCALE = 0.96f

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Mono.Muted,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp))
            .background(Mono.Surface).border(1.dp, Mono.Line, RoundedCornerShape(16.dp)),
    ) { content() }
}

@Composable
fun monoSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Mono.Bg, checkedTrackColor = Mono.Text, checkedBorderColor = Mono.Text,
    uncheckedThumbColor = Mono.Muted, uncheckedTrackColor = Mono.Bg, uncheckedBorderColor = Mono.Line,
)

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch) {
                Haptics.tick(view)
                onChange(it)
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Mono.Text else Mono.Muted,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Mono.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = monoSwitchColors())
    }
}

/**
 * A navigation row (0.5.1): the title always stays on one line; a long [value] ellipsizes at
 * one line instead of squeezing the title, and descriptions go under the title as [subtitle].
 * [valueText] (annotated, e.g. a mono count) wins over [value].
 */
@Composable
fun NavRow(
    title: String,
    value: String?,
    icon: ImageVector? = null,
    subtitle: String? = null,
    valueText: androidx.compose.ui.text.AnnotatedString? = null,
    chevron: Boolean = true,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), role = Role.Button) {
                Haptics.tick(view)
                onClick()
            }
            .pressScale(source)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Mono.Muted, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall, color = Mono.Muted, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        val v = valueText ?: value?.let { androidx.compose.ui.text.AnnotatedString(it) }
        if (v != null) {
            Text(
                v, style = MaterialTheme.typography.bodyMedium, color = Mono.Muted, maxLines = 1, softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = NAV_VALUE_MAX),
            )
        }
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = Mono.Muted, modifier = Modifier.size(18.dp),
            )
        }
    }
}

private val NAV_VALUE_MAX = 150.dp

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).heightIn(min = 1.dp, max = 1.dp).background(Mono.Line))
}

/** Filled white when on, hollow grey when off. */
@Composable
fun StatusDot(on: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.size(8.dp).clip(CircleShape).background(if (on) Mono.Text else Mono.Bg)
            .border(1.dp, if (on) Mono.Text else Mono.Muted, CircleShape),
    )
}

/** A small outlined label, e.g. "Wi-Fi" or "Bluetooth". Highlighted = white, otherwise grey. */
@Composable
fun Chip(text: String, highlighted: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = if (highlighted) Mono.Text else Mono.Muted,
        modifier = Modifier.border(1.dp, if (highlighted) Mono.Text else Mono.Line, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
