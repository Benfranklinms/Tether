@file:OptIn(ExperimentalSharedTransitionApi::class)
@file:Suppress("TooManyFunctions", "MagicNumber", "LongMethod", "CyclomaticComplexMethod")

package dev.benfranklin.tether.ui.screens

import dev.benfranklin.tether.ui.screenPadding
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.material.icons.outlined.NorthEast
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.benfranklin.tether.core.features.FileKinds
import dev.benfranklin.tether.core.features.ShelfCache
import dev.benfranklin.tether.core.features.ShelfMeta
import dev.benfranklin.tether.core.features.TransferMeta
import dev.benfranklin.tether.core.features.When
import dev.benfranklin.tether.core.features.ShelfDetect
import dev.benfranklin.tether.core.features.ShelfRename
import dev.benfranklin.tether.core.features.ShelfCardText
import dev.benfranklin.tether.core.features.ShelfSearch
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.proto.ShelfKinds
import dev.benfranklin.tether.log.guarded
import dev.benfranklin.tether.ui.Haptics
import dev.benfranklin.tether.ui.clamp01
import dev.benfranklin.tether.ui.pressScale
import dev.benfranklin.tether.ui.LocalSharedScopes
import dev.benfranklin.tether.ui.sharedContainer
import androidx.compose.ui.geometry.Offset
import dev.benfranklin.tether.core.proto.ShelfFolder
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.MonoCode
import dev.benfranklin.tether.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which Shelf files have a local copy / are being fetched (with progress), and whether the Mac is connected (0.5.3). */
data class ShelfFilesUi(
    val local: Set<String> = emptySet(),
    val fetching: Set<String> = emptySet(),
    val connected: Boolean = true,
    val progress: Map<String, Float> = emptyMap(),
) {
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
    /** A small picture for an image file (decoded off the main thread; kept after the copy is evicted). */
    suspend fun thumbnail(item: ShelfItem): ImageBitmap? = null

    // ----- 0.5.4 folders and offline copies -----
    /** Add text into a folder (null = loose). */
    fun addTextTo(text: String, folderId: String?) = addText(text)
    /** Pick files to add into a folder (null = loose). */
    fun addFileTo(folderId: String?) = addFile()
    /** Reorder within one folder's list (null = the loose items). */
    fun moveWithin(folderId: String?, id: String, to: Int) = move(id, to)
    fun createFolder(name: String): String? = null
    fun renameFolder(id: String, name: String): Boolean = false
    fun deleteFolder(id: String) = Unit
    /** False when nothing changed (dropped onto the folder it is already in). */
    fun moveTo(itemId: String, folderId: String?): Boolean = false
    fun openFolder(id: String) = Unit
    fun keepOffline(itemId: String) = Unit
    fun keepFolderOffline(folderId: String) = Unit
    fun keptOffline(item: ShelfItem): Boolean = false
    fun folderKeptOffline(folderId: String): Boolean = false
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ShelfScreen(
    items: List<ShelfItem>,
    connected: Boolean,
    a: ShelfActions,
    onBack: () -> Unit,
    files: ShelfFilesUi = ShelfFilesUi(connected = connected),
    initialQuery: String = "",
    now: Long = System.currentTimeMillis(),
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    /** 0.5.4: the Shelf's folders (chip order) and which one this page shows (null = the Shelf itself). */
    folders: List<ShelfFolder> = emptyList(),
    folderId: String? = null,
) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var query by rememberSaveable(folderId) { mutableStateOf(initialQuery) }
    var renaming by remember { mutableStateOf<ShelfItem?>(null) }
    // 0.5.4 folders: which sheet / menu / dialog is up.
    var moving by remember { mutableStateOf<ShelfItem?>(null) }
    var naming by remember { mutableStateOf<FolderNaming?>(null) }
    var folderMenu by remember { mutableStateOf<ShelfFolder?>(null) }
    var deleting by remember { mutableStateOf<ShelfFolder?>(null) }
    val folderIds = remember(folders) { folders.map { it.id }.toSet() }
    fun placeOf(i: ShelfItem): String? = i.folderId?.takeIf { it in folderIds }
    val counts = remember(items, folderIds) { items.mapNotNull { placeOf(it) }.groupingBy { it }.eachCount() }
    val folder = folders.firstOrNull { it.id == folderId }
    // This page's items: the loose ones on the Shelf, or one folder's.
    val pageItems = remember(items, folderIds, folderId) { items.filter { placeOf(it) == folderId } }
    // Local copy so a drag can reorder instantly; the shelf store is updated when the drag ends.
    var local by remember { mutableStateOf(pageItems) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    // Where the finger is (root coordinates) while a row is dragged, and the chip under it.
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var hover by remember { mutableStateOf<String?>(null) }
    val chipBounds = remember { HashMap<String, androidx.compose.ui.geometry.Rect>() }
    LaunchedEffect(pageItems) { if (dragId == null) local = pageItems }
    // Rows that weren't here when the screen opened spring in (a sync from the Mac, a new add).
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

    fun moveItem(itemId: String, to: String?) {
        if (a.moveTo(itemId, to)) {
            Haptics.confirm(view)
            val name = folders.firstOrNull { it.id == to }?.name
            scope.launch {
                snack.currentSnackbarData?.dismiss()
                snack.showSnackbar(if (name != null) "Moved to $name" else "Moved to Shelf", duration = SnackbarDuration.Short)
            }
        }
    }

    // 0.5.4 crash fix ("layouts are not part of the same hierarchy" on open/resume): no nested
    // SharedTransitionLayout here any more. The + ⇄ add-sheet morph and the row ⇄ detail container
    // transform both use the one app-level scope (LocalSharedScopes), and nothing shared runs
    // inside a popup, dialog or bottom sheet.
    val shared = LocalSharedScopes.current?.shared
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Mono.Bg,
        topBar = {
            val addButton: @Composable () -> Unit = {
                AnimatedVisibility(!adding, enter = fadeIn(Motion.fade(reduce)), exit = fadeOut(Motion.fade(reduce))) {
                    val source = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .addMorph(shared, this, reduce)
                            .pressScale(source).size(44.dp).clip(CircleShape).background(Mono.SurfaceHigh)
                            .clickable(source, null, role = Role.Button) {
                                Haptics.tick(view)
                                adding = true
                            }
                            .semantics { contentDescription = "Add to Shelf" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Add, contentDescription = null, tint = Mono.Text) }
                }
            }
            if (folder != null) {
                FolderHeader(folder, pageItems.size, onBack, onAdd = { adding = true }, onMore = { folderMenu = folder }) {
                    FolderMenu(
                        folderMenu?.takeIf { it.id == folder.id }, a.folderKeptOffline(folder.id), onDismiss = { folderMenu = null },
                        onRename = { naming = FolderNaming.Rename(folder) }, onKeep = { a.keepFolderOffline(folder.id) },
                        onDelete = { deleting = folder },
                    )
                }
            } else {
                LargeTitle("Shelf", onBack, heroKey = "shelf-title", count = items.size.takeIf { it > 0 }) { addButton() }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(screenPadding(pad, bottom = 0.dp))) {
                // Search: the whole Shelf on the Shelf page, one folder on a folder page.
                val searching = query.isNotBlank()
                val shown = if (!searching) local else ShelfSearch.filter(if (folderId == null) items else local, query)
                val headers = remember(shown, searching, now) { if (searching) emptyMap() else ShelfMeta.sections(shown, now, zone) }
                if (items.isEmpty() && folderId == null && folders.isEmpty()) {
                    ShelfEmpty(connected)
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(), state = listState,
                        contentPadding = PaddingValues(top = 0.dp, bottom = 120.dp),
                    ) {
                        item(key = "search") {
                            ShelfSearchBar(query, onChange = { query = it }, placeholder = folder?.let { "Search ${it.name}" } ?: "Search")
                        }
                        if (folderId == null) {
                            item(key = "chips") {
                                Box {
                                    FolderChipRow(
                                        folders, counts, hover, chipBounds,
                                        onNew = { naming = FolderNaming.New(null) },
                                        onOpen = { f -> a.openFolder(f.id) },
                                        onMenu = { f -> folderMenu = f },
                                    )
                                    FolderMenu(
                                        folderMenu, folderMenu?.let { a.folderKeptOffline(it.id) } == true,
                                        onDismiss = { folderMenu = null },
                                        onRename = { folderMenu?.let { naming = FolderNaming.Rename(it) } },
                                        onKeep = { folderMenu?.let { a.keepFolderOffline(it.id) } },
                                        onDelete = { deleting = folderMenu },
                                    )
                                }
                            }
                        }
                        if (searching && shown.isEmpty()) {
                            item(key = "nomatch") {
                                Text(
                                    "No match for “${query.trim()}”", style = MaterialTheme.typography.bodyMedium,
                                    color = Mono.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp).animateItem(),
                                )
                            }
                        } else if (!searching && shown.isEmpty()) {
                            item(key = "pageempty") {
                                Text(
                                    if (folderId != null) {
                                        "Nothing in this folder yet. Hold an item on your Shelf and choose Move to, " +
                                            "or drag it onto the folder."
                                    } else {
                                        "Everything is in folders."
                                    },
                                    style = MaterialTheme.typography.bodyMedium, color = Mono.Muted,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp).animateItem(),
                                )
                            }
                        }
                        shown.forEachIndexed { index, item ->
                            headers[index]?.let { h ->
                                item(key = "h-$h") {
                                    Text(
                                        h, style = MaterialTheme.typography.bodyLarge, color = Mono.Muted,
                                        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp).animateItem(),
                                    )
                                }
                            }
                            item(key = item.id) {
                                val dragging = dragId == item.id
                                ShelfRow(
                                    item = item, isNew = item.id !in initial, dragging = dragging, dragDy = if (dragging) dragDy else 0f,
                                    dragDx = if (dragging) dragDx else 0f, overChip = dragging && hover != null,
                                    a = a, onRemove = { removeWithUndo(item.id) },
                                    state = files.state(item), progress = files.progress[item.id],
                                    reorder = !searching, now = now, zone = zone,
                                    divider = index < shown.lastIndex && headers[index + 1] == null,
                                    onRename = { renaming = item },
                                    onMove = { moving = item },
                                    onDragStart = { at ->
                                        dragId = item.id
                                        dragDy = 0f
                                        dragDx = 0f
                                        pointer = at
                                        hover = null
                                        Haptics.confirm(view)
                                    },
                                    onDrag = { amount ->
                                        dragDy += amount.y
                                        dragDx += amount.x
                                        pointer += amount
                                        // Over a chip: highlight it and leave the order alone.
                                        val over = if (folderId != null) null else {
                                            chipBounds.entries.firstOrNull { it.value.contains(pointer) }?.key
                                        }
                                        if (over != hover) {
                                            hover = over
                                            if (over != null) Haptics.tick(view)
                                        }
                                        if (over != null) return@ShelfRow
                                        val visible = listState.layoutInfo.visibleItemsInfo
                                            .filter { it.key is String && it.key !in NON_CARD_KEYS && !(it.key as String).startsWith("h-") }
                                        val me = visible.firstOrNull { it.key == item.id } ?: return@ShelfRow
                                        val centre = me.offset + me.size / 2f + dragDy
                                        val slots = visible.map { it.offset to it.size }
                                        val from = visible.indexOf(me)
                                        val to = shelfDropIndex(slots, from, centre)
                                        if (to != from) {
                                            val target = visible.getOrNull(to) ?: return@ShelfRow
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
                                        val target = hover
                                        dragId = null
                                        dragDy = 0f
                                        dragDx = 0f
                                        hover = null
                                        if (id != null && target != null) {
                                            // Dropped on a chip: into that folder (the list order is left as it was).
                                            local = pageItems
                                            guarded(ctx, "shelf.dropOnChip") { moveItem(id, target) }
                                        } else if (id != null) {
                                            val to = local.indexOfFirst { it.id == id }
                                            if (to >= 0 && pageItems.indexOfFirst { it.id == id } != to) a.moveWithin(folderId, id, to)
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
                }

                // Scrim behind the add sheet.
                AnimatedVisibility(adding, enter = fadeIn(Motion.fade(reduce)), exit = fadeOut(Motion.fade(reduce))) {
                    Box(
                        Modifier.fillMaxSize().background(Mono.Bg.copy(alpha = 0.72f))
                            .clickable(remember { MutableInteractionSource() }, null) { adding = false },
                    )
                }
                // The + in the title bar morphs into the add sheet (container transform) and back.
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
                        folderId = folderId,
                        onDone = { adding = false },
                        modifier = Modifier
                            .addMorph(shared, this, reduce || folder != null)
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
    moving?.let { m ->
        MoveSheet(
            m, placeOf(m), folders, counts, files.state(m), { a.thumbnail(it) },
            onPick = { to ->
                moving = null
                moveItem(m.id, to)
            },
            onNewFolder = {
                moving = null
                naming = FolderNaming.New(m)
            },
            onDismiss = { moving = null },
        )
    }
    naming?.let { n ->
        when (n) {
            is FolderNaming.New -> FolderNameSheet("New folder", "Create", "") { name ->
                naming = null
                if (name != null) {
                    val id = a.createFolder(name)
                    if (id != null) {
                        Haptics.confirm(view)
                        n.thenMove?.let { moveItem(it.id, id) }
                    }
                }
            }
            is FolderNaming.Rename -> FolderNameSheet("Rename folder", "Save", n.folder.name) { name ->
                naming = null
                if (name != null && a.renameFolder(n.folder.id, name)) Haptics.confirm(view)
            }
        }
    }
    deleting?.let { f ->
        DeleteFolderDialog(f, counts[f.id] ?: 0) { yes ->
            deleting = null
            if (yes) {
                // On its own page, the page leaves by itself once the folder is gone (no double back).
                a.deleteFolder(f.id)
                Haptics.confirm(view)
            }
        }
    }
}

/** The + ⇄ add-sheet morph, in the app-level shared scope only (a no-op without one, or with reduce motion). */
@Composable
private fun Modifier.addMorph(shared: SharedTransitionScope?, visibility: AnimatedVisibilityScope, off: Boolean): Modifier {
    if (shared == null || off) return this
    return with(shared) { this@addMorph.sharedBounds(rememberSharedContentState("shelf-add"), visibility) }
}

/** Which folder-name sheet is up: a new folder (then [thenMove] goes into it) or a rename. */
private sealed interface FolderNaming {
    data class New(val thenMove: ShelfItem?) : FolderNaming
    data class Rename(val folder: ShelfFolder) : FolderNaming
}

/** Search the Shelf (0.5.3): title, content, file name and type; results filter with the list's own animation. */
@Composable
private fun ShelfSearchBar(query: String, onChange: (String) -> Unit, placeholder: String = "Search") {
    OutlinedTextField(
        value = query, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder, color = Mono.Muted, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Mono.Muted) },
        trailingIcon = {
            AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") }
            }
        },
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Mono.Muted, unfocusedBorderColor = Mono.Line, cursorColor = Mono.Text,
            focusedContainerColor = Mono.Surface, unfocusedContainerColor = Mono.Surface,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics { contentDescription = "Search Shelf" },
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
    /** The finger's position in root coordinates when the drag starts. */
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier,
    state: ShelfCache.State = ShelfCache.State.LOCAL,
    dragDx: Float = 0f,
    /** 0.5.4: the dragged row is over a folder chip (it shrinks towards it). */
    overChip: Boolean = false,
    progress: Float? = null,
    onMove: () -> Unit = {},
    reorder: Boolean = true,
    now: Long = System.currentTimeMillis(),
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    divider: Boolean = true,
    onRename: () -> Unit = {},
) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val ctx = LocalContext.current
    // The gesture outlives recompositions (it is keyed on the row), so it calls the latest callbacks.
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentDragEnd by rememberUpdatedState(onDragEnd)
    // Long-press without moving opens the menu; long-press and drag reorders (or drops onto a chip).
    var menu by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val shrink by animateFloatAsState(if (overChip) 1f else 0f, Motion.spring(reduce), label = "shrink")
    var travelled by remember { mutableFloatStateOf(0f) }
    var held by remember { mutableStateOf(false) }
    // Spring in when the row arrives (sync / add).
    val appear = remember { Animatable(if (isNew && !reduce) 0.86f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)) }
    // Lift while dragging: a little larger, raised, on a soft card.
    // Read through clamp01(): the spring overshoots below 0 on the way down, and a negative corner
    // radius threw "Corner size in Px can't be negative" the moment a hold was released (0.5.3 crash).
    val liftAnim by animateFloatAsState(if (dragging) 1f else 0f, Motion.spring(reduce, stiffness = Spring.StiffnessMedium), label = "lift")
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v != SwipeToDismissBoxValue.Settled) onRemove()
            v != SwipeToDismissBoxValue.Settled
        },
        positionalThreshold = { it * 0.4f },
    )
    val missing = state == ShelfCache.State.NOT_DOWNLOADED
    val (title, detail) = ShelfMeta.lines(item, ::formatBytes, missing)
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        gesturesEnabled = !dragging,
        modifier = modifier.onGloballyPositioned { origin = it.positionInRoot() }.graphicsLayer {
            translationY = dragDy
            translationX = dragDx
            val s = appear.value * (1f + 0.03f * liftAnim.clamp01()) * (1f - 0.38f * shrink.clamp01())
            scaleX = s
            scaleY = s
            shadowElevation = 24f * liftAnim.clamp01()
            shape = RoundedCornerShape(18.dp)
            clip = false
        },
        backgroundContent = {
            val past = dismiss.progress > 0.4f && dismiss.targetValue != SwipeToDismissBoxValue.Settled
            val bg by animateColorAsState(if (past) Mono.Text else Mono.SurfaceHigh, Motion.fade(reduce), label = "bg")
            val iconScale by animateFloatAsState(if (past) 1.2f else 1f, Motion.bouncy(reduce), label = "icon")
            LaunchedEffect(past) { if (past) Haptics.tick(view) }
            Box(Modifier.fillMaxSize().background(bg).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
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
        val rowBg by animateColorAsState(if (dragging) Mono.SurfaceHigh else Mono.Bg, Motion.fade(reduce), label = "rowBg")
        Box(
            Modifier.fillMaxWidth()
                .sharedContainer("shelf-${item.id}", reduce)
                .clip(RoundedCornerShape((18 * liftAnim.clamp01()).dp)).background(rowBg)
                .pointerInput(item.id, reorder) {
                    // Every callback is guarded: a bad index in a reorder stops the drag, never the app.
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at ->
                            guarded(ctx, "shelf.dragStart") {
                                travelled = 0f
                                held = true
                                if (reorder) currentDragStart(origin + at) else Haptics.confirm(view)
                            }
                        },
                        onDragEnd = {
                            guarded(ctx, "shelf.dragEnd") {
                                if (reorder) currentDragEnd()
                                if (travelled < MENU_SLOP_PX) menu = true
                                held = false
                            }
                        },
                        // The row's click handler takes the finger-up of a still hold, so the drag
                        // detector sees it as a cancel: a hold that didn't move still opens the menu.
                        onDragCancel = {
                            guarded(ctx, "shelf.dragCancel") {
                                if (reorder) currentDragEnd()
                                if (held && travelled < MENU_SLOP_PX) menu = true
                                held = false
                            }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            guarded(ctx, "shelf.drag") {
                                travelled += kotlin.math.abs(amount.y) + kotlin.math.abs(amount.x)
                                if (reorder) currentDrag(amount)
                            }
                        },
                    )
                }
                .clickable(source, androidx.compose.material3.ripple(), role = Role.Button) {
                    // The release of a hold is not a tap (it used to open the item under the menu).
                    if (held) return@clickable
                    Haptics.tick(view)
                    a.open(item.id)
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = "$title, $detail, from ${ShelfMeta.source(item)}"
                },
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                ShelfLeading(item, 56.dp, state) { a.thumbnail(it) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(Modifier.padding(top = 16.dp, end = 20.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (item.kind == ShelfKinds.FILE) TransferMeta.middle(title, 28) else title,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                            )
                            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                // 0.5.4: a file without a copy here keeps its picture and details, plus a small cloud
                                // ("Available when connected" spelled out while the Mac is away) or a ring while it downloads.
                                if (item.file != null && state != ShelfCache.State.LOCAL) {
                                    OfflineGlyph(state, progress)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    detail, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = Mono.Muted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(When.short(item.created, now, zone), style = MaterialTheme.typography.bodyMedium, color = Mono.Muted)
                            Text(ShelfMeta.source(item), style = MaterialTheme.typography.bodyMedium, color = Mono.Muted,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    val line = if (divider && !dragging) Mono.Line.copy(alpha = 0.7f) else Mono.Bg.copy(alpha = 0f)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(line))
                }
            }
            ShelfMenu(menu, item, a, onDismiss = { menu = false }, onRename = onRename, onRemove = onRemove, onMove = onMove)
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
    onMove: () -> Unit = {},
) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        val primary = ShelfDetect.action(item.kind)
        if (primary != "Copy") {
            DropdownMenuItem(text = { Text(primary) }, leadingIcon = { Icon(Icons.Outlined.NorthEast, null) }, onClick = {
                onDismiss()
                a.primary(item)
            })
        }
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
        DropdownMenuItem(text = { Text("Move to…") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) }, onClick = {
            onDismiss()
            onMove()
        })
        if (item.file != null) {
            val kept = a.keptOffline(item)
            DropdownMenuItem(
                text = { Text("Keep offline") },
                leadingIcon = { Icon(Icons.Outlined.OfflinePin, null) },
                trailingIcon = { Icon(Icons.Outlined.Check, null, tint = if (kept) Mono.Text else Mono.Bg.copy(alpha = 0f)) },
                onClick = {
                    onDismiss()
                    a.keepOffline(item.id)
                },
            )
        }
        DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = {
            onDismiss()
            onRemove()
        })
    }
}

private const val MENU_SLOP_PX = 24f
private val NON_CARD_KEYS = setOf("hint", "search", "nomatch", "chips", "pageempty")

@Composable
private fun KindBadge(kind: String) {
    Text(
        shelfKindLabel(kind), style = MaterialTheme.typography.labelSmall, color = Mono.Text,
        modifier = Modifier.border(1.dp, Mono.Line, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun AddSheet(a: ShelfActions, onDone: () -> Unit, modifier: Modifier, folderId: String? = null) {
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
                a.addFileTo(folderId)
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
                        a.addTextTo(text, folderId)
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
                // 0.5.4 fix: a snippet titled from its own first line showed that line twice (title, then body).
                val text = ShelfCardText.detail(item)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Text(text.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
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
                            else -> if (text.preview.isNotEmpty()) {
                                Text(
                                    text.preview, style = MaterialTheme.typography.bodyLarge,
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
