package dev.benfranklin.tether

import dev.benfranklin.tether.ble.BleCentral
import dev.benfranklin.tether.core.LinkState
import dev.benfranklin.tether.core.proto.Protocol
import dev.benfranklin.tether.core.session.PeerRecord
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.share.ShareActivity
import dev.benfranklin.tether.ui.homeModel
import dev.benfranklin.tether.ui.screens.formatBytes
import dev.benfranklin.tether.ui.screens.minutesLabel
import dev.benfranklin.tether.ui.screens.statusLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest {
    private val peer = PeerRecord.paired("Ben's Mac", "macos", ByteArray(32), 0)

    @Test
    fun homeModelStates() {
        val connected = homeModel(UiState(link = LinkState.Connected("Ben's Mac", wifi = true), peer = peer), emptyList(), false)
        assertEquals("Ben's Mac", connected.title)
        assertTrue(connected.connected && connected.wifi && !connected.searching)
        val off = homeModel(UiState(radio = BleCentral.RadioState.OFF, peer = peer), emptyList(), false)
        assertEquals("Bluetooth is off", off.subtitle)
        assertFalse(off.searching)
        val looking = homeModel(UiState(radio = BleCentral.RadioState.SCANNING, peer = peer), emptyList(), true)
        assertTrue(looking.searching && looking.mirroringOff)
        assertEquals("Allow Nearby devices to connect", homeModel(UiState(radio = BleCentral.RadioState.NO_PERMISSION), emptyList(), false).subtitle)
    }

    @Test
    fun sizeWarnings() {
        assertNull(ShareActivity.sizeWarning(Protocol.FILE_WARN_BYTES, wifi = false))
        assertNull(ShareActivity.sizeWarning(Protocol.FILE_MAX_BYTES * 2, wifi = true))
        assertEquals("Large file", ShareActivity.sizeWarning(Protocol.FILE_WARN_BYTES + 1, wifi = false)?.first)
        assertEquals("Waits for Wi-Fi", ShareActivity.sizeWarning(Protocol.FILE_MAX_BYTES + 1, wifi = false)?.first)
        assertNotNull(ShareActivity.sizeWarning(50L * 1024 * 1024, wifi = false)?.second?.contains("min"))
    }

    @Test
    fun formatting() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("2 KB", formatBytes(2048))
        assertEquals("1.5 MB", formatBytes(1_572_864))
        assertEquals("2.0 GB", formatBytes(2L * 1024 * 1024 * 1024))
        assertEquals("07:05", minutesLabel(7 * 60 + 5))
        assertEquals("22:00", minutesLabel(22 * 60))
        val r = TransferRecord("i", Direction.IN, "a", 2048, "0", "x", TransferStatus.ACTIVE, 1024, createdAt = 0)
        assertEquals("1 KB of 2 KB", statusLine(r))
        assertEquals("Received · 2 KB", statusLine(r.copy(status = TransferStatus.DONE)))
        assertEquals("boom", statusLine(r.copy(status = TransferStatus.FAILED, error = "boom")))
    }

    @Test
    fun pairingStatusLine() {
        val scanning = BleCentral.Diag(stage = BleCentral.Stage.SCANNING, adsSeen = 42)
        assertTrue(pairStatus(UiState(radio = BleCentral.RadioState.SCANNING, diag = scanning)).contains("42 Bluetooth signals"))
        val quiet = BleCentral.Diag(stage = BleCentral.Stage.SCANNING, locationOff = true)
        assertTrue(pairStatus(UiState(diag = quiet)).contains("Location"))
        val found = BleCentral.Diag(stage = BleCentral.Stage.CONNECTING, connectAttempts = 2, macSeen = true)
        assertEquals("Found your Mac · connecting (try 2)", pairStatus(UiState(diag = found)))
        assertTrue(pairStatus(UiState(link = LinkState.Handshaking)).contains("securing"))
        assertTrue(pairStatus(UiState(radio = BleCentral.RadioState.OFF)).startsWith("Bluetooth is off"))
    }

    @Test
    fun pairingFailureReasons() {
        assertTrue(pairFailure(BleCentral.Diag(adsSeen = 120), LinkState.Offline).startsWith("Couldn't find your Mac"))
        assertTrue(pairFailure(BleCentral.Diag(), LinkState.Offline).startsWith("No Bluetooth signals"))
        assertTrue(pairFailure(BleCentral.Diag(macSeen = true, lastError = "gatt status 133"), LinkState.Offline).contains("133"))
        assertTrue(pairFailure(BleCentral.Diag(macSeen = true, linkUps = 1), LinkState.Offline).contains("handshake"))
    }
}
