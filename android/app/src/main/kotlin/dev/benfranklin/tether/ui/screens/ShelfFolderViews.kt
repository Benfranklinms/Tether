@file:Suppress("TooManyFunctions", "MagicNumber", "LongMethod", "LongParameterList")

package dev.benfranklin.tether.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.tether.core.features.ShelfCache
import dev.benfranklin.tether.core.features.ShelfFolders
import dev.benfranklin.tether.core.features.ShelfMeta
import dev.benfranklin.tether.core.features.ShelfOffline
import dev.benfranklin.tether.core.proto.ShelfFolder
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.log.guarded
import dev.benfranklin.tether.ui.Haptics
import dev.benfranklin.tether.ui.clamp01
import dev.benfranklin.tether.ui.pressScale
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.MonoCode
import dev.benfranklin.tether.ui.theme.Motion

/*
 * Shelf folders (0.5.4, approved design "A · chips"): a chip row under the search bar ("+" first,
 * then each folder with its count, fading out at the right edge), a pushed page per folder, a
 * "Move to" sheet, a "New folder" sheet, and a long-press menu on chips (Rename, Keep offline,
 * Delete). Every chip is always composed; hover (a row dragged onto a chip) only changes colours.
 */

/** Height of a chip and of the "+" circle. */
private val CHIP_H = 40.dp

/** The chip row. [hover] is the folder a dragged row is over; [bounds] receives each chip's rect in root coordinates. */
@Composable
fun FolderChipRow(
    folders: List<ShelfFolder>,
    counts: Map<String, Int>,
    hover: String?,
    bounds: MutableMap<String, Rect>,
    onNew: () -> Unit,
    onOpen: (ShelfFolder) -> Unit,
    onMenu: (ShelfFolder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Box(
        modifier.fillMaxWidth()
            // The row fades out at the right edge so a cut-off chip reads as "more this way".
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val w = 48.dp.toPx().coerceAtMost(size.width)
                drawRect(
                    Brush.horizontalGradient(
                        listOf(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Transparent),
                        startX = size.width - w, endX = size.width,
                    ),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            },
    ) {
        LazyRow(
            contentPadding = PaddingValues(start = 16.dp, end = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        ) {
            item(key = "new") {
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier.pressScale(source).size(CHIP_H).clip(CircleShape).background(Mono.Surface)
                        .border(1.dp, Mono.Line, CircleShape)
                        .clickable(source, null, role = Role.Button) {
                            Haptics.tick(view)
                            onNew()
                        }
                        .semantics { contentDescription = "New folder" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Add, contentDescription = null, tint = Mono.Text, modifier = Modifier.size(20.dp)) }
            }
            items(folders, key = { it.id }) { f ->
                FolderChip(f, counts[f.id] ?: 0, hover == f.id, bounds, onOpen = { onOpen(f) }, onMenu = { onMenu(f) })
            }
        }
    }
}

@Composable
private fun FolderChip(
    f: ShelfFolder, count: Int, hovered: Boolean, bounds: MutableMap<String, Rect>, onOpen: () -> Unit, onMenu: () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val ctx = LocalContext.current
    val bg by animateColorAsState(if (hovered) Mono.Accent else Mono.Surface, Motion.fade(reduce), label = "chipBg")
    val fg by animateColorAsState(if (hovered) Mono.Bg else Mono.Text, Motion.fade(reduce), label = "chipFg")
    val grow by animateFloatAsState(if (hovered) 1f else 0f, Motion.spring(reduce), label = "chipGrow")
    DisposableEffect(f.id) { onDispose { bounds.remove(f.id) } }
    Row(
        Modifier
            .graphicsLayer {
                val s = 1f + 0.06f * grow.clamp01()
                scaleX = s
                scaleY = s
            }
            .height(CHIP_H).clip(RoundedCornerShape(50)).background(bg)
            .border(1.dp, if (hovered) Mono.Accent else Mono.Line, RoundedCornerShape(50))
            .onGloballyPositioned { bounds[f.id] = it.boundsInRoot() }
            .pointerInput(f.id) {
                detectTapGestures(
                    onTap = { guarded(ctx, "shelf.chipTap") { onOpen() } },
                    onLongPress = {
                        guarded(ctx, "shelf.chipHold") {
                            Haptics.confirm(view)
                            onMenu()
                        }
                    },
                )
            }
            .semantics(mergeDescendants = true) { contentDescription = "Folder ${f.name}, $count items" }
            .padding(start = 14.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = if (hovered) Mono.Bg else Mono.Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            f.name, color = fg, maxLines = 1,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(Modifier.width(8.dp))
        Text("$count", color = if (hovered) Mono.Bg.copy(alpha = 0.7f) else Mono.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Long-press menu on a chip (or the "…" on a folder page): Rename, Keep offline, Delete folder. */
@Composable
fun FolderMenu(
    folder: ShelfFolder?, kept: Boolean, onDismiss: () -> Unit, onRename: () -> Unit, onKeep: () -> Unit, onDelete: () -> Unit,
) {
    DropdownMenu(expanded = folder != null, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = {
            onDismiss()
            onRename()
        })
        DropdownMenuItem(
            text = { Text("Keep offline") },
            leadingIcon = { Icon(Icons.Outlined.OfflinePin, null) },
            trailingIcon = { Icon(Icons.Outlined.Check, null, tint = if (kept) Mono.Text else Mono.Bg.copy(alpha = 0f)) },
            onClick = {
                onDismiss()
                onKeep()
            },
        )
        DropdownMenuItem(text = { Text("Delete folder") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = {
            onDismiss()
            onDelete()
        })
    }
}

/** Deleting a folder never deletes items: confirm, and say where they go. */
@Composable
fun DeleteFolderDialog(folder: ShelfFolder, count: Int, onDone: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDone(false) },
        containerColor = Mono.SurfaceHigh, titleContentColor = Mono.Text, textContentColor = Mono.Muted,
        title = { Text("Delete “${folder.name}”?") },
        text = {
            Text(
                when (count) {
                    0 -> "The folder is empty. This removes it on both devices."
                    1 -> "Its item moves back to your Shelf. Nothing is removed."
                    else -> "Its $count items move back to your Shelf. Nothing is removed."
                },
            )
        },
        confirmButton = { TextButton(onClick = { onDone(true) }) { Text("Delete folder", color = Mono.Text) } },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text("Cancel", color = Mono.Muted) } },
    )
}

/** A folder page's top: "‹ Shelf", + and …, the folder tile, then the name and its count. */
@Composable
fun FolderHeader(folder: ShelfFolder, count: Int, onBack: () -> Unit, onAdd: () -> Unit, onMore: () -> Unit, menu: @Composable () -> Unit) {
    val view = LocalView.current
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onBack)
                    .semantics(mergeDescendants = true) { contentDescription = "Back to Shelf" }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, contentDescription = null, tint = Mono.Text)
                Spacer(Modifier.width(6.dp))
                Text("Shelf", style = MaterialTheme.typography.titleMedium, color = Mono.Muted)
            }
            Spacer(Modifier.weight(1f))
            CircleButton(Icons.Outlined.Add, "Add to ${folder.name}") {
                Haptics.tick(view)
                onAdd()
            }
            Spacer(Modifier.width(10.dp))
            Box {
                CircleButton(Icons.Filled.MoreHoriz, "Folder options") {
                    Haptics.tick(view)
                    onMore()
                }
                menu()
            }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)) {
            FolderTile(48.dp)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(folder.name, style = MaterialTheme.typography.headlineMedium, color = Mono.Text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(10.dp))
                Text("$count", style = MaterialTheme.typography.titleMedium, color = Mono.Muted, fontFamily = MonoCode,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
}

@Composable
private fun CircleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.pressScale(source).size(44.dp).clip(CircleShape).background(Mono.SurfaceHigh)
            .clickable(source, null, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = Mono.Text) }
}

/** The rounded tile with a solid folder in it (folder page, New folder sheet, Move to rows). */
@Composable
fun FolderTile(size: Dp, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Folder) {
    val r = RoundedCornerShape(size * 0.28f)
    Box(Modifier.size(size).clip(r).background(Mono.SurfaceHigh).border(1.dp, Mono.Line, r), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Mono.Text, modifier = Modifier.size(size * 0.46f))
    }
}

/** "Move to…": the item, then Loose, every folder with its count, and New folder. The current place has a check. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveSheet(
    item: ShelfItem,
    current: String?,
    folders: List<ShelfFolder>,
    counts: Map<String, Int>,
    state: ShelfCache.State,
    thumbnail: suspend (ShelfItem) -> androidx.compose.ui.graphics.ImageBitmap?,
    onPick: (String?) -> Unit,
    onNewFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Mono.Surface, contentColor = Mono.Text) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            val (title, detail) = ShelfMeta.lines(item, ::formatBytes)
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ShelfLeading(item, 48.dp, state, thumbnail)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        detail, style = MaterialTheme.typography.bodyMedium, color = Mono.Muted, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth().height(1.dp).background(Mono.Line))
            Text(
                "Move to", style = MaterialTheme.typography.bodyLarge, color = Mono.Muted,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
            )
            MoveRow(Icons.Outlined.Inbox, "Loose", null, current == null, divider = true) { onPick(null) }
            folders.forEach { f ->
                MoveRow(Icons.Filled.Folder, f.name, counts[f.id] ?: 0, current == f.id, divider = true) { onPick(f.id) }
            }
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onNewFolder).padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).border(1.dp, Mono.Line, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = Mono.Text)
                }
                Spacer(Modifier.width(16.dp))
                Text("New folder", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), color = Mono.Muted)
            }
        }
    }
}

@Composable
private fun MoveRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    count: Int?,
    selected: Boolean,
    divider: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = if (selected) "$name, current" else name }
            .padding(start = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderTile(44.dp, icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.height(64.dp).padding(end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (selected) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = Mono.Text)
                } else if (count != null) {
                    Text("$count", style = MaterialTheme.typography.bodyLarge, color = Mono.Muted)
                }
            }
            if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Mono.Line.copy(alpha = 0.7f)))
        }
    }
}

/**
 * New folder (or Rename folder): Cancel · title · Create, the folder tile, and the name field with
 * the keyboard up. Create is disabled until the name has something in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderNameSheet(title: String, action: String, initial: String, onDone: (String?) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val ok = ShelfFolders.cleanName(text.text) != null
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ModalBottomSheet(onDismissRequest = { onDone(null) }, sheetState = sheet, containerColor = Mono.Surface, contentColor = Mono.Text) {
        Column(Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Cancel", style = MaterialTheme.typography.bodyLarge, color = Mono.Muted,
                    modifier = Modifier.weight(1f).clickable(role = Role.Button) { onDone(null) }.padding(vertical = 8.dp),
                )
                Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    val source = remember { MutableInteractionSource() }
                    Text(
                        action, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = Mono.Bg,
                        modifier = Modifier.pressScale(source).clip(RoundedCornerShape(50))
                            .background(if (ok) Mono.Accent else Mono.Muted.copy(alpha = 0.5f))
                            .clickable(source, null, enabled = ok, role = Role.Button) { onDone(text.text) }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { FolderTile(64.dp) }
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = text, onValueChange = { if (it.text.length <= ShelfFolders.NAME_MAX + 8) text = it }, singleLine = true,
                placeholder = { Text("Folder name", color = Mono.Muted) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Mono.Text, unfocusedBorderColor = Mono.Line, cursorColor = Mono.Text,
                    focusedContainerColor = Mono.Bg, unfocusedContainerColor = Mono.Bg,
                ),
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onDone(text.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Folder name" },
            )
        }
    }
}

/**
 * The small mono state glyph after a file row's detail: a cloud when the copy isn't on this phone
 * and the Mac is away (labelled "Available when connected"), a ring while it downloads.
 * Always the same size, so a row never jumps.
 */
@Composable
fun OfflineGlyph(state: ShelfCache.State, progress: Float?) {
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        when (state) {
            ShelfCache.State.FETCHING -> if (progress != null && progress > 0f) {
                CircularProgressIndicator(
                    progress = { progress.clamp01() }, color = Mono.Muted, strokeWidth = 1.5.dp, trackColor = Mono.Line,
                    modifier = Modifier.size(12.dp))
            } else {
                CircularProgressIndicator(color = Mono.Muted, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
            }
            ShelfCache.State.NOT_DOWNLOADED ->
                Icon(Icons.Outlined.CloudQueue, contentDescription = ShelfOffline.AWAY, tint = Mono.Muted, modifier = Modifier.size(14.dp))
            ShelfCache.State.LOCAL, ShelfCache.State.REMOTE -> Unit
        }
    }
}
