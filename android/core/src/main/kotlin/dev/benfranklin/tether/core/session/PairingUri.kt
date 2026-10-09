package dev.benfranklin.tether.core.session

import dev.benfranklin.tether.core.crypto.KeyPair
import dev.benfranklin.tether.core.util.B64
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Contents of the QR code the Mac shows while pairing:
 * `tether://pair?v=1&k=<static key, base64url>&t=<one-time token, base64url>&n=<Mac name>`.
 */
data class PairingUri(val key: ByteArray, val token: ByteArray, val name: String) {
    fun encode(): String =
        "tether://pair?v=1&k=${B64.encode(key)}&t=${B64.encode(token)}&n=${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}"

    override fun equals(other: Any?) =
        other is PairingUri && other.key.contentEquals(key) && other.token.contentEquals(token) && other.name == name

    override fun hashCode() = (key.contentHashCode() * PRIME + token.contentHashCode()) * PRIME + name.hashCode()

    companion object {
        private const val PRIME = 31
        const val TOKEN_LEN = 16

        /** Returns null for anything that isn't a well-formed v1 Tether pairing code. */
        fun parse(raw: String): PairingUri? {
            val s = raw.trim()
            if (!s.startsWith("tether://pair?")) return null
            val params = s.substringAfter('?').split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }.toMap()
            if (params["v"] != "1") return null
            val key = params["k"]?.let { B64.decode(it) }?.takeIf { it.size == KeyPair.KEY_LEN } ?: return null
            val token = params["t"]?.let { B64.decode(it) }?.takeIf { it.size == TOKEN_LEN } ?: return null
            val name = try {
                URLDecoder.decode(params["n"].orEmpty(), "UTF-8")
            } catch (_: IllegalArgumentException) {
                return null
            }
            return PairingUri(key, token, name.ifBlank { "Mac" })
        }
    }
}
