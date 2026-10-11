package dev.benfranklin.tether.ui.screens

import dev.benfranklin.tether.ui.screenPadding
import dev.benfranklin.tether.core.proto.FindActions

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.benfranklin.tether.core.notify.AppRule
import dev.benfranklin.tether.core.notify.NotificationSettings
import dev.benfranklin.tether.data.AppSettings
import dev.benfranklin.tether.ui.Card
import dev.benfranklin.tether.ui.Divider
import dev.benfranklin.tether.ui.NavRow
import dev.benfranklin.tether.ui.SectionHeader
import dev.benfranklin.tether.ui.TetherMark
import dev.benfranklin.tether.ui.ToggleRow
import dev.benfranklin.tether.ui.sharedHero
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.monoSwitchColors
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.Motion
import java.util.Locale

@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    CenterAlignedTopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Mono.Bg),
    )
}

/** Device facts shown under "This pair". */
data class DeviceInfo(
    val macName: String?,
    val macFingerprint: String?,
    val phoneFingerprint: String,
    val connected: Boolean,
    val listenerEnabled: Boolean,
    val version: String,
    val batteryOptimized: Boolean,
)

@Suppress("TooManyFunctions")
interface SettingsActions {
    fun update(change: (AppSettings) -> AppSettings)
    fun openApps()
    fun openKeywords()
    fun pickQuietStart()
    fun pickQuietEnd()
    fun openNotificationAccess()
    fun pickFolder()
    fun resetFolder()
    fun openBatterySettings()
    fun rotateKeys()
    fun unpair()
    fun exportLogs()
    fun openLicenses()
    fun openFeatures() = Unit
    fun openSetup() = Unit
    fun openMore5() = Unit
    fun openShelf() = Unit
    fun openHotspot() = Unit
    /** "12 of 16 ready" for the setup row. */
    fun setupSummary(): String = ""
}

fun minutesLabel(m: Int): String = String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)

private fun SettingsActions.notif(change: (NotificationSettings) -> NotificationSettings) =
    update { it.copy(notifications = change(it.notifications)) }

@Composable
fun SettingsScreen(
    s: AppSettings,
    d: DeviceInfo,
    a: SettingsActions,
    onBack: () -> Unit,
    find: FindModel? = null,
    onFind: (String) -> Unit = {},
) {
    var confirmUnpair by remember { mutableStateOf(false) }
    val reduce = LocalReduceMotion.current
    val n = s.notifications
    Scaffold(containerColor = Mono.Bg, topBar = { TopBar("Settings", onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(pad)) {
            item { SectionHeader("Features") }
            item {
                Card {
                    Column {
                        NavRow("Setup checklist", null, valueText = setupValueText(a.setupSummary()), onClick = a::openSetup)
                        Divider()
                        NavRow("On your Mac", null, subtitle = "Calls, texts, codes, photos, screen", onClick = a::openFeatures)
                        Divider()
                        NavRow(
                            "Continue, files & updates", null, subtitle = "Continue on Mac, phone files, updates", onClick = a::openMore5,
                        )
                        Divider()
                        NavRow("Shelf", null, subtitle = "Links, Wi-Fi, addresses and notes, synced with your Mac", onClick = a::openShelf)
                        Divider()
                        NavRow(
                            "Instant Hotspot", if (s.allowHotspot) "On" else "Off",
                            subtitle = "Your Mac joins this phone's hotspot", onClick = a::openHotspot,
                        )
                        Divider()
                        ToggleRow(
                            "Insert from Phone", "Your Mac can open this phone's camera or document scanner", s.allowCapture,
                        ) { v -> a.update { it.copy(allowCapture = v) } }
                    }
                }
            }
            // 0.5.3: Find my Mac lives here (it used to sit on Home). Null = not paired.
            find?.let { f ->
                item(key = "find-header") { SectionHeader("Find my Mac") }
                item(key = "find") {
                    FindMacCard(
                        f, onRing = { onFind(FindActions.RING) }, onStop = { onFind(FindActions.STOP) },
                        onLock = { onFind(FindActions.LOCK) },
                    )
                }
            }
            item { SectionHeader("Clipboard") }
            item {
                Card {
                    Column {
                        ToggleRow("Receive from Mac", "Text you copy on the Mac lands on this phone's clipboard", s.receiveClipboard) { v ->
                            a.update { it.copy(receiveClipboard = v) }
                        }
                        Divider()
                        ToggleRow(
                            "Send when Tether opens", "Android only lets apps on screen read the clipboard", s.sendClipboardOnOpen,
                        ) { v ->
                            a.update { it.copy(sendClipboardOnOpen = v) }
                        }
                        Divider()
                        NavRow("Send without opening Tether", if (s.autoClip) "On" else "Set up", onClick = a::openFeatures)
                    }
                }
            }
            item { SectionHeader("Notifications") }
            item {
                Card {
                    Column {
                        ToggleRow(
                            "Mirror to Mac", if (d.listenerEnabled) null else "Needs notification access", n.enabled && d.listenerEnabled,
                        ) { v ->
                            if (!d.listenerEnabled) a.openNotificationAccess() else a.notif { it.copy(enabled = v) }
                        }
                        Divider()
                        NavRow("Apps", "${n.apps.size} custom", onClick = a::openApps)
                        Divider()
                        val muted = if (n.mutedKeywords.isEmpty()) "None" else n.mutedKeywords.size.toString()
                        NavRow("Muted words", muted, onClick = a::openKeywords)
                    }
                }
            }
            item { SectionHeader("Skip") }
            item {
                Card {
                    Column {
                        ToggleRow("Ongoing", "Music, navigation, downloads", n.skipOngoing) { v -> a.notif { it.copy(skipOngoing = v) } }
                        Divider()
                        ToggleRow("Silent", "Channels with no sound or vibration", n.skipSilent) { v ->
                            a.notif { it.copy(skipSilent = v) }
                        }
                        Divider()
                        ToggleRow("Low priority", null, n.skipLowPriority) { v -> a.notif { it.copy(skipLowPriority = v) } }
                        Divider()
                        ToggleRow("Group summaries", "\"5 new messages\" bundles", n.skipGroupSummaries) { v ->
                            a.notif { it.copy(skipGroupSummaries = v) }
                        }
                    }
                }
            }
            item { SectionHeader("When") }
            item {
                Card {
                    Column {
                        ToggleRow("Quiet hours", "Don't mirror during these hours", n.quietHours.enabled) { v ->
                            a.notif { it.copy(quietHours = it.quietHours.copy(enabled = v)) }
                        }
                        if (n.quietHours.enabled) {
                            Divider()
                            NavRow("From", minutesLabel(n.quietHours.startMinute), onClick = a::pickQuietStart)
                            Divider()
                            NavRow("Until", minutesLabel(n.quietHours.endMinute), onClick = a::pickQuietEnd)
                        }
                        Divider()
                        ToggleRow("Only when phone is locked", "Skip what you're already looking at", n.onlyWhenLocked) { v ->
                            a.notif { it.copy(onlyWhenLocked = v) }
                        }
                    }
                }
            }
            item { SectionHeader("Privacy") }
            item {
                Card {
                    Column {
                        ToggleRow("Hide content", "The Mac shows only \"New notification from WhatsApp\"", n.hideContent) { v ->
                            a.notif { it.copy(hideContent = v) }
                        }
                        Divider()
                        ToggleRow("Dismiss together", "Clearing on one device clears it on the other", n.dismissSync) { v ->
                            a.notif { it.copy(dismissSync = v) }
                        }
                    }
                }
            }
            item { SectionHeader("Files") }
            item {
                Card {
                    Column {
                        NavRow("Save to", s.downloadTreeLabel ?: "Downloads/Tether", onClick = a::pickFolder)
                        if (s.downloadTreeUri != null) {
                            Divider()
                            NavRow("Use Downloads/Tether", null, onClick = a::resetFolder)
                        }
                        Divider()
                        ToggleRow("Wi-Fi fast path", "When both are on the same network or your hotspot", s.wifiFastPath) { v ->
                            a.update { it.copy(wifiFastPath = v) }
                        }
                        Divider()
                        ToggleRow("Notify when a file arrives", null, s.notifyOnReceive) { v -> a.update { it.copy(notifyOnReceive = v) } }
                    }
                }
            }
            item { SectionHeader("This pair") }
            item {
                Card {
                    Column {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            TetherMark(40.dp, linked = d.connected, pulse = false, modifier = Modifier.sharedHero("mark", reduce))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (d.connected) "Connected" else "Not connected", style = MaterialTheme.typography.titleSmall,
                                color = if (d.connected) Mono.Text else Mono.Muted,
                            )
                        }
                        Divider()
                        InfoRow("Mac", d.macName ?: "Not paired")
                        d.macFingerprint?.let {
                            Divider()
                            InfoRow("Mac key", it)
                        }
                        Divider()
                        InfoRow("This phone's key", d.phoneFingerprint)
                        Divider()
                        NavRow("Rotate keys", if (d.connected) null else "Connect first", onClick = a::rotateKeys)
                        Divider()
                        NavRow("Unpair", null) { confirmUnpair = true }
                    }
                }
            }
            item { SectionHeader("Battery") }
            item {
                Card {
                    NavRow(
                        "Battery optimization", if (d.batteryOptimized) "On · may delay reconnects" else "Off",
                        onClick = a::openBatterySettings,
                    )
                }
            }
            item { SectionHeader("About") }
            item {
                Card {
                    Column {
                        NavRow("Export logs", null, onClick = a::exportLogs)
                        Divider()
                        NavRow("Open-source licenses", null, onClick = a::openLicenses)
                        Divider()
                        InfoRow("Version", d.version)
                    }
                }
            }
        }
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair ${d.macName ?: "Mac"}?") },
            text = { Text("Both devices forget each other's keys. You'll need to pair again to reconnect.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    a.unpair()
                }) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Keep") } },
            containerColor = Mono.SurfaceHigh,
        )
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelSmall, color = Mono.Muted)
    }
}

/** One app in the per-app filter list. */
data class AppRow(val pkg: String, val label: String, val rule: AppRule, val effectiveOn: Boolean, val icon: ImageBitmap?)

@Composable
fun AppsScreen(apps: List<AppRow>, onSet: (String, AppRule) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
    Scaffold(containerColor = Mono.Bg, topBar = { TopBar("Apps", onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(pad)) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search apps") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear search") }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mono.Muted, unfocusedBorderColor = Mono.Line),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    "Messaging and social apps are on by default; system apps are off. Tap to change.",
                    style = MaterialTheme.typography.bodySmall, color = Mono.Muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (apps.isEmpty()) "Apps appear here once they post a notification." else "No apps match \"$query\".",
                        style = MaterialTheme.typography.bodyMedium, color = Mono.Muted, modifier = Modifier.padding(20.dp),
                    )
                }
            }
            items(shown, key = { it.pkg }) { app ->
                val r = LocalReduceMotion.current
                AppItem(
                    app, onSet,
                    Modifier.animateItem(fadeInSpec = Motion.fade(r), placementSpec = Motion.spring(r), fadeOutSpec = Motion.fade(r)),
                )
            }
        }
    }
}

@Composable
private fun AppItem(app: AppRow, onSet: (String, AppRule) -> Unit, modifier: Modifier = Modifier) {
    val next = if (app.effectiveOn) AppRule.BLOCK else AppRule.ALLOW
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Switch) { onSet(app.pkg, next) }
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${app.label}, ${if (app.effectiveOn) "mirrored" else "not mirrored"}" +
                    if (app.rule == AppRule.INHERIT) ", default" else ""
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (app.rule == AppRule.INHERIT) "Default" else "Custom", style = MaterialTheme.typography.bodySmall,
                color = Mono.Muted,
            )
        }
        if (app.rule != AppRule.INHERIT) {
            TextButton(onClick = { onSet(app.pkg, AppRule.INHERIT) }) { Text("Reset", color = Mono.Muted) }
        }
        androidx.compose.material3.Switch(checked = app.effectiveOn, onCheckedChange = null, colors = monoSwitchColors())
    }
}

@Composable
private fun AppIcon(app: AppRow) {
    val icon = app.icon
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)))
    } else {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(Mono.SurfaceHigh), contentAlignment = Alignment.Center) {
            Text(app.label.take(1).uppercase(), style = MaterialTheme.typography.titleSmall, color = Mono.Muted)
        }
    }
}

@Composable
fun KeywordsScreen(words: List<String>, onChange: (List<String>) -> Unit, onBack: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    fun add() {
        val w = draft.trim()
        if (w.isNotEmpty() && words.none { it.equals(w, true) }) onChange(words + w)
        draft = ""
    }
    Scaffold(containerColor = Mono.Bg, topBar = { TopBar("Muted words", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(screenPadding(pad, bottom = 0.dp))) {
            Text(
                "Notifications whose title or text contains any of these words aren't mirrored. Not case-sensitive.",
                style = MaterialTheme.typography.bodyMedium, color = Mono.Muted, modifier = Modifier.padding(20.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it }, singleLine = true, placeholder = { Text("e.g. OTP") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mono.Muted, unfocusedBorderColor = Mono.Line),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = ::add, enabled = draft.isNotBlank()) { Text("Add") }
            }
            Spacer(Modifier.height(8.dp))
            if (words.isEmpty()) {
                Text(
                    "No muted words yet.", style = MaterialTheme.typography.bodyMedium, color = Mono.Muted,
                    modifier = Modifier.padding(20.dp),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(words, key = { it }) { w ->
                    val r = LocalReduceMotion.current
                    Row(
                        Modifier.animateItem(fadeInSpec = Motion.fade(r), placementSpec = Motion.spring(r), fadeOutSpec = Motion.fade(r))
                            .fillMaxWidth().padding(start = 20.dp, end = 8.dp).heightIn(min = 52.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(w, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onChange(words - w) }) { Icon(Icons.Outlined.Close, contentDescription = "Remove $w") }
                    }
                }
            }
        }
    }
}

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(containerColor = Mono.Bg, topBar = { TopBar("Licenses", onBack) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = screenPadding(pad, start = 20.dp, end = 20.dp),
        ) {
            items(LICENSES) { (name, text) ->
                Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = Mono.Muted)
            }
        }
    }
}

val LICENSES = listOf(
    "Tether" to "MIT License. Copyright (c) 2026 Tether contributors.",
    "bitchat" to "The Bluetooth framing and Noise XX structure are adapted from permissionlesstech/bitchat (Swift), " +
        "released into the public domain (Unlicense). Thank you.",
    "Bouncy Castle" to "X25519 and ChaCha20-Poly1305. MIT-style Bouncy Castle License.",
    "ZXing / zxing-android-embedded" to "QR scanning. Apache License 2.0.",
    "Manrope" to "Typeface by The Manrope Project Authors. SIL Open Font License 1.1.",
    "AndroidX, Jetpack Compose, Kotlin, kotlinx" to "Apache License 2.0.",
)
