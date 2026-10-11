package dev.benfranklin.tether.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.benfranklin.tether.core.features.ClipRecents
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.data.HeaderLine
import dev.benfranklin.tether.data.HomeRow
import dev.benfranklin.tether.data.HomeSection
import dev.benfranklin.tether.data.HomeSections
import dev.benfranklin.tether.log.guarded
import dev.benfranklin.tether.ui.Haptics
import dev.benfranklin.tether.ui.clamp01
import dev.benfranklin.tether.ui.monoSwitchColors
import dev.benfranklin.tether.ui.pressScale
import dev.benfranklin.tether.ui.screenPadding
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.Motion
import dev.benfranklin.tether.v7.MacStatusUi
import dev.benfranklin.tether.v7.RecentPhoto
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Everything the header and banners show; built from runtime state (or fakes in screenshot tests). */
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
    /** 0.5.4: the link is being set up (handshake / pairing): "Connecting…" with a pulsing dot. */
    val connecting: Boolean = false,
    /** 0.5.4: why the Mac can't connect, when the phone is the reason ("Bluetooth off"); shown after "Not connected". */
    val reason: String? = null,
)

/** What the Home sections show besides [HomeModel]. */
data class HomeData(
    val shelf: List<ShelfItem> = emptyList(),
    /** The phone's clipboard as text. Never shown on Home (only its presence matters). */
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
    /** 0.5.4: photos taken since Photos was last opened (the button's badge). */
    val newPhotos: Int = 0,
    /** 0.5.4: Find my Mac section; null when no Mac is paired. */
    val find: FindModel? = null,
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
    /** The Photos quick button: pick photos to send (clears the new-photos badge). */
    fun pickPhotos() {}
    fun allowPhotos() {}
    fun openHotspot() {}
    fun findMac(action: String) {}
    fun update() {}
    fun reconnect() {}
    fun stopScreen() {}
    fun fixMirroring() {}
    fun noticeShown() {}
    /** Edit Home changed the sections (order or visibility); saved at once. */
    fun sectionsChanged(sections: HomeSections) {}
    /** Home is on screen: poll the Mac's status (battery for the header; media too when Now playing shows). */
    fun macPanelsVisible(media: Boolean) {}
    suspend fun photoThumb(p: RecentPhoto): androidx.compose.ui.graphics.ImageBitmap? = null
    suspend fun shelfThumb(item: ShelfItem): androidx.compose.ui.graphics.ImageBitmap? = null
}

/**
 * Home (0.5.4, "Control Center"): the Mac header, one block with Quick controls and Now playing,
 * Recent transfers, Shelf, and a quiet "Edit Home" button that opens a sheet to reorder and
 * show/hide sections. No long-press anywhere on Home: nothing is inserted or removed while a
 * finger is down (the 0.5.3 hold crashes).
 */
@Composable
fun HomeScreen(
    m: HomeModel,
    sections: HomeSections,
    a: HomeActions,
    data: HomeData = HomeData(),
    startEditing: Boolean = false,
) {
    val snack = remember { SnackbarHostState() }
    val view = LocalView.current
    var editing by remember { mutableStateOf(startEditing) }
    // Local copy so the sheet reflects a change at once; saved on every committed change.
    var local by remember { mutableStateOf(sections) }
    LaunchedEffect(sections) { local = sections }
    val list = rememberLazyListState()

    LaunchedEffect(m.notice) {
        m.notice?.let {
            snack.showSnackbar(it)
            a.noticeShown()
        }
    }
    val wantsMedia = local.isOn(HomeSection.NOW_PLAYING)
    LaunchedEffect(wantsMedia, m.connected) {
        if (m.connected) a.macPanelsVisible(wantsMedia)
    }
    BackHandler(enabled = editing) { editing = false }

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val rows = remember(local) { local.rows() }

    Box(Modifier.fillMaxSize()) {
    Scaffold(containerColor = Mono.Bg, snackbarHost = { SnackbarHost(snack) }) { pad ->
        val navGap = (navInset - pad.calculateBottomPadding()).coerceAtLeast(0.dp)
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = list, modifier = Modifier.fillMaxSize().testTag("home-list"),
                // The last row must clear the gesture/navigation bar even if the Scaffold padding lacks it.
                contentPadding = screenPadding(pad, start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp + navGap),
            ) {
                item(key = "header") { Header(m, data, a) }
                banners(m, a)
                rows.forEach { row ->
                    item(key = rowKey(row), contentType = rowKey(row)) {
                        Box(Modifier.animateItem(fadeInSpec = Motion.fade(LocalReduceMotion.current), placementSpec = null,
                            fadeOutSpec = Motion.fade(LocalReduceMotion.current))) {
                            Column { SectionContent(row, m, data, a, editing) }
                        }
                    }
                }
                item(key = "edit") {
                    EditHomeButton(Modifier.padding(top = if (rows.isEmpty()) 24.dp else 36.dp)) {
                        Haptics.tick(view)
                        editing = true
                    }
                }
            }
            StatusBarScrim()
            NavBarScrim(Modifier.align(Alignment.BottomCenter))
        }
    }
    // In the app's own window (no dialog/popup, no shared elements) and always composed: opening
    // and closing only animate it, so nothing is inserted into or removed from Home's layout.
    EditHomeSheet(
        open = editing,
        sections = local,
        onChange = { next ->
            if (next != local) {
                local = next
                a.sectionsChanged(next)
            }
        },
        onDismiss = { editing = false },
    )
    }
}

private fun rowKey(row: HomeRow): String = when (row) {
    HomeRow.Control -> "control"
    HomeRow.QuickOnly -> "quick"
    HomeRow.PlayerOnly -> "player"
    is HomeRow.Single -> row.section.id
}

@Composable
private fun SectionContent(row: HomeRow, m: HomeModel, data: HomeData, a: HomeActions, editing: Boolean) {
    when (row) {
        HomeRow.Control -> Box(Modifier.padding(top = 20.dp)) { ControlBlock(m, data, a, editing) }
        HomeRow.QuickOnly -> Box(Modifier.padding(top = 20.dp)) { QuickRow(m, data, a, editing) }
        HomeRow.PlayerOnly -> Box(Modifier.padding(top = 20.dp)) { PlayerRow(m, data, a, editing) }
        is HomeRow.Single -> when (row.section) {
            HomeSection.TRANSFERS -> TransfersSection(m, a)
            HomeSection.SHELF -> ShelfSection(m, data, a)
            HomeSection.CLIPBOARD -> ClipboardSection(data, a)
            HomeSection.PHOTOS -> PhotosSection(data, a)
            HomeSection.FIND_MAC -> FindSection(data, a)
            HomeSection.QUICK, HomeSection.NOW_PLAYING -> Unit
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.banners(m: HomeModel, a: HomeActions) {
    if (m.screenLive) {
        item(key = "screen") {
            Banner("Your Mac is viewing this screen", "Stop", strong = true, onClick = a::stopScreen)
        }
    }
    if (m.mirroringOff) {
        item(key = "mirroring") {
            Banner("Notification mirroring needs access", "Allow", strong = false, onClick = a::fixMirroring)
        }
    }
}

@Composable
private fun Banner(text: String, action: String, strong: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).animateContentSize().clip(RoundedCornerShape(16.dp))
            .border(1.dp, if (strong) Mono.Text else Mono.Line, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (strong) Mono.Text else Mono.Muted,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        TextButton(onClick = onClick) { Text(action, color = Mono.Text) }
    }
}

// ---------------- Header ----------------

/** The Mac, the link ("Connected · Wi-Fi · 82%"), Update when offered, Settings. */
@Composable
private fun Header(m: HomeModel, data: HomeData, a: HomeActions) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val line = HeaderLine.of(
        connected = m.connected, connecting = m.connecting, wifi = m.wifi,
        battery = data.mac?.battery?.takeIf { m.connected }, canReconnect = m.canReconnect, reason = m.reason,
    )
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).animateContentSize(Motion.spring(reduce))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Mono.Surface).border(1.dp, Mono.Line, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Laptop, contentDescription = null, tint = Mono.Text, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(m.title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                AnimatedContent(line.text to line.dot, label = "link",
                    transitionSpec = { fadeIn(Motion.fade(reduce)) togetherWith fadeOut(Motion.fade(reduce)) }) { (text, dot) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinkDot(dot)
                        Spacer(Modifier.width(8.dp))
                        // Two lines at most: at large font sizes the battery wraps instead of being cut off.
                        Text(text, style = MaterialTheme.typography.bodyLarge, color = Mono.Muted, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                }
            }
            AnimatedVisibility(
                data.updateVersion != null, enter = Motion.enter(reduce, from = 0.7f), exit = Motion.exit(reduce, to = 0.7f),
            ) {
                Box(Modifier.padding(start = 8.dp)) { UpdatePill(data.updateVersion.orEmpty(), a::update) }
            }
            Spacer(Modifier.width(8.dp))
            val gear = remember { MutableInteractionSource() }
            Box(
                Modifier.pressScale(gear).size(48.dp).clip(CircleShape).background(Mono.Surface).border(1.dp, Mono.Line, CircleShape)
                    .clickable(gear, ripple(), role = Role.Button) {
                        Haptics.tick(view)
                        a.settings()
                    }
                    .semantics { contentDescription = "Settings" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Settings, contentDescription = null, tint = Mono.Text, modifier = Modifier.size(22.dp)) }
        }
        // Always composed (hidden when not offered) so the header never inserts children mid-gesture.
        AnimatedVisibility(
            line.reconnect, enter = fadeIn(Motion.fade(reduce)) + expandVertically(Motion.spring(reduce)),
            exit = fadeOut(Motion.fade(reduce)) + shrinkVertically(Motion.spring(reduce)),
        ) {
            Row(Modifier.padding(start = 62.dp, top = 8.dp)) {
                val source = remember { MutableInteractionSource() }
                Text(
                    "Reconnect", style = MaterialTheme.typography.labelLarge, color = Mono.Text,
                    modifier = Modifier.pressScale(source).clip(RoundedCornerShape(50)).border(1.dp, Mono.Line, RoundedCornerShape(50))
                        .clickable(source, ripple(), role = Role.Button) {
                            Haptics.tick(view)
                            a.reconnect()
                        }
                        .semantics { contentDescription = "Reconnect: restart Bluetooth for Tether" }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        AnimatedVisibility(
            m.error != null, enter = fadeIn(Motion.fade(reduce)) + expandVertically(Motion.spring(reduce)),
            exit = fadeOut(Motion.fade(reduce)) + shrinkVertically(Motion.spring(reduce)),
        ) {
            Text(
                m.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Mono.Text,
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth().border(1.dp, Mono.Line, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Filled when linked, breathing while connecting (steady with Reduce Motion), hollow when not connected. */
@Composable
private fun LinkDot(dot: HeaderLine.Dot) {
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "dot")
    val pulse by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(DOT_MS), RepeatMode.Reverse), label = "a")
    Canvas(Modifier.size(9.dp)) {
        when (dot) {
            HeaderLine.Dot.FILLED -> drawCircle(Mono.Text)
            HeaderLine.Dot.PULSE -> drawCircle(Mono.Text.copy(alpha = if (reduce) 0.6f else pulse.clamp01()))
            HeaderLine.Dot.HOLLOW -> drawCircle(Mono.Muted, radius = size.minDimension / 2 - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        }
    }
}

private const val DOT_MS = 900

@Composable
private fun EditHomeButton(modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val source = remember { MutableInteractionSource() }
        Row(
            Modifier.pressScale(source).clip(RoundedCornerShape(50)).border(1.dp, Mono.Line, RoundedCornerShape(50))
                .clickable(source, ripple(), role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp).padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Edit, contentDescription = null, tint = Mono.Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Edit Home", style = MaterialTheme.typography.bodyLarge, color = Mono.Text.copy(alpha = 0.86f))
        }
    }
}

// ---------------- Edit Home sheet ----------------

/**
 * The Edit Home sheet. A modal bottom sheet opens a separate dialog window (taps never reached it
 * under Robolectric, and shared-element modifiers in other windows crash with "layouts are not part
 * of the same hierarchy"), so this one is drawn over Home in the same window, outside the list and
 * with no shared elements. It appears only after the tap that opens it has ended and slides in on
 * a critically damped spring (a fade with Reduce Motion); the handle drags it down to close; the
 * scrim, Back and Done close it.
 */
@Composable
private fun EditHomeSheet(open: Boolean, sections: HomeSections, onChange: (HomeSections) -> Unit, onDismiss: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val ctx = LocalContext.current
    var pull by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(open) { pull = 0f }
    val dismissPx = with(LocalDensity.current) { SHEET_DISMISS.toPx() }
    val dismiss by rememberUpdatedState(onDismiss)
    val slide = Motion.spring<androidx.compose.ui.unit.IntOffset>(false, stiffness = SHEET_STIFFNESS, damping = 1f)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(Motion.fade(reduce)), exit = fadeOut(Motion.fade(reduce))) {
            Box(
                Modifier.fillMaxSize().background(Mono.Bg.copy(alpha = 0.6f))
                    .clickable(remember { MutableInteractionSource() }, null) { dismiss() }
                    .semantics { contentDescription = "Close Edit Home" },
            )
        }
        AnimatedVisibility(
            open, modifier = Modifier.align(Alignment.BottomCenter),
            enter = if (reduce) fadeIn(tween(Motion.REDUCED_FADE_MS)) else slideInVertically(slide) { it },
            exit = if (reduce) fadeOut(tween(Motion.REDUCED_FADE_MS)) else slideOutVertically(slide) { it },
        ) {
            Column(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 24.dp)
                    .offset { IntOffset(0, pull.roundToInt()) }
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).background(Mono.Surface)
                    .testTag("edit-sheet"),
            ) {
                SheetHandle(
                    Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                guarded(ctx, "editHome.sheetDragEnd") {
                                    if (pull > dismissPx) {
                                        Haptics.tick(view)
                                        dismiss()
                                    } else {
                                        pull = 0f
                                    }
                                }
                            },
                            onDragCancel = { pull = 0f },
                        ) { change, dy ->
                            change.consume()
                            guarded(ctx, "editHome.sheetDrag") { pull = (pull + dy).coerceAtLeast(0f) }
                        }
                    },
                )
                EditHomeContent(sections, onChange, onDone = {
                    Haptics.confirm(view)
                    dismiss()
                }, enabled = open)
            }
        }
    }
}

private const val SHEET_STIFFNESS = 500f
private val SHEET_DISMISS = 96.dp

@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Mono.Line))
    }
}

/** Row height in the Edit Home list; drag math uses it, so every row is exactly this tall. */
internal val EDIT_ROW = 64.dp

/**
 * The Edit Home list: a drag handle, the section and its switch per row. Reorder by dragging the
 * handle (or the Move up / Move down accessibility actions). During a drag nothing is inserted,
 * removed or reordered in composition: rows only translate; the new order is committed once,
 * when the finger lifts. Only the first finger on a handle drives a drag; others are ignored.
 */
@Composable
fun EditHomeContent(sections: HomeSections, onChange: (HomeSections) -> Unit, onDone: () -> Unit, enabled: Boolean = true) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val ctx = LocalContext.current
    val rowPx = with(LocalDensity.current) { EDIT_ROW.toPx() }
    val current by rememberUpdatedState(sections)
    val change by rememberUpdatedState(onChange)
    // The section being dragged (one at a time) and how far it moved.
    var dragging by remember { mutableStateOf<HomeSection?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val order = sections.order
    val from = dragging?.let { order.indexOf(it) } ?: -1
    val target = dropIndex(from, dragY, rowPx, order.size)

    var lastTarget by remember { mutableIntStateOf(-1) }

    /** False (ignored) while another row is being dragged: a second finger never starts a second drag. */
    fun startDrag(s: HomeSection): Boolean {
        if (dragging != null) return false
        guarded(ctx, "editHome.dragStart") {
            dragging = s
            dragY = 0f
            lastTarget = dropIndex(current.order.indexOf(s), 0f, rowPx, current.order.size)
            Haptics.tick(view)
        }
        return true
    }

    fun moveBy(s: HomeSection, dy: Float) {
        val f = current.order.indexOf(s)
        val n = current.order.size
        if (f < 0 || dragging != s) return
        // Never past the first / last slot, however fast the finger flies.
        dragY = (dragY + dy).coerceIn(-f * rowPx - rowPx / 2, (n - 1 - f) * rowPx + rowPx / 2)
        val t = dropIndex(f, dragY, rowPx, n)
        if (t != lastTarget) {
            lastTarget = t
            Haptics.tick(view)
        }
    }

    fun finish(s: HomeSection, commit: Boolean) {
        if (dragging != s) return
        val f = current.order.indexOf(s)
        val t = dropIndex(f, dragY, rowPx, current.order.size)
        dragging = null
        dragY = 0f
        if (commit && f >= 0 && t >= 0 && t != f) {
            change(current.move(f, t))
            Haptics.confirm(view)
        }
    }

    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Edit Home", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            val done = remember { MutableInteractionSource() }
            Box(
                Modifier.pressScale(done).clip(RoundedCornerShape(50)).background(Mono.Text)
                    .clickable(done, null, enabled = enabled, role = Role.Button, onClick = onDone)
                    .heightIn(min = 44.dp).padding(horizontal = 26.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Done", style = MaterialTheme.typography.titleMedium, color = Mono.Bg) }
        }
        order.forEachIndexed { i, s ->
            key(s.id) {
                val shift = when {
                    from < 0 || s == dragging -> 0f
                    i in (from + 1)..target -> -rowPx
                    i in target until from -> rowPx
                    else -> 0f
                }
                // Springs while a drag is on; snaps the instant the order is committed (no double move).
                val anim by animateFloatAsState(shift, if (from >= 0 && !reduce) Motion.spring(false) else snap(), label = "shift")
                val lifted = s == dragging
                val lift by animateFloatAsState(if (lifted) 1f else 0f, Motion.spring(reduce), label = "lift")
                EditRow(
                    section = s, on = sections.isOn(s), last = i == order.lastIndex,
                    modifier = Modifier.zIndex(if (lifted) 1f else 0f).graphicsLayer {
                        translationY = if (lifted) dragY else anim
                        val l = lift.clamp01()
                        scaleX = 1f + 0.02f * l
                        scaleY = 1f + 0.02f * l
                        shadowElevation = 16f * l
                    },
                    enabled = enabled,
                    onToggle = { on ->
                        if (dragging == null) {
                            Haptics.tick(view)
                            change(current.set(s, on))
                        }
                    },
                    onMove = { by ->
                        val f = current.order.indexOf(s)
                        if (dragging == null && f >= 0) change(current.move(f, f + by))
                    },
                    handle = Modifier.reorderHandle(
                        key = s,
                        onStart = { startDrag(s) },
                        onMove = { dy -> guarded(ctx, "editHome.drag") { moveBy(s, dy) } },
                        onEnd = { commit -> guarded(ctx, "editHome.dragEnd") { finish(s, commit) } },
                    ),
                )
            }
        }
    }
    // A drag that loses its pointer (sheet dismissed, gesture cancelled by the system) is reset, not committed.
    LaunchedEffect(sections) { if (dragging != null && dragging !in sections.order) dragging = null }
}

/**
 * A drag handle: follows only the first finger that lands on it ([onStart] may refuse, e.g. while
 * another row is dragged), reports vertical movement, and ends with commit = true on lift or
 * false when the gesture is cancelled (pointer lost, sheet closed): nothing is committed then.
 */
private fun Modifier.reorderHandle(key: Any, onStart: () -> Boolean, onMove: (Float) -> Unit, onEnd: (Boolean) -> Unit) =
    pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!onStart()) return@awaitEachGesture
            val id: PointerId = down.id
            var ended = false
            try {
                while (!ended) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == id }
                    if (c == null || !c.pressed) {
                        ended = true
                        onEnd(c != null)
                    } else {
                        val dy = c.positionChange().y
                        c.consume()
                        onMove(dy)
                    }
                }
            } finally {
                if (!ended) onEnd(false)
            }
        }
    }

/** Where a row dragged [dy] px from [from] would land, clamped to the list; -1 when nothing is dragged. */
internal fun dropIndex(from: Int, dy: Float, rowPx: Float, size: Int): Int {
    if (from < 0 || size <= 0 || rowPx <= 0f) return -1
    // Clamp the step count before adding: a wild delta (Float.MAX) rounds to Int.MAX and would overflow.
    val steps = if (abs(dy) < rowPx / 2 || dy.isNaN()) 0 else (dy / rowPx).roundToInt().coerceIn(-size, size)
    return (from + steps).coerceIn(0, size - 1)
}

@Composable
private fun EditRow(
    section: HomeSection,
    on: Boolean,
    last: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    handle: Modifier,
) {
    Column(
        modifier.fillMaxWidth().height(EDIT_ROW).background(Mono.Surface)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Move ${section.title} up") {
                        onMove(-1)
                        true
                    },
                    CustomAccessibilityAction("Move ${section.title} down") {
                        onMove(1)
                        true
                    },
                )
            },
    ) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 64.dp, height = EDIT_ROW).then(if (enabled) handle else Modifier).testTag("edit-handle-${section.id}")
                    .semantics { contentDescription = "Reorder ${section.title}" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Menu, contentDescription = null, tint = Mono.Muted, modifier = Modifier.size(22.dp)) }
            Row(
                Modifier.weight(1f).fillMaxSize().testTag("edit-row-${section.id}")
                    .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
                    .padding(end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(section.title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                    color = if (on) Mono.Text else Mono.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Switch(checked = on, onCheckedChange = null, colors = monoSwitchColors())
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().padding(start = 64.dp, end = 20.dp).height(1.dp).background(Mono.Line))
    }
}

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

/**
 * The same for the gesture/navigation bar at the bottom (0.5.3 fix: on a phone the last visible
 * panel sat under the gesture handle). Only as tall as the bar; nothing on a three-button-less
 * screen without a bottom inset.
 */
@Composable
fun NavBarScrim(modifier: Modifier = Modifier) {
    if (WindowInsets.navigationBars.getBottom(LocalDensity.current) == 0) return
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(12.dp)
                .background(Brush.verticalGradient(listOf(Mono.Bg.copy(alpha = 0f), Mono.Bg))),
        )
        Box(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars).background(Mono.Bg))
    }
}

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
