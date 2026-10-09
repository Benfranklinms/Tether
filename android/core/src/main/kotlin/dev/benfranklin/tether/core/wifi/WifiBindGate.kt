package dev.benfranklin.tether.core.wifi

import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.core.proto.WifiBind
import dev.benfranklin.tether.core.util.B64
import dev.benfranklin.tether.core.util.constantTimeEquals
import java.security.SecureRandom

/**
 * Phone side of the Wi-Fi fast path. Each WIFI_OFFER carries a fresh 16-byte token; the
 * first message on the TCP session must be WIFI_BIND with that token, and a token works once.
 * Together with the Noise handshake (only the paired Mac's static key is accepted) this ties
 * the TCP session to the Bluetooth session that advertised it.
 */
class WifiBindGate(private val random: SecureRandom = SecureRandom()) {
    private var current: ByteArray? = null

    /** Mint a new single-use token (invalidates the previous one). Base64url. */
    fun mint(): String {
        val t = ByteArray(TOKEN_LEN).also { random.nextBytes(it) }
        current = t
        return B64.encode(t)
    }

    fun revoke() {
        current = null
    }

    val hasToken: Boolean get() = current != null

    /** Check the first message of a TCP session. Consumes the token on success. */
    fun admit(first: Message): Boolean {
        val expected = current ?: return false
        val bind = first as? WifiBind ?: return false
        val got = B64.decode(bind.token) ?: return false
        if (!constantTimeEquals(expected, got)) return false
        current = null
        return true
    }

    companion object {
        const val TOKEN_LEN = 16
    }
}
