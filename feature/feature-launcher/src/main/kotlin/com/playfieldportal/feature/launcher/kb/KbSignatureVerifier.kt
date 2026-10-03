package com.playfieldportal.feature.launcher.kb

import com.google.crypto.tink.subtle.Ed25519Verify
import java.util.Base64

/**
 * Checks a detached Ed25519 signature over the exact bytes of an official knowledge file (AD-7).
 *
 * The app pins a list of raw 32-byte public keys and accepts a file when any one of them verifies
 * it, which allows key rotation. An empty list verifies nothing, so official updates stay disabled
 * until a key is pinned rather than running unverified.
 *
 * Only [Ed25519Verify] is used: the platform has no Ed25519 at minSdk 29, and hand-written
 * cryptography is not acceptable.
 */
class KbSignatureVerifier(private val pinnedKeys: List<ByteArray> = PINNED_KEYS) {

    /** False while no key is pinned, in which case [verify] always returns false. */
    val hasPinnedKeys: Boolean get() = pinnedKeys.isNotEmpty()

    /**
     * True when [sigBase64] (base64 of the 64-byte signature) is a valid signature of [body] under
     * any pinned key. Malformed base64, a wrong-length signature or a malformed key never throws;
     * they simply do not verify.
     */
    fun verify(body: ByteArray, sigBase64: String): Boolean {
        val signature = try {
            Base64.getDecoder().decode(sigBase64.trim())
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (signature.size != SIGNATURE_BYTES) return false
        return pinnedKeys.any { key -> verifiesWith(key, signature, body) }
    }

    private fun verifiesWith(key: ByteArray, signature: ByteArray, body: ByteArray): Boolean {
        if (key.size != KEY_BYTES) return false
        return try {
            Ed25519Verify(key).verify(signature, body)
            true
        } catch (_: java.security.GeneralSecurityException) {
            false
        }
    }

    companion object {
        private const val KEY_BYTES = 32
        private const val SIGNATURE_BYTES = 64

        /**
         * The production keys, raw 32-byte Ed25519 public keys. **Empty until the release key is
         * generated (see tools/emulator-kb/README.md)**, which disables official updates.
         */
        val PINNED_KEYS: List<ByteArray> = emptyList()
    }
}
