package dev.benfranklin.tether.v6

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.benfranklin.tether.log.Log
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encryption at rest for 0.5.2 data (Shelf, the saved hotspot password): AES-256-GCM with a key
 * that never leaves the Android Keystore. Blob = 12-byte IV + ciphertext + tag.
 */
object Vault {
    private const val ALIAS = "tether.vault"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val PREFS = "tether_secure"

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

    fun seal(plain: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        c.iv + c.doFinal(plain)
    } catch (e: GeneralSecurityException) {
        Log.w("vault.seal_failed", "error" to e.javaClass.simpleName)
        null
    }

    fun open(blob: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
        c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    } catch (e: GeneralSecurityException) {
        Log.w("vault.open_failed", "error" to e.javaClass.simpleName)
        null
    } catch (e: IllegalArgumentException) {
        Log.w("vault.open_failed", "error" to e.javaClass.simpleName)
        null
    }

    fun writeFile(f: File, plain: ByteArray): Boolean {
        val blob = seal(plain) ?: return false
        return try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(blob)
            tmp.renameTo(f)
        } catch (e: IOException) {
            Log.w("vault.write_failed", "error" to e.message)
            false
        }
    }

    fun readFile(f: File): ByteArray? = if (f.exists()) open(f.readBytes()) else null

    fun putString(c: Context, key: String, value: String?) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (value.isNullOrEmpty()) {
            p.remove(key)
        } else {
            seal(value.toByteArray(Charsets.UTF_8))?.let { p.putString(key, Base64.encodeToString(it, Base64.NO_WRAP)) }
        }
        p.apply()
    }

    fun getString(c: Context, key: String): String? {
        val b64 = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return null
        return open(Base64.decode(b64, Base64.NO_WRAP))?.toString(Charsets.UTF_8)
    }
}

/** The hotspot name and password the user saved for Instant Hotspot (Android hides the real ones from apps). */
object HotspotStore {
    private const val SSID = "hotspot_ssid"
    private const val PSK = "hotspot_psk"

    fun ssid(c: Context): String? = Vault.getString(c, SSID)
    fun psk(c: Context): String? = Vault.getString(c, PSK)

    fun save(c: Context, ssid: String, psk: String?) {
        Vault.putString(c, SSID, ssid.trim())
        Vault.putString(c, PSK, psk)
        Log.i("hotspot.saved", "ssid" to true, "psk" to !psk.isNullOrEmpty())
    }

    fun clear(c: Context) {
        Vault.putString(c, SSID, null)
        Vault.putString(c, PSK, null)
    }
}
