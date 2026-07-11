package com.playfieldportal.feature.artwork.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream

class ArtworkTempIOTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Minimal valid PNG header (magic bytes) followed by payload filler.
    private fun pngBytes(size: Int): ByteArray = ByteArray(size).also {
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(it)
    }

    @Test
    fun `valid image under the cap is accepted`() {
        val file = ArtworkTempIO.copyToTemp(ByteArrayInputStream(pngBytes(4096)), tmp.root, ArtworkKind.ICON)
        assertNotNull(file)
        assertEquals(4096L, file!!.length())
    }

    @Test
    fun `stream over the per-kind cap is rejected without draining`() {
        var served = 0L
        // Endless PNG-prefixed stream — terminates only if the cap aborts the copy.
        val endless = object : InputStream() {
            private val header = pngBytes(8)
            override fun read(): Int = (if (served < 8) header[served.toInt()].toInt() else 0).also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                for (i in 0 until len) b[off + i] = if (served + i < 8) header[(served + i).toInt()] else 0
                served += len
                return len
            }
        }
        val file = ArtworkTempIO.copyToTemp(endless, tmp.root, ArtworkKind.ICON)
        assertNull(file)
        // Aborted just past the 50 MB image cap — not gigabytes later.
        val cap = ArtworkTempIO.maxBytesFor(ArtworkKind.ICON)
        assert(served <= cap + (128 * 1024)) { "stream drained $served bytes past the cap" }
    }

    @Test
    fun `manual kind uses the larger media cap`() {
        assert(ArtworkTempIO.maxBytesFor(ArtworkKind.MANUAL) > ArtworkTempIO.maxBytesFor(ArtworkKind.ICON))
    }

    @Test
    fun `empty stream is rejected`() {
        assertNull(ArtworkTempIO.copyToTemp(ByteArrayInputStream(ByteArray(0)), tmp.root, ArtworkKind.ICON))
    }

    @Test
    fun `no temp file is left behind on rejection`() {
        ArtworkTempIO.copyToTemp(ByteArrayInputStream("not an image".toByteArray()), tmp.root, ArtworkKind.ICON)
        assertEquals(0, tmp.root.listFiles()?.size ?: -1)
    }
}
