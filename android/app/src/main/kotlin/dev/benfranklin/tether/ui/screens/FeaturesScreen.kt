package dev.benfranklin.tether.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.benfranklin.tether.core.proto.Caps
import dev.benfranklin.tether.data.AppSettings
import dev.benfranklin.tether.ui.Card
import dev.benfranklin.tether.ui.Divider
import dev.benfranklin.tether.ui.NavRow
import dev.benfranklin.tether.ui.SectionHeader
import dev.benfranklin.tether.ui.ToggleRow
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.Motion

/** Permission and capability facts for the feature screen (only missing ones get a row). */
data class FeatureStatus(
    val connected: Boolean = false,
    /** Features the Mac announced; null when we haven't connected yet this session. */
    val macCaps: Set<String>? = null,
    val listenerEnabled: Boolean = true,
    val mediaPermission: Boolean = true,
    val mediaLimited: Boolean = false,
    val phoneState: Boolean = true,
    val fullScreenAlerts: Boolean = true,
    val controlEnabled: Boolean = false,
)

interface FeatureActions {
    fun update(change: (AppSettings) -> AppSettings)
    fun grantMedia()
    fun grantPhoneState()
    fun openFullScreenSettings()
    fun openAccessibility()
    fun openNotificationAccess()
}

/** Next screen-size choice when the row is tapped. Pure; unit-tested. */
fun nextScreenSize(current: Int): Int = SCREEN_SIZES.firstOrNull { it > current } ?: SCREEN_SIZES.first()

val SCREEN_SIZES = listOf(720, 1080, 1280, 1600)

fun screenSizeLabel(px: Int): String = when (px) {
    720 -> "720p · lightest"
    1080 -> "1080p"
    1280 -> "1280 · balanced"
    else -> "$px · sharpest"
}

@Composable
fun FeaturesScreen(s: AppSettings, st: FeatureStatus, a: FeatureActions, onBack: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val caps = st.macCaps
    fun macLacks(cap: String) = caps != null && cap !in caps
    Scaffold(containerColor = Mono.Bg, topBar = { TopBar("On your Mac", onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 40.dp)) {
            if (caps != null && Caps.CURRENT.any { it !in caps }) {
                item {
                    Text(
                        "Your Mac runs an older Tether. Update it to use replies, photos, battery, ring and screen sharing.",
                        style = MaterialTheme.typography.bodyMedium, color = Mono.Text,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            item { SectionHeader("Notifications") }
            item {
                Card {
                    Column {
                        ToggleRow(
                            "Reply and actions", "Reply to WhatsApp, Messages, Telegram… and tap buttons like Mark as read",
                            s.notificationActions, enabled = !macLacks(Caps.REPLY),
                        ) { v -> a.update { it.copy(notificationActions = v) } }
                        Reveal(!st.listenerEnabled, reduce) {
                            Column {
                                Divider()
                                NavRow("Allow notification access", "Needed", onClick = a::openNotificationAccess)
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Photos") }
            item {
                Card {
                    Column {
                        ToggleRow(
                            "Send new screenshots", "Over Bluetooth or Wi-Fi", s.sendScreenshots, enabled = !macLacks(Caps.MEDIA),
                        ) { v -> a.update { it.copy(sendScreenshots = v) } }
                        Divider()
                        ToggleRow(
                            "Send new camera photos", "Waits for Wi-Fi, never over Bluetooth", s.sendPhotos,
                            enabled = !macLacks(Caps.MEDIA),
                        ) { v -> a.update { it.copy(sendPhotos = v) } }
                        Reveal((s.sendScreenshots || s.sendPhotos) && !st.mediaPermission, reduce) {
                            Column {
                                Divider()
                                NavRow(
                                    if (st.mediaLimited) "Allow all photos" else "Allow photo access",
                                    if (st.mediaLimited) "Limited" else "Needed", onClick = a::grantMedia,
                                )
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Battery") }
            item {
                Card {
                    Column {
                        ToggleRow(
                            "Show battery on the Mac", "Level, charging and network. The Mac warns at 20%", s.shareBattery,
                            enabled = !macLacks(Caps.BATTERY),
                        ) { v -> a.update { it.copy(shareBattery = v) } }
                        Reveal(s.shareBattery && !st.phoneState, reduce) {
                            Column {
                                Divider()
                                NavRow("Show 4G / 5G too", "Optional", onClick = a::grantPhoneState)
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Find my phone") }
            item {
                Card {
                    Column {
                        ToggleRow(
                            "Let the Mac ring this phone", "Loud alarm, even on silent. Stops after a minute", s.allowRing,
                            enabled = !macLacks(Caps.RING),
                        ) { v -> a.update { it.copy(allowRing = v) } }
                        Reveal(s.allowRing && !st.fullScreenAlerts, reduce) {
                            Column {
                                Divider()
                                NavRow("Allow full-screen alerts", "Recommended", onClick = a::openFullScreenSettings)
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Screen") }
            item {
                Card {
                    Column {
                        ToggleRow(
                            "Screen sharing", "Your Mac can ask to see this screen. You approve every time", s.allowScreen,
                            enabled = !macLacks(Caps.SCREEN),
                        ) { v -> a.update { it.copy(allowScreen = v) } }
                        Reveal(s.allowScreen, reduce) {
                            Column {
                                Divider()
                                NavRow("Quality", screenSizeLabel(s.screenMaxSize)) {
                                    a.update { it.copy(screenMaxSize = nextScreenSize(it.screenMaxSize)) }
                                }
                                Divider()
                                NavRow("Control from the Mac", if (st.controlEnabled) "On" else "Off", onClick = a::openAccessibility)
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Screen sharing needs Wi-Fi (same network, or the Mac on this phone's hotspot). Control uses an " +
                        "accessibility service: on a sideloaded app, Android first asks you to allow restricted settings " +
                        "(App info › ⋮ › Allow restricted settings).",
                    style = MaterialTheme.typography.bodySmall, color = Mono.Muted,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Reveal(visible: Boolean, reduce: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible,
        enter = expandVertically(Motion.spring<IntSize>(reduce)) + fadeIn(Motion.spring(reduce)),
        exit = shrinkVertically(Motion.spring<IntSize>(reduce)) + fadeOut(Motion.spring(reduce)),
    ) { content() }
}
