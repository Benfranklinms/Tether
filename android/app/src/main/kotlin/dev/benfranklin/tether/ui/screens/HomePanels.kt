package dev.benfranklin.tether.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.SouthWest
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.tether.core.features.MacStatusPolicy
import dev.benfranklin.tether.core.features.Panel
import dev.benfranklin.tether.core.features.PanelKind
import dev.benfranklin.tether.core.features.PanelSize
import dev.benfranklin.tether.core.features.ShelfMeta
import dev.benfranklin.tether.core.features.TransferList
import dev.benfranklin.tether.core.features.TransferMeta
import dev.benfranklin.tether.core.features.When
import dev.benfranklin.tether.core.proto.MacMediaCmds
import dev.benfranklin.tether.core.proto.ShelfKinds
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.ui.Haptics
import dev.benfranklin.tether.ui.pressScale
import dev.benfranklin.tether.ui.sharedHero
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.MonoCode
import dev.benfranklin.tether.ui.theme.Motion
import dev.benfranklin.tether.v7.RecentPhoto
import kotlinx.coroutines.delay
import java.time.ZoneId

/** One panel's inside, by kind and size. In edit mode nothing inside reacts to taps. */
@Composable
fun PanelContent(p: Panel, m: HomeModel, data: HomeData, a: HomeActions, editing: Boolean) {
    when (p.kind) {
        PanelKind.SEND_CLIPBOARD -> ActionPanel(
            // Never the clipboard itself (0.5.3 fix: a copied phone number sat on Home for anyone to read).
            "Send clipboard", "Tap to send", Icons.Outlined.ContentPaste,
            p.size, enabled = m.connected && !editing, onClick = a::sendClipboard,
        )
        PanelKind.SEND_FILE -> ActionPanel("Send file", "Browse", Icons.Outlined.UploadFile, p.size, enabled = !editing,
            onClick = a::sendFile)
        PanelKind.SHELF -> ShelfPanel(p.size, m, data, a, editing)
        PanelKind.MAC -> MacPanel(p.size, m, data)
        PanelKind.CLIPBOARD -> ClipboardPanel(p.size, data, a, editing)
        PanelKind.NOW_PLAYING -> NowPlayingPanel(p.size, m, data, a, editing)
        PanelKind.TRANSFERS -> TransfersPanel(p.size, m, a, editing)
        PanelKind.PHOTOS -> PhotosPanel(p.size, data, a, editing)
        PanelKind.HOTSPOT -> HotspotPanel(p.size, data, a, editing)
    }
}

private val PAD = 16.dp

/** What Now playing shows: the Mac's active media while linked, else null (idle). */
private fun nowPlaying(m: HomeModel, data: HomeData) = data.mac?.media?.takeIf { it.active && m.connected }

/** The transfers the Recent transfers panel lists at [size]. */
private fun transfersShown(m: HomeModel, size: PanelSize): List<dev.benfranklin.tether.core.transfer.TransferRecord> {
    val rows = if (size == PanelSize.LARGE) 7 else 3
    return if (rows > TransferList.HOME_MAX) {
        m.transfers.sortedByDescending { it.createdAt }.take(rows)
    } else {
        TransferList.home(m.transfers, m.now)
    }
}

/**
 * A full-width panel with nothing in it collapses to [PANEL_COMPACT_HEIGHT] (0.5.3 fix: an empty
 * Shelf or an idle Now playing was a tall card around one grey line). It grows back on a spring
 * as soon as something arrives.
 */
internal fun panelCompact(p: Panel, m: HomeModel, data: HomeData): Boolean = p.span == 2 && when (p.kind) {
    PanelKind.SHELF -> data.shelf.isEmpty()
    PanelKind.NOW_PLAYING -> nowPlaying(m, data) == null
    PanelKind.TRANSFERS -> transfersShown(m, p.size).isEmpty()
    else -> false
}

/** A whole-panel tap target with press scale + ripple + a tick. */
@Composable
private fun Modifier.panelClick(enabled: Boolean, desc: String? = null, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    return this.pressScale(source).clickable(source, ripple(), enabled = enabled, role = Role.Button) {
        Haptics.tick(view)
        onClick()
    }.then(if (desc != null) Modifier.semantics { contentDescription = desc } else Modifier)
}

/** Panel header: small icon, title, optional trailing count / action. */
@Composable
private fun PanelHeader(icon: ImageVector, title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Mono.Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Mono.Text, maxLines = 1, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Count(n: Int) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        n, label = "count",
        transitionSpec = {
            if (reduce) {
                fadeIn(Motion.fade(true)) togetherWith fadeOut(Motion.fade(true))
            } else {
                val up = targetState > initialState
                (slideInVertically(Motion.spring(false)) { if (up) it else -it } + fadeIn()) togetherWith
                    (slideOutVertically(Motion.spring(false)) { if (up) -it else it } + fadeOut())
            }
        },
    ) { c -> Text("$c", style = MaterialTheme.typography.bodyMedium, color = Mono.Muted, fontFamily = MonoCode) }
}

@Composable
private fun IconCircle(icon: ImageVector, enabled: Boolean, size: Dp = 42.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Mono.SurfaceHigh), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = if (enabled) Mono.Text else Mono.Muted, modifier = Modifier.size(size * 0.48f))
    }
}

// ---------------- Send clipboard / Send file ----------------

@Composable
private fun ActionPanel(title: String, subtitle: String, icon: ImageVector, size: PanelSize, enabled: Boolean, onClick: () -> Unit) {
    val fg = if (enabled) Mono.Text else Mono.Muted
    if (size == PanelSize.SMALL) {
        Column(Modifier.fillMaxSize().panelClick(enabled, title, onClick).padding(PAD)) {
            Row(Modifier.fillMaxWidth()) {
                IconCircle(icon, enabled)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.NorthEast, contentDescription = null, tint = Mono.Muted, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp), color = fg, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Mono.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Row(Modifier.fillMaxSize().panelClick(enabled, title, onClick).padding(PAD), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(icon, enabled, 52.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = fg, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Mono.Muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.NorthEast, contentDescription = null, tint = Mono.Muted, modifier = Modifier.size(20.dp))
        }
    }
}

// ---------------- Shelf ----------------

@Composable
private fun ShelfPanel(size: PanelSize, m: HomeModel, data: HomeData, a: HomeActions, editing: Boolean) {
    val reduce = LocalReduceMotion.current
    val rows = if (size == PanelSize.LARGE) 7 else 3
    val zone = remember { ZoneId.systemDefault() }
    Column(Modifier.fillMaxSize().padding(horizontal = PAD, vertical = 14.dp)) {
        PanelHeader(
            Icons.Outlined.Inbox, "Shelf",
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !editing, onClick = a::openShelf).sharedHero("shelf-title", reduce),
        ) {
            Count(data.shelf.size)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Open Shelf", tint = Mono.Muted,
                modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (data.shelf.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                Text("Nothing pinned", style = MaterialTheme.typography.bodyMedium, color = Mono.Muted)
            }
        }
        data.shelf.take(rows).forEach { item ->
            Row(
                Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !editing) { a.openShelfItem(item.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShelfLeading(item, 22.dp, thumbnail = { a.shelfThumb(it) })
                Spacer(Modifier.width(10.dp))
                val title = ShelfMeta.lines(item, ::formatBytes).first
                Text(
                    if (item.kind == ShelfKinds.FILE) TransferMeta.middle(title, 40) else title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(When.short(item.created, m.now, zone), style = MaterialTheme.typography.bodySmall, color = Mono.Muted,
                    fontFamily = MonoCode)
            }
        }
    }
}

// ---------------- Mac status ----------------

@Composable
private fun MacPanel(size: PanelSize, m: HomeModel, data: HomeData) {
    val reduce = LocalReduceMotion.current
    val s = data.mac
    val level = s?.battery?.takeIf { m.connected }
    val sub = when {
        !m.connected -> "Not connected"
        !data.macSupported -> "Update Tether on your Mac"
        s == null -> "Checking…"
        s.battery == null -> "No battery"
        s.charging == true -> if (s.battery >= FULL) "Charged" else "Charging"
        else -> "On battery"
    }
    val fill by animateFloatAsState((level ?: 0) / 100f, Motion.spring(reduce, stiffness = Spring.StiffnessLow), label = "battery")
    Column(
        Modifier.fillMaxSize().padding(PAD).semantics(mergeDescendants = true) {
            contentDescription = "Mac battery " + (level?.let { "$it percent, " } ?: "") + sub
        },
    ) {
        PanelHeader(Icons.Outlined.LaptopMac, "Mac")
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedContent(
                level, label = "level",
                transitionSpec = { fadeIn(Motion.fade(reduce)) togetherWith fadeOut(Motion.fade(reduce)) },
            ) { l ->
                Text(
                    l?.toString() ?: "—", fontFamily = MonoCode, fontWeight = FontWeight.Normal,
                    fontSize = if (size == PanelSize.SMALL) 36.sp else 44.sp,
                    lineHeight = if (size == PanelSize.SMALL) 40.sp else 44.sp,
                    color = if (l != null) Mono.Text else Mono.Muted,
                )
            }
            if (level != null) {
                Text("%", fontFamily = MonoCode, fontSize = 18.sp, color = Mono.Muted, modifier = Modifier.padding(start = 4.dp,
                    bottom = 5.dp))
            }
            if (size == PanelSize.WIDE) {
                Spacer(Modifier.weight(1f))
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = Mono.Muted, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { fill }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = Mono.Accent, trackColor = Mono.Line, drawStopIndicator = {}, gapSize = 0.dp,
        )
        if (size == PanelSize.SMALL) {
            Spacer(Modifier.height(6.dp))
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = Mono.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val FULL = 100
private const val CLIP_ROWS = 3

// ---------------- Clipboard ----------------

@Composable
private fun ClipboardPanel(size: PanelSize, data: HomeData, a: HomeActions, editing: Boolean) {
    val view = LocalView.current
    Column(Modifier.fillMaxSize().padding(horizontal = PAD, vertical = 14.dp)) {
        PanelHeader(Icons.AutoMirrored.Outlined.Notes, "Clipboard") { if (data.clips.isNotEmpty()) Count(data.clips.size) }
        Spacer(Modifier.height(4.dp))
        if (data.clips.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                Text("Copies show up here", style = MaterialTheme.typography.bodyMedium, color = Mono.Muted)
            }
        }
        data.clips.take(CLIP_ROWS).forEachIndexed { i, c ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Mono.Line))
            var copied by remember(c.text) { mutableStateOf(false) }
            LaunchedEffect(copied) {
                if (copied) {
                    delay(1_200)
                    copied = false
                }
            }
            Row(
                Modifier.fillMaxWidth().height(30.dp).clickable(enabled = !editing) {
                    a.copyClip(c.text)
                    copied = true
                    Haptics.confirm(view)
                }.semantics { contentDescription = "Copy " + c.text.take(60) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    dev.benfranklin.tether.core.features.ClipRecents.preview(c.text, 60),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (i == 0) Mono.Text else Mono.Muted, modifier = Modifier.weight(1f),
                )
                if (size != PanelSize.SMALL && !copied) {
                    Text(if (c.fromMac) "Mac" else "Phone", style = MaterialTheme.typography.bodySmall, color = Mono.Muted)
                }
                AnimatedContent(copied, label = "copied", transitionSpec = {
                    (fadeIn() + scaleIn(Motion.bouncy(false), 0.5f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.5f))
                }) { done ->
                    if (done) Icon(Icons.Outlined.Check, contentDescription = null, tint = Mono.Text, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// ---------------- Now playing ----------------

@Composable
private fun NowPlayingPanel(size: PanelSize, m: HomeModel, data: HomeData, a: HomeActions, editing: Boolean) {
    val reduce = LocalReduceMotion.current
    val s = data.mac
    val media = nowPlaying(m, data)
    if (media == null || s == null) {
        NowPlayingIdle(m.connected)
        return
    }
    var tick by remember { mutableLongStateOf(m.now) }
    LaunchedEffect(media?.playing, s?.at) {
        while (media?.playing == true) {
            tick = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val pos = media?.let { MacStatusPolicy.position(it.positionMs, it.durationMs, it.playing, s.at, maxOf(tick, s.at)) } ?: 0L
    val progress by animateFloatAsState(
        if (media != null && media.durationMs > 0) pos.toFloat() / media.durationMs else 0f,
        Motion.spring(reduce, stiffness = Spring.StiffnessLow), label = "np",
    )
    val title = media?.title?.ifBlank { media.app } ?: "Nothing playing"
    val artist = media?.artist.orEmpty()
    val controls: @Composable (Boolean) -> Unit = { big ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (big) MediaButton(Icons.Outlined.SkipPrevious, "Previous", media != null && !editing) { a.mediaCommand(MacMediaCmds.PREV) }
            if (!big) MediaButton(Icons.Outlined.SkipNext, "Next", media != null && !editing) { a.mediaCommand(MacMediaCmds.NEXT) }
            PlayButton(media?.playing == true, media != null && !editing, if (big) 60.dp else 52.dp) { a.mediaCommand(MacMediaCmds.TOGGLE) }
            if (big) MediaButton(Icons.Outlined.SkipNext, "Next", media != null && !editing) { a.mediaCommand(MacMediaCmds.NEXT) }
        }
    }
    val text: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LaptopMac, contentDescription = null, tint = Mono.Muted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("On Mac", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = Mono.Muted)
            }
            Spacer(Modifier.height(2.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = if (media != null) Mono.Text else Mono.Muted, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (artist.isNotEmpty()) {
                Text(artist, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = Mono.Muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val bar: @Composable () -> Unit = {
        Column {
            LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Mono.Accent, trackColor = Mono.Line, drawStopIndicator = {}, gapSize = 0.dp,
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(if (media != null) MacStatusPolicy.clock(pos) else "", style = MaterialTheme.typography.labelSmall, color = Mono.Muted,
                    modifier = Modifier.weight(1f))
                Text(if (media != null && media.durationMs > 0) MacStatusPolicy.clock(media.durationMs) else "",
                    style = MaterialTheme.typography.labelSmall, color = Mono.Muted)
            }
        }
    }
    if (size == PanelSize.LARGE) {
        Column(Modifier.fillMaxSize().padding(PAD)) {
            Row(Modifier.weight(1f)) {
                Artwork(media?.title.orEmpty(), Modifier.fillMaxHeight().aspectRatio(1f))
                Spacer(Modifier.width(16.dp))
                text(Modifier.weight(1f).padding(top = 4.dp))
            }
            Spacer(Modifier.height(12.dp))
            bar()
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { controls(true) }
        }
    } else {
        Row(Modifier.fillMaxSize().padding(PAD)) {
            Artwork(media?.title.orEmpty(), Modifier.fillMaxHeight().aspectRatio(1f))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row {
                    text(Modifier.weight(1f))
                    controls(false)
                }
                Spacer(Modifier.weight(1f))
                bar()
            }
        }
    }
}

/**
 * Nothing playing (or no Mac): one calm line instead of an empty artwork box, disabled controls
 * and a title squeezed to "Nothi…" (0.5.3 fix). Home shows it at the compact height.
 */
@Composable
private fun NowPlayingIdle(connected: Boolean) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = PAD).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconCircle(Icons.Outlined.MusicNote, enabled = false)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Now playing", style = MaterialTheme.typography.titleMedium, color = Mono.Text, maxLines = 1)
            Text(
                if (connected) "Nothing playing on your Mac" else "Your Mac isn't connected",
                style = MaterialTheme.typography.bodyMedium, color = Mono.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** No artwork travels from the Mac, so a calm mono glyph stands in (a moon over its reflection). */
@Composable
private fun Artwork(seed: String, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(Brush.verticalGradient(listOf(Color(0xFF1F1F22), Color(0xFF0E0E10))))
            .border(1.dp, Mono.Line, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (seed.isEmpty()) {
            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = Mono.Muted, modifier = Modifier.size(28.dp))
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height * 0.44f)
                val r = size.minDimension * 0.24f
                drawCircle(Brush.verticalGradient(listOf(Color(0xFFEDEDED), Color(0xFF9C9CA0)), c.y - r, c.y + r), r, c)
                val horizon = size.height * 0.62f
                drawRect(Color(0xFF0B0B0D), Offset(0f, horizon), androidx.compose.ui.geometry.Size(size.width, size.height - horizon))
                var y = horizon + size.height * 0.03f
                var w = r * 1.5f
                while (y < size.height * 0.95f) {
                    drawLine(Color(0x88D8D8DC), Offset(c.x - w / 2, y), Offset(c.x + w / 2, y), strokeWidth = size.height * 0.012f)
                    y += size.height * 0.035f
                    w *= 0.82f
                }
            }
        }
    }
}

@Composable
private fun MediaButton(icon: ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    Box(
        Modifier.pressScale(source).size(40.dp).clip(CircleShape)
            .clickable(source, ripple(), enabled = enabled, role = Role.Button) {
                Haptics.tick(view)
                onClick()
            }.semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = if (enabled) Mono.Text else Mono.Muted, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun PlayButton(playing: Boolean, enabled: Boolean, size: Dp, onClick: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    val bg by animateColorAsState(if (enabled) Mono.Text else Mono.SurfaceHigh, Motion.fade(reduce), label = "playBg")
    Box(
        Modifier.pressScale(source).size(size).clip(CircleShape).background(bg)
            .clickable(source, null, enabled = enabled, role = Role.Button) {
                Haptics.confirm(view)
                onClick()
            }.semantics { contentDescription = if (playing) "Pause on Mac" else "Play on Mac" },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(playing, label = "play", transitionSpec = {
            if (reduce) {
                fadeIn(Motion.fade(true)) togetherWith fadeOut(Motion.fade(true))
            } else {
                (fadeIn() + scaleIn(Motion.bouncy(false), 0.4f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.4f))
            }
        }) { p ->
            Icon(if (p) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = null,
                tint = if (enabled) Mono.Bg else Mono.Muted, modifier = Modifier.size(size * 0.46f))
        }
    }
}

// ---------------- Recent transfers ----------------

@Composable
private fun TransfersPanel(size: PanelSize, m: HomeModel, a: HomeActions, editing: Boolean) {
    val reduce = LocalReduceMotion.current
    val list = remember(m.transfers, m.now, size) { transfersShown(m, size) }
    Column(Modifier.fillMaxSize().padding(horizontal = PAD, vertical = 14.dp)) {
        PanelHeader(Icons.Outlined.NorthEast, "Recent transfers", Modifier.sharedHero("transfers-title", reduce)) {
            Text(
                "See all", style = MaterialTheme.typography.bodyMedium, color = Mono.Text,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !editing, onClick = a::seeAllTransfers)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (list.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                Text("Nothing sent yet", style = MaterialTheme.typography.bodyMedium, color = Mono.Muted)
            }
        }
        list.forEach { t ->
            val progress by animateFloatAsState(t.progress, Motion.spring(reduce, stiffness = Spring.StiffnessLow), label = "p")
            Row(
                Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !editing && t.status == TransferStatus.DONE) { a.openTransfer(t) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = (if (t.direction == Direction.OUT) "Sent " else "Received ") + t.name
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(Mono.SurfaceHigh), contentAlignment = Alignment.Center) {
                    Icon(
                        if (t.direction == Direction.OUT) Icons.Outlined.NorthEast else Icons.Outlined.SouthWest, contentDescription = null,
                        tint = Mono.Text, modifier = Modifier.size(13.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(TransferMeta.middle(t.name, 36), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (!t.status.isFinal) {
                        LinearProgressIndicator(
                            progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
                            color = Mono.Accent, trackColor = Mono.Line, drawStopIndicator = {}, gapSize = 0.dp,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    TransferMeta.percent(t)
                        ?: if (t.status == TransferStatus.DONE) formatBytes(t.size) else TransferMeta.subtitle(t, ::formatBytes),
                    style = MaterialTheme.typography.bodySmall, color = Mono.Muted, fontFamily = MonoCode, maxLines = 1,
                )
            }
        }
    }
}

// ---------------- Photos ----------------

@Composable
private fun PhotosPanel(size: PanelSize, data: HomeData, a: HomeActions, editing: Boolean) {
    val view = LocalView.current
    val reduce = LocalReduceMotion.current
    val perRow = 4
    val count = if (size == PanelSize.LARGE) perRow * 2 else perRow
    var picked by remember { mutableStateOf(setOf<Long>()) }
    val shown = data.photos.take(count)
    Column(Modifier.fillMaxSize().padding(horizontal = PAD, vertical = 12.dp)) {
        PanelHeader(Icons.Outlined.Image, "Photos") {
            AnimatedContent(picked.size, label = "send", transitionSpec = {
                if (reduce) fadeIn(Motion.fade(true)) togetherWith fadeOut(Motion.fade(true))
                else (fadeIn() + scaleIn(Motion.bouncy(false), 0.7f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.7f))
            }) { n ->
                if (n > 0) {
                    val source = remember { MutableInteractionSource() }
                    Box(
                        Modifier.pressScale(source).clip(RoundedCornerShape(50)).background(Mono.Text)
                            .clickable(source, null, enabled = !editing, role = Role.Button) {
                                Haptics.confirm(view)
                                a.sendPhotos(shown.filter { it.id in picked })
                                picked = emptySet()
                            }.heightIn(min = 32.dp).padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Send $n", style = MaterialTheme.typography.labelLarge, color = Mono.Bg) }
                } else {
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (!data.photosAllowed) {
            Box(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).border(1.dp, Mono.Line, RoundedCornerShape(14.dp))
                    .clickable(enabled = !editing, onClick = a::allowPhotos),
                contentAlignment = Alignment.Center,
            ) { Text("Allow photos", style = MaterialTheme.typography.labelLarge, color = Mono.Text) }
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val gap = 8.dp
            val cell = minOf((maxWidth - gap * (perRow - 1)) / perRow, if (size == PanelSize.LARGE) (maxHeight - gap) / 2 else maxHeight)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                shown.chunked(perRow).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { ph ->
                            PhotoCell(ph, cell, ph.id in picked, !editing, a) {
                                picked = if (ph.id in picked) picked - ph.id else picked + ph.id
                                Haptics.tick(view)
                            }
                        }
                    }
                }
                if (shown.isEmpty()) Text("No photos yet", style = MaterialTheme.typography.bodyMedium, color = Mono.Muted)
            }
        }
    }
}

@Composable
private fun PhotoCell(p: RecentPhoto, size: Dp, picked: Boolean, enabled: Boolean, a: HomeActions, onToggle: () -> Unit) {
    val reduce = LocalReduceMotion.current
    var bmp by remember(p.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(p.id) { bmp = a.photoThumb(p) }
    val scale by animateFloatAsState(if (picked) 0.9f else 1f, Motion.bouncy(reduce), label = "pick")
    Box(
        Modifier.size(size).graphicsScale(scale).clip(RoundedCornerShape(12.dp)).background(Mono.SurfaceHigh)
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle)
            .semantics {
                selected = picked
                contentDescription = "Photo"
            },
    ) {
        bmp?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape)
                .background(if (picked) Mono.Text else Color(0x33000000)).border(1.5.dp, Mono.Text.copy(alpha = 0.9f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (picked) Icon(Icons.Outlined.Check, contentDescription = null, tint = Mono.Bg, modifier = Modifier.size(13.dp)) }
    }
}

private fun Modifier.graphicsScale(s: Float) = this.then(
    Modifier.graphicsLayer {
        scaleX = s
        scaleY = s
    },
)

// ---------------- Hotspot ----------------

@Composable
private fun HotspotPanel(size: PanelSize, data: HomeData, a: HomeActions, editing: Boolean) {
    val state = when (data.hotspotState) {
        "on" -> "On"
        "starting", "manual" -> "Starting"
        else -> if (data.hotspotSsid != null) "Ready" else "Set up"
    }
    Column(Modifier.fillMaxSize().panelClick(!editing, "Hotspot $state", a::openHotspot).padding(PAD)) {
        PanelHeader(Icons.Outlined.WifiTethering, "Hotspot")
        Spacer(Modifier.weight(1f))
        Text(state, style = MaterialTheme.typography.headlineSmall.copy(fontSize = if (size == PanelSize.SMALL) 26.sp else 28.sp))
        Text(data.hotspotSsid ?: "For your Mac", style = MaterialTheme.typography.bodyMedium, color = Mono.Muted, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}
