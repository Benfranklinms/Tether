package dev.benfranklin.tether.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.benfranklin.tether.core.crypto.KeyPair
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.session.PeerRecord
import dev.benfranklin.tether.core.IdentityStore
import dev.benfranklin.tether.core.util.B64
import dev.benfranklin.tether.log.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Crash-safe JSON persistence: [AtomicFile] writes to a temp file and renames, so a crash
 * mid-write leaves the previous version intact.
 */
class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>) {
    private val atomic = AtomicFile(file)

    fun read(): T? = try {
        if (!file.exists()) null else MessageCodec.json.decodeFromString(serializer, String(atomic.readFully(), Charsets.UTF_8))
    } catch (e: IOException) {
        Log.e("storage.read_failed", e, "file" to file.name)
        null
    } catch (e: SerializationException) {
        Log.e("storage.corrupt", e, "file" to file.name)
        null
    } catch (e: IllegalArgumentException) {
        Log.e("storage.corrupt", e, "file" to file.name)
        null
    }

    @Synchronized
    fun write(value: T) {
        var out: FileOutputStream? = null
        try {
            out = atomic.startWrite()
            out.write(MessageCodec.json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: IOException) {
            out?.let { atomic.failWrite(it) }
            Log.e("storage.write_failed", e, "file" to file.name)
        }
    }

    fun delete() = atomic.delete()
}

/**
 * Wraps secrets with an AES-GCM key held in the Android Keystore (never exported), so the
 * X25519 private key on disk is useless without this device's secure hardware/TEE.
 */
object KeystoreWrap {
    private const val ALIAS = "tether.identity.wrap"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return gen.generateKey()
    }

    fun seal(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return B64.encode(c.iv + c.doFinal(plain))
    }

    fun open(sealed: String): ByteArray? = try {
        val all = B64.decode(sealed) ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, all, 0, IV_LEN))
        c.doFinal(all, IV_LEN, all.size - IV_LEN)
    } catch (e: java.security.GeneralSecurityException) {
        Log.e("keystore.open_failed", e)
        null
    }
}

@Serializable
private data class IdentityFile(
    val key: String,
    val pendingKey: String? = null,
    val peer: PeerRecord? = null,
)

/** [IdentityStore] backed by an atomic JSON file with Keystore-wrapped private keys. */
class FileIdentityStore(context: Context) : IdentityStore {
    private val file = JsonFile(File(context.filesDir, "identity.json"), IdentityFile.serializer())
    private var state: IdentityFile = load()

    private fun load(): IdentityFile {
        val saved = file.read()
        val key = saved?.key?.let { KeystoreWrap.open(it) }
        if (saved != null && key != null) return saved
        if (saved != null) Log.w("identity.reset", "reason" to "unreadable key")
        val fresh = IdentityFile(KeystoreWrap.seal(KeyPair.generate().privateKey))
        file.write(fresh)
        return fresh
    }

    @get:Synchronized @set:Synchronized
    override var localKey: KeyPair
        get() = KeyPair.fromPrivate(checkNotNull(KeystoreWrap.open(state.key)))
        set(value) {
            state = state.copy(key = KeystoreWrap.seal(value.privateKey))
            file.write(state)
        }

    @get:Synchronized @set:Synchronized
    override var pendingLocalKey: KeyPair?
        get() = state.pendingKey?.let { KeystoreWrap.open(it) }?.let { KeyPair.fromPrivate(it) }
        set(value) {
            state = state.copy(pendingKey = value?.let { KeystoreWrap.seal(it.privateKey) })
            file.write(state)
        }

    @get:Synchronized @set:Synchronized
    override var peer: PeerRecord?
        get() = state.peer
        set(value) {
            state = state.copy(peer = value)
            file.write(state)
        }
}
