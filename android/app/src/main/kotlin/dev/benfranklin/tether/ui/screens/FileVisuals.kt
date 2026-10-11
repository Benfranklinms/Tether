package dev.benfranklin.tether.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.ShortText
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.tether.core.features.ShelfCache
import dev.benfranklin.tether.core.features.ShelfMeta
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.proto.ShelfKinds
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.MonoCode
import dev.benfranklin.tether.ui.theme.Motion

/** A paper document with a folded corner and its type on a black tab ("PDF"), as in the mockups. */
@Composable
fun DocIcon(badge: String, size: Dp, modifier: Modifier = Modifier) {
    val outline = Mono.Line
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width * 0.74f
            val h = this.size.height * 0.92f
            val left = (this.size.width - w) / 2
            val top = (this.size.height - h) / 2
            val fold = w * 0.3f
            val r = w * 0.08f
            val paper = Path().apply {
                moveTo(left + r, top)
                lineTo(left + w - fold, top)
                lineTo(left + w, top + fold)
                lineTo(left + w, top + h - r)
                quadraticTo(left + w, top + h, left + w - r, top + h)
                lineTo(left + r, top + h)
                quadraticTo(left, top + h, left, top + h - r)
                lineTo(left, top + r)
                quadraticTo(left, top, left + r, top)
                close()
            }
            // Paper stays paper in every theme (an object, like album art); the outline keeps it visible on light surfaces.
            drawPath(paper, PAPER)
            drawPath(paper, outline, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.03f))
            val corner = Path().apply {
                moveTo(left + w - fold, top)
                lineTo(left + w - fold, top + fold - r)
                quadraticTo(left + w - fold, top + fold, left + w - fold + r, top + fold)
                lineTo(left + w, top + fold)
                close()
            }
            drawPath(corner, Color(0xFFB8B8BC))
            val line = Color(0xFF9A9AA0)
            val lw = h * 0.035f
            for (i in 0..2) {
                val y = top + h * (0.34f + i * 0.11f)
                drawRoundRect(line, Offset(left + w * 0.18f, y), Size(w * (if (i == 2) 0.42f else 0.64f), lw), CornerRadius(lw))
            }
        }
        Text(
            badge,
            style = TextStyle(fontFamily = MonoCode, fontWeight = FontWeight.Medium, fontSize = (size.value * 0.17f).sp,
                color = PAPER),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = size * 0.12f)
                .clip(RoundedCornerShape(size * 0.06f)).background(INK).padding(horizontal = size * 0.06f),
            maxLines = 1,
        )
    }
}

private val PAPER = Color(0xFFF2F2F2)
private val INK = Color(0xFF111112)

/** The icon for a non-file Shelf item. */
fun shelfKindIcon(kind: String): ImageVector = when (kind) {
    ShelfKinds.LINK -> Icons.Outlined.Language
    ShelfKinds.WIFI -> Icons.Outlined.Wifi
    ShelfKinds.ADDRESS -> Icons.Outlined.Place
    ShelfKinds.SNIPPET -> Icons.AutoMirrored.Outlined.Notes
    else -> Icons.Outlined.ShortText
}

/**
 * The leading visual of a Shelf row: a real thumbnail for an image with a local copy, a paper
 * document with its type for other files, or the kind's icon on a soft square.
 */
@Composable
fun ShelfLeading(
    item: ShelfItem,
    size: Dp,
    state: ShelfCache.State = ShelfCache.State.LOCAL,
    thumbnail: suspend (ShelfItem) -> ImageBitmap? = { null },
) {
    val reduce = LocalReduceMotion.current
    val f = item.file
    val radius = RoundedCornerShape(size * 0.24f)
    if (f != null && ShelfMeta.hasPicture(item)) {
        var bmp by remember(item.id, f.sha256) { mutableStateOf<ImageBitmap?>(null) }
        // 0.5.4: an evicted copy keeps its (saved) thumbnail, so ask whatever the state.
        LaunchedEffect(item.id, f.sha256, state) { thumbnail(item)?.let { bmp = it } }
        Box(Modifier.size(size).clip(radius).background(Mono.SurfaceHigh).border(1.dp, Mono.Line, radius)) {
            AnimatedContent(
                bmp, label = "thumb",
                transitionSpec = {
                    if (reduce) fadeIn(Motion.fade(true)) togetherWith fadeOut(Motion.fade(true))
                    else (fadeIn(Motion.fade(false)) + scaleIn(Motion.spring(false), 0.92f)) togetherWith fadeOut(Motion.fade(false))
                },
            ) { b ->
                if (b != null) {
                    Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    DocIcon(ShelfMeta.badge(f.mime, f.name), size)
                }
            }
        }
    } else if (f != null) {
        DocIcon(ShelfMeta.badge(f.mime, f.name), size)
    } else {
        Box(Modifier.size(size).clip(radius).background(Mono.SurfaceHigh), contentAlignment = Alignment.Center) {
            Icon(shelfKindIcon(item.kind), contentDescription = null, tint = Mono.Text, modifier = Modifier.size(size * 0.46f))
        }
    }
}
