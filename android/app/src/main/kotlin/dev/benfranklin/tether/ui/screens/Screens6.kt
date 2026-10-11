@file:Suppress("TooManyFunctions", "MagicNumber", "LongMethod", "LongParameterList")

package dev.benfranklin.tether.ui.screens

import dev.benfranklin.tether.ui.screenPadding
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.benfranklin.tether.core.proto.HotspotReasons
import dev.benfranklin.tether.core.proto.HotspotStates
import dev.benfranklin.tether.data.AppSettings
import dev.benfranklin.tether.ui.Card
import dev.benfranklin.tether.ui.Divider
import dev.benfranklin.tether.ui.Haptics
import dev.benfranklin.tether.ui.NavRow
import dev.benfranklin.tether.ui.SectionHeader
import dev.benfranklin.tether.ui.ToggleRow
import dev.benfranklin.tether.ui.pressScale
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.MonoCode
import dev.benfranklin.tether.ui.theme.Motion
import java.util.Locale

// ============================ Link badge (Home card) ============================

/**
 * Live link badge: "Bluetooth" with a slow radio pulse, morphing into "Wi-Fi fast path" when the
 * fast path comes up — the arcs fill in one by one, the pill inverts and a light sweep crosses it.
 */
@Composable
fun LinkBadge(wifi: Boolean, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val arcs = remember { List(3) { Animatable(if (wifi) 1f else 0f) } }
    val sweep = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(wifi) {
        if (!first && wifi) Haptics.tick(view)
        first = false
        arcs.forEachIndexed { i, a ->
            val target = if (wifi) 1f else 0f
            if (reduce) {
                a.snapTo(target)
            } else {
                kotlinx.coroutines.delay(if (wifi) 90L * i else 0L)
                a.animateTo(target, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium))
            }
        }
        if (wifi && !reduce) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(700, easing = LinearEasing))
        }
    }
    val t = rememberInfiniteTransition(label = "radio")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    val bg by animateColorAsState(if (wifi) Mono.Accent else Mono.Bg, Motion.fade(reduce), label = "badgeBg")
    val fg by animateColorAsState(if (wifi) Mono.Bg else Mono.Text, Motion.fade(reduce), label = "badgeFg")
    Row(
        modifier.clip(RoundedCornerShape(50)).background(bg).border(1.dp, if (wifi) Mono.Accent else Mono.Line, RoundedCornerShape(50))
            .animateContentSize(Motion.spring(reduce))
            .semantics { contentDescription = if (wifi) "Connected over Wi-Fi fast path" else "Connected over Bluetooth" }
            .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val c = Offset(size.width / 2, size.height * 0.82f)
            // Bluetooth: a dot with an expanding ring.
            val dot = 1f - arcs[0].value
            if (dot > 0f) {
                drawCircle(fg.copy(alpha = dot), size.minDimension * 0.14f, c)
                if (!reduce) {
                    drawCircle(
                        fg.copy(alpha = dot * (1 - pulse) * 0.7f), size.minDimension * (0.14f + 0.4f * pulse), c,
                        style = Stroke(1.2.dp.toPx()),
                    )
                }
            }
            // Wi-Fi: three arcs that fill in one by one.
            arcs.forEachIndexed { i, a ->
                val r = size.minDimension * (0.28f + 0.26f * i)
                if (a.value > 0f) {
                    drawArc(
                        fg.copy(alpha = a.value), startAngle = -135f, sweepAngle = 90f * a.value, useCenter = false,
                        topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = Stroke(1.6.dp.toPx()),
                    )
                }
            }
            if (arcs[0].value > 0.5f) drawCircle(fg, size.minDimension * 0.08f, c)
        }
        Spacer(Modifier.width(6.dp))
        AnimatedContent(
            wifi, label = "badgeText",
            transitionSpec = {
                if (reduce) {
                    fadeIn(Motion.fade(true)) togetherWith fadeOut(Motion.fade(true))
                } else {
                    (slideInVertically(Motion.spring(false)) { it } + fadeIn()) togetherWith
                        (slideOutVertically(Motion.spring(false)) { -it } + fadeOut())
                }
            },
        ) { w ->
            Text(
                if (w) "Wi-Fi fast path" else "Bluetooth", style = MaterialTheme.typography.labelSmall, color = fg,
                modifier = Modifier.graphicsLayer {
                    // One light sweep across the text when Wi-Fi arrives.
                    alpha = if (w && sweep.value < 1f) 0.75f + 0.25f * sweep.value else 1f
                },
            )
        }
    }
}

/** "Update" pill on the Home header when a newer version is ready. */
@Composable
fun UpdatePill(version: String, onClick: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val source = remember { MutableInteractionSource() }
    val t = rememberInfiniteTransition(label = "pill")
    val glow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "glow")
    Row(
        Modifier.pressScale(source).clip(RoundedCornerShape(50))
            .background(
                if (reduce) {
                    Brush.linearGradient(listOf(Mono.Text, Mono.Text))
                } else {
                    Brush.horizontalGradient(
                        0f to Mono.Text, (glow - 0.15f).coerceIn(0f, 1f) to Mono.Text, glow to Mono.Accent.copy(alpha = 0.75f),
                        (glow + 0.15f).coerceIn(0f, 1f) to Mono.Text, 1f to Mono.Text,
                    )
                },
            )
            .clickable(source, null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Tether $version is available. Update" }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ArrowUpward, contentDescription = null, tint = Mono.Bg, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("Update", style = MaterialTheme.typography.labelLarge, color = Mono.Bg, fontWeight = FontWeight.SemiBold)
    }
}

// ============================ Find my Mac ============================

/** What the Find my Mac card shows. */
data class FindModel(val ringing: Boolean, val pending: String?, val locked: Boolean, val error: String?, val available: Boolean)

@Composable
fun FindMacCard(f: FindModel, onRing: () -> Unit, onStop: () -> Unit, onLock: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val t = rememberInfiniteTransition(label = "ring")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "wave")
    Card(Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp).animateContentSize(Motion.spring(reduce))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    if (f.ringing && !reduce) {
                        Canvas(Modifier.fillMaxSize()) {
                            for (i in 0..1) {
                                val p = (wave + i * 0.5f) % 1f
                                drawCircle(
                                    Mono.Text.copy(alpha = (1 - p) * 0.6f), size.minDimension / 2 * (0.55f + 0.45f * p),
                                    style = Stroke(1.5f),
                                )
                            }
                        }
                    }
                    Icon(Icons.Outlined.Laptop, contentDescription = null, tint = Mono.Text)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Find my Mac", style = MaterialTheme.typography.titleMedium)
                    AnimatedContent(
                        when {
                            !f.available -> "Connect to your Mac to use this"
                            f.pending != null -> "Asking your Mac…"
                            f.ringing -> "Ringing — press Found it on the Mac"
                            f.locked -> "Locked"
                            else -> "Play a sound or lock the screen"
                        },
                        label = "findStatus",
                        transitionSpec = { fadeIn(Motion.fade(reduce)) togetherWith fadeOut(Motion.fade(reduce)) },
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    ) { s -> Text(s, style = MaterialTheme.typography.bodySmall, color = Mono.Muted) }
                }
                AnimatedVisibility(f.pending != null, enter = Motion.enter(reduce), exit = Motion.exit(reduce)) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Mono.Text, strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    label = if (f.ringing) "Stop" else "Ring", icon = Icons.Outlined.NotificationsActive, filled = f.ringing,
                    enabled = f.available && f.pending == null, modifier = Modifier.weight(1f),
                ) {
                    Haptics.confirm(view)
                    if (f.ringing) onStop() else onRing()
                }
                PillButton(
                    label = if (f.locked) "Locked" else "Lock", icon = if (f.locked) Icons.Outlined.Check else Icons.Outlined.Lock,
                    filled = f.locked, enabled = f.available && f.pending == null, modifier = Modifier.weight(1f),
                ) {
                    Haptics.confirm(view)
                    onLock()
                }
            }
            AnimatedVisibility(
                f.error != null,
                enter = fadeIn(Motion.fade(reduce)) + expandVertically(Motion.spring(reduce)),
                exit = fadeOut(Motion.fade(reduce)) + shrinkVertically(Motion.spring(reduce)),
            ) {
                Text(
                    f.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Mono.Text,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
fun PillButton(
    label: String,
    icon: ImageVector?,
    filled: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val source = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (filled) Mono.Accent else Mono.Bg, Motion.fade(reduce), label = "pbg")
    val fg by animateColorAsState(
        when {
            !enabled -> Mono.Muted
            filled -> Mono.Bg
            else -> Mono.Text
        },
        Motion.fade(reduce), label = "pfg",
    )
    Row(
        modifier.pressScale(source).clip(RoundedCornerShape(14.dp)).background(bg)
            .border(1.dp, if (enabled) Mono.Text else Mono.Line, RoundedCornerShape(14.dp))
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            AnimatedContent(icon, label = "pi", transitionSpec = {
                if (reduce) {
                    fadeIn(Motion.fade(true)) togetherWith fadeOut(Motion.fade(true))
                } else {
                    (fadeIn() + scaleIn(Motion.bouncy(false), 0.4f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.4f))
                }
            }) { i -> Icon(i, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(8.dp))
        }
        AnimatedContent(
            label, label = "pl", transitionSpec = { fadeIn(Motion.fade(reduce)) togetherWith fadeOut(Motion.fade(reduce)) },
        ) { l ->
            Text(l, style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
}

// ============================ Instant Hotspot ============================

interface HotspotActions {
    fun update(change: (AppSettings) -> AppSettings)
    fun save(ssid: String, password: String)
    fun forget()
    fun openAccessibility()
}

/** Human text for the last hotspot request. Pure; unit-tested. */
fun hotspotStatusText(state: String?, reason: String?): String? = when (state) {
    null -> null
    HotspotStates.STARTING -> "Turning on the hotspot…"
    HotspotStates.ON -> "Hotspot on · your Mac joined"
    HotspotStates.MANUAL -> when (reason) {
        HotspotReasons.NO_SWITCH -> "Couldn't find the switch · turn Hotspot on by hand"
        else -> "Turn Hotspot on by hand · your Mac joins by itself"
    }
    HotspotStates.FAILED -> when (reason) {
        HotspotReasons.NO_CREDENTIALS -> "Your Mac asked, but no hotspot name is saved"
        HotspotReasons.DISABLED -> "Your Mac asked, but Instant Hotspot is off"
        HotspotReasons.TIMEOUT -> "The hotspot didn't come on in time"
        else -> "Couldn't turn on the hotspot"
    }
    else -> null
}

@Composable
fun HotspotScreen(
    s: AppSettings,
    savedSsid: String?,
    hasPassword: Boolean,
    controlOn: Boolean,
    status: String?,
    a: HotspotActions,
    onBack: () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    var ssid by remember(savedSsid) { mutableStateOf(savedSsid.orEmpty()) }
    var pass by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) {
        if (saved) {
            kotlinx.coroutines.delay(1_600)
            saved = false
        }
    }
    Scaffold(containerColor = Mono.Bg, topBar = { TopBar("Instant Hotspot", onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(pad)) {
            item {
                Text(
                    "When your Mac has no Wi-Fi it can ask this phone for its hotspot. Android doesn't let apps read the " +
                        "hotspot's name and password, so save them here once. They stay encrypted on this phone and are " +
                        "only sent to your paired Mac, inside the encrypted link.",
                    style = MaterialTheme.typography.bodyMedium, color = Mono.Muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            item {
                AnimatedVisibility(
                    status != null, enter = fadeIn(Motion.fade(reduce)) + expandVertically(Motion.spring(reduce)),
                    exit = fadeOut(Motion.fade(reduce)) + shrinkVertically(Motion.spring(reduce)),
                ) {
                    Text(
                        status.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Mono.Text,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .border(1.dp, Mono.Line, RoundedCornerShape(12.dp)).padding(12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            item { SectionHeader("Your hotspot") }
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = ssid, onValueChange = { ssid = it }, label = { Text("Hotspot name") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), colors = monoFieldColors(), shape = RoundedCornerShape(14.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        SecretField(pass, { pass = it }, if (hasPassword) "New password (one is saved)" else "Password")
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AnimatedVisibility(savedSsid != null, enter = Motion.enter(reduce), exit = Motion.exit(reduce)) {
                                TextButton(onClick = {
                                    a.forget()
                                    ssid = ""
                                    pass = ""
                                    Haptics.tick(view)
                                }) { Text("Forget", color = Mono.Muted) }
                            }
                            Spacer(Modifier.weight(1f))
                            PillButton(
                                label = if (saved) "Saved" else "Save", icon = if (saved) Icons.Outlined.Check else null, filled = saved,
                                enabled = ssid.isNotBlank(),
                            ) {
                                a.save(ssid, pass)
                                pass = ""
                                saved = true
                                Haptics.confirm(view)
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Your Mac can") }
            item {
                Card {
                    Column {
                        ToggleRow("Ask for the hotspot", "Your Mac joins this phone's hotspot when it has no Wi-Fi", s.allowHotspot) { v ->
                            a.update { it.copy(allowHotspot = v) }
                        }
                        Divider()
                        ToggleRow(
                            "Turn it on for me",
                            "Tether control (Accessibility) opens hotspot settings and taps the switch. Off: you get a " +
                                "notification and switch it on yourself.",
                            s.hotspotAutomation, enabled = s.allowHotspot,
                        ) { v -> a.update { it.copy(hotspotAutomation = v) } }
                        AnimatedVisibility(
                            s.allowHotspot && s.hotspotAutomation && !controlOn,
                            enter = fadeIn(Motion.fade(reduce)) + expandVertically(Motion.spring(reduce)),
                            exit = fadeOut(Motion.fade(reduce)) + shrinkVertically(Motion.spring(reduce)),
                        ) {
                            Column {
                                Divider()
                                NavRow(
                                    "Tether control is off", "Turn on", subtitle = "Needed to tap the switch for you",
                                    onClick = a::openAccessibility,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================ Update popup / What's new ============================

/** Everything the update sheet shows. */
data class UpdateSheetModel(
    val version: String,
    val current: String,
    val changelog: String,
    /** 0..1 while downloading, null otherwise. */
    val progress: Float?,
    val installing: Boolean,
    val error: String?,
)

@Composable
fun UpdateSheet(m: UpdateSheetModel, onUpdate: () -> Unit, onLater: () -> Unit, onSkip: () -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Mono.Surface, contentColor = Mono.Text) {
        UpdateSheetContent(m, onUpdate, onLater, onSkip)
    }
}

/** The sheet's body (separate so screenshot tests can render it without a window). */
@Composable
fun UpdateSheetContent(m: UpdateSheetModel, onUpdate: () -> Unit, onLater: () -> Unit, onSkip: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val busy = m.progress != null || m.installing
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        Text("UPDATE AVAILABLE", style = MaterialTheme.typography.labelMedium, color = Mono.Muted)
        Text("Tether ${m.version}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp))
        Text("You have ${m.current}", style = MaterialTheme.typography.bodySmall, color = Mono.Muted)
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(16.dp)).background(Mono.Bg)
                .border(1.dp, Mono.Line, RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(
                changelogText(m.changelog.ifBlank { "Bug fixes and improvements." }),
                style = MaterialTheme.typography.bodyMedium, color = Mono.Text,
            )
        }
        Spacer(Modifier.height(20.dp))
        AnimatedContent(
            busy, label = "updateBusy",
            transitionSpec = { fadeIn(Motion.fade(reduce)) togetherWith fadeOut(Motion.fade(reduce)) },
        ) { b ->
            if (b) {
                UpdateProgress(m.progress, m.installing)
            } else {
                Column {
                    PillButton(
                        "Update now", Icons.Outlined.ArrowUpward, filled = true, enabled = true, modifier = Modifier.fillMaxWidth(),
                    ) {
                        Haptics.confirm(view)
                        onUpdate()
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onSkip) { Text("Skip this version", color = Mono.Muted) }
                        TextButton(onClick = onLater) { Text("Later", color = Mono.Text) }
                    }
                }
            }
        }
        AnimatedVisibility(m.error != null, enter = Motion.enter(reduce), exit = Motion.exit(reduce)) {
            Text(m.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Mono.Text, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** Markdown-ish changelog to plain text: "- item" bullets become "•", headings lose their #. Pure; unit-tested. */
fun changelogText(md: String): String = md.lines().joinToString("\n") { l ->
    val t = l.trimStart()
    when {
        t.startsWith("- ") || t.startsWith("* ") -> "•  " + t.drop(2)
        t.startsWith("#") -> t.trimStart('#').trim()
        else -> l
    }.replace("**", "").replace("`", "")
}.trim()

@Composable
private fun UpdateProgress(progress: Float?, installing: Boolean) {
    val reduce = LocalReduceMotion.current
    val p by animateFloatAsState(
        if (installing) 1f else progress ?: 0f, Motion.spring(reduce, stiffness = Spring.StiffnessLow, damping = 1f), label = "dl",
    )
    val t = rememberInfiniteTransition(label = "shine")
    val shine by t.animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "shine")
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (installing) "Installing — confirm on screen" else "Downloading", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            val pct = (p * 100).toInt().coerceIn(0, 100)
            AnimatedContent(
                pct, label = "pct",
                transitionSpec = {
                    if (reduce) {
                        fadeIn(Motion.fade(true)) togetherWith fadeOut(Motion.fade(true))
                    } else {
                        (slideInVertically(Motion.spring(false)) { it / 2 } + fadeIn()) togetherWith
                            (slideOutVertically(Motion.spring(false)) { -it / 2 } + fadeOut())
                    }
                },
            ) { n -> Text(String.format(Locale.ROOT, "%d%%", n), style = MaterialTheme.typography.labelLarge, fontFamily = MonoCode) }
        }
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
            drawRect(Mono.Line)
            val w = size.width * p
            drawRect(Mono.Text, size = Size(w, size.height))
            if (!reduce && w > 0f) {
                val x = size.width * shine
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Mono.Text, Mono.Bg.copy(alpha = 0.35f), Mono.Text), startX = x - 60f, endX = x + 60f,
                    ),
                    topLeft = Offset(0f, 0f), size = Size(w, size.height),
                )
            }
        }
    }
}

@Composable
fun WhatsNewSheet(version: String, notes: String?, onDone: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet, containerColor = Mono.Surface, contentColor = Mono.Text) {
        WhatsNewContent(version, notes, onDone)
    }
}

@Composable
fun WhatsNewContent(version: String, notes: String?, onDone: () -> Unit) {
    val reduce = LocalReduceMotion.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        AnimatedVisibility(shown, enter = Motion.enter(reduce, from = 0.6f)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Mono.Accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = Mono.Bg)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("What's new in $version", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            changelogText(notes?.ifBlank { null } ?: "Tether is up to date. Thanks for updating!"),
            style = MaterialTheme.typography.bodyMedium, color = Mono.Text,
            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(20.dp))
        PillButton("Continue", null, filled = true, enabled = true, modifier = Modifier.fillMaxWidth(), onClick = onDone)
    }
}
