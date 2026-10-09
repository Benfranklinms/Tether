package dev.benfranklin.tether.core.transfer

import dev.benfranklin.tether.core.proto.FileAccept
import dev.benfranklin.tether.core.proto.FileAck
import dev.benfranklin.tether.core.proto.FileCancel
import dev.benfranklin.tether.core.proto.FileChunk
import dev.benfranklin.tether.core.proto.FileComplete
import dev.benfranklin.tether.core.proto.FileDone
import dev.benfranklin.tether.core.proto.FileOffer
import dev.benfranklin.tether.core.proto.FileReject
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.core.proto.Protocol
import kotlinx.serialization.Serializable
import java.io.IOException
import java.util.UUID

enum class Direction { OUT, IN }

/** Which link a transfer is moving over. */
enum class Transport { BLE, WIFI }

/** A way to send messages to the peer. */
class Channel(val transport: Transport, val send: (Message) -> Unit)

enum class TransferStatus {
    QUEUED, OFFERED, ACTIVE, WAITING, VERIFYING, DONE, FAILED, CANCELLED;

    val isFinal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
}

/** Persisted state of one transfer. Both apps store these as JSON (atomic writes). */
@Serializable
data class TransferRecord(
    val id: String,
    val direction: Direction,
    val name: String,
    val size: Long,
    val sha256: String,
    val mime: String,
    val status: TransferStatus,
    val transferred: Long = 0,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    /** OUT: path of the staged copy we read from. IN: final location once DONE. */
    val ref: String? = null,
    val retries: Int = 0,
    /** Link the transfer last moved over. */
    val via: Transport? = null,
) {
    val progress: Float get() = if (size <= 0) 1f else (transferred.toFloat() / size).coerceIn(0f, 1f)
}

class IntegrityException(message: String) : IOException(message)

/** Reads staged outgoing files. */
interface OutgoingFiles {
    @Throws(IOException::class)
    fun read(record: TransferRecord, offset: Long, length: Int): ByteArray

    /** The transfer reached a final state; delete the staged copy. */
    fun release(record: TransferRecord)
}

/** Writes incoming files: a partial file per transfer id, then a verified final file. */
interface IncomingFiles {
    /** Bytes already on disk for [id], 0 if there is no partial file. */
    fun partialLength(id: String): Long

    @Throws(IOException::class)
    fun begin(record: TransferRecord)

    @Throws(IOException::class)
    fun append(id: String, data: ByteArray)

    /**
     * Verify the partial file against [TransferRecord.sha256] and move it into place.
     * Returns the final location. Throws [IntegrityException] on a hash mismatch.
     */
    @Throws(IOException::class)
    fun finish(record: TransferRecord): String

    fun discard(id: String)
}

interface TransferListener {
    /** [important] is true for status changes; progress-only updates are false. */
    fun onTransferUpdated(record: TransferRecord, important: Boolean)

    fun onTransferRemoved(id: String) {}
}

/**
 * Chunked, resumable, verified file transfer over an ordered message channel.
 *
 * Sender: OFFER -> (ACCEPT offset) -> CHUNK* (windowed by ACK) -> DONE -> (COMPLETE).
 * Receiver persists bytes as they arrive; after a reconnect the sender re-offers the same
 * id and the receiver answers with how much it already has. Single-threaded.
 */
@Suppress("TooManyFunctions")
class TransferEngine(
    private val outgoing: OutgoingFiles,
    private val incoming: IncomingFiles,
    private val listener: TransferListener,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxIncoming: Long = Protocol.FILE_MAX_BYTES_WIFI,
    private val maxOverBle: Long = Protocol.FILE_MAX_BYTES,
) {
    private val records = LinkedHashMap<String, TransferRecord>()
    private var channel: Channel? = null
    private val send: ((Message) -> Unit)? get() = channel?.send
    private val chunkSize: Int get() = if (channel?.transport == Transport.WIFI) Protocol.FILE_CHUNK_WIFI else Protocol.FILE_CHUNK
    private val windowChunks: Int get() = if (channel?.transport == Transport.WIFI) WIFI_WINDOW else DEFAULT_WINDOW

    // Live sender state for the one active outgoing transfer.
    private var activeOut: String? = null
    private var nextOffset = 0L
    private var acked = 0L
    private var doneSent = false

    /** Live receiver byte counts (avoids a stat() per chunk). */
    private val received = HashMap<String, Long>()

    val all: List<TransferRecord> get() = records.values.toList()

    operator fun get(id: String): TransferRecord? = records[id]

    val isConnected: Boolean get() = channel != null

    val transport: Transport? get() = channel?.transport

    /** Load persisted records after a restart. Anything in flight becomes WAITING. */
    fun restore(saved: List<TransferRecord>) {
        for (r in saved) {
            val status = when (r.status) {
                TransferStatus.OFFERED, TransferStatus.ACTIVE, TransferStatus.VERIFYING -> TransferStatus.WAITING
                else -> r.status
            }
            val fixed = if (r.direction == Direction.IN && !status.isFinal) {
                r.copy(status = status, transferred = incoming.partialLength(r.id))
            } else {
                r.copy(status = status)
            }
            records[r.id] = fixed
        }
    }

    fun enqueue(record: TransferRecord) {
        require(record.direction == Direction.OUT) { "only outgoing transfers are enqueued" }
        put(record.copy(status = TransferStatus.QUEUED, transferred = 0), important = true)
        startNextOutgoing()
    }

    /** A link is up; queued and interrupted outgoing transfers start (or resume) on it. */
    fun onConnected(channel: Channel) {
        this.channel = channel
        startNextOutgoing()
    }

    /**
     * Move to a different link (Wi-Fi came up, or Wi-Fi dropped and Bluetooth is still there).
     * The active outgoing transfer is re-offered on [channel] and resumes from the receiver's
     * last durable byte.
     */
    fun switchTo(channel: Channel) {
        onDisconnected()
        onConnected(channel)
    }

    fun onDisconnected() {
        channel = null
        activeOut = null
        for (r in records.values.toList()) {
            if (r.status == TransferStatus.OFFERED || r.status == TransferStatus.ACTIVE ||
                r.status == TransferStatus.VERIFYING
            ) {
                put(r.copy(status = TransferStatus.WAITING), important = true)
            }
        }
    }

    /** Cancel locally and tell the peer. */
    fun cancel(id: String) {
        val r = records[id] ?: return
        if (r.status.isFinal) return
        send?.invoke(FileCancel(id, "user"))
        finishCancelled(r, "Cancelled")
    }

    /** Re-queue a failed outgoing transfer (its staged copy is kept until [remove]). */
    fun retry(id: String) {
        val r = records[id] ?: return
        if (r.direction != Direction.OUT || r.status != TransferStatus.FAILED) return
        // A fresh id, so the receiver never confuses the retry with the transfer it gave up on.
        records.remove(id)
        listener.onTransferRemoved(id)
        val again = r.copy(
            id = UUID.randomUUID().toString(), status = TransferStatus.QUEUED, transferred = 0, error = null,
            retries = 0, createdAt = clock(),
        )
        put(again, important = true)
        startNextOutgoing()
    }

    /** Forget a transfer. Received files stay; an outgoing staged copy is deleted. */
    fun remove(id: String) {
        val r = records[id] ?: return
        if (!r.status.isFinal) cancel(id)
        if (r.direction == Direction.OUT) outgoing.release(r)
        records.remove(id)
        listener.onTransferRemoved(id)
    }

    /**
     * Route a file message that arrived on [from]. Receiver-side replies (ACCEPT, ACK,
     * COMPLETE) go back on the same channel. Returns false for non-file messages.
     */
    @Suppress("CyclomaticComplexMethod")
    fun handle(message: Message, from: Channel): Boolean {
        when (message) {
            is FileOffer -> onOffer(message, from)
            is FileAccept -> onAccept(message)
            is FileReject -> onReject(message)
            is FileChunk -> onChunk(message, from)
            is FileAck -> onAck(message)
            is FileDone -> onDone(message, from)
            is FileComplete -> onComplete(message)
            is FileCancel -> onRemoteCancel(message)
            else -> return false
        }
        return true
    }

    // ---- sender ----

    private fun startNextOutgoing() {
        val ch = channel ?: return
        val tx = ch.send
        if (activeOut != null) return
        val pending = records.values.filter {
            it.direction == Direction.OUT && (it.status == TransferStatus.WAITING || it.status == TransferStatus.QUEUED)
        }
        val next = pending.firstOrNull { ch.transport == Transport.WIFI || it.size <= maxOverBle }
        // Files too big for Bluetooth wait for Wi-Fi instead of crawling over BLE.
        for (r in pending) {
            if (r !== next && ch.transport == Transport.BLE && r.size > maxOverBle && r.error != WAITING_FOR_WIFI) {
                put(r.copy(status = TransferStatus.WAITING, error = WAITING_FOR_WIFI), important = true)
            }
        }
        if (next == null) return
        activeOut = next.id
        nextOffset = 0
        acked = 0
        doneSent = false
        put(next.copy(status = TransferStatus.OFFERED, via = ch.transport, error = null), important = true)
        tx(FileOffer(next.id, next.name, next.size, next.sha256, next.mime))
    }

    private fun onAccept(m: FileAccept) {
        val r = records[m.id]
        if (r == null || r.direction != Direction.OUT || r.status.isFinal || activeOut != m.id) return
        if (m.offset < 0 || m.offset > r.size) return failOut(r, "Peer asked for an invalid offset", notify = true)
        nextOffset = m.offset
        acked = m.offset
        doneSent = false
        put(r.copy(status = TransferStatus.ACTIVE, transferred = m.offset), important = true)
        pump()
    }

    private fun onAck(m: FileAck) {
        val r = records[m.id] ?: return
        if (r.direction != Direction.OUT || activeOut != m.id || r.status.isFinal) return
        if (m.offset > acked && m.offset <= nextOffset) {
            acked = m.offset
            put(r.copy(transferred = acked), important = false)
        }
        pump()
    }

    /** Send chunks while the window allows. */
    private fun pump() {
        val tx = send ?: return
        val id = activeOut ?: return
        var r = records[id] ?: return
        if (r.status != TransferStatus.ACTIVE) return
        val window = chunkSize.toLong() * windowChunks
        while (nextOffset < r.size && nextOffset - acked < window) {
            val len = minOf(chunkSize.toLong(), r.size - nextOffset).toInt()
            val data = try {
                outgoing.read(r, nextOffset, len)
            } catch (e: IOException) {
                tx(FileCancel(id, "source"))
                return failOut(r, "Couldn't read the file (${e.message})", notify = false)
            }
            if (data.size != len) {
                tx(FileCancel(id, "source"))
                return failOut(r, "The file changed while sending", notify = false)
            }
            tx(FileChunk(id, nextOffset, data))
            nextOffset += len
        }
        if (nextOffset >= r.size && !doneSent) {
            doneSent = true
            r = r.copy(status = TransferStatus.VERIFYING)
            put(r, important = true)
            tx(FileDone(id))
        }
    }

    private fun onComplete(m: FileComplete) {
        val r = records[m.id] ?: return
        if (r.direction != Direction.OUT || r.status.isFinal) return
        activeOut = null
        if (m.ok) {
            outgoing.release(r)
            put(r.copy(status = TransferStatus.DONE, transferred = r.size, error = null), important = true)
        } else if (m.reason == "hash" && r.retries < MAX_INTEGRITY_RETRIES) {
            put(r.copy(status = TransferStatus.QUEUED, transferred = 0, retries = r.retries + 1), important = true)
        } else {
            failOut(r, "The Mac/phone couldn't verify the file", notify = false)
        }
        startNextOutgoing()
    }

    private fun onReject(m: FileReject) {
        val r = records[m.id] ?: return
        if (r.direction != Direction.OUT || r.status.isFinal) return
        val why = when (m.reason) {
            "too_large" -> "Too large for the other device"
            "space" -> "Not enough space on the other device"
            else -> "Declined (${m.reason})"
        }
        if (activeOut == m.id) activeOut = null
        failOut(r, why, notify = false)
        startNextOutgoing()
    }

    private fun failOut(r: TransferRecord, why: String, notify: Boolean) {
        if (notify) send?.invoke(FileCancel(r.id, "error"))
        if (activeOut == r.id) activeOut = null
        // The staged copy is kept so the user can retry; remove() deletes it.
        put(r.copy(status = TransferStatus.FAILED, error = why), important = true)
        startNextOutgoing()
    }

    // ---- receiver ----

    private fun onOffer(m: FileOffer, from: Channel) {
        val tx = from.send
        val existing = records[m.id]
        if (existing != null && existing.direction == Direction.IN) {
            when (existing.status) {
                TransferStatus.DONE -> return tx(FileComplete(m.id, ok = true))
                TransferStatus.CANCELLED -> return tx(FileCancel(m.id, "cancelled"))
                else -> Unit
            }
            val resumable = existing.status != TransferStatus.FAILED
            if (resumable && existing.size == m.size && existing.sha256.equals(m.sha256, ignoreCase = true)) {
                val have = incoming.partialLength(m.id).coerceAtMost(m.size)
                if (have == 0L) {
                    try {
                        incoming.begin(existing)
                    } catch (e: IOException) {
                        put(existing.copy(status = TransferStatus.FAILED, error = "Couldn't save (${e.message})"), true)
                        return tx(FileReject(m.id, "space"))
                    }
                }
                received[m.id] = have
                put(existing.copy(status = TransferStatus.ACTIVE, transferred = have, error = null, via = from.transport), true)
                return tx(FileAccept(m.id, have))
            }
            incoming.discard(m.id)
        }
        val now = clock()
        val record = TransferRecord(
            id = m.id, direction = Direction.IN, name = safeFileName(m.name), size = m.size,
            sha256 = m.sha256.lowercase(), mime = m.mime, status = TransferStatus.ACTIVE, createdAt = now,
            via = from.transport,
        )
        if (m.size < 0 || m.size > maxIncoming || !SHA256_HEX.matches(m.sha256)) {
            put(record.copy(status = TransferStatus.FAILED, error = "Too large (limit ${maxIncoming / MIB} MB)"), true)
            return tx(FileReject(m.id, if (m.size > maxIncoming) "too_large" else "invalid"))
        }
        try {
            incoming.begin(record)
        } catch (e: IOException) {
            put(record.copy(status = TransferStatus.FAILED, error = "Couldn't save (${e.message})"), true)
            return tx(FileReject(m.id, "space"))
        }
        received[m.id] = 0
        put(record, important = true)
        tx(FileAccept(m.id, 0))
    }

    private fun onChunk(m: FileChunk, from: Channel) {
        val tx = from.send
        val r = records[m.id]
        if (r == null || r.direction != Direction.IN || r.status != TransferStatus.ACTIVE) {
            if (r == null) tx(FileCancel(m.id, "unknown"))
            return
        }
        val have = received[m.id] ?: incoming.partialLength(m.id)
        val end = m.offset + m.data.size
        if (end > r.size) return failIn(r, "Peer sent more data than offered")
        when {
            m.offset > have -> return tx(FileAccept(m.id, have)) // gap: ask the sender to rewind
            end <= have -> Unit // duplicate after a resync
            else -> try {
                val skip = (have - m.offset).toInt()
                incoming.append(m.id, if (skip == 0) m.data else m.data.copyOfRange(skip, m.data.size))
                received[m.id] = end
            } catch (e: IOException) {
                return failIn(r, "Couldn't write (${e.message})")
            }
        }
        val now = received[m.id] ?: have
        put(r.copy(transferred = now), important = false)
        tx(FileAck(m.id, now))
    }

    private fun onDone(m: FileDone, from: Channel) {
        val tx = from.send
        val r = records[m.id] ?: return
        if (r.direction != Direction.IN || r.status != TransferStatus.ACTIVE) return
        val have = received[m.id] ?: incoming.partialLength(m.id)
        if (have != r.size) return tx(FileAccept(m.id, have))
        put(r.copy(status = TransferStatus.VERIFYING, transferred = have), important = true)
        try {
            val where = incoming.finish(r)
            received.remove(m.id)
            put(r.copy(status = TransferStatus.DONE, transferred = r.size, ref = where, error = null), true)
            tx(FileComplete(m.id, ok = true))
        } catch (e: IntegrityException) {
            incoming.discard(m.id)
            received.remove(m.id)
            put(r.copy(status = TransferStatus.WAITING, transferred = 0, error = e.message), true)
            tx(FileComplete(m.id, ok = false, reason = "hash"))
        } catch (e: IOException) {
            failIn(r, "Couldn't save (${e.message})")
        }
    }

    private fun failIn(r: TransferRecord, why: String) {
        send?.invoke(FileCancel(r.id, "error"))
        incoming.discard(r.id)
        received.remove(r.id)
        put(r.copy(status = TransferStatus.FAILED, error = why), important = true)
    }

    // ---- shared ----

    private fun onRemoteCancel(m: FileCancel) {
        val r = records[m.id] ?: return
        if (r.status.isFinal) return
        val why = if (m.reason == "user") "Cancelled on the other device" else "Stopped by the other device (${m.reason})"
        finishCancelled(r, why)
    }

    private fun finishCancelled(r: TransferRecord, why: String) {
        if (r.direction == Direction.OUT) {
            if (activeOut == r.id) activeOut = null
            outgoing.release(r)
        } else {
            incoming.discard(r.id)
            received.remove(r.id)
        }
        put(r.copy(status = TransferStatus.CANCELLED, error = why), important = true)
        startNextOutgoing()
    }

    private fun put(r: TransferRecord, important: Boolean) {
        val stamped = r.copy(updatedAt = clock())
        records[r.id] = stamped
        listener.onTransferUpdated(stamped, important)
    }

    companion object {
        const val DEFAULT_WINDOW = 4
        const val WIFI_WINDOW = 16
        const val WAITING_FOR_WIFI = "Waiting for Wi-Fi (too large for Bluetooth)"
        const val MAX_INTEGRITY_RETRIES = 1
        private const val MIB = 1024 * 1024
        private const val MAX_NAME = 180
        private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")
        private val UNSAFE = Regex("[\\u0000-\\u001f\\u007f/\\\\:*?\"<>|]")

        /** Strip paths, control and reserved characters; never empty, never hidden. */
        fun safeFileName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            var s = UNSAFE.replace(base, "_").trim().trimStart('.')
            if (s.length > MAX_NAME) {
                val dot = s.lastIndexOf('.')
                val ext = if (dot > 0 && s.length - dot <= EXT_MAX) s.substring(dot) else ""
                s = s.take(MAX_NAME - ext.length) + ext
            }
            return s.ifBlank { "file" }
        }

        private const val EXT_MAX = 16
    }
}
