package dev.benfranklin.tether.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.tether.core.features.FileKinds
import dev.benfranklin.tether.core.features.ShelfMeta
import dev.benfranklin.tether.core.proto.ShelfFolder
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.proto.ShelfKinds
import dev.benfranklin.tether.ui.Haptics
import dev.benfranklin.tether.ui.pressScale
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.MonoCode
import dev.benfranklin.tether.ui.theme.Motion

// 0.5.5 Shelf on Home (approved v2 mockup): header with count and "See all ›", folder chips, and a
// horizontal carousel (Add tile first, then rich 124×150 cards) that fades out at the right edge.

internal val SHELF_CARD_W = 124.dp
internal val SHELF_CARD_H = 150.dp
private val SHELF_PREVIEW_H = 92.dp
private val ADD_TILE_W = 64.dp

/** Most cards the Home carousel shows; "See all" opens the rest. */
internal const val SHELF_CAROUSEL_MAX = 12

@Composable
internal fun ShelfSection(@Suppress("UNUSED_PARAMETER") m: HomeModel, data: HomeData, a: HomeActions) {
    val count = data.shelf.size
    SectionTitle(
        "Shelf", count = count.takeIf { it > 0 }, action = "See all", actionDesc = "Open Shelf", chevron = true,
        onAction = a::openShelf,
    )
    if (data.shelf.isEmpty()) {
        ShelfEmptyCard(a)
        return
    }
    val counts = remember(data.shelf) { data.shelf.groupingBy { it.folderId }.eachCount() }
    if (data.folders.isNotEmpty()) {
        FadeRight(Modifier.bleed(16.dp)) {
            LazyRow(
                contentPadding = PaddingValues(start = 20.dp, end = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp).testTag("home-folders"),
            ) {
                items(data.folders, key = { it.id }) { f -> HomeFolderChip(f, counts[f.id] ?: 0) { a.openShelfFolder(f.id) } }
            }
        }
    }
    val shown = remember(data.shelf) { data.shelf.take(SHELF_CAROUSEL_MAX) }
    FadeRight(Modifier.bleed(16.dp)) {
        LazyRow(
            contentPadding = PaddingValues(start = 16.dp, end = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(top = if (data.folders.isEmpty()) 4.dp else 0.dp).testTag("home-shelf"),
        ) {
            item(key = "add") { ShelfAddTile(a) }
            items(shown, key = { it.id }) { item -> ShelfCard(item, a) }
        }
    }
}

/** Content fades out over the last 40 dp on the right, so a cut-off card reads as "more this way". */
@Composable
private fun FadeRight(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val w = 40.dp.toPx().coerceAtMost(size.width)
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) { content() }
}

@Composable
private fun HomeFolderChip(f: ShelfFolder, count: Int, onClick: () -> Unit) {
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.pressScale(source).height(30.dp).clip(RoundedCornerShape(50)).background(Mono.Surface2)
            .border(0.5.dp, Mono.Line2, RoundedCornerShape(50))
            .clickable(source, null, role = Role.Button) {
                Haptics.tick(view)
                onClick()
            }
            .semantics(mergeDescendants = true) { contentDescription = "Folder ${f.name}, $count items" }
            .padding(start = 11.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, contentDescription = null, tint = Mono.Muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(7.dp))
        Text(f.name, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
            maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text("$count", fontFamily = MonoCode, fontSize = 11.5.sp, color = Mono.Faint, maxLines = 1)
    }
}

@Composable
private fun ShelfAddTile(a: HomeActions) {
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier.pressScale(source).size(ADD_TILE_W, SHELF_CARD_H).clip(RoundedCornerShape(20.dp))
            .border(0.8.dp, Mono.Line, RoundedCornerShape(20.dp))
            .clickable(source, null, role = Role.Button) {
                Haptics.tick(view)
                a.addToShelf()
            }
            .semantics(mergeDescendants = true) { contentDescription = "Add to Shelf" }
            .testTag("home-shelf-add"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Mono.SurfaceHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = Mono.Text, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("Add", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = Mono.Muted)
    }
}

/** One Shelf item: a preview (photo, globe and domain, document, text) over its title and meta line. */
@Composable
private fun ShelfCard(item: ShelfItem, a: HomeActions) {
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    val (title, meta) = remember(item) { ShelfMeta.lines(item, ::formatBytes) }
    Column(
        Modifier.pressScale(source).size(SHELF_CARD_W, SHELF_CARD_H).clip(RoundedCornerShape(20.dp)).background(Mono.Surface)
            .border(0.5.dp, Mono.Line2, RoundedCornerShape(20.dp))
            .clickable(source, null, role = Role.Button) {
                Haptics.tick(view)
                a.openShelfItem(item.id)
            }
            .semantics(mergeDescendants = true) { contentDescription = "$title, $meta" }
            .testTag("home-shelf-card"),
    ) {
        ShelfPreview(item, meta, a)
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = Mono.Faint, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ShelfPreview(item: ShelfItem, meta: String, a: HomeActions) {
    val f = item.file
    if (f != null && FileKinds.isImage(f.mime, f.name)) {
        var bmp by remember(item.id, f.sha256) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(item.id, f.sha256) { a.shelfThumb(item)?.let { bmp = it } }
        val reduce = LocalReduceMotion.current
        Box(Modifier.fillMaxWidth().height(SHELF_PREVIEW_H).background(Mono.Surface2), contentAlignment = Alignment.Center) {
            if (bmp == null) Icon(Icons.Outlined.Image, contentDescription = null, tint = Mono.Faint, modifier = Modifier.size(28.dp))
            AnimatedVisibility(bmp != null, enter = androidx.compose.animation.fadeIn(Motion.fade(reduce))) {
                bmp?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
        return
    }
    Box(
        Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp).fillMaxWidth().height(SHELF_PREVIEW_H - 12.dp)
            .clip(RoundedCornerShape(14.dp)).background(Mono.Surface2),
        contentAlignment = Alignment.Center,
    ) {
        when (item.kind) {
            ShelfKinds.FILE -> DocIcon(ShelfMeta.badge(f?.mime, f?.name ?: item.title), 44.dp)
            ShelfKinds.LINK -> GlyphWithCaption(Icons.Outlined.Language, meta)
            ShelfKinds.WIFI -> GlyphWithCaption(Icons.Outlined.Wifi, null)
            ShelfKinds.ADDRESS -> GlyphWithCaption(Icons.Outlined.Place, null)
            else -> TextPreview(item, mono = item.kind == ShelfKinds.SNIPPET)
        }
    }
}

@Composable
private fun GlyphWithCaption(icon: ImageVector, caption: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = Mono.Muted, modifier = Modifier.size(30.dp))
        if (!caption.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(caption, fontFamily = MonoCode, fontSize = 10.5.sp, color = Mono.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

/** The first lines of a snippet (mono) or note (sans). */
@Composable
private fun TextPreview(item: ShelfItem, mono: Boolean) {
    val lines = remember(item) {
        val body = item.body.ifBlank { item.title }
        body.lines().filter { it.isNotBlank() }.take(PREVIEW_LINES).joinToString("\n")
    }
    Text(
        lines, color = Mono.Muted, maxLines = PREVIEW_LINES, overflow = TextOverflow.Ellipsis,
        style = if (mono) MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 15.sp)
        else MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

private const val PREVIEW_LINES = 3

/** Empty Shelf: one card with Paste and Add file (approved v2 mockup), never a blank strip. */
@Composable
private fun ShelfEmptyCard(a: HomeActions) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(22.dp)).background(Mono.Surface)
            .border(0.5.dp, Mono.Line2, RoundedCornerShape(22.dp)).padding(16.dp).testTag("home-shelf-empty"),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Mono.SurfaceHigh)
                .border(0.5.dp, Mono.Line, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Inbox, contentDescription = null, tint = Mono.Text, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Nothing on your Shelf", style = MaterialTheme.typography.titleMedium)
            Text("Files, links and text on both devices", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                color = Mono.Faint)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EmptyPill("Paste", Icons.Outlined.ContentPaste, "home-shelf-paste", a::pasteToShelf)
                EmptyPill("Add file", Icons.Outlined.UploadFile, "home-shelf-addfile", a::addFileToShelf)
            }
        }
    }
}

@Composable
private fun EmptyPill(label: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.pressScale(source).heightIn(min = 34.dp).clip(RoundedCornerShape(50)).background(Mono.SurfaceHigh)
            .border(0.5.dp, Mono.Line, RoundedCornerShape(50))
            .clickable(source, null, role = Role.Button) {
                Haptics.tick(view)
                onClick()
            }
            .testTag(tag)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Mono.Text, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
