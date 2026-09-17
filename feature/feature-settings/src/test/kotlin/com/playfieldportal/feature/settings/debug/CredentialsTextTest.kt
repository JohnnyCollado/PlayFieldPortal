package com.playfieldportal.feature.settings.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The size cap on a picked credentials file: a wrong pick (a video, a ROM) must never be read whole. */
class CredentialsTextTest {

    @Test
    fun `a small file is read whole`() {
        val text = "steamgriddb.apiKey=k\n"
        assertEquals(text, readCredentialsText(text.byteInputStream(), maxBytes = 64))
    }

    @Test
    fun `a file at exactly the cap is still read`() {
        val text = "x".repeat(64)
        assertEquals(text, readCredentialsText(text.byteInputStream(), maxBytes = 64))
    }

    @Test
    fun `a file past the cap is refused without reading it all`() {
        var read = 0
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 'x'.code.also { read++ }
        }
        assertNull(readCredentialsText(endless, maxBytes = 64))
        assertEquals("stops one byte past the cap", 65, read)
    }
}
