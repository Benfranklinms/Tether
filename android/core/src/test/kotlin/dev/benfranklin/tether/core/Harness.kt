package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.crypto.KeyPair
import dev.benfranklin.tether.core.crypto.Role
import dev.benfranklin.tether.core.proto.CloseReason
import dev.benfranklin.tether.core.proto.Hello
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.core.proto.WifiOffer
import dev.benfranklin.tether.core.session.PairingContext
import dev.benfranklin.tether.core.session.PeerRecord
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.IncomingFiles
import dev.benfranklin.tether.core.transfer.IntegrityException
import dev.benfranklin.tether.core.transfer.OutgoingFiles
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.core.util.Hex
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A serial event loop the tests drive by hand (thread-safe submission for TCP tests). */
class Loop : Executor {
    private val q = LinkedBlockingQueue<Runnable>()
    var now = 1_000_000L

    override fun execute(command: Runnable) {
        q.offer(command)
    }

    /** Run queued work until idle. Returns the number of tasks run. */
    fun drain(limit: Int = 5_000_000): Int {
        var n = 0
        while (true) {
            val r = q.poll() ?: return n
            r.run()
            if (++n > limit) error("loop did not settle")
        }
    }

    /** Run at most [n] queued tasks (to stop part-way through a transfer). */
    fun step(n: Int) {
        repeat(n) { (q.poll() ?: return).run() }
    }

    /** For tests with real threads: run work until [done] or timeout. */
    fun runUntil(timeoutMs: Long = 20_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > end) error("timed out")
            q.poll(20, TimeUnit.MILLISECONDS)?.run()
        }
    }
}

class MemIdentity(override var localKey: KeyPair = KeyPair.generate()) : IdentityStore {
    override var pendingLocalKey: KeyPair? = null
    override var peer: PeerRecord? = null
}

class MemFiles : OutgoingFiles, IncomingFiles {
    val sources = HashMap<String, ByteArray>()
    val partial = HashMap<String, ByteArrayOutputStream>()
    val finished = HashMap<String, ByteArray>()
    val released = HashSet<String>()
    var corruptNextFinish = false
    var failReads = false

    override fun read(record: TransferRecord, offset: Long, length: Int): ByteArray {
        if (failReads) throw IOException("gone")
        val src = sources[record.ref ?: record.id] ?: throw IOException("missing")
        return src.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    override fun release(record: TransferRecord) {
        released += record.id
    }

    override fun partialLength(id: String): Long = partial[id]?.size()?.toLong() ?: 0

    override fun begin(record: TransferRecord) {
        partial[record.id] = ByteArrayOutputStream()
    }

    override fun append(id: String, data: ByteArray) {
        (partial[id] ?: throw IOException("no partial")).write(data)
    }

    override fun finish(record: TransferRecord): String {
        var bytes = partial[record.id]!!.toByteArray()
        if (corruptNextFinish) {
            corruptNextFinish = false
            bytes = bytes.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        }
        if (Hex.encode(sha256(bytes)) != record.sha256) throw IntegrityException("hash mismatch")
        finished[record.id] = bytes
        partial.remove(record.id)
        return "mem://${record.name}"
    }

    override fun discard(id: String) {
        partial.remove(id)
    }

    /** Stage [bytes] for sending and build the outgoing record. */
    fun stage(name: String, bytes: ByteArray, now: Long): TransferRecord {
        val id = UUID.randomUUID().toString()
        sources[id] = bytes
        return TransferRecord(
            id = id, direction = Direction.OUT, name = name, size = bytes.size.toLong(),
            sha256 = Hex.encode(sha256(bytes)), mime = "application/octet-stream",
            status = TransferStatus.QUEUED, createdAt = now, ref = id,
        )
    }
}

fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

/** One side: controller + its stores + everything it reported. */
class Side(val name: String, val role: Role, val loop: Loop) : ControllerListener {
    val identity = MemIdentity()
    val files = MemFiles()
    val states = ArrayList<LinkState>()
    val messages = ArrayList<Message>()
    val closes = ArrayList<Pair<CloseReason, Boolean>>()
    val transfers = LinkedHashMap<String, TransferRecord>()
    val wifiOffers = ArrayList<WifiOffer>()
    var unpairedByRemote: Boolean? = null
    var rotated = 0
    /** What this side announces; set to Caps.V1 to play a 0.1.x build. */
    var caps: List<String> = dev.benfranklin.tether.core.proto.Caps.CURRENT

    val controller = TetherController(
        role, identity,
        { Hello(name = name, platform = if (role == Role.INITIATOR) "android" else "macos", app = "test", caps = caps) },
        files, files, this, clock = { loop.now },
    )

    val state: LinkState get() = controller.state

    override fun onState(state: LinkState) {
        states += state
    }

    override fun onMessage(message: Message) {
        messages += message
    }

    override fun onTransfer(record: TransferRecord, important: Boolean) {
        transfers[record.id] = record
    }

    override fun onTransferRemoved(id: String) {
        transfers.remove(id)
    }

    override fun onUnpaired(byRemote: Boolean) {
        unpairedByRemote = byRemote
    }

    override fun onClosed(reason: CloseReason, byRemote: Boolean, wifi: Boolean) {
        closes += reason to byRemote
    }

    override fun onWifiOffer(offer: WifiOffer) {
        wifiOffers += offer
    }

    override fun onKeyRotated() {
        rotated++
    }
}

/**
 * In-memory BLE link between a phone (initiator) and a Mac (responder): fragments are
 * delivered through the loop, so there is no re-entrancy, like real radio callbacks.
 */
class LoopbackBle(private val loop: Loop, val phone: Side, val mac: Side, val mtu: Int = 185) {
    var up = false
        private set
    var fragmentsSent = 0
    /** Cut the link after this many more fragments (simulates walking out of range). */
    var cutAfter: Int? = null
    /** Silently lose the next N fragments towards the Mac / the phone (radio loss, setup races). */
    var dropToMac = 0
    var dropToPhone = 0
    private var generation = 0

    fun connect(phonePairing: PairingContext = PairingContext.CLOSED, macPairing: PairingContext = PairingContext.CLOSED) {
        up = true
        val gen = ++generation
        mac.controller.bleConnected(mtu, { f -> deliver(gen, f, mac = false) }, macPairing)
        phone.controller.bleConnected(mtu, { f -> deliver(gen, f, mac = true) }, phonePairing)
    }

    private fun deliver(gen: Int, fragment: ByteArray, mac: Boolean) {
        if (!up || gen != generation) return
        fragmentsSent++
        if (mac && dropToMac > 0) {
            dropToMac--
            return
        }
        if (!mac && dropToPhone > 0) {
            dropToPhone--
            return
        }
        cutAfter?.let { left ->
            if (left <= 0) {
                loop.execute { disconnect() }
                return
            }
            cutAfter = left - 1
        }
        val copy = fragment.copyOf()
        loop.execute {
            if (up && gen == generation) {
                if (mac) this.mac.controller.bleFragment(copy) else phone.controller.bleFragment(copy)
            }
        }
    }

    fun disconnect() {
        if (!up) return
        up = false
        cutAfter = null
        phone.controller.bleDisconnected()
        mac.controller.bleDisconnected()
    }
}

/** In-memory stand-in for the TCP link (record-level, no fragmentation). */
class LoopbackWifi(private val loop: Loop, val phone: Side, val mac: Side) {
    var up = false
        private set
    private var generation = 0
    var cutAfter: Int? = null

    fun connect(token: String) {
        up = true
        val gen = ++generation
        mac.controller.wifiConnected({ r -> deliver(gen, r, toMac = false) }, token)
        phone.controller.wifiConnected({ r -> deliver(gen, r, toMac = true) }, null)
    }

    private fun deliver(gen: Int, record: ByteArray, toMac: Boolean) {
        if (!up || gen != generation) return
        cutAfter?.let { left ->
            if (left <= 0) {
                loop.execute { disconnect() }
                return
            }
            cutAfter = left - 1
        }
        loop.execute {
            if (up && gen == generation) {
                if (toMac) mac.controller.wifiRecord(record) else phone.controller.wifiRecord(record)
            }
        }
    }

    fun disconnect() {
        if (!up) return
        up = false
        cutAfter = null
        phone.controller.wifiDisconnected()
        mac.controller.wifiDisconnected()
    }
}

/** Phone + Mac already paired with each other. */
fun pairedSides(loop: Loop = Loop()): Pair<Side, Side> {
    val phone = Side("Pixel", Role.INITIATOR, loop)
    val mac = Side("MacBook", Role.RESPONDER, loop)
    phone.identity.peer = PeerRecord.paired("MacBook", "macos", mac.identity.localKey.publicKey, 0)
    mac.identity.peer = PeerRecord.paired("Pixel", "android", phone.identity.localKey.publicKey, 0)
    return phone to mac
}
