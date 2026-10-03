package com.playfieldportal.themekit

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * TS-08: the pure-JVM container-header duration math. MP3/WAV cases are ported from core-data's
 * MediaDurationFallbackTest; OGG (Vorbis + Opus) and MP4/M4A are timed from synthetic minimal
 * containers with known durations. Hostile or truncated input returns null and never throws.
 */
class MediaDurationProbeTest {

    // ── Xing/VBR MP3 ─────────────────────────────────────────────────────────

    @Test
    fun `Xing VBR MPEG1 layer III duration is frames times samples over sample rate`() {
        val bytes = xingMp3(versionBits = 3, layerIndex = 2, sampleRateIdx = 0, channelMode = 0, sideInfoSize = 32, frames = 3)
        // 3 frames x 1152 samples / 44100 Hz = 78.4 ms
        assertEquals(78L, durationOf(bytes, "audio/mpeg"))
    }

    @Test
    fun `Xing VBR MPEG2 layer III uses its 576 samples per frame`() {
        val bytes = xingMp3(versionBits = 2, layerIndex = 2, sampleRateIdx = 0, channelMode = 0, sideInfoSize = 17, frames = 2)
        assertEquals(52L, durationOf(bytes, "audio/mpeg"))
    }

    @Test
    fun `Xing header without a frame count cannot be timed`() {
        val bytes = xingMp3(versionBits = 3, layerIndex = 2, sampleRateIdx = 0, channelMode = 0, sideInfoSize = 32, frames = null)
        assertNull(durationOf(bytes, "audio/mpeg"))
    }

    // ── CBR MP3 ──────────────────────────────────────────────────────────────

    @Test
    fun `CBR MP3 duration is audio bytes over bitrate, ID3 tags excluded`() {
        // 1049 total - 45 tag = 1004 bytes -> 1004 x 8 x 1000 / 128000 = 62.75 ms
        assertEquals(62L, durationOf(cbrMp3(bitrateKbps = 128, payloadBytes = 1000), "audio/mpeg"))
    }

    @Test
    fun `CBR MP3 excludes an ID3v1 footer from the byte math`() {
        val bytes = cbrMp3(bitrateKbps = 128, payloadBytes = 1000)
        assertEquals(62L, durationOf(bytes + "TAG".toByteArray() + ByteArray(125), "audio/mpeg"))
    }

    // ── WAV ──────────────────────────────────────────────────────────────────

    @Test
    fun `PCM WAV duration is data bytes over byte rate`() {
        assertEquals(500L, durationOf(pcmWav(byteRate = 88_200, dataBytes = 44_100), "audio/wav"))
    }

    @Test
    fun `WAV with unknown data size falls back to everything after the header`() {
        assertEquals(500L, durationOf(pcmWav(byteRate = 88_200, dataBytes = 44_100, unknownDataSize = true), "audio/x-wav"))
    }

    @Test
    fun `non-PCM WAV is left to the extractor`() {
        assertNull(durationOf(pcmWav(byteRate = 88_200, dataBytes = 1_000, audioFormat = 3), "audio/wav"))
    }

    // ── OGG ──────────────────────────────────────────────────────────────────

    @Test
    fun `Vorbis OGG duration is last granule over identification sample rate`() {
        val bytes = oggVorbis(sampleRate = 44_100, lastGranule = 44_100L * 3)
        assertEquals(3000L, durationOf(bytes, "audio/ogg"))
    }

    @Test
    fun `Opus OGG duration is last granule minus pre-skip over 48 kHz`() {
        val bytes = oggOpus(preSkip = 312, lastGranule = 312L + 48_000L * 2)
        assertEquals(2000L, durationOf(bytes, "audio/ogg"))
    }

    @Test
    fun `OGG skips trailing pages with no granule position`() {
        val bytes = oggVorbis(sampleRate = 48_000, lastGranule = 48_000L, trailingUnsetPage = true)
        assertEquals(1000L, durationOf(bytes, "audio/ogg"))
    }

    @Test
    fun `OGG tail read is bounded to 64 KB`() {
        // Last granule page sits well inside the tail window of a large file: still timed.
        val near = oggVorbis(sampleRate = 44_100, lastGranule = 44_100L, paddingBeforeLast = 300_000)
        assertEquals(1000L, durationOf(near, "audio/ogg"))
        // A granule page followed by more than 64 KB of non-page bytes is out of reach: null.
        val far = oggVorbis(sampleRate = 44_100, lastGranule = 44_100L, junkAfterLast = MediaDurationProbe.TAIL_BYTES + 10)
        assertNull(durationOf(far, "audio/ogg"))
    }

    @Test
    fun `OGG is also recognised under the application-ogg mime`() {
        val bytes = oggVorbis(sampleRate = 44_100, lastGranule = 44_100L)
        assertEquals(1000L, durationOf(bytes, "application/ogg"))
    }

    // ── MP4 / M4A ────────────────────────────────────────────────────────────

    @Test
    fun `M4A duration is mvhd duration over timescale`() {
        val bytes = mp4(timescale = 44_100, duration = 44_100L * 5, moovFirst = true)
        assertEquals(5000L, durationOf(bytes, "audio/mp4"))
        assertEquals(5000L, durationOf(bytes, "audio/mp4a-latm"))
        assertEquals(5000L, durationOf(bytes, "video/mp4"))
    }

    @Test
    fun `M4A with moov after a large mdat is still timed`() {
        val bytes = mp4(timescale = 1000, duration = 2500, moovFirst = false, mdatBytes = 500_000)
        assertEquals(2500L, durationOf(bytes, "audio/mp4"))
    }

    @Test
    fun `M4A version 1 mvhd with 64-bit fields is timed`() {
        val bytes = mp4(timescale = 90_000, duration = 90_000L * 4, moovFirst = true, version = 1)
        assertEquals(4000L, durationOf(bytes, "audio/mp4"))
    }

    @Test
    fun `M4A with a 64-bit mdat size box is walked`() {
        val bytes = mp4(timescale = 1000, duration = 1500, moovFirst = false, mdatBytes = 100, largeMdat = true)
        assertEquals(1500L, durationOf(bytes, "audio/mp4"))
    }

    @Test
    fun `MP4 with zero timescale cannot be timed`() {
        assertNull(durationOf(mp4(timescale = 0, duration = 1000, moovFirst = true), "audio/mp4"))
    }

    // ── hostile / truncated input ────────────────────────────────────────────

    @Test
    fun `garbage bytes cannot be timed under any mime`() {
        val bytes = "not a media file at all".toByteArray() + ByteArray(64)
        for (mime in listOf("audio/mpeg", "audio/wav", "audio/ogg", "audio/mp4", "video/mp4")) {
            assertNull(durationOf(bytes, mime), mime)
        }
    }

    @Test
    fun `unknown or null mime is left to the extractor`() {
        val bytes = xingMp3(versionBits = 3, layerIndex = 2, sampleRateIdx = 0, channelMode = 0, sideInfoSize = 32, frames = 3)
        assertNull(durationOf(bytes, "audio/flac"))
        assertNull(durationOf(bytes, null))
    }

    @Test
    fun `every truncation of every container returns null or a value, never throws`() {
        val samples = mapOf(
            "audio/mpeg" to xingMp3(3, 2, 0, 0, 32, 3),
            "audio/wav" to pcmWav(88_200, 2_000),
            "audio/ogg" to oggVorbis(44_100, 44_100L),
            "audio/mp4" to mp4(1000, 2000, moovFirst = true),
        )
        for ((mime, full) in samples) {
            for (len in 0..full.size) {
                durationOf(full.copyOf(len), mime) // must not throw
            }
        }
    }

    @Test
    fun `hostile box sizes and counts return null`() {
        // moov claiming 4 GB, box smaller than its own header, and a box chain that never reaches moov.
        assertNull(durationOf(box("ftyp", ByteArray(8)) + be32(0xFFFFFFF0L) + "moov".toByteArray(), "audio/mp4"))
        assertNull(durationOf(box("ftyp", ByteArray(8)) + be32(4) + "moov".toByteArray(), "audio/mp4"))
        val chain = ByteArrayOutputStream().also { o -> repeat(500) { o.write(box("free", ByteArray(0))) } }.toByteArray()
        assertNull(durationOf(chain, "audio/mp4"))
    }

    @Test
    fun `OGG with zero sample rate or non-positive duration returns null`() {
        assertNull(durationOf(oggVorbis(sampleRate = 0, lastGranule = 1000L), "audio/ogg"))
        assertNull(durationOf(oggOpus(preSkip = 500, lastGranule = 400L), "audio/ogg"))
    }

    @Test
    fun `empty file returns null`() {
        assertNull(durationOf(ByteArray(0), "audio/mpeg"))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun durationOf(bytes: ByteArray, mime: String?): Long? {
        val file = File.createTempFile("probe", ".bin")
        file.writeBytes(bytes)
        try {
            return MediaDurationProbe.durationMs(file, mime)
        } finally {
            file.delete()
        }
    }

    private fun xingMp3(
        versionBits: Int, layerIndex: Int, sampleRateIdx: Int, channelMode: Int, sideInfoSize: Int, frames: Int?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 0x23))
        out.write(ByteArray(35))
        val layerBits = intArrayOf(3, 2, 1)[layerIndex]
        out.write(0xFF)
        out.write(0xE0 or (versionBits shl 3) or (layerBits shl 1))
        out.write(0x50 or (sampleRateIdx shl 2))
        out.write(channelMode shl 6)
        out.write(ByteArray(sideInfoSize))
        out.write("Xing".toByteArray())
        out.write(byteArrayOf(0, 0, 0, (if (frames != null) 0x0F else 0x02).toByte()))
        if (frames != null) {
            out.write(byteArrayOf(0, 0, 0, frames.toByte()))
            out.write(ByteArray(8))
        }
        out.write(ByteArray(512))
        return out.toByteArray()
    }

    private fun cbrMp3(bitrateKbps: Int, payloadBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 0x23))
        out.write(ByteArray(35))
        val bitrateIndex = MPEG1_L3_BITRATES.indexOf(bitrateKbps)
        check(bitrateIndex > 0)
        out.write(0xFF)
        out.write(0xFB)
        out.write(bitrateIndex shl 4)
        out.write(0x00)
        out.write(ByteArray(payloadBytes))
        return out.toByteArray()
    }

    private fun pcmWav(byteRate: Int, dataBytes: Int, unknownDataSize: Boolean = false, audioFormat: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(le32(36L + dataBytes))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(le32(16))
        out.write(le16(audioFormat))
        out.write(le16(1))
        out.write(le32(44_100))
        out.write(le32(byteRate.toLong()))
        out.write(le16(2))
        out.write(le16(16))
        out.write("data".toByteArray())
        out.write(le32(if (unknownDataSize) 0xFFFFFFFFL else dataBytes.toLong()))
        out.write(ByteArray(dataBytes))
        return out.toByteArray()
    }

    /** One Ogg page; the CRC is left zero (the probe does not verify it). */
    private fun oggPage(headerType: Int, granule: Long, seq: Int, packet: ByteArray): ByteArray {
        val segs = ArrayList<Int>()
        var left = packet.size
        while (left >= 255) { segs += 255; left -= 255 }
        segs += left
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray())
        out.write(0)
        out.write(headerType)
        out.write(le64(granule))
        out.write(le32(1))
        out.write(le32(seq.toLong()))
        out.write(le32(0))
        out.write(segs.size)
        segs.forEach { out.write(it) }
        out.write(packet)
        return out.toByteArray()
    }

    private fun vorbisId(sampleRate: Int): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(1); o.write("vorbis".toByteArray())
        o.write(le32(0)); o.write(2); o.write(le32(sampleRate.toLong()))
        o.write(ByteArray(12)); o.write(0xB8); o.write(1)
        return o.toByteArray()
    }

    private fun opusHead(preSkip: Int): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("OpusHead".toByteArray()); o.write(1); o.write(2)
        o.write(le16(preSkip)); o.write(le32(48_000)); o.write(le16(0)); o.write(0)
        return o.toByteArray()
    }

    private fun oggFile(
        idPacket: ByteArray, lastGranule: Long, trailingUnsetPage: Boolean,
        paddingBeforeLast: Int, junkAfterLast: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(oggPage(0x02, 0, 0, idPacket))
        out.write(oggPage(0x00, 0, 1, ByteArray(40)))
        if (paddingBeforeLast > 0) out.write(oggPage(0x00, 100, 2, ByteArray(paddingBeforeLast)))
        out.write(oggPage(0x04, lastGranule, 3, ByteArray(30)))
        if (trailingUnsetPage) out.write(oggPage(0x00, -1L, 4, ByteArray(20)))
        out.write(ByteArray(junkAfterLast))
        return out.toByteArray()
    }

    private fun oggVorbis(
        sampleRate: Int, lastGranule: Long, trailingUnsetPage: Boolean = false,
        paddingBeforeLast: Int = 0, junkAfterLast: Int = 0,
    ) = oggFile(vorbisId(sampleRate), lastGranule, trailingUnsetPage, paddingBeforeLast, junkAfterLast)

    private fun oggOpus(preSkip: Int, lastGranule: Long) =
        oggFile(opusHead(preSkip), lastGranule, false, 0, 0)

    private fun box(type: String, payload: ByteArray): ByteArray =
        be32(8L + payload.size) + type.toByteArray() + payload

    private fun mp4(
        timescale: Long, duration: Long, moovFirst: Boolean,
        version: Int = 0, mdatBytes: Int = 64, largeMdat: Boolean = false,
    ): ByteArray {
        val mvhdPayload = ByteArrayOutputStream().also { o ->
            o.write(version); o.write(ByteArray(3))
            if (version == 1) {
                o.write(ByteArray(16)); o.write(be32(timescale)); o.write(be64(duration))
            } else {
                o.write(ByteArray(8)); o.write(be32(timescale)); o.write(be32(duration))
            }
            o.write(ByteArray(80))
        }.toByteArray()
        val trak = box("trak", box("tkhd", ByteArray(84)))
        val moov = box("moov", box("mvhd", mvhdPayload) + trak)
        val mdat = if (largeMdat) {
            be32(1) + "mdat".toByteArray() + be64(16L + mdatBytes) + ByteArray(mdatBytes)
        } else box("mdat", ByteArray(mdatBytes))
        val ftyp = box("ftyp", "M4A ".toByteArray() + be32(0) + "M4A ".toByteArray())
        return if (moovFirst) ftyp + moov + mdat else ftyp + mdat + moov
    }

    private fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun be64(v: Long) = be32(v shr 32) + be32(v and 0xFFFFFFFFL)
    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le64(v: Long) = le32(v and 0xFFFFFFFFL) + le32(v shr 32)

    private companion object {
        val MPEG1_L3_BITRATES = listOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    }
}
