package com.playfieldportal.core.common.security

import android.util.Base64
import timber.log.Timber

/**
 * Encrypts small secrets (the user's artwork-API keys) at rest via [KeystoreAesGcm] — the key
 * material never leaves secure hardware, so even on a rooted device the stored DataStore value is
 * not directly usable. Stored form is Base64(iv ‖ ciphertext).
 *
 * [decryptOrLegacy] returns the original string when decryption fails, so values written before
 * encryption was introduced (plain text) keep working until they're next saved (and re-encrypted).
 */
object KeystoreSecretCipher {

    private val aesGcm = KeystoreAesGcm("pfp_secret_key_v1")

    fun encrypt(plain: String): String {
        return try {
            aesGcm.seal(plain)
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
        if (data.size <= KeystoreAesGcm.IV_BYTES) return true // too short to be iv‖ciphertext → legacy plaintext
        return runCatching { aesGcm.open(stored) }.isSuccess
    }

    /** Decrypts a value produced by [encrypt]; returns [stored] unchanged if it isn't ours. */
    fun decryptOrLegacy(stored: String): String {
        return try {
            aesGcm.open(stored)
        } catch (e: Exception) {
            // Not our ciphertext (e.g. a legacy plaintext key) — return it verbatim.
            stored
        }
    }
}
