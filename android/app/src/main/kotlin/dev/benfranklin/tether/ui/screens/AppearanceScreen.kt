package dev.benfranklin.tether.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.tether.ui.Card
import dev.benfranklin.tether.ui.Haptics
import dev.benfranklin.tether.ui.SectionHeader
import dev.benfranklin.tether.ui.ToggleRow
import dev.benfranklin.tether.ui.pressScale
import dev.benfranklin.tether.ui.screenPadding
import dev.benfranklin.tether.ui.theme.Appearance
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.Motion
import dev.benfranklin.tether.ui.theme.TextSizes
import dev.benfranklin.tether.ui.theme.ThemeSpec
import dev.benfranklin.tether.ui.theme.Themes
import kotlin.math.roundToInt

/** "Mono", or "Light / Mono" when following the system; plus the text size when it isn't the default. */
fun appearanceSummary(a: Appearance): String {
    val theme = if (a.followSystem) Themes.byId(a.lightId).name + " / " + Themes.byId(a.darkId).name else Themes.byId(a.themeId).name
    val size = TextSizes.labels[a.textSize.coerceIn(0, TextSizes.labels.lastIndex)]
    return if (a.textSize == TextSizes.DEFAULT) theme else "$theme · $size"
}

/**
 * Settings › Appearance (0.5.5, approved mockup themes/appearance.png): theme cards (a mini
 * preview, the name, a check when picked) under Dark and Light, "Follow system light/dark" (then
 * one light and one dark theme are picked), and a four-step text size slider.
 */
@Composable
fun AppearanceScreen(value: Appearance, onChange: (Appearance) -> Unit, onBack: () -> Unit) {
    val view = LocalView.current
    Scaffold(containerColor = Mono.Bg, topBar = { TopBar("Appearance", onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().testTag("appearance-list"), contentPadding = screenPadding(pad)) {
            item(key = "dark-h") { SectionHeader(if (value.followSystem) "Dark · used when the system is dark" else "Dark") }
            themeGrid(Themes.dark, value) { id ->
                Haptics.tick(view)
                onChange(value.pick(id))
            }
            item(key = "light-h") { SectionHeader(if (value.followSystem) "Light · used when the system is light" else "Light") }
            themeGrid(Themes.light, value) { id ->
                Haptics.tick(view)
                onChange(value.pick(id))
            }
            item(key = "system") {
                Box(Modifier.padding(top = 20.dp)) {
                    Card {
                        Column {
                            ToggleRow("Follow system light/dark", if (value.followSystem) "Pick a light and a dark theme above" else null,
                                value.followSystem) { on -> onChange(value.copy(followSystem = on)) }
                        }
                    }
                }
            }
            item(key = "size-h") { SectionHeader("Text size") }
            item(key = "size") { TextSizeCard(value.textSize) { step -> onChange(value.copy(textSize = step)) } }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.themeGrid(themes: List<ThemeSpec>, value: Appearance, onPick: (String) -> Unit) {
    themes.chunked(2).forEach { pair ->
        item(key = "row-" + pair.first().id) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { t -> ThemeCard(t, value.isSelected(t.id), Modifier.weight(1f)) { onPick(t.id) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** One theme: a mini Home in its own colours, the name, and a check when picked. */
@Composable
private fun ThemeCard(t: ThemeSpec, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val source = remember { MutableInteractionSource() }
    val border by animateColorAsState(if (selected) Mono.Accent else Mono.Line2, Motion.fade(reduce), label = "themeBorder")
    val check by animateFloatAsState(if (selected) 1f else 0f, Motion.bouncy(reduce), label = "themeCheck")
    val p = t.palette
    Column(
        modifier.pressScale(source).clip(RoundedCornerShape(20.dp)).background(Mono.Surface)
            .border(if (selected) 1.6.dp else 1.dp, border, RoundedCornerShape(20.dp))
            .clickable(source, null, role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = t.name + if (p.light) ", light theme" else ", dark theme"
            }
            .testTag("theme-" + t.id)
            .padding(10.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(78.dp)) {
            val r = 13.dp.toPx()
            drawRoundRect(p.bg, cornerRadius = CornerRadius(r))
            drawRoundRect(p.line3, cornerRadius = CornerRadius(r), style = Stroke(0.5.dp.toPx()))
            val i = 9.dp.toPx()
            drawRoundRect(p.surface, Offset(i, 10.dp.toPx()), Size(size.width - 2 * i, 20.dp.toPx()), CornerRadius(7.dp.toPx()))
            drawCircle(p.text, 4.dp.toPx(), Offset(19.dp.toPx(), 20.dp.toPx()))
            drawRoundRect(p.t2, Offset(28.dp.toPx(), 17.5.dp.toPx()), Size(34.dp.toPx(), 5.dp.toPx()), CornerRadius(2.5.dp.toPx()))
            val bw = (size.width - 2 * i - 6.dp.toPx()) / 2
            val by = 36.dp.toPx()
            drawRoundRect(p.surface3, Offset(i, by), Size(bw, 32.dp.toPx()), CornerRadius(8.dp.toPx()))
            drawRoundRect(p.surface3, Offset(i + bw + 6.dp.toPx(), by), Size(bw, 32.dp.toPx()), CornerRadius(8.dp.toPx()))
            drawCircle(p.accent, 8.dp.toPx(), Offset(size.width - 26.dp.toPx(), 52.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 10.dp).height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1,
                modifier = Modifier.weight(1f))
            if (check > 0.01f) {
                Box(
                    Modifier.size((18 * check.coerceIn(0f, 1.2f)).dp).clip(CircleShape).background(Mono.Accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Check, contentDescription = null, tint = Mono.OnAccent, modifier = Modifier.size(12.dp)) }
            }
        }
    }
}

/** A–A slider with four stops (Small, Default, Large, Larger); the step name shows under it. */
@Composable
private fun TextSizeCard(step: Int, onStep: (Int) -> Unit) {
    val view = LocalView.current
    var drag by remember(step) { mutableFloatStateOf(step.toFloat()) }
    val label = TextSizes.labels[drag.roundToInt().coerceIn(0, TextSizes.labels.lastIndex)]
    Card {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Mono.Muted)
                Slider(
                    value = drag, valueRange = 0f..TextSizes.labels.lastIndex.toFloat(), steps = TextSizes.labels.size - 2,
                    onValueChange = { v ->
                        if (v.roundToInt() != drag.roundToInt()) Haptics.tick(view)
                        drag = v
                    },
                    onValueChangeFinished = { onStep(drag.roundToInt()) },
                    colors = SliderDefaults.colors(
                        thumbColor = Mono.Accent, activeTrackColor = Mono.Accent, inactiveTrackColor = Mono.Surface4,
                        activeTickColor = Mono.OnAccent, inactiveTickColor = Mono.Faint,
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp).testTag("text-size")
                        .semantics { stateDescription = label },
                )
                Text("A", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Mono.Muted)
            }
            Text(label, style = MaterialTheme.typography.bodySmall, color = Mono.Muted,
                modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}
