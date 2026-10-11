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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.tether.core.features.FileKinds
import dev.benfranklin.tether.core.features.MacStatusPolicy
import dev.benfranklin.tether.core.features.ShelfMeta
import dev.benfranklin.tether.core.features.TransferList
import dev.benfranklin.tether.core.features.TransferMeta
import dev.benfranklin.tether.core.features.When
import dev.benfranklin.tether.core.proto.FindActions
import dev.benfranklin.tether.core.proto.MacMediaCmds
import dev.benfranklin.tether.core.proto.ShelfKinds
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.data.ClipLabel
import dev.benfranklin.tether.data.PhotoBadge
import dev.benfranklin.tether.ui.Haptics
import dev.benfranklin.tether.ui.clamp01
import dev.benfranklin.tether.ui.pressScale
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.MonoCode
import dev.benfranklin.tether.ui.theme.Motion
import dev.benfranklin.tether.v7.RecentPhoto
import kotlinx.coroutines.delay
import java.time.ZoneId

// 0.5.4 Home sections ("Control Center"). Pure mono; every tap ticks; springs respect Reduce Motion.

internal val BLOCK_RADIUS = 30.dp
internal val TILE_RADIUS = 24.dp
private val TILE_GAP = 10.dp
private val BLOCK_PAD = 12.dp
private val ROW_HEIGHT = 68.dp
private val LEAD = 44.dp

/** Alpha for Mac-dependent controls while the Mac isn't connected. */
private const val DIM = 0.38f

/** What Now playing shows: the Mac's active media while linked, else null (idle). */
internal fun nowPlaying(m: HomeModel, data: HomeData) = data.mac?.media?.takeIf { it.active && m.connected }

/** The transfers Home lists: running first, then the newest, at most three. */
internal fun homeTransfers(m: HomeModel): List<TransferRecord> = TransferList.home(m.transfers, m.now)

/** The rounded block that holds Quick controls and Now playing. */
@Composable
private fun Block(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(BLOCK_RADIUS)).background(Mono.Surface)
            .border(1.dp, Mono.Line, RoundedCornerShape(BLOCK_RADIUS)).padding(BLOCK_PAD),
    ) { content() }
}

// ---------------- Quick controls + Now playing ----------------

/** Quick controls (2×2) next to the Now playing tile: the approved Option C block. */
@Composable
internal fun ControlBlock(m: HomeModel, data: HomeData, a: HomeActions, editingOpen: Boolean) {
    Block {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val col = (maxWidth - TILE_GAP) / 2
            val b = (col - TILE_GAP) / 2
            val h = b * 2 + TILE_GAP
            Row(Modifier.fillMaxWidth().height(h), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                Column(Modifier.width(col), verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                        ClipboardButton(m, a, b, editingOpen)
                        FileButton(m, a, b, editingOpen)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                        HotspotButton(data, a, b, editingOpen)
                        PhotosButton(data, a, b, editingOpen)
                    }
                }
                NowPlayingTile(m, data, a, editingOpen, Modifier.width(col).fillMaxHeight())
            }
        }
    }
}

/** Quick controls on their own (Now playing hidden or elsewhere): four squares in a row. */
@Composable
internal fun QuickRow(m: HomeModel, data: HomeData, a: HomeActions, editingOpen: Boolean) {
    Block {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val b = (maxWidth - TILE_GAP * 3) / 4
            Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                ClipboardButton(m, a, b, editingOpen)
                FileButton(m, a, b, editingOpen)
                HotspotButton(data, a, b, editingOpen)
                PhotosButton(data, a, b, editingOpen)
            }
        }
    }
}

@Composable
private fun ClipboardButton(m: HomeModel, a: HomeActions, size: Dp, blocked: Boolean) {
    // Never the clipboard itself: the label is fixed ("Clipboard"), whatever was copied.
    SquareButton(
        "Clipboard", "Send clipboard", Icons.Outlined.ContentPaste, size,
        enabled = m.connected && !blocked, dim = !m.connected, onClick = a::sendClipboard,
    )
}

@Composable
private fun FileButton(m: HomeModel, a: HomeActions, size: Dp, blocked: Boolean) {
    // Files picked while the Mac is away queue and resume on reconnect, so it stays tappable (dimmed).
    SquareButton("File", "Send file", Icons.Outlined.UploadFile, size, enabled = !blocked, dim = !m.connected, onClick = a::sendFile)
}

@Composable
private fun HotspotButton(data: HomeData, a: HomeActions, size: Dp, blocked: Boolean) {
    val on = data.hotspotState == "on"
    SquareButton(
        "Hotspot", if (on) "Hotspot, on" else "Hotspot", Icons.Outlined.WifiTethering, size,
        enabled = !blocked, filled = on, onClick = a::openHotspot,
    )
}

@Composable
private fun PhotosButton(data: HomeData, a: HomeActions, size: Dp, blocked: Boolean) {
    val badge = PhotoBadge.label(data.newPhotos)
    SquareButton(
        "Photos", if (badge != null) "Photos, $badge new" else "Photos", Icons.Outlined.Image, size,
        enabled = !blocked, badge = badge, onClick = a::pickPhotos,
    )
}

/** One square of Quick controls: icon over label; [filled] = on (white with a dark icon). */
@Composable
private fun SquareButton(
    label: String,
    desc: String,
    icon: ImageVector,
    size: Dp,
    enabled: Boolean,
    dim: Boolean = false,
    filled: Boolean = false,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (filled) Mono.Text else Mono.SurfaceHigh, Motion.fade(reduce), label = "sqBg")
    val fg by animateColorAsState(if (filled) Mono.Bg else Mono.Text, Motion.fade(reduce), label = "sqFg")
    val alpha by animateFloatAsState(if (dim) DIM else 1f, Motion.fade(reduce), label = "sqDim")
    Box(Modifier.size(size)) {
        Column(
            Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.clamp01() }.pressScale(source)
                .clip(RoundedCornerShape(TILE_RADIUS)).background(bg)
                .border(1.dp, if (filled) Mono.Text else Mono.Line, RoundedCornerShape(TILE_RADIUS))
                .clickable(source, ripple(), enabled = enabled, role = Role.Button) {
                    Haptics.tick(view)
                    onClick()
                }
                .semantics { contentDescription = desc },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (size < 70.dp) 22.dp else 26.dp))
            Spacer(Modifier.height(if (size < 70.dp) 6.dp else 10.dp))
            Text(
                label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = squareLabelSp(size, LocalDensity.current.fontScale)),
                color = if (filled) Mono.Bg else Mono.Text.copy(alpha = 0.86f), maxLines = 1, softWrap = false,
            )
        }
        if (badge != null) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(24.dp).clip(CircleShape).background(Mono.Text)
                    .border(2.dp, Mono.Surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(badge, fontFamily = MonoCode, fontSize = 11.sp, color = Mono.Bg, maxLines = 1) }
        }
    }
}

/**
 * The square's label grows with the system font size up to 1.15× and no further: the square has a
 * fixed size, and at 1.3× "Clipboard" no longer fit and was cut (0.5.4 font-scale check).
 */
internal fun squareLabelSp(size: Dp, fontScale: Float): androidx.compose.ui.unit.TextUnit {
    val base = if (size < 74.dp) 13f else 15f
    val scale = if (fontScale > 0f) fontScale else 1f
    return (base * scale.coerceAtMost(MAX_LABEL_SCALE) / scale).sp
}

private const val MAX_LABEL_SCALE = 1.15f

/** The Now playing tile inside the block: art, title, artist, progress, prev/play/next; or a calm idle state. */
@Composable
private fun NowPlayingTile(m: HomeModel, data: HomeData, a: HomeActions, blocked: Boolean, modifier: Modifier) {
    val reduce = LocalReduceMotion.current
    val alpha by animateFloatAsState(if (m.connected) 1f else DIM, Motion.fade(reduce), label = "npDim")
    val media = nowPlaying(m, data)
    val s = data.mac
    Box(
        modifier.graphicsLayer { this.alpha = alpha.clamp01() }.clip(RoundedCornerShape(TILE_RADIUS)).background(Mono.SurfaceHigh)
            .border(1.dp, Mono.Line, RoundedCornerShape(TILE_RADIUS)),
    ) {
        if (media == null || s == null) {
            Column(Modifier.fillMaxSize().padding(14.dp).semantics(mergeDescendants = true) {}) {
                // The glyph gives way first when space is short (large font on a narrow phone), never the text.
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Mono.Surface), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = Mono.Muted, modifier = Modifier.size(20.dp))
                    }
                }
                // Wraps instead of cutting off ("Nothi…" in 0.5.3).
                Text("Nothing playing", style = MaterialTheme.typography.titleMedium, color = Mono.Text, maxLines = 2)
                Text(
                    if (m.connected) "on your Mac" else "Mac not connected", style = MaterialTheme.typography.bodyMedium,
                    color = Mono.Muted, maxLines = 2,
                )
            }
            return@Box
        }
        val progress = rememberProgress(media, s.at, m.now)
        Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 8.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Artwork(media.title, Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true))
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.Laptop, contentDescription = "On your Mac", tint = Mono.Muted, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(media.title.ifBlank { media.app }, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (media.artist.isNotBlank()) {
                Text(media.artist, style = MaterialTheme.typography.bodyMedium, color = Mono.Muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Bar(progress)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                MediaButton(Icons.Outlined.FastRewind, "Previous", m.connected && !blocked) { a.mediaCommand(MacMediaCmds.PREV) }
                PlayButton(media.playing, m.connected && !blocked, 38.dp) { a.mediaCommand(MacMediaCmds.TOGGLE) }
                MediaButton(Icons.Outlined.FastForward, "Next", m.connected && !blocked) { a.mediaCommand(MacMediaCmds.NEXT) }
            }
        }
    }
}

/** Now playing on its own row (Quick controls hidden or not next to it). */
@Composable
internal fun PlayerRow(m: HomeModel, data: HomeData, a: HomeActions, blocked: Boolean) {
    val reduce = LocalReduceMotion.current
    val alpha by animateFloatAsState(if (m.connected) 1f else DIM, Motion.fade(reduce), label = "prDim")
    val media = nowPlaying(m, data)
    val s = data.mac
    Block(Modifier.graphicsLayer { this.alpha = alpha.clamp01() }) {
        if (media == null || s == null) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Mono.SurfaceHigh), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = Mono.Muted, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Nothing playing", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(if (m.connected) "on your Mac" else "Mac not connected", style = MaterialTheme.typography.bodyMedium,
                        color = Mono.Muted, maxLines = 1)
                }
            }
            return@Block
        }
        val progress = rememberProgress(media, s.at, m.now)
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(media.title, Modifier.size(56.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(media.title.ifBlank { media.app }, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(media.artist, style = MaterialTheme.typography.bodyMedium, color = Mono.Muted, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                MediaButton(Icons.Outlined.FastRewind, "Previous", m.connected && !blocked) { a.mediaCommand(MacMediaCmds.PREV) }
                PlayButton(media.playing, m.connected && !blocked, 44.dp) { a.mediaCommand(MacMediaCmds.TOGGLE) }
                MediaButton(Icons.Outlined.FastForward, "Next", m.connected && !blocked) { a.mediaCommand(MacMediaCmds.NEXT) }
            }
            Spacer(Modifier.height(10.dp))
            Bar(progress)
        }
    }
}

@Composable
private fun rememberProgress(media: dev.benfranklin.tether.core.proto.MacMedia, at: Long, now: Long): Float {
    val reduce = LocalReduceMotion.current
    var tick by remember { mutableLongStateOf(now) }
    LaunchedEffect(media.playing, at) {
        while (media.playing) {
            tick = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val pos = MacStatusPolicy.position(media.positionMs, media.durationMs, media.playing, at, maxOf(tick, at))
    val target = if (media.durationMs > 0) (pos.toFloat() / media.durationMs).clamp01() else 0f
    val p by animateFloatAsState(target, Motion.spring(reduce, stiffness = Spring.StiffnessLow, damping = 1f), label = "np")
    return p.clamp01()
}

@Composable
private fun Bar(progress: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress.clamp01() }, modifier = modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
        color = Mono.Accent, trackColor = Mono.Line, drawStopIndicator = {}, gapSize = 0.dp,
    )
}

/** No artwork travels from the Mac, so a calm mono glyph stands in (a moon over its reflection). */
@Composable
private fun Artwork(seed: String, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(Brush.verticalGradient(listOf(Color(0xFF2A2A2D), Color(0xFF0E0E10))))
            .border(1.dp, Mono.Line, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (seed.isEmpty()) {
            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = Mono.Muted, modifier = Modifier.size(22.dp))
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height * 0.44f)
                val r = size.minDimension * 0.24f
                drawCircle(Brush.verticalGradient(listOf(Color(0xFFEDEDED), Color(0xFF9C9CA0)), c.y - r, c.y + r), r, c)
                val horizon = size.height * 0.6f
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
        Modifier.pressScale(source).size(36.dp).clip(CircleShape)
            .clickable(source, ripple(), enabled = enabled, role = Role.Button) {
                Haptics.tick(view)
                onClick()
            }.semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = if (enabled) Mono.Text else Mono.Muted, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun PlayButton(playing: Boolean, enabled: Boolean, size: Dp, onClick: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    Box(
        Modifier.pressScale(source).size(size).clip(CircleShape).background(Mono.Text)
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
            Icon(if (p) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = null, tint = Mono.Bg,
                modifier = Modifier.size(size * 0.5f))
        }
    }
}

// ---------------- Section heading + empty line ----------------

/** "Recent transfers ........ See all": a quiet heading with an optional count and trailing action. */
@Composable
internal fun SectionTitle(title: String, count: Int? = null, action: String? = null, actionDesc: String? = null,
    actionIcon: ImageVector? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 28.dp, bottom = 6.dp).heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp), color = Mono.Muted, maxLines = 1,
            modifier = Modifier.semantics { heading() })
        if (count != null) {
            Spacer(Modifier.width(10.dp))
            Count(count)
        }
        Spacer(Modifier.weight(1f))
        if (onAction != null && (action != null || actionIcon != null)) {
            val view = LocalView.current
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) {
                    Haptics.tick(view)
                    onAction()
                }.padding(horizontal = 6.dp, vertical = 4.dp)
                    .semantics { if (actionDesc != null) contentDescription = actionDesc },
                contentAlignment = Alignment.Center,
            ) {
                if (actionIcon != null) {
                    Icon(actionIcon, contentDescription = null, tint = Mono.Text, modifier = Modifier.size(22.dp))
                } else {
                    Text(action.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = Mono.Text)
                }
            }
        }
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
    ) { c -> Text("$c", style = MaterialTheme.typography.bodyLarge, color = Mono.Muted, fontFamily = MonoCode) }
}

/** An empty section: its heading plus one grey line, never a big empty card. */
@Composable
internal fun EmptyLine(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text, style = MaterialTheme.typography.bodyLarge, color = Mono.Muted,
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
    )
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(start = LEAD + 16.dp).height(1.dp).background(Mono.Line))
}

// ---------------- Recent transfers ----------------

@Composable
internal fun TransfersSection(m: HomeModel, a: HomeActions) {
    val list = remember(m.transfers, m.now) { homeTransfers(m) }
    SectionTitle("Recent transfers", action = "See all", actionDesc = "See all transfers", onAction = a::seeAllTransfers)
    if (list.isEmpty()) {
        EmptyLine("None yet")
        return
    }
    list.forEachIndexed { i, t ->
        if (i > 0) RowDivider()
        TransferRow(t, m.now, a)
    }
}

@Composable
private fun TransferRow(t: TransferRecord, now: Long, a: HomeActions) {
    val reduce = LocalReduceMotion.current
    val zone = remember { ZoneId.systemDefault() }
    val view = LocalView.current
    val progress by animateFloatAsState(t.progress, Motion.spring(reduce, stiffness = Spring.StiffnessLow, damping = 1f), label = "p")
    val running = !t.status.isFinal && t.status != TransferStatus.WAITING
    Row(
        Modifier.fillMaxWidth().height(ROW_HEIGHT).clip(RoundedCornerShape(12.dp))
            .clickable(enabled = t.status == TransferStatus.DONE) {
                Haptics.tick(view)
                a.openTransfer(t)
            }
            .semantics(mergeDescendants = true) {
                contentDescription = (if (t.direction == Direction.OUT) "Sent " else "Received ") + t.name + ". " + statusLine(t)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(LEAD)) {
            FileLead(t.name, t.mime, LEAD)
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 5.dp, y = 5.dp).size(20.dp).clip(CircleShape).background(Mono.Bg)
                    .border(1.dp, Mono.Line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (t.direction == Direction.OUT) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                    contentDescription = null, tint = Mono.Text, modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(TransferMeta.middle(t.name, 32), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (t.status == TransferStatus.DONE) statusLine(t) else TransferMeta.subtitle(t, ::formatBytes),
                    style = MaterialTheme.typography.bodyMedium, color = Mono.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (running) Modifier else Modifier.weight(1f, fill = false),
                )
                if (running) {
                    Spacer(Modifier.width(12.dp))
                    Bar(progress, Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            val pct = TransferMeta.percent(t)
            if (pct != null) {
                Text(pct, style = MaterialTheme.typography.bodyMedium, fontFamily = MonoCode, color = Mono.Text)
                Text(if (t.direction == Direction.OUT) "Sending" else "Receiving", style = MaterialTheme.typography.bodyMedium,
                    color = Mono.Muted)
            } else {
                Text(When.short(maxOf(t.createdAt, t.updatedAt), now, zone), style = MaterialTheme.typography.bodyMedium,
                    fontFamily = MonoCode, color = Mono.Muted)
            }
        }
    }
}

/** A transfer's leading visual: picture / video glyph on a soft square, or the paper document with its type. */
@Composable
private fun FileLead(name: String, mime: String?, size: Dp) {
    val shape = RoundedCornerShape(size * 0.24f)
    when {
        FileKinds.isImage(mime, name) -> GlyphSquare(Icons.Outlined.Image, size, shape)
        isVideo(mime, name) -> GlyphSquare(Icons.Outlined.Movie, size, shape)
        else -> DocIcon(ShelfMeta.badge(mime, name), size)
    }
}

private val VIDEO = setOf("mp4", "mov", "mkv", "webm", "avi", "3gp")

internal fun isVideo(mime: String?, name: String): Boolean =
    mime?.startsWith("video/") == true || name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in VIDEO

@Composable
private fun GlyphSquare(icon: ImageVector, size: Dp, shape: RoundedCornerShape) {
    Box(
        Modifier.size(size).clip(shape).background(Brush.verticalGradient(listOf(Color(0xFF3A3A3E), Color(0xFF18181B))))
            .border(1.dp, Mono.Line, shape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = Mono.Text, modifier = Modifier.size(size * 0.46f)) }
}

// ---------------- Shelf ----------------

@Composable
internal fun ShelfSection(m: HomeModel, data: HomeData, a: HomeActions) {
    val zone = remember { ZoneId.systemDefault() }
    val view = LocalView.current
    if (data.shelf.isEmpty()) {
        SectionTitle("Shelf", actionIcon = Icons.Outlined.Add, actionDesc = "Open Shelf", onAction = a::openShelf)
        EmptyLine("Empty")
        return
    }
    SectionTitle("Shelf", count = data.shelf.size, action = "See all", actionDesc = "Open Shelf", onAction = a::openShelf)
    data.shelf.take(SHELF_ROWS).forEachIndexed { i, item ->
        if (i > 0) RowDivider()
        val (title, sub) = ShelfMeta.lines(item, ::formatBytes)
        Row(
            Modifier.fillMaxWidth().height(ROW_HEIGHT).clip(RoundedCornerShape(12.dp))
                .clickable {
                    Haptics.tick(view)
                    a.openShelfItem(item.id)
                }
                .semantics(mergeDescendants = true) { contentDescription = "$title, $sub" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShelfLeading(item, LEAD, thumbnail = { a.shelfThumb(it) })
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(if (item.kind == ShelfKinds.FILE) TransferMeta.middle(title, 34) else title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = Mono.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(When.short(item.created, m.now, zone), style = MaterialTheme.typography.bodyMedium, fontFamily = MonoCode,
                    color = Mono.Muted)
                Text(ShelfMeta.source(item), style = MaterialTheme.typography.bodyMedium, color = Mono.Muted)
            }
        }
    }
}

private const val SHELF_ROWS = 3
private const val CLIP_ROWS = 3
private const val PHOTO_CELLS = 4

// ---------------- Clipboard history ----------------

@Composable
internal fun ClipboardSection(data: HomeData, a: HomeActions) {
    val view = LocalView.current
    SectionTitle("Clipboard history", count = data.clips.size.takeIf { it > 0 })
    if (data.clips.isEmpty()) {
        EmptyLine("Copies show up here")
        return
    }
    data.clips.take(CLIP_ROWS).forEachIndexed { i, c ->
        if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 4.dp).height(1.dp).background(Mono.Line))
        var copied by remember(c.text) { mutableStateOf(false) }
        LaunchedEffect(copied) {
            if (copied) {
                delay(1_200)
                copied = false
            }
        }
        Row(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)).clickable {
                a.copyClip(c.text)
                copied = true
                Haptics.confirm(view)
            }.padding(horizontal = 4.dp).semantics {
                contentDescription = "Copy " + ClipLabel.describe(c.text) + " from " + if (c.fromMac) "Mac" else "phone"
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ClipLabel.describe(c.text),
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (i == 0) Mono.Text else Mono.Muted, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            AnimatedContent(copied, label = "copied", transitionSpec = {
                (fadeIn() + scaleIn(Motion.bouncy(false), 0.5f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.5f))
            }) { done ->
                if (done) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = Mono.Text, modifier = Modifier.size(18.dp))
                } else {
                    Text(if (c.fromMac) "Mac" else "Phone", style = MaterialTheme.typography.bodyMedium, color = Mono.Muted)
                }
            }
        }
    }
}

// ---------------- Photos ----------------

@Composable
internal fun PhotosSection(data: HomeData, a: HomeActions) {
    val view = LocalView.current
    val reduce = LocalReduceMotion.current
    var picked by remember { mutableStateOf(setOf<Long>()) }
    val shown = data.photos.take(PHOTO_CELLS)
    val n = picked.count { id -> shown.any { it.id == id } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SectionTitle("Photos") }
        AnimatedContent(n, label = "send", modifier = Modifier.padding(top = 22.dp), transitionSpec = {
            if (reduce) fadeIn(Motion.fade(true)) togetherWith fadeOut(Motion.fade(true))
            else (fadeIn() + scaleIn(Motion.bouncy(false), 0.7f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.7f))
        }) { c ->
            if (c > 0) {
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier.pressScale(source).clip(RoundedCornerShape(50)).background(Mono.Text)
                        .clickable(source, null, role = Role.Button) {
                            Haptics.confirm(view)
                            a.sendPhotos(shown.filter { it.id in picked })
                            picked = emptySet()
                        }.heightIn(min = 34.dp).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Send $c", style = MaterialTheme.typography.labelLarge, color = Mono.Bg) }
            } else {
                Spacer(Modifier.height(34.dp))
            }
        }
    }
    when {
        !data.photosAllowed -> EmptyLine("Allow photo access", onClick = a::allowPhotos)
        shown.isEmpty() -> EmptyLine("No photos yet")
        else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            shown.forEach { ph ->
                PhotoCell(ph, ph.id in picked, a, Modifier.weight(1f)) {
                    picked = if (ph.id in picked) picked - ph.id else picked + ph.id
                    Haptics.tick(view)
                }
            }
            repeat(PHOTO_CELLS - shown.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun PhotoCell(p: RecentPhoto, picked: Boolean, a: HomeActions, modifier: Modifier, onToggle: () -> Unit) {
    val reduce = LocalReduceMotion.current
    var bmp by remember(p.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(p.id) { bmp = a.photoThumb(p) }
    val scale by animateFloatAsState(if (picked) 0.9f else 1f, Motion.bouncy(reduce), label = "pick")
    Box(
        modifier.aspectRatio(1f).graphicsLayer {
            scaleX = scale.coerceIn(0.5f, 1.1f)
            scaleY = scale.coerceIn(0.5f, 1.1f)
        }.clip(RoundedCornerShape(16.dp)).background(Mono.SurfaceHigh)
            .clickable(role = Role.Checkbox, onClick = onToggle)
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

// ---------------- Find my Mac ----------------

@Composable
internal fun FindSection(data: HomeData, a: HomeActions) {
    SectionTitle("Find my Mac")
    val f = data.find
    if (f == null) {
        EmptyLine("Pair a Mac to use it")
        return
    }
    // FindMacCard pads itself for a full-width list; bleed it out to Home's own margins.
    Box(Modifier.bleed(16.dp)) {
        FindMacCard(f, onRing = { a.findMac(FindActions.RING) }, onStop = { a.findMac(FindActions.STOP) },
            onLock = { a.findMac(FindActions.LOCK) })
    }
}

/** Widens content by [by] on each side (to undo a child's own horizontal padding). */
private fun Modifier.bleed(by: Dp) = layout { measurable, c ->
    val extra = (by * 2).roundToPx()
    val max = if (c.hasBoundedWidth) c.maxWidth + extra else c.maxWidth
    val p = measurable.measure(c.copy(minWidth = max, maxWidth = max))
    val w = if (c.hasBoundedWidth) c.maxWidth else p.width
    layout(w, p.height) { p.place(-by.roundToPx(), 0) }
}
