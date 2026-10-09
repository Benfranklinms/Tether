package dev.benfranklin.tether.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.benfranklin.tether.ui.theme.Mono

/** The Tether mark: two linked rings. [linked] tints the second ring sage; [pulse] breathes while searching. */
@Composable
fun TetherMark(size: Dp, linked: Boolean, pulse: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "pulse")
    val phase by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Reverse), label = "phase",
    )
    val gap = if (pulse) 0.08f + 0.10f * phase else 0.08f
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension * 0.27f
        val stroke = Stroke(width = this.size.minDimension * 0.06f)
        val cy = this.size.height / 2
        val cx = this.size.width / 2
        val dx = this.size.width * gap + r * 0.55f
        drawCircle(Mono.Text, r, Offset(cx - dx, cy), style = stroke)
        drawCircle(if (linked) Mono.Sage else Mono.Muted, r, Offset(cx + dx, cy), style = stroke)
    }
}

private const val PULSE_MS = 1400

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
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Mono.Text else Mono.Muted)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Mono.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Mono.Bg, checkedTrackColor = Mono.Sage, checkedBorderColor = Mono.Sage,
                uncheckedThumbColor = Mono.Muted, uncheckedTrackColor = Mono.Bg, uncheckedBorderColor = Mono.Line,
            ),
        )
    }
}

@Composable
fun NavRow(title: String, value: String?, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Mono.Muted, modifier = Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = Mono.Muted)
    }
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).heightIn(min = 1.dp, max = 1.dp).background(Mono.Line))
}

@Composable
fun StatusDot(on: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(if (on) Mono.Sage else Mono.Muted))
}

/** A small outlined label, e.g. "Wi-Fi" or "Bluetooth". */
@Composable
fun Chip(text: String, highlighted: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = if (highlighted) Mono.Sage else Mono.Muted,
        modifier = Modifier.border(1.dp, if (highlighted) Mono.Sage else Mono.Line, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
