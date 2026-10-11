@file:Suppress("MagicNumber")

package dev.benfranklin.tether.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.benfranklin.tether.core.features.ShelfOffline
import dev.benfranklin.tether.ui.Card
import dev.benfranklin.tether.ui.Divider
import dev.benfranklin.tether.ui.NavRow
import dev.benfranklin.tether.ui.SectionHeader
import dev.benfranklin.tether.ui.screenPadding
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.MonoCode

/**
 * Settings › Shelf (0.5.4): open the Shelf, and Offline files — how much space the copies use
 * ("312 MB of 1 GB"), the cap (500 MB, 1 GB, 2 GB, 5 GB or Off) and "Remove offline copies"
 * (never removes Shelf items). All of it is local to this phone.
 */
@Composable
fun ShelfSettingsScreen(
    used: Long,
    cap: Long,
    onCap: (Long) -> Unit,
    onRemoveCopies: () -> Unit,
    onOpenShelf: () -> Unit,
    onBack: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Scaffold(containerColor = Mono.Bg, topBar = { TopBar("Shelf", onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(pad)) {
            item {
                Card(Modifier.padding(top = 16.dp)) {
                    NavRow("Open Shelf", null, subtitle = "Links, Wi-Fi, addresses, notes and files, synced with your Mac", onClick = onOpenShelf)
                }
            }
            item { SectionHeader("Offline files") }
            item {
                Card {
                    Column {
                        Column(Modifier.padding(16.dp)) {
                            Text(ShelfOffline.usageLabel(used, cap), style = MaterialTheme.typography.bodyLarge, fontFamily = MonoCode)
                            Spacer(Modifier.height(10.dp))
                            // Space used, as a thin mono bar.
                            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Mono.Line)) {
                                val f = if (cap <= 0) 0f else (used.toFloat() / cap).coerceIn(0f, 1f)
                                Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Mono.Text))
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Files up to 100 MB are kept on this phone so they open while your Mac is away. " +
                                    "Over the limit, the ones opened least recently go first; items you keep offline always stay.",
                                style = MaterialTheme.typography.bodySmall, color = Mono.Muted,
                            )
                        }
                        Divider()
                        ShelfOffline.CAP_OPTIONS.forEachIndexed { i, c ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .selectable(selected = c == cap, role = Role.RadioButton) { onCap(c) }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(ShelfOffline.capLabel(c), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                if (c == cap) Icon(Icons.Outlined.Check, contentDescription = null, tint = Mono.Text)
                            }
                            if (i < ShelfOffline.CAP_OPTIONS.lastIndex) Divider()
                        }
                    }
                }
            }
            item {
                Card(Modifier.padding(top = 16.dp)) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { confirm = true }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Remove offline copies", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                }
            }
            item {
                Text(
                    "Removing copies frees space only. Your Shelf items stay, and files come back from your Mac when you open them.",
                    style = MaterialTheme.typography.bodySmall, color = Mono.Muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(1.dp))
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Mono.SurfaceHigh, titleContentColor = Mono.Text, textContentColor = Mono.Muted,
            title = { Text("Remove offline copies?") },
            text = { Text("Frees the space on this phone. Shelf items aren't removed; files added on this phone stay.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onRemoveCopies()
                }) { Text("Remove", color = Mono.Text) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = Mono.Muted) } },
        )
    }
}
