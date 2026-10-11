package dev.benfranklin.tether.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.benfranklin.tether.core.features.ClipRecents
import dev.benfranklin.tether.core.features.HomeLayout
import dev.benfranklin.tether.core.features.Panel
import dev.benfranklin.tether.core.features.PanelKind
import dev.benfranklin.tether.core.features.PanelSize
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.ui.Haptics
import dev.benfranklin.tether.ui.TetherMark
import dev.benfranklin.tether.ui.pressScale
import dev.benfranklin.tether.ui.screenPadding
import dev.benfranklin.tether.ui.sharedHero
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.Motion
import dev.benfranklin.tether.v7.MacStatusUi
import dev.benfranklin.tether.v7.RecentPhoto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Everything the status card and banners show; built from runtime state (or fakes in screenshot tests). */
data class HomeModel(
    val title: String,
    val subtitle: String,
    val connected: Boolean,
    val searching: Boolean,
    val wifi: Boolean,
    val error: String?,
    val notice: String?,
    val transfers: List<TransferRecord>,
    val mirroringOff: Boolean,
    /** The Mac is viewing this screen right now. */
    val screenLive: Boolean = false,
    /** Paired, Bluetooth usable, but not linked: offer a manual radio restart (0.3.1). */
    val canReconnect: Boolean = false,
    /** "Now" for relative times (fixed in screenshot tests). */
    val now: Long = System.currentTimeMillis(),
)

/** What the 0.5.3 panels show besides [HomeModel]. */
data class HomeData(
    val shelf: List<ShelfItem> = emptyList(),
    /** The phone's clipboard as text, for "Send clipboard" (null when unreadable / empty). */
    val clipboard: String? = null,
    val clips: List<ClipRecents.Entry> = emptyList(),
    val mac: MacStatusUi? = null,
    /** The connected Mac reports its status (0.5.3+). */
    val macSupported: Boolean = true,
    val photos: List<RecentPhoto> = emptyList(),
    val photosAllowed: Boolean = true,
    val hotspotSsid: String? = null,
    val hotspotState: String? = null,
    val updateVersion: String? = null,
)

/** What Home can do; every action has a no-op default so tests and previews stay short. */
@Suppress("TooManyFunctions")
interface HomeActions {
    fun sendClipboard() {}
    fun sendFile() {}
    fun settings() {}
    fun openShelf() {}
    fun openShelfItem(id: String) {}
    fun copyClip(text: String) {}
    fun seeAllTransfers() {}
    fun openTransfer(t: TransferRecord) {}
    fun mediaCommand(cmd: String) {}
    fun sendPhotos(photos: List<RecentPhoto>) {}
    fun allowPhotos() {}
    fun openHotspot() {}
    fun update() {}
    fun reconnect() {}
    fun stopScreen() {}
    fun fixMirroring() {}
    fun noticeShown() {}
    fun layoutChanged(layout: HomeLayout) {}
    /** Home is on screen and shows the Now playing / Mac panel: poll the Mac's status. */
    fun macPanelsVisible(media: Boolean) {}
    suspend fun photoThumb(p: RecentPhoto): androidx.compose.ui.graphics.ImageBitmap? = null
    suspend fun shelfThumb(item: ShelfItem): androidx.compose.ui.graphics.ImageBitmap? = null
}

/** Panel heights: small and wide share a row height; large is two rows plus the gap. */
internal val PANEL_HEIGHT = 136.dp
internal val PANEL_GAP = 12.dp
internal val PANEL_RADIUS = 22.dp

internal fun panelHeight(size: PanelSize) = if (size == PanelSize.LARGE) PANEL_HEIGHT * 2 + PANEL_GAP else PANEL_HEIGHT

/**
 * Home (0.5.3): the status card, then panels the user arranges. Long-press a panel (or tap
 * Edit at the bottom) to edit: panels wiggle, "−" removes, the corner handle resizes, drag
 * reorders, "+" opens the panel picker. Every change is saved at once.
 */
@Composable
fun HomeScreen(
    m: HomeModel,
    layout: HomeLayout,
    a: HomeActions,
    data: HomeData = HomeData(),
    startEditing: Boolean = false,
    startPicker: Boolean = false,
) {
    val snack = remember { SnackbarHostState() }
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(startEditing) }
    var picker by remember { mutableStateOf(startPicker) }
    // Local copy so a drag reorders instantly; saved on every committed change.
    var local by remember { mutableStateOf(layout) }
    var dragKind by remember { mutableStateOf<PanelKind?>(null) }
    LaunchedEffect(layout) { if (dragKind == null) local = layout }
    val grid = rememberLazyGridState()

    fun commit(next: HomeLayout) {
        if (next == local) return
        local = next
        a.layoutChanged(next)
    }

    LaunchedEffect(m.notice) {
        m.notice?.let {
            snack.showSnackbar(it)
            a.noticeShown()
        }
    }
    val wantsMedia = PanelKind.NOW_PLAYING in local
    val wantsMac = wantsMedia || PanelKind.MAC in local
    LaunchedEffect(wantsMac, wantsMedia, m.connected) {
        if (wantsMac && m.connected) a.macPanelsVisible(wantsMedia)
    }
    BackHandler(enabled = editing && !picker) {
        editing = false
        Haptics.tick(view)
    }

    val drag = remember { PanelDrag() }

    Scaffold(containerColor = Mono.Bg, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2), state = grid, modifier = Modifier.fillMaxSize(),
                contentPadding = screenPadding(pad, start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(PANEL_GAP), verticalArrangement = Arrangement.spacedBy(PANEL_GAP),
            ) {
                item(key = "edit-bar", span = { GridItemSpan(2) }) {
                    EditBar(editing, onAdd = {
                        Haptics.tick(view)
                        picker = true
                    }, onDone = {
                        editing = false
                        Haptics.confirm(view)
                    })
                }
                item(key = "status", span = { GridItemSpan(2) }) {
                    StatusCard(m, data.updateVersion, editing, a)
                }
                banners(m, a)
                items(local.panels, key = { it.kind.id }, span = { GridItemSpan(it.span) }) { p ->
                    val index = local.panels.indexOf(p)
                    PanelFrame(
                        p = p, index = index, editing = editing, dragging = dragKind == p.kind, grid = grid, drag = drag,
                        onEnterEdit = {
                            if (!editing) {
                                editing = true
                                Haptics.confirm(view)
                            }
                        },
                        onRemove = {
                            Haptics.confirm(view)
                            commit(local.remove(p.kind))
                        },
                        onResize = { next ->
                            val l = if (next == null) local.cycleSize(p.kind) else local.resize(p.kind, next)
                            if (l != local) Haptics.tick(view)
                            commit(l)
                        },
                        onDragStart = {
                            dragKind = p.kind
                            drag.start(grid, p.kind.id)
                        },
                        onDrag = { delta ->
                            drag.total += delta
                            val to = drag.target(grid, p.kind.id, local.kinds.map { it.id })
                            if (to != null && to != local.kinds.indexOf(p.kind)) {
                                local = local.move(p.kind, to)
                                Haptics.tick(view)
                            }
                            scope.launch { drag.autoScroll(grid) }
                        },
                        onDragEnd = {
                            dragKind = null
                            drag.total = Offset.Zero
                            a.layoutChanged(local)
                        },
                        modifier = Modifier
                            .zIndex(if (dragKind == p.kind) 2f else 0f)
                            .animateItem(
                                fadeInSpec = Motion.fade(reduce),
                                placementSpec = if (dragKind == p.kind) null else Motion.spring(reduce),
                                fadeOutSpec = Motion.fade(reduce),
                            ),
                    ) {
                        PanelContent(p, m, data, a, editing)
                    }
                }
                item(key = "edit-hint", span = { GridItemSpan(2) }) {
                    AnimatedVisibility(!editing, enter = fadeIn(Motion.fade(reduce)), exit = fadeOut(Motion.fade(reduce))) {
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = {
                                editing = true
                                Haptics.confirm(view)
                            }) {
                                Text(if (local.panels.isEmpty()) "Add panels" else "Edit", color = Mono.Muted)
                            }
                        }
                    }
                }
            }
            StatusBarScrim()
        }
    }
    if (picker) {
        AddPanelSheet(
            layout = local, m = m, data = data,
            onAdd = { kind, size ->
                commit(local.add(kind, size))
                Haptics.confirm(view)
                picker = false
                scope.launch {
                    delay(120)
                    grid.animateScrollToItem(0)
                }
            },
            onDismiss = { picker = false },
        )
    }
}

/** Drag bookkeeping: where the dragged panel started and how far the finger moved. */
internal class PanelDrag {
    var start: IntOffset = IntOffset.Zero
    var total: Offset = Offset.Zero

    fun start(grid: LazyGridState, key: String) {
        start = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset ?: IntOffset.Zero
        total = Offset.Zero
    }

    /** Translation that keeps the panel under the finger while the grid re-lays it out. */
    fun translation(grid: LazyGridState, key: String): Offset {
        val now = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset ?: return total
        return Offset(start.x + total.x - now.x, start.y + total.y - now.y)
    }

    /** The panel index under the dragged panel's centre, or null. */
    fun target(grid: LazyGridState, key: String, order: List<String>): Int? {
        val info = grid.layoutInfo.visibleItemsInfo
        val me = info.firstOrNull { it.key == key } ?: return null
        val cx = start.x + total.x + me.size.width / 2f
        val cy = start.y + total.y + me.size.height / 2f
        val hit = info.firstOrNull {
            it.key != key && it.key in order &&
                cx >= it.offset.x && cx < it.offset.x + it.size.width && cy >= it.offset.y && cy < it.offset.y + it.size.height
        } ?: return null
        return order.indexOf(hit.key)
    }

    suspend fun autoScroll(grid: LazyGridState) {
        val vp = grid.layoutInfo.viewportSize.height
        val y = start.y + total.y
        val edge = vp * EDGE
        val by = when {
            y < edge -> -STEP
            y > vp - edge -> STEP
            else -> 0f
        }
        if (by != 0f) {
            val used = grid.scrollBy(by)
            start = IntOffset(start.x, start.y - used.toInt())
        }
    }

    private companion object {
        const val EDGE = 0.12f
        const val STEP = 14f
    }
}

@Composable
private fun EditBar(editing: Boolean, onAdd: () -> Unit, onDone: () -> Unit) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        editing, enter = fadeIn(Motion.fade(reduce)) + expandVertically(Motion.spring(reduce)),
        exit = fadeOut(Motion.fade(reduce)) + shrinkVertically(Motion.spring(reduce)),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val add = remember { MutableInteractionSource() }
            Box(
                Modifier.pressScale(add).size(40.dp).clip(CircleShape).background(Mono.SurfaceHigh)
                    .clickable(add, ripple(), role = Role.Button, onClick = onAdd)
                    .semantics { contentDescription = "Add panel" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Add, contentDescription = null, tint = Mono.Text) }
            Spacer(Modifier.weight(1f))
            val done = remember { MutableInteractionSource() }
            Box(
                Modifier.pressScale(done).clip(RoundedCornerShape(50)).background(Mono.Text)
                    .clickable(done, null, role = Role.Button, onClick = onDone)
                    .heightIn(min = 40.dp).padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Done", style = MaterialTheme.typography.titleMedium, color = Mono.Bg) }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.banners(m: HomeModel, a: HomeActions) {
    if (m.canReconnect) {
        item(key = "reconnect", span = { GridItemSpan(2) }) {
            Banner("Not connected", "Reconnect", strong = false, desc = "Reconnect: restart Bluetooth for Tether", onClick = a::reconnect)
        }
    }
    if (m.screenLive) {
        item(key = "screen", span = { GridItemSpan(2) }) {
            Banner("Your Mac is viewing this screen", "Stop", strong = true, onClick = a::stopScreen)
        }
    }
    if (m.mirroringOff) {
        item(key = "mirroring", span = { GridItemSpan(2) }) {
            Banner("Notification mirroring needs access", "Allow", strong = false, onClick = a::fixMirroring)
        }
    }
}

@Composable
private fun Banner(text: String, action: String, strong: Boolean, desc: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().animateContentSize().clip(RoundedCornerShape(16.dp))
            .border(1.dp, if (strong) Mono.Text else Mono.Line, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (strong) Mono.Text else Mono.Muted,
            modifier = Modifier.weight(1f))
        TextButton(
            onClick = onClick,
            modifier = if (desc != null) Modifier.semantics { contentDescription = desc } else Modifier,
        ) { Text(action, color = Mono.Text) }
    }
}

/**
 * One panel's card plus its edit chrome: a gentle wiggle, the "−" badge, the resize corner,
 * long-press to edit and drag to reorder.
 */
@Composable
private fun PanelFrame(
    p: Panel,
    index: Int,
    editing: Boolean,
    dragging: Boolean,
    grid: LazyGridState,
    drag: PanelDrag,
    onEnterEdit: () -> Unit,
    onRemove: () -> Unit,
    onResize: (PanelSize?) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val wiggle = rememberInfiniteTransition(label = "wiggle")
    val phase by wiggle.animateFloat(
        -1f, 1f, infiniteRepeatable(tween(WIGGLE_MS + (index % 3) * 18), RepeatMode.Reverse), label = "phase",
    )
    val edit by animateFloatAsState(if (editing) 1f else 0f, Motion.spring(reduce), label = "edit")
    val lift by animateFloatAsState(if (dragging) 1f else 0f, Motion.spring(reduce, stiffness = Spring.StiffnessMedium), label = "lift")
    val height by androidx.compose.animation.core.animateDpAsState(panelHeight(p.size), Motion.spring(reduce), label = "h")
    val sign = if (index % 2 == 0) 1f else -1f
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                if (dragging) {
                    val t = drag.translation(grid, p.kind.id)
                    translationX = t.x
                    translationY = t.y
                }
                rotationZ = if (editing && !dragging && !reduce) phase * WIGGLE_DEG * sign else 0f
                val s = 1f - 0.02f * edit + 0.04f * lift
                scaleX = s
                scaleY = s
                shadowElevation = 28f * lift
                shape = RoundedCornerShape(PANEL_RADIUS)
                clip = false
            }
            .semantics {
                if (editing) {
                    val remove = CustomAccessibilityAction("Remove ${p.kind.title}") {
                        onRemove()
                        true
                    }
                    val resize = CustomAccessibilityAction("Resize ${p.kind.title}") {
                        onResize(null)
                        true
                    }
                    customActions = if (p.kind.sizes.size > 1) listOf(remove, resize) else listOf(remove)
                }
            }
            .pointerInput(p.kind, editing) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        onEnterEdit()
                        onDragStart()
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount)
                    },
                )
            },
    ) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(PANEL_RADIUS)).background(if (dragging) Mono.SurfaceHigh else Mono.Surface)
                .border(1.dp, if (dragging) Mono.Muted else Mono.Line, RoundedCornerShape(PANEL_RADIUS)),
        ) { content() }
        // Edit chrome springs in.
        if (edit > 0.01f) {
            RemoveBadge(p.kind.title, edit, onRemove, Modifier.align(Alignment.TopStart).offset((-8).dp, (-8).dp))
            if (p.kind.sizes.size > 1) {
                ResizeHandle(p, edit, onResize, Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

@Composable
private fun RemoveBadge(title: String, shown: Float, onRemove: () -> Unit, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.graphicsLayer {
            scaleX = shown
            scaleY = shown
            alpha = shown
        }.pressScale(source).size(28.dp).clip(CircleShape).background(Mono.SurfaceHigh).border(1.dp, Mono.Line, CircleShape)
            .clickable(source, null, role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = "Remove $title" },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.Remove, contentDescription = null, tint = Mono.Text, modifier = Modifier.size(16.dp)) }
}

/** The resize corner: tap for the next size, or drag outward (bigger) / inward (smaller). */
@Composable
private fun ResizeHandle(p: Panel, shown: Float, onResize: (PanelSize?) -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    val threshold = with(density) { 36.dp.toPx() }
    Box(
        modifier.size(44.dp).graphicsLayer { alpha = shown }
            .pointerInput(p.kind, p.size) {
                detectTapGestures { onResize(null) }
            }
            .pointerInput(p.kind, p.size) {
                var acc = 0f
                detectDragGestures(onDragStart = { acc = 0f }) { change, amount ->
                    change.consume()
                    acc += amount.x + amount.y
                    val i = p.kind.sizes.indexOf(p.size)
                    when {
                        acc > threshold && i < p.kind.sizes.lastIndex -> {
                            onResize(p.kind.sizes[i + 1])
                            acc = 0f
                        }
                        acc < -threshold && i > 0 -> {
                            onResize(p.kind.sizes[i - 1])
                            acc = 0f
                        }
                    }
                }
            }
            .semantics { contentDescription = "Resize ${p.kind.title}" },
    ) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val w = 3.dp.toPx()
            val r = PANEL_RADIUS.toPx()
            drawArc(
                Mono.Text, startAngle = 0f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(size.width - r * 2 + w / 2, size.height - r * 2 + w / 2),
                size = Size(r * 2 - w, r * 2 - w), style = Stroke(w, cap = StrokeCap.Round),
            )
        }
    }
}

private const val WIGGLE_MS = 130
private const val WIGGLE_DEG = 0.9f

/**
 * Edge-to-edge: the status bar is transparent, so content scrolling up would run under the clock
 * and icons. A solid strip the height of the status bar (plus a short fade) keeps them readable.
 */
@Composable
fun StatusBarScrim() {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Mono.Bg))
        Box(
            Modifier.fillMaxWidth().height(12.dp)
                .background(Brush.verticalGradient(listOf(Mono.Bg, Mono.Bg.copy(alpha = 0f)))),
        )
    }
}

/** The pinned top card: the Mac, the link, Settings. Not a panel: it can't be removed or moved. */
@Composable
private fun StatusCard(m: HomeModel, updateVersion: String?, editing: Boolean, a: HomeActions) {
    val reduce = LocalReduceMotion.current
    val dim by animateFloatAsState(if (editing) 0.55f else 1f, Motion.fade(reduce), label = "dim")
    Column(
        Modifier.fillMaxWidth().graphicsLayer { alpha = dim }.clip(RoundedCornerShape(PANEL_RADIUS))
            .background(Mono.Surface).border(1.dp, Mono.Line, RoundedCornerShape(PANEL_RADIUS))
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
            .animateContentSize(Motion.spring(reduce))
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Mono.SurfaceHigh), contentAlignment = Alignment.Center) {
                TetherMark(34.dp, linked = m.connected, pulse = m.searching, modifier = Modifier.sharedHero("mark", reduce))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                AnimatedContent(
                    m.title to m.subtitle, label = "status",
                    transitionSpec = {
                        (fadeIn(Motion.spring(reduce)) + slideInVertically(Motion.spring(reduce)) { it / 3 }) togetherWith
                            (fadeOut(Motion.spring(reduce)) + slideOutVertically(Motion.spring(reduce)) { -it / 3 })
                    },
                ) { (t, sub) ->
                    Column {
                        Text(t, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinkDot(m.connected, m.searching)
                            Spacer(Modifier.width(6.dp))
                            Text(sub, style = MaterialTheme.typography.bodyMedium, color = Mono.Muted, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            AnimatedVisibility(updateVersion != null, enter = Motion.enter(reduce, from = 0.7f), exit = Motion.exit(reduce, to = 0.7f)) {
                Box(Modifier.padding(end = 8.dp)) { UpdatePill(updateVersion.orEmpty(), a::update) }
            }
            val gear = remember { MutableInteractionSource() }
            Box(
                Modifier.pressScale(gear).size(44.dp).clip(CircleShape).background(Mono.SurfaceHigh)
                    .clickable(gear, ripple(), enabled = !editing, role = Role.Button, onClick = a::settings)
                    .semantics { contentDescription = "Settings" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Settings, contentDescription = null, tint = Mono.Text, modifier = Modifier.size(22.dp)) }
        }
        AnimatedVisibility(
            m.error != null, enter = fadeIn(Motion.spring(reduce)) + expandVertically(Motion.spring(reduce)),
            exit = fadeOut(Motion.spring(reduce)) + shrinkVertically(Motion.spring(reduce)),
        ) {
            Text(
                m.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Mono.Text,
                modifier = Modifier.padding(top = 12.dp).border(1.dp, Mono.Line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** A small dot: solid when linked, breathing while searching, hollow when idle. */
@Composable
private fun LinkDot(connected: Boolean, searching: Boolean) {
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(DOT_MS), RepeatMode.Reverse), label = "a")
    val alpha = when {
        connected -> 1f
        searching && !reduce -> a
        else -> 0.5f
    }
    Canvas(Modifier.size(7.dp)) {
        if (connected) {
            drawCircle(Mono.Text.copy(alpha = alpha))
        } else {
            drawCircle(Mono.Text.copy(alpha = alpha), style = Stroke(1.2.dp.toPx()))
        }
    }
}

private const val DOT_MS = 900

fun formatBytes(b: Long): String = when {
    b >= GB -> String.format(Locale.ROOT, "%.1f GB", b / GB.toDouble())
    b >= MB -> String.format(Locale.ROOT, "%.1f MB", b / MB.toDouble())
    b >= KB -> String.format(Locale.ROOT, "%.0f KB", b / KB.toDouble())
    else -> "$b B"
}

private const val KB = 1024L
private const val MB = KB * 1024
private const val GB = MB * 1024

fun statusLine(t: TransferRecord): String = when (t.status) {
    TransferStatus.QUEUED -> "Queued · ${formatBytes(t.size)}"
    TransferStatus.OFFERED -> "Starting…"
    TransferStatus.ACTIVE -> "${formatBytes(t.transferred)} of ${formatBytes(t.size)}"
    TransferStatus.WAITING -> t.error ?: "Paused · resumes when connected"
    TransferStatus.VERIFYING -> "Verifying…"
    TransferStatus.DONE -> (if (t.direction == Direction.OUT) "Sent · " else "Received · ") + formatBytes(t.size)
    TransferStatus.FAILED -> t.error ?: "Failed"
    TransferStatus.CANCELLED -> t.error ?: "Cancelled"
}
