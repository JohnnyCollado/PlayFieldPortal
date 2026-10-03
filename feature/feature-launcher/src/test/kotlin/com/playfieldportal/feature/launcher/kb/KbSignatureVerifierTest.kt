package com.playfieldportal.feature.launcher.kb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * The signature check. Fixtures are signed by the JDK's own Ed25519, so Tink (the
 * production verifier) is cross-checked against a second implementation.
 */
class KbSignatureVerifierTest {

    private val body = """{"format":"pfp-emulator-kb","schemaVersion":1}""".toByteArray()

    private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** X.509 SubjectPublicKeyInfo for Ed25519 is a 12-byte header followed by the raw 32-byte key. */
    private fun rawPublicKey(pair: KeyPair): ByteArray = pair.public.encoded.takeLast(32).toByteArray()

    private fun sign(pair: KeyPair, bytes: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(bytes)
            sign()
        }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `a body signed by a pinned key verifies`() {
        val pair = newKeyPair()
        val verifier = KbSignatureVerifier(listOf(rawPublicKey(pair)))
        assertTrue(verifier.verify(body, b64(sign(pair, body))))
    }

    @Test
    fun `a flipped body byte fails`() {
        val pair = newKeyPair()
        val sig = b64(sign(pair, body))
        val tampered = body.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertFalse(KbSignatureVerifier(listOf(rawPublicKey(pair))).verify(tampered, sig))
    }

    @Test
    fun `a flipped signature byte fails`() {
        val pair = newKeyPair()
        val sig = sign(pair, body).also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFalse(KbSignatureVerifier(listOf(rawPublicKey(pair))).verify(body, b64(sig)))
    }

    @Test
    fun `a key that did not sign fails`() {
        val signer = newKeyPair()
        val other = newKeyPair()
        assertFalse(KbSignatureVerifier(listOf(rawPublicKey(other))).verify(body, b64(sign(signer, body))))
    }

    @Test
    fun `any pinned key may verify, so the second of two works`() {
        val first = newKeyPair()
        val second = newKeyPair()
        val verifier = KbSignatureVerifier(listOf(rawPublicKey(first), rawPublicKey(second)))
        assertTrue(verifier.verify(body, b64(sign(second, body))))
        assertTrue(verifier.verify(body, b64(sign(first, body))))
    }

    @Test
    fun `an empty key list never verifies`() {
        val pair = newKeyPair()
        assertFalse(KbSignatureVerifier(emptyList()).verify(body, b64(sign(pair, body))))
    }

    @Test
    fun `the production key list ships empty, which disables updates`() {
        assertTrue(KbSignatureVerifier.PINNED_KEYS.isEmpty())
        assertFalse(KbSignatureVerifier().hasPinnedKeys)
    }

    @Test
    fun `a malformed key is skipped and does not block a valid one`() {
        val pair = newKeyPair()
        val verifier = KbSignatureVerifier(listOf(ByteArray(7), rawPublicKey(pair)))
        assertTrue(verifier.verify(body, b64(sign(pair, body))))
    }

    @Test
    fun `garbage and wrong-length signatures fail without throwing`() {
        val pair = newKeyPair()
        val verifier = KbSignatureVerifier(listOf(rawPublicKey(pair)))
        assertFalse(verifier.verify(body, "!!! not base64 !!!"))
        assertFalse(verifier.verify(body, ""))
        assertFalse(verifier.verify(body, b64(ByteArray(63))))
        assertFalse(verifier.verify(body, b64(ByteArray(65))))
        assertFalse(verifier.verify(body, b64(ByteArray(64))))
    }

    /**
     * A fixture produced by `tools/emulator-kb/KbSign.java sign` with a throwaway key
     * whose private half was discarded. Proves the offline tool and the Tink verifier agree.
     */
    @Test
    fun `a fixture signed by KbSign verifies and fails once edited`() {
        fun resource(name: String): ByteArray =
            requireNotNull(javaClass.getResourceAsStream("/kb_sign/$name")) { name }.readBytes()

        val fixture = resource("fixture.json")
        val sig = String(resource("fixture.json.sig")).trim()
        val key = Base64.getDecoder().decode(String(resource("fixture.pub")).trim())
        val verifier = KbSignatureVerifier(listOf(key))

        assertTrue(verifier.verify(fixture, sig))
        val edited = fixture.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(verifier.verify(edited, sig))
    }
}
