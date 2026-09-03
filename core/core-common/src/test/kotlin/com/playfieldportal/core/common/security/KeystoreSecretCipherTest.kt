package com.playfieldportal.core.common.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the sealing outcome only. The Keystore itself is not exercised — [KeystoreSecretCipher]
 * delegates to an injected sealer here so the *failure* path, which is the whole point of
 * [SealedSecret], is reachable without secure hardware.
 */
class KeystoreSecretCipherTest {

    @Test
    fun `successful seal reports the value as protected`() {
        val result = KeystoreSecretCipher.sealWith("hunter2") { "sealed:$it" }

        assertTrue(result is SealedSecret.Sealed)
        assertEquals("sealed:hunter2", result.stored)
        assertTrue(result.isProtected)
    }

    @Test
    fun `keystore failure reports unprotected rather than silently returning plaintext`() {
        val boom = IllegalStateException("keystore unavailable")

        val result = KeystoreSecretCipher.sealWith("hunter2") { throw boom }

        assertTrue(result is SealedSecret.Unprotected)
        // The user's input is never lost — but the caller can now see that it is not protected.
        assertEquals("hunter2", result.stored)
        assertEquals(false, result.isProtected)
        assertSame(boom, (result as SealedSecret.Unprotected).cause)
    }

    @Test
    fun `unprotected outcome survives being treated as a plain stored value`() {
        // Callers that ignore the distinction still store something usable; the regression this
        // guards is a future change that returns null or an empty string on keystore failure.
        val result = KeystoreSecretCipher.sealWith("  spaced  ") { throw RuntimeException() }

        assertEquals("  spaced  ", result.stored)
    }
}
