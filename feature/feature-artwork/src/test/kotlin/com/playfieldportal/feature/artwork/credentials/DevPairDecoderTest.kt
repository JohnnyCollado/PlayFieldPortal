package com.playfieldportal.feature.artwork.credentials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bundled developer pair rides through a Gradle XOR encoder (see
 * feature-artwork/build.gradle.kts) and [DevPairDecoder] on the way back. The Gradle side and the
 * decoder must agree exactly or the provider silently turns off, so the round-trip is pinned here
 * with a mirrored implementation of the Gradle key derivation.
 */
class DevPairDecoderTest {

    // Mirrors the Gradle encoder: SHA-256(salt + propName) as the XOR keystream. If the Gradle
    // derivation ever changes, this mirror must change with it — the tests fail first.
    private fun gradleKey(salt: String, propName: String): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(salt.toByteArray(Charsets.UTF_8) + propName.toByteArray(Charsets.UTF_8))

    private fun encode(value: String, salt: String, propName: String): Pair<ByteArray, ByteArray> {
        val key = gradleKey(salt, propName)
        val plain = value.toByteArray(Charsets.UTF_8)
        val share = ByteArray(plain.size) { i -> (plain[i].toInt() xor key[i % key.size].toInt()).toByte() }
        val mask = ByteArray(plain.size) { i -> key[i % key.size] }
        return share to mask
    }

    @Test
    fun `round-trips a developer id through the gradle-style encoding`() {
        val (share, mask) = encode("mydevid123", "salt-1", "screenscraper.devId")

        assertEquals("mydevid123", DevPairDecoder.decode(share, mask))
    }

    @Test
    fun `round-trips a password containing non-ascii bytes`() {
        val value = "pässwörd!€"
        val (share, mask) = encode(value, "salt-1", "screenscraper.devPassword")

        assertEquals(value, DevPairDecoder.decode(share, mask))
    }

    @Test
    fun `long values wrap around the 32-byte keystream`() {
        val value = "a".repeat(200)
        val (share, mask) = encode(value, "salt-1", "screenscraper.devId")

        assertEquals(value, DevPairDecoder.decode(share, mask))
    }

    @Test
    fun `different salts produce different ciphertexts for the same value`() {
        val (share1, mask1) = encode("secret", "salt-1", "screenscraper.devId")
        val (share2, mask2) = encode("secret", "salt-2", "screenscraper.devId")

        org.junit.Assert.assertFalse(share1.contentEquals(share2))
        assertEquals("secret", DevPairDecoder.decode(share1, mask1))
        assertEquals("secret", DevPairDecoder.decode(share2, mask2))
    }

    @Test
    fun `empty or null arrays decode to null - the bundled pair is absent`() {
        assertNull(DevPairDecoder.decode(byteArrayOf(), byteArrayOf()))
        assertNull(DevPairDecoder.decode(null, null))
        assertNull(DevPairDecoder.decode(null, byteArrayOf(1)))
        assertNull(DevPairDecoder.decode(byteArrayOf(1), null))
    }

    @Test
    fun `length mismatch decodes to null instead of garbage`() {
        assertNull(DevPairDecoder.decode(byteArrayOf(1, 2, 3), byteArrayOf(4)))
    }

    @Test
    fun `whitespace-only value decodes to null`() {
        val (share, mask) = encode("   ", "salt-1", "screenscraper.devId")

        assertNull(DevPairDecoder.decode(share, mask))
    }
}
