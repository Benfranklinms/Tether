package dev.benfranklin.tether.v5

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.benfranklin.tether.core.features.GitHubReleases
import dev.benfranklin.tether.log.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The GitHub update token, encrypted with an AES-256-GCM key that never leaves the Android
 * Keystore, stored in private prefs. The token is never logged (only "set (n chars)").
 */
object TokenStore {
    private const val ALIAS = "tether.github-token"
    private const val PREFS = "tether_secure"
    private const val KEY = "github_token"
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

    fun save(c: Context, token: String): Boolean {
        val t = token.trim()
        if (!GitHubReleases.looksLikeToken(t)) return false
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val blob = cipher.iv + cipher.doFinal(t.toByteArray(Charsets.UTF_8))
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
            Log.i("update.token_saved", "token" to GitHubReleases.redact(t))
            true
        } catch (e: GeneralSecurityException) {
            Log.w("update.token_save_failed", "error" to e.javaClass.simpleName)
            false
        }
    }

    fun read(c: Context): String? {
        val b64 = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
            String(cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            Log.w("update.token_read_failed", "error" to e.javaClass.simpleName)
            null
        } catch (e: IllegalArgumentException) {
            Log.w("update.token_read_failed", "error" to e.javaClass.simpleName)
            null
        }
    }

    fun has(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY)

    fun clear(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
