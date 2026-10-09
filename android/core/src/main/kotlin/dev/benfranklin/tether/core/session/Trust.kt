package dev.benfranklin.tether.core.session

import dev.benfranklin.tether.core.util.B64
import dev.benfranklin.tether.core.util.constantTimeEquals
import kotlinx.serialization.Serializable

/**
 * The one device this install is paired with.
 *
 * Key rotation is two-phase so a lost acknowledgement can never lock the pair out:
 * 1. The rotating side keeps using its old key and sends KEY_ROTATE(new).
 * 2. The peer stores `new` as [pendingKey] (both keys now trusted) and replies KEY_ROTATE_ACK.
 * 3. On the ack the rotating side switches to `new` and reconnects.
 * 4. The first handshake that presents [pendingKey] promotes it and forgets the old key.
 */
@Serializable
data class PeerRecord(
    val name: String,
    val platform: String,
    val key: String,
    val pendingKey: String? = null,
    val pairedAt: Long,
) {
    fun trusts(remote: ByteArray): Boolean =
        matches(key, remote) || (pendingKey != null && matches(pendingKey, remote))

    /** Call after a successful handshake with [remote]. */
    fun afterHandshake(remote: ByteArray, remoteName: String): PeerRecord = when {
        pendingKey != null && matches(pendingKey, remote) -> copy(key = pendingKey, pendingKey = null, name = remoteName)
        else -> copy(name = remoteName)
    }

    fun withPendingKey(newKey: ByteArray): PeerRecord = copy(pendingKey = B64.encode(newKey))

    val keyBytes: ByteArray get() = checkNotNull(B64.decode(key))

    private fun matches(stored: String, remote: ByteArray): Boolean {
        val bytes = B64.decode(stored) ?: return false
        return constantTimeEquals(bytes, remote)
    }

    companion object {
        fun paired(name: String, platform: String, key: ByteArray, now: Long) =
            PeerRecord(name = name, platform = platform, key = B64.encode(key), pairedAt = now)
    }
}
