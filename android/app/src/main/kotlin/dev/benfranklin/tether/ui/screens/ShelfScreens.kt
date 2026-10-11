@file:OptIn(ExperimentalSharedTransitionApi::class)
@file:Suppress("TooManyFunctions", "MagicNumber", "LongMethod", "CyclomaticComplexMethod")

package dev.benfranklin.tether.ui.screens

import dev.benfranklin.tether.ui.screenPadding
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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
import dev.benfranklin.tether.core.features.FileKinds
import dev.benfranklin.tether.core.features.ShelfCache
import dev.benfranklin.tether.core.features.ShelfDetect
import dev.benfranklin.tether.core.features.ShelfRename
import dev.benfranklin.tether.core.features.ShelfCardText
import dev.benfranklin.tether.core.features.ShelfSearch
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

/** Which Shelf files have a local copy / are being fetched, and whether the Mac is connected (0.5.3). */
data class ShelfFilesUi(val local: Set<String> = emptySet(), val fetching: Set<String> = emptySet(), val connected: Boolean = true) {
    fun state(item: ShelfItem): ShelfCache.State =
        ShelfCache.state(haveLocal = item.id in local, fetching = item.id in fetching, connected = connected)
}

/** What the Shelf screens can do; backed by the runtime's Features6 (or fakes in tests). */
@Suppress("TooManyFunctions")
interface ShelfActions {
    fun addText(text: String)
    fun addFile()
    fun pasteText(): String?
    fun remove(id: String): ShelfItem?
    fun restore(item: ShelfItem)
    fun move(id: String, to: Int)
    /** The card's main action ("Copied" / "Opened"). */
    fun primary(item: ShelfItem): String
    /** Copy: the text, or for a file the file itself. Returns the confirmation ("Copied"). */
    fun copy(item: ShelfItem): String
    fun open(id: String)
    /** Renames an item (synced); false when nothing changed. */
    fun rename(id: String, title: String): Boolean = false
    fun share(item: ShelfItem): String = "Shared"
    /** A small picture for an image file with a local copy (decoded off the main thread). */
    suspend fun thumbnail(item: ShelfItem): ImageBitmap? = null
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

/**
 * The card's preview line(s): never shows a Wi-Fi password; files show size and a friendly kind
 * ("11 KB · Word document"), plus "Not downloaded yet" when the copy is missing and the Mac is away.
 * Pure; unit-tested.
 */
fun shelfPreview(item: ShelfItem, state: ShelfCache.State = ShelfCache.State.LOCAL): String = when (item.kind) {
    ShelfKinds.WIFI -> if (item.secret.isNullOrEmpty()) "Open network" else "Password ••••••••"
    ShelfKinds.FILE -> item.file?.let {
        listOfNotNull(
            formatBytes(it.size), FileKinds.describe(it.mime, it.name),
            "Not downloaded yet".takeIf { state == ShelfCache.State.NOT_DOWNLOADED },
        ).joinToString(" · ")
    } ?: ""
    else -> item.body
}

/** Card title + preview; a snippet titled from its own first line doesn't repeat it (0.5.3). */
fun shelfCardText(item: ShelfItem, state: ShelfCache.State = ShelfCache.State.LOCAL): ShelfCardText.Parts =
    if (item.kind == ShelfKinds.FILE || item.kind == ShelfKinds.WIFI) {
        ShelfCardText.Parts(item.title, shelfPreview(item, state))
    } else {
        ShelfCardText.split(item.title, item.body)
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
fun ShelfScreen(
    items: List<ShelfItem>,
    connected: Boolean,
    a: ShelfActions,
    onBack: () -> Unit,
    files: ShelfFilesUi = ShelfFilesUi(connected = connected),
    initialQuery: String = "",
) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var renaming by remember { mutableStateOf<ShelfItem?>(null) }
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
        SharedTransitionLayout(Modifier.fillMaxSize().padding(screenPadding(pad, bottom = 0.dp))) {
            Box(Modifier.fillMaxSize()) {
                val shown = if (query.isBlank()) local else ShelfSearch.filter(local, query)
                val searching = query.isNotBlank()
                if (local.isEmpty()) {
                    ShelfEmpty(connected)
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(), state = listState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "search") {
                            ShelfSearchBar(query, onChange = { query = it })
                        }
                        if (searching && shown.isEmpty()) {
                            item(key = "nomatch") {
                                Text(
                                    "Nothing on your Shelf matches “${query.trim()}”", style = MaterialTheme.typography.bodyMedium,
                                    color = Mono.Muted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 24.dp).animateItem(),
                                )
                            }
                        }
                        if (!searching) item(key = "hint") {
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
                        items(shown, key = { it.id }) { item ->
                            val dragging = dragId == item.id
                            ShelfRow(
                                item = item, isNew = item.id !in initial, dragging = dragging, dragDy = if (dragging) dragDy else 0f,
                                a = a, onRemove = { removeWithUndo(item.id) },
                                state = files.state(item), reorder = !searching,
                                onRename = { renaming = item },
                                onDragStart = {
                                    dragId = item.id
                                    dragDy = 0f
                                    Haptics.confirm(view)
                                },
                                onDrag = { dy ->
                                    dragDy += dy
                                    val visible = listState.layoutInfo.visibleItemsInfo
                                        .filter { it.key is String && it.key !in NON_CARD_KEYS }
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
    renaming?.let { r ->
        RenameDialog(r, onDone = { title ->
            if (title != null && a.rename(r.id, title)) Haptics.confirm(view)
            renaming = null
        })
    }
}

/** Search the Shelf (0.5.3): title, content, file name and type; results filter with the list's own animation. */
@Composable
private fun ShelfSearchBar(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onChange, singleLine = true,
        placeholder = { Text("Search Shelf", color = Mono.Muted) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Mono.Muted) },
        trailingIcon = {
            AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Mono.Text, unfocusedBorderColor = Mono.Line, cursorColor = Mono.Text,
            focusedContainerColor = Mono.Surface, unfocusedContainerColor = Mono.Surface,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { contentDescription = "Search Shelf" },
    )
}

/** Rename a Shelf item: prefilled, all selected; Save renames on both devices. */
@Composable
fun RenameDialog(item: ShelfItem, onDone: (String?) -> Unit) {
    var text by remember(item.id) {
        mutableStateOf(TextFieldValue(item.title, selection = TextRange(0, item.title.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = Mono.SurfaceHigh, titleContentColor = Mono.Text, textContentColor = Mono.Text,
        title = { Text("Rename") },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Mono.Text, unfocusedBorderColor = Mono.Line, cursorColor = Mono.Text,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone(text.text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                item.file?.let { f ->
                    Text(
                        "Opens and copies as “${ShelfRename.fileName(text.text.ifBlank { item.title }, f.name)}”",
                        style = MaterialTheme.typography.bodySmall, color = Mono.Muted, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(text.text) }, enabled = text.text.isNotBlank()) { Text("Save", color = Mono.Text) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel", color = Mono.Muted) } },
    )
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
    state: ShelfCache.State = ShelfCache.State.LOCAL,
    reorder: Boolean = true,
    onRename: () -> Unit = {},
) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    // Long-press without moving opens the menu; long-press and drag reorders.
    var menu by remember { mutableStateOf(false) }
    var travelled by remember { mutableFloatStateOf(0f) }
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
                .pointerInput(item.id, reorder) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            travelled = 0f
                            if (reorder) onDragStart() else Haptics.confirm(view)
                        },
                        onDragEnd = {
                            if (reorder) onDragEnd()
                            if (travelled < MENU_SLOP_PX) menu = true
                        },
                        onDragCancel = { if (reorder) onDragEnd() },
                        onDrag = { change, amount ->
                            change.consume()
                            travelled += kotlin.math.abs(amount.y) + kotlin.math.abs(amount.x)
                            if (reorder) onDrag(amount.y)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val (title, preview) = shelfCardText(item, state)
                    Text(
                        title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp, end = 8.dp),
                    )
                    if (preview.isNotBlank()) {
                        Text(
                            preview, style = MaterialTheme.typography.bodySmall, color = Mono.Muted, maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = if (item.kind == ShelfKinds.SNIPPET || item.kind == ShelfKinds.WIFI) MonoCode else null,
                            modifier = Modifier.padding(top = 2.dp, end = 8.dp, bottom = 4.dp),
                        )
                    }
                }
                ShelfThumb(item, state, a)
            }
            ShelfMenu(menu, item, a, onDismiss = { menu = false }, onRename = onRename, onRemove = onRemove)
        }
    }
}

/** An image file's picture (from the local copy), shown at the card's trailing edge. */
@Composable
private fun ShelfThumb(item: ShelfItem, state: ShelfCache.State, a: ShelfActions) {
    val f = item.file ?: return
    if (!FileKinds.isImage(f.mime, f.name) || state != ShelfCache.State.LOCAL) return
    var bmp by remember(item.id, f.sha256) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.id, f.sha256) { bmp = a.thumbnail(item) }
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(bmp != null, enter = Motion.enter(reduce, from = 0.85f)) {
        bmp?.let {
            Image(
                it, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.padding(end = 8.dp, bottom = 4.dp).size(52.dp).clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Mono.Line, RoundedCornerShape(12.dp)),
            )
        }
    }
}

/** Long-press menu: Rename, Copy, Share, Remove. */
@Composable
private fun ShelfMenu(
    open: Boolean, item: ShelfItem, a: ShelfActions, onDismiss: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit,
) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = {
            onDismiss()
            onRename()
        })
        DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = {
            onDismiss()
            a.copy(item)
        })
        DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = {
            onDismiss()
            a.share(item)
        })
        DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = {
            onDismiss()
            onRemove()
        })
    }
}

private const val MENU_SLOP_PX = 24f
private val DONE_LABELS = setOf("Copied", "Opened", "Shared")
private val NON_CARD_KEYS = setOf("hint", "search", "nomatch")

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
                    if (d in DONE_LABELS) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
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
fun ShelfDetailScreen(item: ShelfItem?, a: ShelfActions, onBack: () -> Unit, files: ShelfFilesUi = ShelfFilesUi()) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    var renaming by remember { mutableStateOf(false) }
    Scaffold(containerColor = Mono.Bg, topBar = { TopBar("Shelf", onBack) }) { pad ->
        if (item == null) {
            Text(
                "This item was removed.", color = Mono.Muted,
                modifier = Modifier.padding(top = pad.calculateTopPadding() + 24.dp, start = 24.dp),
            )
            return@Scaffold
        }
        var reveal by remember { mutableStateOf(false) }
        val maskLen = 10 // fixed: never hint at the real length
        Column(
            Modifier.fillMaxSize().padding(screenPadding(pad, bottom = 0.dp)).verticalScroll(rememberScrollState()).padding(16.dp),
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Text(item.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        Haptics.tick(view)
                        renaming = true
                    }) { Icon(Icons.Outlined.Edit, contentDescription = "Rename") }
                }
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
                            ShelfKinds.FILE -> Row(
                                verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp),
                            ) {
                                Text(
                                    shelfPreview(item, files.state(item)), style = MaterialTheme.typography.bodyLarge,
                                    color = Mono.Muted, modifier = Modifier.weight(1f),
                                )
                                ShelfThumb(item, files.state(item), a)
                            }
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
        if (renaming) {
            RenameDialog(item) { title ->
                if (title != null && a.rename(item.id, title)) Haptics.confirm(view)
                renaming = false
            }
        }
    }
}

@Composable
private fun DetailActions(item: ShelfItem, a: ShelfActions, onRemoved: () -> Unit) {
    val view = LocalView.current
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(1_400)
            copied = null
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigAction(
            copied ?: "Copy", if (copied != null) Icons.Outlined.Check else Icons.Outlined.ContentPaste,
            filled = copied != null, modifier = Modifier.weight(1f),
        ) {
            copied = a.copy(item)
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
    if (item.kind == ShelfKinds.FILE) {
        Spacer(Modifier.height(10.dp))
        BigAction("Share", Icons.Outlined.Share, modifier = Modifier.fillMaxWidth()) {
            a.share(item)
            Haptics.tick(view)
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
