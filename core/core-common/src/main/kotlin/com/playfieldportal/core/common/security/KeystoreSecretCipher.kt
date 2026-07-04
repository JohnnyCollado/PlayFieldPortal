package com.playfieldportal.core.common.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (the user's artwork-API keys) at rest using an AES-256/GCM key held in the
 * Android Keystore — the key material never leaves secure hardware, so even on a rooted device the
 * stored DataStore value is not directly usable. Stored form is Base64(iv ‖ ciphertext).
 *
 * [decryptOrLegacy] returns the original string when decryption fails, so values written before
 * encryption was introduced (plain text) keep working until they're next saved (and re-encrypted).
 */
object KeystoreSecretCipher {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "pfp_secret_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    fun encrypt(plain: String): String {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Never lose the user's input if the keystore is unavailable — fall back to plaintext.
            Timber.w(e, "Secret encryption failed; storing as-is")
            plain
        }
    }

    /**
     * Whether [stored] can actually be used on this device. A secret is encrypted with a
     * device-bound, non-exportable Keystore key, so a value carried in a backup and restored onto a
     * different device (or after this app's Keystore key was lost on reinstall) can no longer be
     * decrypted — keeping it would silently feed garbage to the API. This returns false only in that
     * case, so the restore path can drop the value and let the user re-enter it.
     *
     * A value that clearly isn't our ciphertext (a legacy plaintext key) returns true — it's usable
     * as-is. Note a legacy plaintext key that happens to be valid Base64 may be conservatively
     * reported unusable; the only consequence is a harmless re-prompt.
     */
    fun isUsableOnThisDevice(stored: String): Boolean {
        val data = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return true // not Base64 → legacy plaintext, usable verbatim
        }
        if (data.size <= IV_LENGTH) return true // too short to be our iv‖ciphertext → legacy plaintext
        return try {
            val iv = data.copyOfRange(0, IV_LENGTH)
            val ct = data.copyOfRange(IV_LENGTH, data.size)
            Cipher.getInstance(TRANSFORMATION)
                .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
                .doFinal(ct)
            true
        } catch (_: Exception) {
            false // our-format ciphertext that won't decrypt here → not usable on this device
        }
    }

    /** Decrypts a value produced by [encrypt]; returns [stored] unchanged if it isn't ours. */
    fun decryptOrLegacy(stored: String): String {
        return try {
            val data = Base64.decode(stored, Base64.NO_WRAP)
            if (data.size <= IV_LENGTH) return stored
            val iv = data.copyOfRange(0, IV_LENGTH)
            val ct = data.copyOfRange(IV_LENGTH, data.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
                .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            // Not our ciphertext (e.g. a legacy plaintext key) — return it verbatim.
            stored
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
