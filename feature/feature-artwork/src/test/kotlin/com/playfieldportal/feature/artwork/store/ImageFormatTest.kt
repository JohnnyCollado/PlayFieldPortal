package com.playfieldportal.feature.artwork.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageFormatTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `sniffs jpeg`() {
        assertEquals(ImageFormat.JPEG, ImageFormat.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `sniffs png`() {
        assertEquals(ImageFormat.PNG, ImageFormat.sniff(bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)))
    }

    @Test
    fun `sniffs webp`() {
        val header = "RIFF....WEBP".map { it.code }.toIntArray()
        assertEquals(ImageFormat.WEBP, ImageFormat.sniff(ByteArray(header.size) { header[it].toByte() }))
    }

    @Test
    fun `sniffs gif and bmp`() {
        assertEquals(ImageFormat.GIF, ImageFormat.sniff("GIF89a------".toByteArray(Charsets.US_ASCII)))
        assertEquals(ImageFormat.BMP, ImageFormat.sniff("BM----------".toByteArray(Charsets.US_ASCII)))
    }

    // An HTML error page saved by a misbehaving CDN must never pass as artwork.
    @Test
    fun `rejects html, empty and short payloads`() {
        assertNull(ImageFormat.sniff("<!DOCTYPE htm".toByteArray(Charsets.US_ASCII)))
        assertNull(ImageFormat.sniff(ByteArray(0)))
        assertNull(ImageFormat.sniff(bytes(0xFF)))
        // RIFF container that is not WEBP (e.g. WAV) is rejected too.
        assertNull(ImageFormat.sniff("RIFF....WAVE".toByteArray(Charsets.US_ASCII)))
    }
}
