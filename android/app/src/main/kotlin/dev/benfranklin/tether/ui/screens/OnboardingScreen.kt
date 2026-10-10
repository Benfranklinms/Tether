package dev.benfranklin.tether.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import dev.benfranklin.tether.ui.Haptics
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Motion
import kotlinx.coroutines.delay
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.tether.ui.TetherMark
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.MonoCode

enum class OnboardingStep { WELCOME, PERMISSIONS, PAIR }

data class PermissionItem(val id: String, val title: String, val why: String, val granted: Boolean, val required: Boolean)

sealed interface PairView {
    data object Idle : PairView
    data class Searching(val qrName: String?, val status: String = "") : PairView
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
    onExportLogs: () -> Unit = {},
) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        step, label = "step",
        transitionSpec = {
            (slideInHorizontally(Motion.spring(reduce)) { it } + fadeIn(Motion.spring(reduce))) togetherWith
                (slideOutHorizontally(Motion.spring(reduce)) { -it / 3 } + fadeOut(Motion.spring(reduce)))
        },
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    ) { s ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            when (s) {
                OnboardingStep.WELCOME -> Welcome(onNext)
                OnboardingStep.PERMISSIONS -> Permissions(permissions, onGrant, onNext)
                OnboardingStep.PAIR -> Pair(pair, onScanQr, onPairNearby, onConfirm, onReject, onCancelPairing, onExportLogs)
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
        Text("—", color = Mono.Text, modifier = Modifier.width(24.dp))
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
                Icon(Icons.Outlined.Check, contentDescription = null, tint = Mono.Text, modifier = Modifier.padding(12.dp))
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
    onExportLogs: () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    Spacer(Modifier.height(24.dp))
    Title("Pair your Mac")
    Spacer(Modifier.height(12.dp))
    AnimatedContent(
        pair, label = "pair", contentKey = { it::class },
        transitionSpec = {
            (fadeIn(Motion.spring(reduce)) + scaleIn(Motion.spring(reduce), initialScale = 0.94f)) togetherWith
                (fadeOut(Motion.spring(reduce)) + scaleOut(Motion.spring(reduce), targetScale = 1.04f))
        },
    ) { pv -> Column { PairBody(pv, view, onScanQr, onNearby, onConfirm, onReject, onCancel) } }
    Spacer(Modifier.height(24.dp))
    Text(
        "Pairing uses an encrypted Noise handshake. The code proves no one is in the middle.",
        style = MaterialTheme.typography.bodySmall, color = Mono.Muted,
    )
    if (pair is PairView.Searching || pair is PairView.Failed) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onExportLogs, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Export diagnostics", style = MaterialTheme.typography.labelMedium, color = Mono.Muted)
        }
    }
}

/** Live one-line status under the pairing animation ("Found your Mac · connecting…"). */
@Composable
private fun StatusLine(text: String, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        text, label = "status", modifier = modifier,
        transitionSpec = { fadeIn(Motion.spring(reduce)) togetherWith fadeOut(Motion.spring(reduce)) },
    ) { t ->
        Text(
            t, style = MaterialTheme.typography.bodySmall, color = Mono.Muted, textAlign = TextAlign.Center,
            fontFamily = MonoCode,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun ColumnScope.PairBody(
    pair: PairView, view: android.view.View, onScanQr: () -> Unit, onNearby: () -> Unit, onConfirm: () -> Unit,
    onReject: () -> Unit, onCancel: () -> Unit,
) {
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
            Spacer(Modifier.height(20.dp))
            if (pair.status.isNotEmpty()) StatusLine(pair.status)
            Spacer(Modifier.height(16.dp))
            Body("Keep the Mac's pairing window open and the devices within a few metres.")
            Spacer(Modifier.height(24.dp))
            Secondary("Cancel", onClick = onCancel)
        }
        is PairView.Confirm -> {
            Body("Check that ${pair.macName} shows the same code, then confirm on both devices.")
            Spacer(Modifier.height(32.dp))
            SasDigits(pair.sas)
            Spacer(Modifier.height(36.dp))
            Primary("Codes match") {
                Haptics.confirm(view)
                onConfirm()
            }
            Spacer(Modifier.height(12.dp))
            Secondary("They don't match") {
                Haptics.reject(view)
                onReject()
            }
        }
        is PairView.Auto -> {
            Body("Pairing with ${pair.macName}…")
            Spacer(Modifier.height(36.dp))
            TetherMark(88.dp, linked = true, pulse = true, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** The six-digit code, digits dropping in one after another on a bouncy spring. */
@Composable
private fun SasDigits(sas: String) {
    val reduce = LocalReduceMotion.current
    var shown by remember(sas) { mutableIntStateOf(if (reduce) sas.length else 0) }
    LaunchedEffect(sas) {
        while (shown < sas.length) {
            delay(DIGIT_STAGGER_MS)
            shown++
        }
    }
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "Pairing code ${sas.toList().joinToString(" ")}"
            liveRegion = LiveRegionMode.Polite
        },
        horizontalArrangement = Arrangement.Center,
    ) {
        sas.forEachIndexed { i, c ->
            if (i == sas.length / 2) Spacer(Modifier.width(18.dp))
            val visible = i < shown
            val y by animateFloatAsState(if (visible) 0f else 1f, Motion.bouncy(reduce), label = "digitY")
            val a by animateFloatAsState(if (visible) 1f else 0f, Motion.spring(reduce), label = "digitA")
            Text(
                c.toString(), fontFamily = MonoCode, fontSize = 44.sp, color = Mono.Text, textAlign = TextAlign.Center,
                modifier = Modifier.width(30.dp).graphicsLayer {
                    alpha = a
                    translationY = y * DIGIT_DROP_PX
                },
            )
        }
    }
}

private const val DIGIT_STAGGER_MS = 70L
private const val DIGIT_DROP_PX = -36f
