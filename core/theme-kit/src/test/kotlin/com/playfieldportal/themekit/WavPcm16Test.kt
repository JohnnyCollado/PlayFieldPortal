package com.playfieldportal.themekit

import com.playfieldportal.themekit.WavFixtures.floats32
import com.playfieldportal.themekit.WavFixtures.floats64
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WavPcm16Test {

    private val dir: File = createTempDirectory("themekit-wav-pcm16").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun sampleWav(
        name: String, formatTag: Int, bits: Int, channels: Int, data: ByteArray,
        rate: Int = 44_100, extensible: Boolean = false, extraChunks: Boolean = false,
    ) = File(dir, name).also { WavFixtures.writeSampleWav(it, formatTag, bits, channels, rate, data, extensible, extraChunks) }

    private fun converted(src: File): WavFixtures.Pcm16 {
        val out = File(dir, "${src.nameWithoutExtension}-pcm16.wav")
        assertTrue(WavPcm16.convert(src, out), "converts")
        return WavFixtures.readPcm16(out)
    }

    private fun shorts(vararg v: Int) = ShortArray(v.size) { v[it].toShort() }

    // ── Float ────────────────────────────────────────────────────────────────

    @Test
    fun `a 32-bit float stereo WAV with fact and PEAK chunks becomes 16-bit PCM`() {
        // The layout DAW and game-audio exports write, e.g. the FFVII menu sounds.
        val src = sampleWav(
            "float.wav", formatTag = 3, bits = 32, channels = 2, extraChunks = true,
            data = floats32(0f, 0.5f, -0.5f, 0.25f),
        )
        assertTrue(WavPcm16.needsConversion(src))
        val pcm = converted(src)
        assertEquals(1, pcm.formatTag)
        assertEquals(2, pcm.channels)
        assertEquals(44_100, pcm.sampleRate)
        assertEquals(16, pcm.bits)
        assertEquals(4, pcm.blockAlign)
        assertEquals(44_100 * 4, pcm.byteRate)
        assertContentEquals(shorts(0, 16384, -16384, 8192), pcm.samples)
    }

    @Test
    fun `float samples past full scale are clipped, never wrapped`() {
        val src = sampleWav("hot.wav", 3, 32, 1, floats32(1f, -1f, 1.5f, -2f, Float.NaN))
        assertContentEquals(shorts(32767, -32768, 32767, -32768, 0), converted(src).samples)
    }

    @Test
    fun `a 64-bit float WAV converts`() {
        val src = sampleWav("double.wav", 3, 64, 1, floats64(0.5, -0.25))
        assertContentEquals(shorts(16384, -8192), converted(src).samples)
    }

    @Test
    fun `an extensible float WAV converts`() {
        val src = sampleWav("ext-float.wav", 3, 32, 2, floats32(0.5f, -0.5f), extensible = true)
        assertTrue(WavPcm16.needsConversion(src))
        val pcm = converted(src)
        assertEquals(1, pcm.formatTag, "written as plain PCM")
        assertContentEquals(shorts(16384, -16384), pcm.samples)
    }

    // ── Integer ──────────────────────────────────────────────────────────────

    @Test
    fun `24-bit PCM keeps its top 16 bits`() {
        val data = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0x7F, // +max
            0x00, 0x00, 0x80.toByte(),          // -max
            0x00, 0x01, 0x00,                   // 256 -> 1
        )
        val src = sampleWav("24.wav", 1, 24, 1, data)
        assertTrue(WavPcm16.needsConversion(src))
        assertContentEquals(shorts(32767, -32768, 1), converted(src).samples)
    }

    @Test
    fun `32-bit PCM keeps its top 16 bits`() {
        val data = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0x40000000).putInt(-0x40000000).array()
        val src = sampleWav("32.wav", 1, 32, 1, data)
        assertContentEquals(shorts(16384, -16384), converted(src).samples)
    }

    @Test
    fun `an extensible 16-bit PCM WAV is rewritten as plain PCM`() {
        val data = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(1234).putShort(-1234).array()
        val src = sampleWav("ext16.wav", 1, 16, 1, data, extensible = true)
        assertTrue(WavPcm16.needsConversion(src))
        val pcm = converted(src)
        assertEquals(1, pcm.formatTag)
        assertContentEquals(shorts(1234, -1234), pcm.samples)
    }

    @Test
    fun `an odd-length data chunk and a chunk after it are read correctly`() {
        // 8-bit PCM, extensible so it converts: 3 samples, padded to an even chunk, then LIST.
        val src = sampleWav("odd.wav", 1, 8, 1, byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0x00), extensible = true, extraChunks = true)
        assertContentEquals(shorts(0, 127 shl 8, -128 shl 8), converted(src).samples)
    }

    // ── Left alone ───────────────────────────────────────────────────────────

    @Test
    fun `plain 8- and 16-bit PCM already plays everywhere and is left alone`() {
        assertFalse(WavPcm16.needsConversion(sampleWav("8.wav", 1, 8, 1, byteArrayOf(0, 1))))
        assertFalse(WavPcm16.needsConversion(sampleWav("16.wav", 1, 16, 2, ByteArray(8))))
    }

    @Test
    fun `what it cannot read is left for the gate to reject`() {
        val adpcm = sampleWav("adpcm.wav", 0x11, 4, 1, ByteArray(16))
        val junk = File(dir, "junk.wav").also { it.writeText("not a wav at all") }
        val truncated = File(dir, "truncated.wav").also { it.writeBytes(sampleWav("t.wav", 3, 32, 1, floats32(0.5f)).readBytes().copyOf(30)) }
        for (f in listOf(adpcm, junk, truncated, File(dir, "missing.wav"))) {
            assertFalse(WavPcm16.needsConversion(f), f.name)
            val out = File(dir, "${f.name}-out.wav")
            assertFalse(WavPcm16.convert(f, out), f.name)
            assertFalse(out.exists(), "nothing left behind for ${f.name}")
        }
    }
}
