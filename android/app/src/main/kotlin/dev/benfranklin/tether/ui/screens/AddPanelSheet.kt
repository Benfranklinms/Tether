package dev.benfranklin.tether.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.benfranklin.tether.core.features.HomeLayout
import dev.benfranklin.tether.core.features.Panel
import dev.benfranklin.tether.core.features.PanelKind
import dev.benfranklin.tether.core.features.PanelSize
import dev.benfranklin.tether.ui.pressScale
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.Motion
import kotlinx.coroutines.launch

/** The panel picker: every panel not on Home, with a live miniature, its sizes and "+". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPanelSheet(
    layout: HomeLayout,
    m: HomeModel,
    data: HomeData,
    onAdd: (PanelKind, PanelSize) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, containerColor = Mono.Surface, contentColor = Mono.Text,
        scrimColor = Mono.Bg.copy(alpha = 0.6f), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        AddPanelContent(layout, m, data, onAdd = onAdd, onClose = {
            scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
        })
    }
}

/** The sheet's content (also rendered on its own in screenshot tests). */
@Composable
fun AddPanelContent(layout: HomeLayout, m: HomeModel, data: HomeData, onAdd: (PanelKind, PanelSize) -> Unit, onClose: () -> Unit) {
    val chosen = remember { mutableStateMapOf<PanelKind, PanelSize>() }
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Add panel", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            val close = remember { MutableInteractionSource() }
            Box(
                Modifier.pressScale(close).size(40.dp).clip(CircleShape).background(Mono.SurfaceHigh)
                    .clickable(close, null, role = Role.Button, onClick = onClose).semantics { contentDescription = "Close" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = Mono.Text, modifier = Modifier.size(20.dp)) }
        }
        val kinds = layout.available()
        if (kinds.isEmpty()) {
            Text(
                "Every panel is on Home", style = MaterialTheme.typography.bodyMedium, color = Mono.Muted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            items(kinds, key = { it.id }) { kind ->
                val size = chosen[kind] ?: kind.defaultSize
                PickerRow(kind, size, m, data, onSize = { chosen[kind] = it }, onAdd = { onAdd(kind, size) },
                    modifier = Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun PickerRow(
    kind: PanelKind, size: PanelSize, m: HomeModel, data: HomeData, onSize: (PanelSize) -> Unit, onAdd: () -> Unit, modifier: Modifier,
) {
    val reduce = LocalReduceMotion.current
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(size, label = "mini", transitionSpec = {
            if (reduce) fadeIn(Motion.fade(true)) togetherWith fadeOut(Motion.fade(true))
            else (fadeIn() + scaleIn(Motion.spring(false), 0.9f)) togetherWith (fadeOut() + scaleOut(Motion.spring(false), 0.9f))
        }) { s -> Miniature(Panel(kind, s), m, data) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(kind.title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            if (kind.sizes.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    kind.sizes.forEach { s -> SizeChip(s, s == size) { onSize(s) } }
                }
            }
        }
        val add = remember { MutableInteractionSource() }
        Box(
            Modifier.pressScale(add).size(44.dp).clip(CircleShape).background(Mono.SurfaceHigh).border(1.dp, Mono.Line, CircleShape)
                .clickable(add, null, role = Role.Button, onClick = onAdd)
                .semantics { contentDescription = "Add ${kind.title}" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Add, contentDescription = null, tint = Mono.Text) }
    }
}

/** The real panel, laid out at its true size and scaled down. */
@Composable
private fun Miniature(p: Panel, m: HomeModel, data: HomeData) {
    val w = if (p.size == PanelSize.SMALL) SMALL_W else WIDE_W
    val h = panelHeight(p.size)
    val boxW = 112.dp
    val boxH = 76.dp
    val scale = minOf(boxW / w, boxH / h)
    Box(Modifier.size(boxW, boxH), contentAlignment = Alignment.Center) {
        Box(
            Modifier.requiredSize(w, h).graphicsLayer {
                scaleX = scale
                scaleY = scale
            }.clip(RoundedCornerShape(PANEL_RADIUS)).background(Mono.Bg).border(1.dp, Mono.Line, RoundedCornerShape(PANEL_RADIUS)),
        ) {
            PanelContent(p, m, data, object : HomeActions {}, editing = true)
        }
    }
}

@Composable
private fun SizeChip(size: PanelSize, on: Boolean, onClick: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val bg by animateColorAsState(if (on) Mono.Text else Mono.Surface, Motion.fade(reduce), label = "chip")
    val fg by animateColorAsState(if (on) Mono.Bg else Mono.Muted, Motion.fade(reduce), label = "chipFg")
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.pressScale(source).size(44.dp, 32.dp).clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, Mono.Line,
            RoundedCornerShape(10.dp))
            .clickable(source, null, role = Role.RadioButton, onClick = onClick)
            .semantics {
                selected = on
                contentDescription = size.id.replaceFirstChar { it.uppercase() } + " size"
            },
        contentAlignment = Alignment.Center,
    ) {
        val glyph = when (size) {
            PanelSize.SMALL -> Modifier.size(9.dp).clip(RoundedCornerShape(2.dp))
            PanelSize.WIDE -> Modifier.size(18.dp, 8.dp).clip(RoundedCornerShape(3.dp))
            PanelSize.LARGE -> Modifier.size(16.dp, 14.dp).clip(RoundedCornerShape(3.dp))
        }
        Box(glyph.background(fg))
    }
}

private val SMALL_W = 178.dp
private val WIDE_W = 368.dp
