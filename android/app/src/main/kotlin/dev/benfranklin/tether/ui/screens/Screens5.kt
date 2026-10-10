@file:Suppress("MaxLineLength") // Compose rows with their copy on one line

package dev.benfranklin.tether.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.benfranklin.tether.data.AppSettings
import dev.benfranklin.tether.ui.Card
import dev.benfranklin.tether.ui.Divider
import dev.benfranklin.tether.ui.NavRow
import dev.benfranklin.tether.ui.SectionHeader
import dev.benfranklin.tether.ui.ToggleRow
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.MonoCode

/** One checklist row as the UI sees it (built from [dev.benfranklin.tether.v5.Setup.Item]). */
data class SetupRow(
    val id: String,
    val label: String,
    val why: String,
    val ok: Boolean,
    val required: Boolean,
    /** Shell commands to show (adb rows); empty when a Fix button does it. */
    val commands: List<String> = emptyList(),
    val note: String? = null,
)

/** Pure: "12 of 16 ready · 1 required missing". Unit-tested. */
fun setupSummary(rows: List<SetupRow>): String {
    val ok = rows.count { it.ok }
    val missingRequired = rows.count { it.required && !it.ok }
    return "$ok of ${rows.size} ready" + if (missingRequired > 0) " · $missingRequired required missing" else ""
}

/** Setup checklist: every permission Tether can use, its status, and a Fix button or adb steps. */
@Composable
fun SetupScreen(rows: List<SetupRow>, onFix: (String) -> Unit, onCopy: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(containerColor = Mono.Bg, topBar = { TopBar("Setup checklist", onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 40.dp)) {
            item {
                Text(
                    setupSummary(rows), style = MaterialTheme.typography.bodyMedium, color = Mono.Muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            val groups = listOf("Required" to rows.filter { it.required }, "Optional" to rows.filter { !it.required })
            for ((title, list) in groups) {
                if (list.isEmpty()) continue
                item { SectionHeader(title) }
                item {
                    Card {
                        Column {
                            list.forEachIndexed { i, r ->
                                if (i > 0) Divider()
                                SetupItem(r, onFix, onCopy)
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Rows marked adb need a computer once: enable Wireless debugging in Developer options, pair with " +
                        "\"adb pair\", then run the commands. Settings › On your Mac › Clipboard has a step-by-step guide.",
                    style = MaterialTheme.typography.bodySmall, color = Mono.Muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SetupItem(r: SetupRow, onFix: (String) -> Unit, onCopy: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Status(r.ok)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.label, style = MaterialTheme.typography.bodyLarge, color = Mono.Text)
                Text(r.why, style = MaterialTheme.typography.bodySmall, color = Mono.Muted)
            }
            if (!r.ok && r.commands.isEmpty()) {
                TextButton(onClick = { onFix(r.id) }, modifier = Modifier.semantics { contentDescription = "Fix ${r.label}" }) {
                    Text("Fix", color = Mono.Text, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        AnimatedVisibility(!r.ok && r.commands.isNotEmpty()) {
            Column(Modifier.padding(start = 28.dp, top = 8.dp)) {
                r.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Mono.Muted) }
                Spacer(Modifier.height(6.dp))
                val block = r.commands.joinToString("\n")
                Text(
                    block, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoCode), color = Mono.Text,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Mono.SurfaceHigh).padding(10.dp),
                )
                TextButton(onClick = { onCopy(block) }) { Text("Copy commands", color = Mono.Text) }
            }
        }
    }
}

/** Filled check = granted, hollow ring = missing. Weight and fill only, no colour. */
@Composable
private fun Status(ok: Boolean) {
    val m = Modifier.size(16.dp).clip(CircleShape)
    if (ok) {
        Text(
            "✓", color = Mono.Bg, style = MaterialTheme.typography.labelSmall,
            modifier = m.background(Mono.Text).padding(start = 3.dp).semantics { contentDescription = "Granted" },
        )
    } else {
        Spacer(m.border(1.5.dp, Mono.Muted, CircleShape).semantics { contentDescription = "Missing" })
    }
}

// ---------------------------------------------------------------------------------------------

/** What the update row shows. */
data class UpdateUi(
    val line: String,
    val action: String?,
    val progress: Float? = null,
)

interface More5Actions {
    fun update(change: (AppSettings) -> AppSettings)
    fun openSetup()
    fun updateAction()
}

/** Settings for everything 0.5 added on the phone. */
@Composable
fun More5Screen(s: AppSettings, setup: String, upd: UpdateUi, version: String, a: More5Actions, onBack: () -> Unit) {
    var feed by rememberSaveable { mutableStateOf(s.updateFeed.orEmpty()) }
    Scaffold(containerColor = Mono.Bg, topBar = { TopBar("Continue, files & updates", onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 40.dp)) {
            item { SectionHeader("Setup") }
            item { Card { NavRow("Setup checklist", setup, onClick = a::openSetup) } }
            item { SectionHeader("Continue on Mac") }
            item {
                Card {
                    Column {
                        ToggleRow("Continue on Mac", "Cards on the Mac for what you were just doing here", s.continueCards) { v ->
                            a.update { it.copy(continueCards = v) }
                        }
                        Divider()
                        ToggleRow("New photos and screenshots", null, s.continuePhotos, enabled = s.continueCards) { v ->
                            a.update { it.copy(continuePhotos = v) }
                        }
                        Divider()
                        ToggleRow("New downloads", "Needs All files access to see other apps' downloads", s.continueDownloads, s.continueCards) { v ->
                            a.update { it.copy(continueDownloads = v) }
                        }
                        Divider()
                        ToggleRow("Copied links", null, s.continueLinks, enabled = s.continueCards) { v -> a.update { it.copy(continueLinks = v) } }
                        Divider()
                        ToggleRow("Song playing", "Open it in Spotify or YouTube Music on the Mac", s.continueSongs, s.continueCards) { v ->
                            a.update { it.copy(continueSongs = v) }
                        }
                        Divider()
                        ToggleRow(
                            "Current browser tab",
                            "Reads only the address bar of Chrome, Brave, Edge or Samsung Internet (accessibility service). Best-effort.",
                            s.continueTabs, s.continueCards,
                        ) { v -> a.update { it.copy(continueTabs = v) } }
                    }
                }
            }
            item { SectionHeader("From your Mac") }
            item {
                Card {
                    Column {
                        ToggleRow("Browse files", "Downloads, Documents, WhatsApp media (needs All files access)", s.shareFiles) { v ->
                            a.update { it.copy(shareFiles = v) }
                        }
                        Divider()
                        ToggleRow("Browse photos", "Recent photos and videos", s.sharePhotoBrowser) { v -> a.update { it.copy(sharePhotoBrowser = v) } }
                        Divider()
                        ToggleRow("Open on phone", "Links and text sent from the Mac", s.allowOpenOnPhone) { v ->
                            a.update { it.copy(allowOpenOnPhone = v) }
                        }
                        Divider()
                        ToggleRow("Type from the Mac", "Into the focused field (needs Tether control in Accessibility)", s.allowTyping) { v ->
                            a.update { it.copy(allowTyping = v) }
                        }
                    }
                }
            }
            item { SectionHeader("Sync") }
            item {
                Card {
                    Column {
                        ToggleRow("One-time codes to Mac clipboard", "From texts and notifications, with a toast on the Mac", s.otpAutoCopy) { v ->
                            a.update { it.copy(otpAutoCopy = v) }
                        }
                        Divider()
                        ToggleRow("Do Not Disturb ↔ Focus", "Needs Do Not Disturb access", s.dndSync) { v -> a.update { it.copy(dndSync = v) } }
                        Divider()
                        ToggleRow("Clipboard images", "Images you copy travel both ways", s.clipImages) { v -> a.update { it.copy(clipImages = v) } }
                    }
                }
            }
            item { SectionHeader("Updates") }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Tether $version", style = MaterialTheme.typography.bodyLarge, color = Mono.Text)
                        Text(upd.line, style = MaterialTheme.typography.bodySmall, color = Mono.Muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        upd.progress?.let {
                            LinearProgressIndicator(
                                progress = { it }, color = Mono.Text, trackColor = Mono.Line, modifier = Modifier.fillMaxWidth().heightIn(min = 3.dp),
                            )
                        }
                        upd.action?.let { label ->
                            TextButton(onClick = a::updateAction) { Text(label, color = Mono.Text) }
                        }
                        OutlinedTextField(
                            value = feed, onValueChange = { feed = it }, singleLine = true,
                            label = { Text("Update feed (https)") },
                            placeholder = { Text("Default public feed", fontFamily = FontFamily.Default) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Mono.Text, unfocusedBorderColor = Mono.Line, cursorColor = Mono.Text,
                                focusedLabelColor = Mono.Text, unfocusedLabelColor = Mono.Muted,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { a.update { it.copy(updateFeed = feed.trim().ifEmpty { null }) } }) {
                            Text("Save feed", color = Mono.Text)
                        }
                        ToggleRow("Check when Tether opens", "At most once a day", s.autoUpdateCheck) { v -> a.update { it.copy(autoUpdateCheck = v) } }
                    }
                }
            }
        }
    }
}

/** Pure mapping of updater states to the row. Unit-tested. */
fun updateUi(state: dev.benfranklin.tether.v5.Updater.State): UpdateUi = when (state) {
    dev.benfranklin.tether.v5.Updater.State.Idle -> UpdateUi("Not checked yet", "Check for updates")
    dev.benfranklin.tether.v5.Updater.State.Checking -> UpdateUi("Checking…", null)
    is dev.benfranklin.tether.v5.Updater.State.UpToDate -> UpdateUi("Up to date (latest ${state.checkedVersion})", "Check again")
    is dev.benfranklin.tether.v5.Updater.State.Available -> UpdateUi("Tether ${state.info.version} is available", "Download and install")
    is dev.benfranklin.tether.v5.Updater.State.Downloading ->
        UpdateUi("Downloading ${state.info.version}… ${(state.progress * 100).toInt()}%", null, state.progress)
    is dev.benfranklin.tether.v5.Updater.State.Installing -> UpdateUi("Installing ${state.info.version}… confirm on screen", null)
    is dev.benfranklin.tether.v5.Updater.State.Failed -> UpdateUi(state.reason, "Try again")
}
