package dev.benfranklin.tether.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.tether.ui.TetherMark
import dev.benfranklin.tether.ui.theme.Mono

enum class OnboardingStep { WELCOME, PERMISSIONS, PAIR }

data class PermissionItem(val id: String, val title: String, val why: String, val granted: Boolean, val required: Boolean)

sealed interface PairView {
    data object Idle : PairView
    data class Searching(val qrName: String?) : PairView
    data class Confirm(val sas: String, val macName: String) : PairView
    data class Auto(val macName: String) : PairView
    data class Failed(val message: String) : PairView
}

@Composable
fun OnboardingScreen(
    step: OnboardingStep,
    permissions: List<PermissionItem>,
    pair: PairView,
    onNext: () -> Unit,
    onGrant: (String) -> Unit,
    onScanQr: () -> Unit,
    onPairNearby: () -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onCancelPairing: () -> Unit,
) {
    AnimatedContent(
        step, label = "step",
        transitionSpec = { (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut()) },
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    ) { s ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            when (s) {
                OnboardingStep.WELCOME -> Welcome(onNext)
                OnboardingStep.PERMISSIONS -> Permissions(permissions, onGrant, onNext)
                OnboardingStep.PAIR -> Pair(pair, onScanQr, onPairNearby, onConfirm, onReject, onCancelPairing)
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Mono.Muted)
}

@Composable
private fun Primary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Mono.Accent, contentColor = Mono.Bg, disabledContainerColor = Mono.Line, disabledContentColor = Mono.Muted,
        ),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun Secondary(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Mono.Line),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = Mono.Text) }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    Spacer(Modifier.height(48.dp))
    TetherMark(96.dp, linked = true, pulse = true)
    Spacer(Modifier.height(32.dp))
    Title("Tether")
    Spacer(Modifier.height(12.dp))
    Body("Your phone and your Mac, linked directly over Bluetooth and your own Wi-Fi.")
    Spacer(Modifier.height(28.dp))
    Feature("Clipboard", "Copy on one, paste on the other.")
    Feature("Notifications", "See your phone's alerts in the Mac menu bar.")
    Feature("Files", "Share to Tether. Fast on Wi-Fi, works anywhere over Bluetooth.")
    Feature("Private", "End-to-end encrypted. No cloud, no account, nothing leaves your devices.")
    Spacer(Modifier.height(36.dp))
    Primary("Get started", onClick = onNext)
}

@Composable
private fun Feature(title: String, text: String) {
    Row(Modifier.padding(vertical = 8.dp)) {
        Text("—", color = Mono.Sage, modifier = Modifier.width(24.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Mono.Muted)
        }
    }
}

@Composable
private fun Permissions(items: List<PermissionItem>, onGrant: (String) -> Unit, onNext: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Title("Permissions")
    Spacer(Modifier.height(12.dp))
    Body("Tether asks only for what each feature needs. You can change these later.")
    Spacer(Modifier.height(24.dp))
    items.forEach { p ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, Mono.Line, RoundedCornerShape(14.dp))
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "${p.title}. ${p.why}. ${if (p.granted) "Allowed" else if (p.required) "Required" else "Optional"}"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.title + if (p.required) "" else "  · optional", style = MaterialTheme.typography.titleSmall)
                Text(p.why, style = MaterialTheme.typography.bodySmall, color = Mono.Muted)
            }
            if (p.granted) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = Mono.Sage, modifier = Modifier.padding(12.dp))
            } else {
                TextButton(onClick = { onGrant(p.id) }) { Text("Allow") }
            }
        }
    }
    Spacer(Modifier.height(28.dp))
    Primary("Continue", enabled = items.filter { it.required }.all { it.granted }, onClick = onNext)
}

@Composable
private fun ColumnScope.Pair(
    pair: PairView, onScanQr: () -> Unit, onNearby: () -> Unit, onConfirm: () -> Unit, onReject: () -> Unit, onCancel: () -> Unit,
) {
    Spacer(Modifier.height(24.dp))
    Title("Pair your Mac")
    Spacer(Modifier.height(12.dp))
    when (pair) {
        PairView.Idle, is PairView.Failed -> {
            Body("On your Mac, open Tether from the menu bar and choose Pair a phone. Then scan the code it shows.")
            if (pair is PairView.Failed) {
                Spacer(Modifier.height(16.dp))
                Text(
                    pair.message, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.border(1.dp, Mono.Line, RoundedCornerShape(10.dp)).padding(12.dp)
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
            Spacer(Modifier.height(28.dp))
            Primary("Scan code", onClick = onScanQr)
            Spacer(Modifier.height(12.dp))
            Secondary("Pair without camera", onClick = onNearby)
        }
        is PairView.Searching -> {
            Body(pair.qrName?.let { "Looking for $it…" } ?: "Looking for a Mac in pairing mode…")
            Spacer(Modifier.height(36.dp))
            TetherMark(88.dp, linked = false, pulse = true, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(36.dp))
            Body("Keep the Mac's pairing window open and the devices within a few metres.")
            Spacer(Modifier.height(24.dp))
            Secondary("Cancel", onClick = onCancel)
        }
        is PairView.Confirm -> {
            Body("Check that ${pair.macName} shows the same code, then confirm on both devices.")
            Spacer(Modifier.height(32.dp))
            val spaced = pair.sas.chunked(3).joinToString(" ")
            Text(
                spaced, fontFamily = FontFamily.Monospace, fontSize = 44.sp, letterSpacing = 4.sp, color = Mono.Text,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Pairing code ${pair.sas.toList().joinToString(" ")}"
                    liveRegion = LiveRegionMode.Polite
                },
            )
            Spacer(Modifier.height(36.dp))
            Primary("Codes match", onClick = onConfirm)
            Spacer(Modifier.height(12.dp))
            Secondary("They don't match", onClick = onReject)
        }
        is PairView.Auto -> {
            Body("Pairing with ${pair.macName}…")
            Spacer(Modifier.height(36.dp))
            TetherMark(88.dp, linked = true, pulse = true, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
    Spacer(Modifier.height(24.dp))
    Text(
        "Pairing uses an encrypted Noise handshake. The code proves no one is in the middle.",
        style = MaterialTheme.typography.bodySmall, color = Mono.Muted,
    )
}
