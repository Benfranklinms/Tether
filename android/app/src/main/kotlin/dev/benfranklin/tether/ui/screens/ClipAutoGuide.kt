package dev.benfranklin.tether.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.benfranklin.tether.core.features.ClipAutoSetup
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.MonoCode

/** The wireless-adb steps for auto mode, shared by the guide, the README and a unit test. */
object ClipAutoSteps {
    fun steps(oem: String): List<String> = buildList {
        add(
            "Turn on Developer options: Settings › About device › Version › tap Build number 7 times " +
                "(other phones: Settings › About phone › Build number).",
        )
        add("Put this phone and your Mac on the same Wi-Fi. In Developer options turn on Wireless debugging.")
        if (SensitiveSteps.isColorOs(oem)) {
            add("OnePlus, OPPO and realme: also turn on Developer options › Disable permission monitoring, or Android refuses the grant.")
        }
        add("On the Mac install adb: brew install android-platform-tools")
        add(
            "On the phone tap Wireless debugging › Pair device with pairing code. On the Mac run " +
                "adb pair <IP address:port shown under the code> and type the 6-digit code.",
        )
        add(
            "Back on the Wireless debugging screen, use the IP address & Port shown there (not the pairing port): " +
                "adb connect <IP address:port>. adb devices should now list the phone.",
        )
        add("Run the three commands below on the Mac. Send to Mac puts them on the Mac's clipboard, ready to paste into Terminal.")
        add(
            "Open Tether and come back here. Android 13 and later then ask \"Allow Tether to access all device logs?\": " +
                "tap Allow. It asks again whenever Tether restarts (a reboot, an update): just open Tether and allow.",
        )
        add("Optional: to hide \"Tether pasted from your clipboard\", turn off Settings › Privacy › Show clipboard access.")
    }

    fun commandBlock(pkg: String) = ClipAutoSetup.commands(pkg).joinToString("\n")
}

/** What each status asks the user to do next (null = nothing). Pure; unit-tested. */
fun clipAutoHint(s: ClipAutoSetup.Status): String? = when (s) {
    ClipAutoSetup.Status.OFF -> null
    ClipAutoSetup.Status.NEEDS_LOGS -> "Android only lets apps on screen read the clipboard. Allow Tether once from your Mac:"
    ClipAutoSetup.Status.NEEDS_RESTART -> "Permission granted. Run the last command (force-stop), then open Tether again."
    ClipAutoSetup.Status.NEEDS_OVERLAY -> "Android needs \"Display over other apps\" so Tether can read a new copy in the background."
    ClipAutoSetup.Status.NEEDS_APPROVAL -> "Android hasn't allowed log access for this session. Tap Allow log access, then Allow."
    ClipAutoSetup.Status.WATCHING -> "Copy some text in another app to check. It reaches your Mac in a second."
    ClipAutoSetup.Status.WORKING -> "Copies reach your Mac by themselves. Android shows \"Tether pasted from your clipboard\" each time."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClipAutoGuide(
    status: ClipAutoSetup.Status,
    oem: String,
    commands: String,
    connected: Boolean,
    onSendToMac: () -> Unit,
    onCopy: () -> Unit,
    onDeveloperOptions: () -> Unit,
    onOverlay: () -> Unit,
    onAllowLogs: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        clipAutoHint(status)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Mono.Text) }
        val setup = status == ClipAutoSetup.Status.NEEDS_LOGS || status == ClipAutoSetup.Status.NEEDS_RESTART
        if (setup) {
            ClipAutoSteps.steps(oem).forEachIndexed { i, s ->
                Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium, color = Mono.Text)
            }
        }
        if (setup || status == ClipAutoSetup.Status.NEEDS_OVERLAY) {
            SelectionContainer {
                Text(
                    commands, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoCode), color = Mono.Text,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Mono.SurfaceHigh).padding(12.dp),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (setup || status == ClipAutoSetup.Status.NEEDS_OVERLAY) {
                if (connected) OutlinedButton(onClick = onSendToMac) { Text("Send to Mac", color = Mono.Text) }
                OutlinedButton(onClick = onCopy) { Text("Copy", color = Mono.Text) }
            }
            if (setup) OutlinedButton(onClick = onDeveloperOptions) { Text("Developer options", color = Mono.Text) }
            if (status == ClipAutoSetup.Status.NEEDS_OVERLAY) {
                OutlinedButton(onClick = onOverlay) { Text("Display over other apps", color = Mono.Text) }
            }
            if (status == ClipAutoSetup.Status.NEEDS_APPROVAL) {
                OutlinedButton(onClick = onAllowLogs) { Text("Allow log access", color = Mono.Text) }
            }
        }
    }
}
