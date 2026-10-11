@file:Suppress("MagicNumber", "LongMethod")

package dev.benfranklin.tether.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SouthWest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.tether.core.features.TransferList
import dev.benfranklin.tether.core.features.TransferMeta
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.core.transfer.Transport
import dev.benfranklin.tether.ui.Haptics
import dev.benfranklin.tether.ui.pressScale
import dev.benfranklin.tether.ui.screenPadding
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.MonoCode
import dev.benfranklin.tether.ui.theme.Motion
import kotlinx.coroutines.launch
import java.time.ZoneId

/** What the Transfers screen can do (runtime-backed in the app, fakes in screenshot tests). */
interface TransfersActions {
    fun cancel(id: String)
    fun retry(id: String)
    fun remove(id: String)
    /** Removes every finished transfer from the list (running ones stay). */
    fun clearFinished()
    /** Opens a finished transfer's file; returns a short reason when it can't. */
    fun open(t: TransferRecord): String?
}

/** Midnight before [now] in [zone] (the same zone the row labels use). */
fun startOfDay(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * Every transfer (0.5.3 redesign), newest first, grouped Today / Earlier, filtered All / Sent /
 * Received. Tap opens a finished file (or retries a failed send), long-press for Cancel / Retry /
 * Remove, swipe left to remove, Clear all removes finished ones.
 */
@Composable
fun TransfersScreen(
    transfers: List<TransferRecord>,
    a: TransfersActions,
    onBack: () -> Unit,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(TransferList.Filter.ALL) }
    var confirmClear by remember { mutableStateOf(false) }
    val sections = remember(transfers, filter, now, zone) { TransferList.sections(transfers, filter, now, startOfDay(now, zone)) }
    val anyFinished = transfers.any { it.status.isFinal }
    Scaffold(
        containerColor = Mono.Bg,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            LargeTitle("Transfers", onBack, heroKey = "transfers-title") {
                TextButton(onClick = { confirmClear = true }, enabled = anyFinished) {
                    Text("Clear all", style = MaterialTheme.typography.bodyLarge, color = if (anyFinished) Mono.Text else Mono.Muted)
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(pad)) {
            item(key = "filter") { FilterChips(filter) { filter = it } }
            if (sections.isEmpty()) {
                item(key = "empty") {
                    Text(
                        when (filter) {
                            TransferList.Filter.SENT -> "Nothing sent yet"
                            TransferList.Filter.RECEIVED -> "Nothing received yet"
                            TransferList.Filter.ALL -> "Files you send or receive show up here"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = Mono.Muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp).animateItem(),
                    )
                }
            }
            for (sec in sections) {
                item(key = "h-" + sec.title) {
                    Text(
                        sec.title, style = MaterialTheme.typography.bodyLarge, color = Mono.Muted,
                        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp).animateItem(),
                    )
                }
                items(sec.items, key = { it.id }) { t ->
                    SwipeRow(t, onRemove = {
                        a.remove(t.id)
                        Haptics.confirm(view)
                    }, modifier = Modifier.animateItem(
                        fadeInSpec = Motion.spring(reduce), placementSpec = Motion.spring(reduce), fadeOutSpec = Motion.spring(reduce),
                    )) {
                        TransferItem(t, now, zone, last = t == sec.items.last(), a = a, onOpenFailed = { why ->
                            scope.launch { snack.showSnackbar(why) }
                        })
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Mono.SurfaceHigh, titleContentColor = Mono.Text, textContentColor = Mono.Muted,
            title = { Text("Clear all transfers?") },
            text = { Text("Received files stay where they were saved.") },
            confirmButton = {
                TextButton(onClick = {
                    a.clearFinished()
                    confirmClear = false
                    Haptics.confirm(view)
                }) { Text("Clear all", color = Mono.Text) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = Mono.Muted) } },
        )
    }
}

/** All / Sent / Received as separate pills; the chosen one fills on a spring. */
@Composable
private fun FilterChips(selected: TransferList.Filter, onSelect: (TransferList.Filter) -> Unit) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in TransferList.Filter.entries) {
            val on = f == selected
            val bg by animateColorAsState(if (on) Mono.Text else Mono.Bg, Motion.spring(reduce), label = "chip")
            val fg by animateColorAsState(if (on) Mono.Bg else Mono.Muted, Motion.spring(reduce), label = "chipText")
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier.pressScale(source).clip(RoundedCornerShape(50)).background(bg)
                    .border(1.dp, if (on) Mono.Text else Mono.Line, RoundedCornerShape(50))
                    .clickable(source, null, role = Role.Tab) {
                        if (!on) Haptics.tick(view)
                        onSelect(f)
                    }
                    .heightIn(min = 40.dp).padding(horizontal = 18.dp).semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (f) {
                        TransferList.Filter.ALL -> "All"
                        TransferList.Filter.SENT -> "Sent"
                        TransferList.Filter.RECEIVED -> "Received"
                    },
                    style = MaterialTheme.typography.bodyLarge, color = fg,
                )
            }
        }
    }
}

/** One transfer: arrow in a circle, name, size (or progress), and its time or percentage. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransferItem(t: TransferRecord, now: Long, zone: ZoneId, last: Boolean, a: TransfersActions, onOpenFailed: (String) -> Unit) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    var menu by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(t.progress, Motion.spring(reduce, stiffness = Spring.StiffnessLow, damping = 1f),
        label = "progress")
    var wasFinal by remember { mutableStateOf(t.status.isFinal) }
    LaunchedEffect(t.status) {
        if (!wasFinal && t.status == TransferStatus.DONE) Haptics.confirm(view)
        wasFinal = t.status.isFinal
    }
    val retryable = t.status == TransferStatus.FAILED && t.direction == Direction.OUT
    val desc = (if (t.direction == Direction.OUT) "Sent " else "Received ") + t.name + ". " + statusLine(t) + when (t.via) {
        Transport.WIFI -> ", over Wi-Fi"
        Transport.BLE -> ", over Bluetooth"
        null -> ""
    }
    Box {
        Row(
            Modifier.fillMaxWidth().background(Mono.Bg)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = ripple(),
                    onLongClick = {
                        Haptics.confirm(view)
                        menu = true
                    },
                ) {
                    when {
                        t.status == TransferStatus.DONE -> a.open(t)?.let(onOpenFailed)
                        retryable -> {
                            Haptics.tick(view)
                            a.retry(t.id)
                        }
                        else -> menu = true
                    }
                }
                .padding(start = 20.dp, end = 20.dp).semantics(mergeDescendants = true) { contentDescription = desc },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Mono.Surface), contentAlignment = Alignment.Center) {
                Icon(
                    if (t.direction == Direction.OUT) Icons.Outlined.NorthEast else Icons.Outlined.SouthWest,
                    contentDescription = null, tint = if (t.status == TransferStatus.FAILED) Mono.Muted else Mono.Text,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        TransferMeta.middle(t.name, 32), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    AnimatedContent(TransferMeta.percent(t) ?: TransferMeta.time(t, now, zone), label = "trail",
                        transitionSpec = { fadeIn(Motion.fade(reduce)) togetherWith fadeOut(Motion.fade(reduce)) }) { s ->
                        Text(s, style = MaterialTheme.typography.bodyMedium, color = Mono.Muted,
                            fontFamily = if (s.first().isDigit()) MonoCode else null)
                    }
                }
                Text(
                    TransferMeta.subtitle(t, ::formatBytes), style = MaterialTheme.typography.bodyMedium, color = Mono.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
                AnimatedVisibility(
                    !t.status.isFinal, enter = fadeIn(Motion.spring(reduce)) + expandVertically(Motion.spring(reduce)),
                    exit = fadeOut(Motion.spring(reduce)) + shrinkVertically(Motion.spring(reduce)),
                ) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color = Mono.Accent, trackColor = Mono.Line, drawStopIndicator = {}, gapSize = 0.dp,
                    )
                }
                Spacer(Modifier.height(14.dp))
                if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(Mono.Line.copy(alpha = 0.7f)))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (!t.status.isFinal) {
                DropdownMenuItem(text = { Text("Cancel") }, leadingIcon = { Icon(Icons.Outlined.Close, null) }, onClick = {
                    menu = false
                    a.cancel(t.id)
                })
            }
            if (retryable) {
                DropdownMenuItem(text = { Text("Retry") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, onClick = {
                    menu = false
                    a.retry(t.id)
                })
            }
            if (t.status.isFinal) {
                DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = {
                    menu = false
                    a.remove(t.id)
                })
            }
        }
    }
}

/** Swipe left to remove a finished transfer from the list. */
@Composable
private fun SwipeRow(t: TransferRecord, onRemove: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            val gone = v == SwipeToDismissBoxValue.EndToStart
            if (gone) onRemove()
            gone
        },
        positionalThreshold = { it * 0.4f },
    )
    LaunchedEffect(t.id) { state.snapTo(SwipeToDismissBoxValue.Settled) }
    SwipeToDismissBox(
        state = state, enableDismissFromStartToEnd = false, gesturesEnabled = t.status.isFinal, modifier = modifier,
        backgroundContent = {
            val past = state.progress > 0.4f && state.targetValue == SwipeToDismissBoxValue.EndToStart
            val bg by animateColorAsState(if (past) Mono.Text else Mono.SurfaceHigh, Motion.fade(reduce), label = "bg")
            Box(Modifier.fillMaxSize().background(bg).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = if (past) Mono.Bg else Mono.Text)
            }
        },
    ) { content() }
}
