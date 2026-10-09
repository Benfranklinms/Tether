package dev.benfranklin.tether.core.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Noise Protocol Framework, `Noise_XX_25519_ChaChaPoly_SHA256`.
 *
 * Written from the Noise specification (revision 34). The structure (CipherState /
 * SymmetricState / HandshakeState) mirrors bitchat's public-domain Swift implementation
 * so the macOS and Android sides read the same way. Verified against the cacophony
 * test vectors in `protocol/vectors/noise_xx.json`.
 */
class NoiseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** An X25519 key pair. [publicKey] and [privateKey] are 32 raw bytes. */
class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {
    init {
        require(privateKey.size == KEY_LEN && publicKey.size == KEY_LEN) { "X25519 keys are 32 bytes" }
    }

    companion object {
        const val KEY_LEN = 32

        fun generate(random: SecureRandom = SecureRandom()): KeyPair {
            val priv = ByteArray(KEY_LEN)
            X25519.generatePrivateKey(random, priv)
            return fromPrivate(priv)
        }

        fun fromPrivate(privateKey: ByteArray): KeyPair {
            val pub = ByteArray(KEY_LEN)
            X25519.generatePublicKey(privateKey, 0, pub, 0)
            return KeyPair(privateKey.copyOf(), pub)
        }
    }
}

internal object Primitives {
    const val HASH_LEN = 32
    const val TAG_LEN = 16
    private const val NONCE_LEN = 12
    private const val NONCE_OFFSET = 4
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFF

    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        if (publicKey.size != KeyPair.KEY_LEN) throw NoiseException("bad public key length")
        val out = ByteArray(KeyPair.KEY_LEN)
        // calculateAgreement returns false for low-order points (all-zero output).
        if (!X25519.calculateAgreement(privateKey, 0, publicKey, 0, out, 0)) {
            throw NoiseException("invalid public key")
        }
        return out
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
        val temp = hmac(chainingKey, ikm)
        val result = ArrayList<ByteArray>(outputs)
        var prev = ByteArray(0)
        for (i in 1..outputs) {
            prev = hmac(temp, prev, byteArrayOf(i.toByte()))
            result += prev
        }
        return result
    }

    private fun nonce(n: Long): ByteArray {
        val out = ByteArray(NONCE_LEN)
        for (i in 0 until BYTE_BITS) out[NONCE_OFFSET + i] = ((n ushr (BYTE_BITS * i)) and BYTE_MASK.toLong()).toByte()
        return out
    }

    fun encrypt(key: ByteArray, n: Long, ad: ByteArray, plaintext: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), TAG_LEN * BYTE_BITS, nonce(n), ad))
        val out = ByteArray(c.getOutputSize(plaintext.size))
        val len = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        c.doFinal(out, len)
        return out
    }

    fun decrypt(key: ByteArray, n: Long, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < TAG_LEN) throw NoiseException("ciphertext too short")
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), TAG_LEN * BYTE_BITS, nonce(n), ad))
        val out = ByteArray(c.getOutputSize(ciphertext.size))
        return try {
            val len = c.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            c.doFinal(out, len)
            out
        } catch (e: InvalidCipherTextException) {
            throw NoiseException("authentication failed", e)
        }
    }
}

/** Noise CipherState with an implicit 64-bit counter nonce (transport is ordered). */
class CipherState internal constructor(private var key: ByteArray? = null) {
    private var n: Long = 0

    val hasKey: Boolean get() = key != null

    internal fun initializeKey(k: ByteArray) {
        key = k.copyOf()
        n = 0
    }

    fun encrypt(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext
        if (n == MAX_NONCE) throw NoiseException("nonce exhausted")
        return Primitives.encrypt(k, n, ad, plaintext).also { n++ }
    }

    fun decrypt(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val k = key ?: return ciphertext
        if (n == MAX_NONCE) throw NoiseException("nonce exhausted")
        // Only advance the nonce after a successful open, so a forged record cannot desync us.
        return Primitives.decrypt(k, n, ad, ciphertext).also { n++ }
    }

    fun encrypt(plaintext: ByteArray): ByteArray = encrypt(EMPTY, plaintext)
    fun decrypt(ciphertext: ByteArray): ByteArray = decrypt(EMPTY, ciphertext)

    fun destroy() {
        key?.fill(0)
        key = null
    }

    private companion object {
        const val MAX_NONCE = -1L // 2^64 - 1 as unsigned
        val EMPTY = ByteArray(0)
    }
}

internal class SymmetricState(protocolName: String) {
    private val cipher = CipherState()
    private var ck: ByteArray
    var h: ByteArray
        private set

    init {
        val name = protocolName.toByteArray(Charsets.US_ASCII)
        h = if (name.size <= Primitives.HASH_LEN) name.copyOf(Primitives.HASH_LEN) else Primitives.sha256(name)
        ck = h.copyOf()
    }

    val hasKey get() = cipher.hasKey

    fun mixKey(ikm: ByteArray) {
        val (newCk, k) = Primitives.hkdf(ck, ikm, 2)
        ck = newCk
        cipher.initializeKey(k)
    }

    fun mixHash(data: ByteArray) {
        h = Primitives.sha256(h, data)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val c = cipher.encrypt(h, plaintext)
        mixHash(c)
        return c
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val p = cipher.decrypt(h, ciphertext)
        mixHash(ciphertext)
        return p
    }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = Primitives.hkdf(ck, ByteArray(0), 2)
        ck.fill(0)
        cipher.destroy()
        return CipherState(k1) to CipherState(k2)
    }
}

enum class Role { INITIATOR, RESPONDER }

/** Result of a completed handshake. */
class HandshakeResult(
    val send: CipherState,
    val receive: CipherState,
    val handshakeHash: ByteArray,
    val remoteStatic: ByteArray,
)

/**
 * Noise XX handshake:
 * ```
 * -> e
 * <- e, ee, s, es
 * -> s, se
 * ```
 */
class HandshakeState(
    val role: Role,
    private val localStatic: KeyPair,
    prologue: ByteArray,
    private val random: SecureRandom = SecureRandom(),
    /** Fixed ephemeral private key. Test vectors only. */
    private val fixedEphemeral: ByteArray? = null,
) {
    private val ss = SymmetricState(PROTOCOL_NAME)
    private var e: KeyPair? = null
    private var re: ByteArray? = null
    private var rs: ByteArray? = null
    private var step = 0

    init {
        ss.mixHash(prologue)
    }

    val isComplete: Boolean get() = step >= PATTERNS.size

    /** True when it is our turn to call [writeMessage]. */
    val isMyTurn: Boolean
        get() = !isComplete && ((step % 2 == 0) == (role == Role.INITIATOR))

    val messageIndex: Int get() = step

    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        if (!isMyTurn) throw NoiseException("not our turn to write")
        val out = java.io.ByteArrayOutputStream()
        for (token in PATTERNS[step]) {
            when (token) {
                Token.E -> {
                    val kp = fixedEphemeral?.let { KeyPair.fromPrivate(it) } ?: KeyPair.generate(random)
                    e = kp
                    out.write(kp.publicKey)
                    ss.mixHash(kp.publicKey)
                }
                Token.S -> out.write(ss.encryptAndHash(localStatic.publicKey))
                else -> dhToken(token)
            }
        }
        out.write(ss.encryptAndHash(payload))
        step++
        return out.toByteArray()
    }

    fun readMessage(message: ByteArray): ByteArray {
        if (isComplete || isMyTurn) throw NoiseException("not our turn to read")
        var offset = 0
        fun take(n: Int): ByteArray {
            if (message.size - offset < n) throw NoiseException("handshake message truncated")
            return message.copyOfRange(offset, offset + n).also { offset += n }
        }
        for (token in PATTERNS[step]) {
            when (token) {
                Token.E -> {
                    val pub = take(KeyPair.KEY_LEN)
                    re = pub
                    ss.mixHash(pub)
                }
                Token.S -> {
                    val len = KeyPair.KEY_LEN + if (ss.hasKey) Primitives.TAG_LEN else 0
                    rs = ss.decryptAndHash(take(len))
                }
                else -> dhToken(token)
            }
        }
        val payload = ss.decryptAndHash(message.copyOfRange(offset, message.size))
        step++
        return payload
    }

    fun split(): HandshakeResult {
        if (!isComplete) throw NoiseException("handshake not complete")
        val hash = ss.h.copyOf()
        val (c1, c2) = ss.split()
        val remote = rs ?: throw NoiseException("no remote static key")
        e?.privateKey?.fill(0)
        return if (role == Role.INITIATOR) {
            HandshakeResult(c1, c2, hash, remote)
        } else {
            HandshakeResult(c2, c1, hash, remote)
        }
    }

    private fun dhToken(token: Token) {
        val initiator = role == Role.INITIATOR
        val shared = when (token) {
            Token.EE -> Primitives.dh(need(e).privateKey, need(re))
            Token.ES -> if (initiator) {
                Primitives.dh(need(e).privateKey, need(rs))
            } else {
                Primitives.dh(localStatic.privateKey, need(re))
            }
            Token.SE -> if (initiator) {
                Primitives.dh(localStatic.privateKey, need(re))
            } else {
                Primitives.dh(need(e).privateKey, need(rs))
            }
            else -> throw NoiseException("unexpected token $token")
        }
        ss.mixKey(shared)
        shared.fill(0)
    }

    private fun <T> need(v: T?): T = v ?: throw NoiseException("missing key for DH")

    private enum class Token { E, S, EE, ES, SE }

    companion object {
        const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
        private val PATTERNS = listOf(
            listOf(Token.E),
            listOf(Token.E, Token.EE, Token.S, Token.ES),
            listOf(Token.S, Token.SE),
        )
    }
}
