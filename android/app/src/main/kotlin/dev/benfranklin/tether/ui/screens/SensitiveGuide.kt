package dev.benfranklin.tether.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.MonoCode

/** The steps, shared by the phone guide and its unit test (the Mac shows the same list). */
object SensitiveSteps {
    fun steps(oem: String): List<String> = buildList {
        add("Turn on Developer options: Settings › About phone › tap Build number 7 times (OnePlus: About device › Version).")
        add("In Developer options turn on Wireless debugging, with this phone and your Mac on the same Wi-Fi.")
        add("On the Mac install adb (brew install android-platform-tools), then tap Pair device with pairing code and run " +
            "adb pair <ip:port> with the code shown, then adb connect <ip:port> from the Wireless debugging screen.")
        add("Run the command below on the Mac.")
        if (isColorOs(oem)) {
            add("OnePlus, OPPO and realme: also turn on Developer options › Disable permission monitoring, or the phone undoes the change.")
        }
        add("Restart the phone. Tether shows \"Codes are shown\" here once Android allows it.")
    }

    fun isColorOs(oem: String) = oem.lowercase().let { it.contains("oneplus") || it.contains("oppo") || it.contains("realme") }
}

/**
 * Android 15+ "Sensitive notification content hidden": what it is, the one-time adb fix and
 * its status. Shown expanded the first time redaction is seen, then as a row.
 */
@Composable
fun SensitiveGuide(
    redacting: Boolean,
    granted: Boolean?,
    oem: String,
    command: String,
    onCopy: () -> Unit,
    onDeveloperOptions: () -> Unit,
    onDismiss: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val status = when {
            granted == true && !redacting -> "Codes are shown on your Mac."
            redacting -> "Android is hiding one-time codes from Tether (\"Sensitive notification content hidden\")."
            else -> "Android 15 and later hide one-time codes from apps like Tether unless you allow it once."
        }
        Text(status, style = MaterialTheme.typography.bodyLarge, color = Mono.Text)
        if (granted == true && !redacting) return@Column
        Text(
            "Android only gives this to phone makers' apps and watch companions, so Tether can't ask for it. " +
                "You can allow it yourself once with a command from your Mac:",
            style = MaterialTheme.typography.bodyMedium, color = Mono.Muted,
        )
        SensitiveSteps.steps(oem).forEachIndexed { i, s ->
            Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium, color = Mono.Text)
        }
        SelectionContainer {
            Text(
                command, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoCode), color = Mono.Text,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Mono.SurfaceHigh).padding(12.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCopy) { Text("Copy command", color = Mono.Text) }
            OutlinedButton(onClick = onDeveloperOptions) { Text("Developer options", color = Mono.Text) }
        }
        if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Not now", color = Mono.Muted) }
    }
}
