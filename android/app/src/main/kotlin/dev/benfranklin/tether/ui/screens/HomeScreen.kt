package dev.benfranklin.tether.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SouthWest
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.core.transfer.Transport
import dev.benfranklin.tether.ui.Chip
import dev.benfranklin.tether.ui.SectionHeader
import dev.benfranklin.tether.ui.TetherMark
import dev.benfranklin.tether.ui.theme.Mono
import java.util.Locale

/** Everything the home screen shows; built from runtime state (or fakes in screenshot tests). */
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
)

@Composable
fun HomeScreen(
    m: HomeModel,
    onSendClipboard: () -> Unit,
    onSendFile: () -> Unit,
    onSettings: () -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onNoticeShown: () -> Unit,
    onFixMirroring: () -> Unit,
) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(m.notice) {
        m.notice?.let {
            snack.showSnackbar(it)
            onNoticeShown()
        }
    }
    Scaffold(containerColor = Mono.Bg, snackbarHost = { SnackbarHost(snack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 32.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Tether", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                }
            }
            item { StatusCard(m) }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ActionTile("Send clipboard", Icons.Outlined.ContentPaste, m.connected, onSendClipboard, Modifier.weight(1f))
                    ActionTile("Send file", Icons.Outlined.UploadFile, true, onSendFile, Modifier.weight(1f))
                }
            }
            if (m.mirroringOff) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Mono.Line, RoundedCornerShape(12.dp)).padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Notification mirroring needs access", style = MaterialTheme.typography.bodyMedium,
                            color = Mono.Muted, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onFixMirroring) { Text("Allow") }
                    }
                }
            }
            item { SectionHeader("Transfers") }
            if (m.transfers.isEmpty()) {
                item {
                    Text(
                        "Files you send or receive show up here.\nShare anything to Tether, or tap Send file.",
                        style = MaterialTheme.typography.bodyMedium, color = Mono.Muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(m.transfers, key = { it.id }) { t ->
                TransferRow(t, onCancel, onRetry, onRemove, Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun StatusCard(m: HomeModel) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(20.dp))
            .background(Mono.Surface).border(1.dp, Mono.Line, RoundedCornerShape(20.dp)).padding(20.dp)
            .animateContentSize()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TetherMark(56.dp, linked = m.connected, pulse = m.searching)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                AnimatedContent(m.title, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "title") { t ->
                    Text(t, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(m.subtitle, style = MaterialTheme.typography.bodyMedium, color = Mono.Muted)
            }
        }
        if (m.connected) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Bluetooth", highlighted = !m.wifi)
                if (m.wifi) Chip("Wi-Fi", highlighted = true)
            }
        }
        AnimatedVisibility(m.error != null) {
            Text(
                m.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Mono.Text,
                modifier = Modifier.padding(top = 14.dp).border(1.dp, Mono.Line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ActionTile(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Mono.Surface).border(1.dp, Mono.Line, RoundedCornerShape(16.dp))
            .heightIn(min = 88.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) Mono.Text else Mono.Muted)
        Spacer(Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) Mono.Text else Mono.Muted)
    }
}

@Composable
private fun TransferRow(
    t: TransferRecord, onCancel: (String) -> Unit, onRetry: (String) -> Unit, onRemove: (String) -> Unit, modifier: Modifier,
) {
    val progress by animateFloatAsState(t.progress, label = "progress")
    val desc = transferDescription(t)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).semantics(mergeDescendants = true) {
            contentDescription = desc
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Mono.Surface), contentAlignment = Alignment.Center) {
            Icon(
                if (t.direction == Direction.OUT) Icons.Outlined.NorthEast else Icons.Outlined.SouthWest,
                contentDescription = null, tint = if (t.status == TransferStatus.DONE) Mono.Sage else Mono.Muted,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    statusLine(t), style = MaterialTheme.typography.bodySmall, color = Mono.Muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                t.via?.let { Chip(if (it == Transport.WIFI) "Wi-Fi" else "BT", highlighted = it == Transport.WIFI && !t.status.isFinal) }
            }
            if (!t.status.isFinal) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = Mono.Accent, trackColor = Mono.Line, drawStopIndicator = {},
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        when {
            !t.status.isFinal -> IconButton(onClick = { onCancel(t.id) }) {
                Icon(Icons.Outlined.Close, contentDescription = "Cancel ${t.name}", tint = Mono.Muted)
            }
            t.status == TransferStatus.FAILED && t.direction == Direction.OUT -> IconButton(onClick = { onRetry(t.id) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Retry ${t.name}", tint = Mono.Text)
            }
            else -> IconButton(onClick = { onRemove(t.id) }) {
                Icon(Icons.Outlined.Delete, contentDescription = "Remove ${t.name} from the list", tint = Mono.Muted)
            }
        }
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

private fun transferDescription(t: TransferRecord): String {
    val dir = if (t.direction == Direction.OUT) "Sending" else "Receiving"
    val via = when (t.via) {
        Transport.WIFI -> " over Wi-Fi"
        Transport.BLE -> " over Bluetooth"
        null -> ""
    }
    return "$dir ${t.name}$via. ${statusLine(t)}"
}
