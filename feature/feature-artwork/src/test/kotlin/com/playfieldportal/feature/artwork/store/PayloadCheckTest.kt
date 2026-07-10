package com.playfieldportal.feature.artwork.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadCheckTest {

    @Test
    fun `manuals must be pdf`() {
        assertTrue(PayloadCheck.accepts(ArtworkKind.MANUAL, "%PDF-1.7 ....".toByteArray(Charsets.US_ASCII)))
        assertFalse(PayloadCheck.accepts(ArtworkKind.MANUAL, "<html>error</".toByteArray(Charsets.US_ASCII)))
        assertFalse(PayloadCheck.accepts(ArtworkKind.MANUAL, ByteArray(0)))
    }

    @Test
    fun `video snaps must be mp4 or webm containers`() {
        val mp4 = byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte())
        val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        assertTrue(PayloadCheck.accepts(ArtworkKind.VIDEO, mp4))
        assertTrue(PayloadCheck.accepts(ArtworkKind.VIDEO, webm))
        assertFalse(PayloadCheck.accepts(ArtworkKind.VIDEO, "%PDF-1.7 ...".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `image kinds still require an image`() {
        assertTrue(PayloadCheck.accepts(ArtworkKind.ICON, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertFalse(PayloadCheck.accepts(ArtworkKind.HERO, "%PDF-1.7 ...".toByteArray(Charsets.US_ASCII)))
    }
}
