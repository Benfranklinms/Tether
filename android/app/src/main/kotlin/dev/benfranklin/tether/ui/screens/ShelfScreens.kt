@file:OptIn(ExperimentalSharedTransitionApi::class)
@file:Suppress("TooManyFunctions", "MagicNumber", "LongMethod", "CyclomaticComplexMethod")

package dev.benfranklin.tether.ui.screens

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.benfranklin.tether.core.features.ShelfDetect
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.proto.ShelfKinds
import dev.benfranklin.tether.ui.Haptics
import dev.benfranklin.tether.ui.pressScale
import dev.benfranklin.tether.ui.sharedContainer
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.MonoCode
import dev.benfranklin.tether.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the Shelf screens can do; backed by the runtime's Features6 (or fakes in tests). */
interface ShelfActions {
    fun addText(text: String)
    fun addFile()
    fun pasteText(): String?
    fun remove(id: String): ShelfItem?
    fun restore(item: ShelfItem)
    fun move(id: String, to: Int)
    /** The card's main action ("Copied" / "Opened"). */
    fun primary(item: ShelfItem): String
    fun copy(item: ShelfItem)
    fun open(id: String)
}

/** Badge text for a card's kind. Pure; unit-tested. */
fun shelfKindLabel(kind: String): String = when (kind) {
    ShelfKinds.LINK -> "LINK"
    ShelfKinds.WIFI -> "WI-FI"
    ShelfKinds.ADDRESS -> "ADDRESS"
    ShelfKinds.SNIPPET -> "SNIPPET"
    ShelfKinds.FILE -> "FILE"
    else -> "NOTE"
}

/** The card's preview line(s): never shows a Wi-Fi password. Pure; unit-tested. */
fun shelfPreview(item: ShelfItem): String = when (item.kind) {
    ShelfKinds.WIFI -> if (item.secret.isNullOrEmpty()) "Open network" else "Password ••••••••"
    ShelfKinds.FILE -> item.file?.let { "${it.name} · ${formatBytes(it.size)}" } ?: item.body
    else -> item.body
}

/**
 * Where a dragged card lands: the index whose slot contains the dragged card's centre. [slots]
 * are (offset, size) of the visible cards in list order. Pure; unit-tested.
 */
fun shelfDropIndex(slots: List<Pair<Int, Int>>, from: Int, centre: Float): Int {
    if (slots.isEmpty()) return from
    slots.forEachIndexed { i, (off, size) -> if (centre >= off && centre < off + size) return i }
    val (lastOff, lastSize) = slots.last()
    return when {
        centre < slots.first().first -> 0
        centre >= lastOff + lastSize -> slots.lastIndex
        else -> from // in the spacing between two cards
    }
}

@Composable
fun ShelfScreen(items: List<ShelfItem>, connected: Boolean, a: ShelfActions, onBack: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    // Local copy so a drag can reorder instantly; the shelf store is updated when the drag ends.
    var local by remember { mutableStateOf(items) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(items) { if (dragId == null) local = items }
    // Cards that weren't here when the screen opened spring in (a sync from the Mac, a new add).
    val initial = remember { items.map { it.id }.toSet() }
    val listState = rememberLazyListState()

    fun removeWithUndo(id: String) {
        val removed = a.remove(id) ?: return
        Haptics.confirm(view)
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            val r = snack.showSnackbar("Removed “${removed.title.take(40)}”", "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) {
                a.restore(removed)
                Haptics.tick(view)
            }
        }
    }

    Scaffold(
        containerColor = Mono.Bg,
        topBar = { TopBar("Shelf", onBack) },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        SharedTransitionLayout(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            Box(Modifier.fillMaxSize()) {
                if (local.isEmpty()) {
                    ShelfEmpty(connected)
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(), state = listState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "hint") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                                AnimatedContent(
                                    local.size, label = "count",
                                    transitionSpec = {
                                        if (reduce) {
                                            fadeIn(Motion.fade(true)) togetherWith fadeOut(Motion.fade(true))
                                        } else {
                                            val up = targetState > initialState
                                            (slideInVertically(Motion.spring(false)) { if (up) it else -it } + fadeIn()) togetherWith
                                                (slideOutVertically(Motion.spring(false)) { if (up) -it else it } + fadeOut())
                                        }
                                    },
                                ) { n ->
                                    Text(
                                        "$n", style = MaterialTheme.typography.labelMedium, color = Mono.Text,
                                        fontFamily = MonoCode,
                                    )
                                }
                                Text(
                                    (if (local.size == 1) " item" else " items") +
                                        if (connected) " · synced with your Mac" else " · syncs when your Mac connects",
                                    style = MaterialTheme.typography.labelMedium, color = Mono.Muted,
                                )
                            }
                        }
                        items(local, key = { it.id }) { item ->
                            val dragging = dragId == item.id
                            ShelfRow(
                                item = item, isNew = item.id !in initial, dragging = dragging, dragDy = if (dragging) dragDy else 0f,
                                a = a, onRemove = { removeWithUndo(item.id) },
                                onDragStart = {
                                    dragId = item.id
                                    dragDy = 0f
                                    Haptics.confirm(view)
                                },
                                onDrag = { dy ->
                                    dragDy += dy
                                    val visible = listState.layoutInfo.visibleItemsInfo.filter { it.key is String && it.key != "hint" }
                                    val me = visible.firstOrNull { it.key == item.id } ?: return@ShelfRow
                                    val centre = me.offset + me.size / 2f + dragDy
                                    val slots = visible.map { it.offset to it.size }
                                    val from = visible.indexOf(me)
                                    val to = shelfDropIndex(slots, from, centre)
                                    if (to != from) {
                                        val target = visible[to]
                                        val li = local.indexOfFirst { it.id == item.id }
                                        val ti = local.indexOfFirst { it.id == target.key }
                                        if (li >= 0 && ti >= 0) {
                                            local = local.toMutableList().apply { add(ti, removeAt(li)) }
                                            dragDy -= (target.offset - me.offset).toFloat()
                                            Haptics.tick(view)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    val id = dragId
                                    dragId = null
                                    dragDy = 0f
                                    if (id != null) {
                                        val to = local.indexOfFirst { it.id == id }
                                        if (to >= 0 && items.indexOfFirst { it.id == id } != to) a.move(id, to)
                                    }
                                },
                                modifier = Modifier
                                    .zIndex(if (dragging) 1f else 0f)
                                    .animateItem(
                                        fadeInSpec = Motion.fade(reduce),
                                        placementSpec = if (dragging) null else Motion.spring(reduce),
                                        fadeOutSpec = Motion.fade(reduce),
                                    ),
                            )
                        }
                    }
                }

                // Scrim behind the add sheet.
                AnimatedVisibility(adding, enter = fadeIn(Motion.fade(reduce)), exit = fadeOut(Motion.fade(reduce))) {
                    Box(
                        Modifier.fillMaxSize().background(Mono.Bg.copy(alpha = 0.72f))
                            .clickable(remember { MutableInteractionSource() }, null) { adding = false },
                    )
                }

                // + FAB ⇄ add sheet: one container that morphs between the two (container transform).
                AnimatedVisibility(
                    !adding, modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
                    enter = fadeIn(Motion.fade(reduce)), exit = fadeOut(Motion.fade(reduce)),
                ) {
                    val source = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .then(if (reduce) Modifier else Modifier.sharedBounds(rememberSharedContentState("shelf-add"), this))
                            .pressScale(source).size(60.dp).clip(RoundedCornerShape(20.dp)).background(Mono.Text)
                            .clickable(source, null, role = Role.Button) {
                                Haptics.tick(view)
                                adding = true
                            }
                            .semantics { contentDescription = "Add to Shelf" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Add, contentDescription = null, tint = Mono.Bg) }
                }
                AnimatedVisibility(
                    adding, modifier = Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(12.dp),
                    enter = fadeIn(Motion.fade(reduce)), exit = fadeOut(Motion.fade(reduce)),
                ) {
                    var back by remember { mutableFloatStateOf(0f) }
                    // Predictive back: the sheet shrinks with the gesture, then folds back into the +.
                    PredictiveBackHandler(enabled = adding) { progress ->
                        try {
                            progress.collect { back = it.progress }
                            adding = false
                        } catch (e: CancellationException) {
                            back = 0f
                            throw e
                        }
                    }
                    AddSheet(
                        a = a,
                        onDone = { adding = false },
                        modifier = Modifier
                            .then(if (reduce) Modifier else Modifier.sharedBounds(rememberSharedContentState("shelf-add"), this))
                            .graphicsLayer {
                                val s = 1f - 0.08f * back
                                scaleX = s
                                scaleY = s
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfEmpty(connected: Boolean) {
    val reduce = LocalReduceMotion.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(shown, enter = Motion.enter(reduce)) {
        Column(
            Modifier.fillMaxSize().padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, Mono.Line, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.ContentPaste, contentDescription = null, tint = Mono.Muted) }
            Spacer(Modifier.height(20.dp))
            Text("Your Shelf is empty", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Keep links, Wi-Fi passwords, addresses and snippets here. " +
                    (if (connected) "They show up on your Mac too." else "They sync with your Mac when it connects.") +
                    "\nTap + or share anything › Add to Shelf.",
                style = MaterialTheme.typography.bodyMedium, color = Mono.Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ShelfRow(
    item: ShelfItem,
    isNew: Boolean,
    dragging: Boolean,
    dragDy: Float,
    a: ShelfActions,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier,
) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    // Spring in when the card arrives (sync / add).
    val appear = remember { Animatable(if (isNew && !reduce) 0.86f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)) }
    // Lift while dragging: a little larger, raised, with a stronger outline.
    val lift by animateFloatAsState(if (dragging) 1f else 0f, Motion.spring(reduce, stiffness = Spring.StiffnessMedium), label = "lift")
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v != SwipeToDismissBoxValue.Settled) onRemove()
            v != SwipeToDismissBoxValue.Settled
        },
        positionalThreshold = { it * 0.4f },
    )
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        gesturesEnabled = !dragging,
        modifier = modifier.graphicsLayer {
            translationY = dragDy
            val s = appear.value * (1f + 0.03f * lift)
            scaleX = s
            scaleY = s
            shadowElevation = 24f * lift
            shape = RoundedCornerShape(18.dp)
            clip = false
        },
        backgroundContent = {
            val past = dismiss.progress > 0.4f && dismiss.targetValue != SwipeToDismissBoxValue.Settled
            val bg by animateColorAsState(if (past) Mono.Text else Mono.SurfaceHigh, Motion.fade(reduce), label = "bg")
            val iconScale by animateFloatAsState(if (past) 1.2f else 1f, Motion.bouncy(reduce), label = "icon")
            LaunchedEffect(past) { if (past) Haptics.tick(view) }
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(bg).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Outlined.Delete, contentDescription = null, tint = if (past) Mono.Bg else Mono.Text,
                    modifier = Modifier.graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                )
            }
        },
    ) {
        val source = remember { MutableInteractionSource() }
        Column(
            Modifier.fillMaxWidth()
                .sharedContainer("shelf-${item.id}", reduce)
                .pressScale(source)
                .clip(RoundedCornerShape(18.dp)).background(if (dragging) Mono.SurfaceHigh else Mono.Surface)
                .border(1.dp, if (dragging) Mono.Muted else Mono.Line, RoundedCornerShape(18.dp))
                .pointerInput(item.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                        onDrag = { change, amount ->
                            change.consume()
                            onDrag(amount.y)
                        },
                    )
                }
                .clickable(source, androidx.compose.material3.ripple(), role = Role.Button) {
                    Haptics.tick(view)
                    a.open(item.id)
                }
                .padding(start = 16.dp, top = 14.dp, bottom = 10.dp, end = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindBadge(item.kind)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (item.from == "mac") "from Mac" else "from phone", style = MaterialTheme.typography.labelSmall,
                    color = Mono.Muted, modifier = Modifier.weight(1f),
                )
                CardAction(item, a)
            }
            Text(
                item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, end = 8.dp),
            )
            val preview = shelfPreview(item)
            if (preview.isNotBlank()) {
                Text(
                    preview, style = MaterialTheme.typography.bodySmall, color = Mono.Muted, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = if (item.kind == ShelfKinds.SNIPPET || item.kind == ShelfKinds.WIFI) MonoCode else null,
                    modifier = Modifier.padding(top = 2.dp, end = 8.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun KindBadge(kind: String) {
    Text(
        shelfKindLabel(kind), style = MaterialTheme.typography.labelSmall, color = Mono.Text,
        modifier = Modifier.border(1.dp, Mono.Line, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The card's one action (Copy / Open / Map). Copy morphs into a check mark + "Copied" for a moment. */
@Composable
private fun CardAction(item: ShelfItem, a: ShelfActions) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    var done by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(done) {
        if (done != null) {
            delay(1_400)
            done = null
        }
    }
    val source = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (done != null) Mono.Text else Mono.Bg, Motion.fade(reduce), label = "actionBg")
    val fg by animateColorAsState(if (done != null) Mono.Bg else Mono.Text, Motion.fade(reduce), label = "actionFg")
    Box(
        Modifier.pressScale(source).clip(RoundedCornerShape(50)).background(bg).border(1.dp, Mono.Text, RoundedCornerShape(50))
            .clickable(source, null, role = Role.Button) {
                done = a.primary(item)
                Haptics.confirm(view)
            }
            .heightIn(min = 32.dp).padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            done, label = "copied",
            transitionSpec = {
                if (reduce) {
                    fadeIn(Motion.fade(true)) togetherWith fadeOut(Motion.fade(true))
                } else {
                    (fadeIn(Motion.spring(false)) + scaleIn(Motion.bouncy(false), initialScale = 0.6f)) togetherWith
                        (fadeOut(Motion.spring(false)) + scaleOut(Motion.spring(false), targetScale = 0.6f))
                }
            },
        ) { d ->
            if (d == null) {
                Text(ShelfDetect.action(item.kind), style = MaterialTheme.typography.labelLarge, color = fg)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(d, style = MaterialTheme.typography.labelLarge, color = fg)
                }
            }
        }
    }
}

@Composable
private fun AddSheet(a: ShelfActions, onDone: () -> Unit, modifier: Modifier) {
    val view = LocalView.current
    var text by remember { mutableStateOf("") }
    val guess = remember(text) { if (text.isBlank()) null else ShelfDetect.classify(text) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Mono.SurfaceHigh)
            .border(1.dp, Mono.Line, RoundedCornerShape(28.dp)).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Add to Shelf", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val reduce = LocalReduceMotion.current
            AnimatedVisibility(guess != null, enter = Motion.enter(reduce), exit = Motion.exit(reduce)) {
                KindBadge(guess?.kind ?: ShelfKinds.TEXT)
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            placeholder = { Text("A link, Wi-Fi password, address or note", color = Mono.Muted) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Mono.Text, unfocusedBorderColor = Mono.Line, cursorColor = Mono.Text,
                focusedContainerColor = Mono.Bg, unfocusedContainerColor = Mono.Bg,
            ),
            shape = RoundedCornerShape(16.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { a.pasteText()?.let { text = it } }) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Paste")
            }
            TextButton(onClick = {
                a.addFile()
                onDone()
            }) {
                Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("File")
            }
            Spacer(Modifier.weight(1f))
            val source = remember { MutableInteractionSource() }
            val enabled = text.isNotBlank()
            val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, label = "addAlpha")
            Text(
                "Add", style = MaterialTheme.typography.labelLarge, color = Mono.Bg, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.graphicsLayer { this.alpha = alpha }.pressScale(source).clip(RoundedCornerShape(14.dp))
                    .background(Mono.Text)
                    .clickable(source, null, enabled = enabled, role = Role.Button) {
                        a.addText(text)
                        Haptics.confirm(view)
                        onDone()
                    }
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }
    }
}

/** Detail page for one card; its container grows out of the card (shared bounds). */
@Composable
fun ShelfDetailScreen(item: ShelfItem?, a: ShelfActions, onBack: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    Scaffold(containerColor = Mono.Bg, topBar = { TopBar("Shelf", onBack) }) { pad ->
        if (item == null) {
            Text(
                "This item was removed.", color = Mono.Muted,
                modifier = Modifier.padding(top = pad.calculateTopPadding() + 24.dp, start = 24.dp),
            )
            return@Scaffold
        }
        var reveal by remember { mutableStateOf(false) }
        val maskLen = item.secret.orEmpty().length.coerceIn(8, 16)
        Column(
            Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().sharedContainer("shelf-${item.id}", reduce)
                    .clip(RoundedCornerShape(24.dp)).background(Mono.Surface).border(1.dp, Mono.Line, RoundedCornerShape(24.dp))
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KindBadge(item.kind)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (item.from == "mac") "Added on your Mac" else "Added on this phone",
                        style = MaterialTheme.typography.labelSmall, color = Mono.Muted,
                    )
                }
                Text(item.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
                SelectionContainer {
                    Column {
                        when (item.kind) {
                            ShelfKinds.WIFI -> {
                                Text(
                                    "Network", style = MaterialTheme.typography.labelMedium, color = Mono.Muted,
                                    modifier = Modifier.padding(top = 16.dp),
                                )
                                Text(item.body.ifEmpty { item.title }, style = MaterialTheme.typography.bodyLarge, fontFamily = MonoCode)
                                if (!item.secret.isNullOrEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Password", style = MaterialTheme.typography.labelMedium, color = Mono.Muted)
                                            AnimatedContent(reveal, label = "reveal") { r ->
                                                Text(
                                                    if (r) item.secret.orEmpty() else "•".repeat(maskLen),
                                                    style = MaterialTheme.typography.bodyLarge, fontFamily = MonoCode,
                                                )
                                            }
                                        }
                                        IconButton(onClick = {
                                            reveal = !reveal
                                            Haptics.tick(view)
                                        }) {
                                            Icon(
                                                if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                                contentDescription = if (reveal) "Hide password" else "Show password",
                                            )
                                        }
                                    }
                                }
                            }
                            ShelfKinds.FILE -> Text(
                                shelfPreview(item), style = MaterialTheme.typography.bodyLarge, color = Mono.Muted,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            else -> if (item.body.isNotEmpty()) {
                                Text(
                                    item.body, style = MaterialTheme.typography.bodyLarge,
                                    fontFamily = if (item.kind == ShelfKinds.SNIPPET) MonoCode else null,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            DetailActions(item, a, onRemoved = onBack)
        }
    }
}

@Composable
private fun DetailActions(item: ShelfItem, a: ShelfActions, onRemoved: () -> Unit) {
    val view = LocalView.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigAction(
            if (copied) "Copied" else "Copy", if (copied) Icons.Outlined.Check else Icons.Outlined.ContentPaste,
            filled = copied, modifier = Modifier.weight(1f),
        ) {
            a.copy(item)
            copied = true
            Haptics.confirm(view)
        }
        if (item.kind == ShelfKinds.LINK || item.kind == ShelfKinds.ADDRESS || item.kind == ShelfKinds.FILE) {
            val icon = if (item.kind == ShelfKinds.ADDRESS) Icons.Outlined.Place else Icons.AutoMirrored.Outlined.OpenInNew
            BigAction(ShelfDetect.action(item.kind), icon, modifier = Modifier.weight(1f)) {
                a.primary(item)
                Haptics.confirm(view)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    TextButton(
        onClick = {
            a.remove(item.id)
            Haptics.confirm(view)
            onRemoved()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Outlined.Delete, contentDescription = null, tint = Mono.Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Remove from Shelf", color = Mono.Muted)
    }
}

@Composable
private fun BigAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val source = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (filled) Mono.Text else Mono.Surface, Motion.fade(reduce), label = "bab")
    val fg by animateColorAsState(if (filled) Mono.Bg else Mono.Text, Motion.fade(reduce), label = "baf")
    val corner by animateDpAsState(if (filled) 26.dp else 16.dp, Motion.spring(reduce), label = "corner")
    Row(
        modifier.pressScale(source).clip(RoundedCornerShape(corner)).background(bg).border(1.dp, Mono.Line, RoundedCornerShape(corner))
            .clickable(source, null, role = Role.Button, onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        run {
            AnimatedContent(icon, label = "icon", transitionSpec = {
                if (reduce) {
                    fadeIn(Motion.fade(true)) togetherWith fadeOut(Motion.fade(true))
                } else {
                    (fadeIn() + scaleIn(Motion.bouncy(false), 0.5f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.5f))
                }
            }) { i -> Icon(i, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Password field used by the hotspot screen (and anything else secret). */
@Composable
fun SecretField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(
                    if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (show) "Hide password" else "Show password",
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
        colors = monoFieldColors(),
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
fun monoFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Mono.Text, unfocusedBorderColor = Mono.Line, cursorColor = Mono.Text,
    focusedLabelColor = Mono.Text, unfocusedLabelColor = Mono.Muted,
)

/** A round icon used by empty states (kept here so Shelf and Find share the look). */
@Composable
fun RingIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).clip(CircleShape).border(1.dp, Mono.Line, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Mono.Text, modifier = Modifier.size(20.dp))
    }
}
