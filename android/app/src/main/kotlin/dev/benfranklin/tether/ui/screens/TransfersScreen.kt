@file:Suppress("MagicNumber", "LongMethod")

package dev.benfranklin.tether.ui.screens

import dev.benfranklin.tether.ui.screenPadding
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.benfranklin.tether.core.features.TransferList
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.ui.Haptics
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.MonoCode
import dev.benfranklin.tether.ui.theme.Motion
import kotlinx.coroutines.launch
import java.util.Calendar

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

/** Local midnight before [now]. */
fun startOfDay(now: Long): Long = Calendar.getInstance().apply {
    timeInMillis = now
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * Every transfer (0.5.3), newest first, grouped Today / Earlier, filtered All / Sent / Received.
 * Swipe a row to remove it, tap to open its file, Clear all removes finished ones. Transfers that
 * finished more than a day ago fold into a collapsed Earlier group.
 */
@Composable
fun TransfersScreen(transfers: List<TransferRecord>, a: TransfersActions, onBack: () -> Unit, now: Long = System.currentTimeMillis()) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(TransferList.Filter.ALL) }
    var confirmClear by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val sections = remember(transfers, filter, now) { TransferList.sections(transfers, filter, now, startOfDay(now)) }
    val anyFinished = transfers.any { it.status.isFinal }
    Scaffold(
        containerColor = Mono.Bg,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Transfers", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = { confirmClear = true }, enabled = anyFinished) {
                        Text("Clear all", color = if (anyFinished) Mono.Text else Mono.Muted)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Mono.Bg),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(pad)) {
            item(key = "filter") { FilterBar(filter) { filter = it } }
            if (sections.isEmpty()) {
                item(key = "empty") {
                    Text(
                        when (filter) {
                            TransferList.Filter.SENT -> "Nothing sent yet."
                            TransferList.Filter.RECEIVED -> "Nothing received yet."
                            TransferList.Filter.ALL -> "Files you send or receive show up here."
                        },
                        style = MaterialTheme.typography.bodyMedium, color = Mono.Muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp).animateItem(),
                    )
                }
            }
            for (sec in sections) {
                val open = expanded[sec.title] ?: !sec.collapsed
                item(key = "h-" + sec.title) {
                    SectionToggle(sec.title, sec.items.size, collapsible = sec.collapsed, open = open, modifier = Modifier.animateItem()) {
                        expanded[sec.title] = !open
                        Haptics.tick(view)
                    }
                }
                if (open) {
                    items(sec.items, key = { it.id }) { t ->
                        SwipeRow(t, onRemove = {
                            a.remove(t.id)
                            Haptics.confirm(view)
                        }, modifier = Modifier.animateItem(
                            fadeInSpec = Motion.spring(reduce), placementSpec = Motion.spring(reduce), fadeOutSpec = Motion.spring(reduce),
                        )) {
                            TransferRow(t, a::cancel, a::retry, a::remove, Modifier, onOpen = {
                                a.open(t)?.let { why -> scope.launch { snack.showSnackbar(why) } }
                            })
                        }
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
            text = { Text("Finished transfers leave this list. Files you received stay where they were saved.") },
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

/** All / Sent / Received as a mono segmented control; the selected pill slides. */
@Composable
private fun FilterBar(selected: TransferList.Filter, onSelect: (TransferList.Filter) -> Unit) {
    val reduce = LocalReduceMotion.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp))
            .border(1.dp, Mono.Line, RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (f in TransferList.Filter.entries) {
            val on = f == selected
            val bg by animateColorAsState(if (on) Mono.Text else Mono.Bg, Motion.spring(reduce), label = "seg")
            val fg by animateColorAsState(if (on) Mono.Bg else Mono.Muted, Motion.spring(reduce), label = "segText")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(bg)
                    .clickable(role = Role.Tab) { onSelect(f) }
                    .heightIn(min = 40.dp).semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (f) {
                        TransferList.Filter.ALL -> "All"
                        TransferList.Filter.SENT -> "Sent"
                        TransferList.Filter.RECEIVED -> "Received"
                    },
                    style = MaterialTheme.typography.labelLarge, color = fg,
                )
            }
        }
    }
}

@Composable
private fun SectionToggle(title: String, count: Int, collapsible: Boolean, open: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val turn by animateFloatAsState(if (open) 180f else 0f, Motion.spring(reduce), label = "chev")
    Row(
        modifier.fillMaxWidth().then(if (collapsible) Modifier.clickable(role = Role.Button, onClick = onToggle) else Modifier)
            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 6.dp)
            .semantics { contentDescription = "$title, $count" + if (collapsible) (if (open) ", expanded" else ", collapsed") else "" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Mono.Muted, modifier = Modifier.weight(1f))
        AnimatedContent(count, label = "count", transitionSpec = { fadeIn() togetherWith fadeOut() }) { n ->
            Text("$n", style = MaterialTheme.typography.labelMedium, color = Mono.Muted, fontFamily = MonoCode)
        }
        if (collapsible) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.ExpandMore, contentDescription = null, tint = Mono.Muted,
                modifier = Modifier.graphicsLayer { rotationZ = turn },
            )
        }
    }
}

/** Swipe left to remove a transfer from the list. */
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
