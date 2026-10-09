package dev.benfranklin.tether.core.util

import java.security.MessageDigest
import java.util.Base64

object B64 {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Decodes base64url with or without padding. Returns null for malformed input. */
    fun decode(s: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(s.trimEnd('='))
    } catch (_: IllegalArgumentException) {
        null
    }
}

object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(DIGITS[v ushr 4])
            append(DIGITS[v and 0x0F])
        }
    }

    fun decode(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { i ->
            ((Character.digit(s[2 * i], 16) shl 4) or Character.digit(s[2 * i + 1], 16)).toByte()
        }
    }
}

/** Constant-time comparison for secrets (pairing tokens, keys). */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

/** Short, stable, human-readable fingerprint of a static key, e.g. `3F9A·0C21·77B4`. */
fun fingerprint(publicKey: ByteArray): String {
    val h = MessageDigest.getInstance("SHA-256").digest(publicKey)
    return Hex.encode(h.copyOf(6)).uppercase().chunked(4).joinToString("·")
}
