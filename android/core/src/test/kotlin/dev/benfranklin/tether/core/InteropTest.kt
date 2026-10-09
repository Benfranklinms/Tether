package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.crypto.Role
import dev.benfranklin.tether.core.proto.Clip
import dev.benfranklin.tether.core.session.PairingContext
import dev.benfranklin.tether.core.util.B64
import dev.benfranklin.tether.core.util.Hex
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Kotlin phone (initiator) against the real Swift Mac core (responder) over a pipe, with
 * real fragmentation. Runs only when TETHER_SWIFT_PEER points at the tether-peer binary
 * (see tools/interop); CI's Android job skips it.
 */
class InteropTest {
    private val peerPath: String? = System.getenv("TETHER_SWIFT_PEER")

    private class Peer(path: String, macFragment: Int, private val loop: Loop, private val phone: Side) {
        val proc: Process = ProcessBuilder(path, macFragment.toString()).redirectErrorStream(false).start()
        private val toPeer = PrintWriter(proc.outputStream, true)
        val lines = CopyOnWriteArrayList<String>()
        var key: ByteArray? = null
        var token: ByteArray? = null

        init {
            val r = BufferedReader(InputStreamReader(proc.inputStream))
            key = B64.decode(r.readLine().removePrefix("KEY "))
            token = B64.decode(r.readLine().removePrefix("TOKEN "))
            Thread {
                while (true) {
                    val l = r.readLine() ?: break
                    lines += l
                    if (l.startsWith("F ")) {
                        val f = Hex.decode(l.substring(2))
                        loop.execute { phone.controller.bleFragment(f) }
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        fun send(line: String) = synchronized(toPeer) { toPeer.println(line) }

        fun close() {
            send("QUIT")
            proc.destroy()
        }
    }

    private fun run(phoneFragment: Int, macFragment: Int, qr: Boolean) {
        val path = peerPath
        assumeTrue("TETHER_SWIFT_PEER not set", path != null)
        val loop = Loop()
        loop.now = System.currentTimeMillis()
        val phone = Side("OnePlus Nord CE5", Role.INITIATOR, loop)
        val peer = Peer(path!!, macFragment, loop, phone)
        try {
            val ctx = if (qr) {
                PairingContext(acceptNew = true, expectedRemoteKey = peer.key, token = peer.token)
            } else {
                PairingContext(acceptNew = true)
            }
            loop.execute {
                phone.controller.bleConnected(phoneFragment, { f -> peer.send("F " + Hex.encode(f)) }, ctx)
            }
            loop.runUntil(10_000) { phone.state is LinkState.Pairing || phone.state is LinkState.Connected }
            val sas = (phone.state as? LinkState.Pairing)?.sas
            if (!qr) {
                loop.runUntil(10_000) { peer.lines.any { it.startsWith("STATE pairing") } }
                val macLine = peer.lines.first { it.startsWith("STATE pairing") }
                assertTrue("SAS differs: phone=$sas mac=$macLine", macLine.contains("\"$sas\""))
                peer.send("CONFIRM")
                loop.execute { phone.controller.confirmPairing() }
            }
            loop.runUntil(10_000) { phone.state is LinkState.Connected && peer.lines.any { it.startsWith("PAIRED") } }
            loop.execute { phone.controller.send(Clip("c1", 1, "hello from android ✓")) }
            loop.runUntil(10_000) { peer.lines.any { it.startsWith("MSG") && it.contains("hello from android") } }
            peer.send("CLIP hello from mac ✓")
            loop.runUntil(10_000) { phone.messages.any { it is Clip && it.text == "hello from mac ✓" } }
            assertEquals("Ben’s MacBook Air", (phone.state as LinkState.Connected).peerName)
        } finally {
            peer.close()
        }
    }

    @Test fun codePairingSmallMtu() = run(phoneFragment = 20, macFragment = 20, qr = false)

    @Test fun codePairingBigMtu() = run(phoneFragment = 514, macFragment = 182, qr = false)

    @Test fun qrPairingAsymmetric() = run(phoneFragment = 182, macFragment = 509, qr = true)
}
