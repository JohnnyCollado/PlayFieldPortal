package com.playfieldportal.themekit

import java.io.File
import java.io.RandomAccessFile

/**
 * Times an audio/video file from its container headers alone — pure JVM, no `MediaMetadataRetriever`,
 * so the Studio (which has no Android runtime) can satisfy [UiMediaLimits.validate]'s duration
 * requirement, and the launcher can use the same math when the device's extractor returns null.
 *
 *  • **MP3** — Xing/Info frame count, else CBR `audio-bytes × 8 ÷ bitrate`.
 *  • **WAV** — PCM `data-chunk bytes ÷ byte-rate`.
 *  • **OGG** (Vorbis / Opus) — identification-header sample rate and the last page's granule position.
 *  • **MP4 / M4A** — `mvhd` duration ÷ timescale.
 *
 * Anything else — an unknown [mime], a truncated or hostile header, a zero rate — returns null,
 * never throws. A null only ever narrows what the gate accepts; it never widens it. Reads are
 * bounded: at most [HEAD_BYTES] from the head, [TAIL_BYTES] from the tail (OGG), plus box headers
 * for MP4 (at most [MAX_BOXES] per level, so a hostile box chain cannot drive an unbounded walk).
 */
object MediaDurationProbe {

    /** How much of the file's head is read — enough for any ID3v2 size + first frames, or an Ogg ID page. */
    const val HEAD_BYTES = 64 * 1024

    /** How much of the file's end is searched for the final Ogg page. */
    const val TAIL_BYTES = 64 * 1024

    /** Bytes at the very end of a file with an ID3v1 footer — excluded from CBR byte math. */
    private const val ID3V1_FOOTER = 128

    /** Box-walk cap per nesting level for MP4. */
    private const val MAX_BOXES = 64

    /**
     * Returns the container's duration in milliseconds, or null when [mime] is not a container
     * this probe understands or the file's headers cannot be trusted. [mime] decides the parser.
     */
    fun durationMs(file: File, mime: String?): Long? = runCatching {
        val total = file.length()
        if (total <= 0) return@runCatching null
        when (mime?.lowercase()) {
            "audio/wav", "audio/x-wav" -> wavDurationMs(readAt(file, 0, HEAD_BYTES), total)
            "audio/mpeg" -> {
                val tail = if (total >= ID3V1_FOOTER) readAt(file, total - ID3V1_FOOTER, ID3V1_FOOTER) else null
                mp3DurationMs(readAt(file, 0, HEAD_BYTES), total, tail)
            }
            "audio/ogg", "application/ogg", "audio/opus" -> oggDurationMs(file, total)
            "audio/mp4", "audio/mp4a-latm", "audio/x-m4a", "video/mp4" -> mp4DurationMs(file, total)
            else -> null
        }
    }.getOrNull()

    /** Reads up to [max] bytes at [offset]; shorter at end of file. */
    private fun readAt(file: File, offset: Long, max: Int): ByteArray = RandomAccessFile(file, "r").use { raf ->
        val want = minOf(max.toLong(), raf.length() - offset).coerceAtLeast(0).toInt()
        val buf = ByteArray(want)
        raf.seek(offset)
        var got = 0
        while (got < want) {
            val n = raf.read(buf, got, want - got)
            if (n <= 0) break
            got += n
        }
        if (got == want) buf else buf.copyOf(got)
    }

    // ── MP3 ──────────────────────────────────────────────────────────────────

    /**
     * MPEG audio sample rates, indexed by the header's MPEG-version bits then sampling index.
     * Version bits: 0 = MPEG-2.5, 1 = invalid, 2 = MPEG-2, 3 = MPEG-1.
     */
    private val SAMPLE_RATES = arrayOf(
        intArrayOf(11025, 12000, 8000),   // MPEG-2.5
        IntArray(3),                       // version bits 01 is reserved
        intArrayOf(22050, 24000, 16000),   // MPEG-2
        intArrayOf(44100, 48000, 32000),   // MPEG-1
    )

    /**
     * Bitrates in kbps per MPEG version → layer → bitrate index; 0 marks free/reserved. Layers
     * are normalized to 0 = Layer I, 1 = Layer II, 2 = Layer III.
     */
    private val BITRATES = arrayOf(
        // MPEG-2.5
        arrayOf(
            intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0),
            intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0),
            intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0),
        ),
        arrayOf(IntArray(16), IntArray(16), IntArray(16)), // reserved version — never reached
        // MPEG-2
        arrayOf(
            intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0),
            intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0),
            intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0),
        ),
        // MPEG-1
        arrayOf(
            intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0),
            intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0),
            intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0),
        ),
    )

    private fun mp3DurationMs(head: ByteArray, total: Long, tail: ByteArray?): Long? {
        // Skip an ID3v2 tag; its size field (the last four bytes of the 10-byte header) is
        // syncsafe, so a huge album-art tag is handled by arithmetic, not by scanning.
        var pos = 0
        if (head.size >= 10 && head.markerAt(0, "ID3")) {
            val tagSize = ((head[6].toInt() and 0x7F) shl 21) or
                ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or
                (head[9].toInt() and 0x7F)
            pos = 10 + tagSize
        }
        // Find the first MPEG frame sync (11 set bits: 0xFF then 0xEx).
        while (pos + 4 <= head.size && !(head[pos] == 0xFF.toByte() && (head[pos + 1].toInt() and 0xE0) == 0xE0)) {
            pos++
        }
        if (pos + 4 > head.size) return null

        val b1 = head[pos + 1].toInt() and 0xFF
        val b2 = head[pos + 2].toInt() and 0xFF
        val b3 = head[pos + 3].toInt() and 0xFF
        val version = (b1 shr 3) and 3
        if (version == 1) return null
        val layerIndex = when ((b1 shr 1) and 3) {
            3 -> 0 // Layer I
            2 -> 1 // Layer II
            1 -> 2 // Layer III
            else -> return null // reserved
        }
        val sampleRateIndex = (b2 shr 2) and 3
        if (sampleRateIndex >= 3) return null
        val sampleRate = SAMPLE_RATES[version][sampleRateIndex]

        // The Xing/Info VBR table lives after the frame header plus that version's side info.
        val channelMode = (b3 shr 6) and 3
        val sideInfoSize = if (version == 3) {
            if (channelMode == 3) 17 else 32
        } else {
            if (channelMode == 3) 9 else 17
        }
        val marker = pos + 4 + sideInfoSize
        if (marker + 12 <= head.size && (head.markerAt(marker, "Xing") || head.markerAt(marker, "Info"))) {
            val flags = head.be32(marker + 4)
            if (flags and 1L == 0L) return null // no frame count — cannot be timed reliably
            val frames = head.be32(marker + 8)
            if (frames <= 0) return null
            val samplesPerFrame = when (layerIndex) {
                0 -> 384L
                1 -> 1152L
                else -> if (version == 3) 1152L else 576L
            }
            return frames * samplesPerFrame * 1000L / sampleRate
        }

        // No VBR table: a CBR stream's duration is audio-bytes × 8 ÷ bitrate. The first
        // frame's bitrate is the stream's bitrate by definition.
        val bitrateIndex = (b2 shr 4) and 0xF
        val kbps = BITRATES[version][layerIndex][bitrateIndex]
        if (kbps == 0) return null
        var audioBytes = total - pos
        if (tail != null && tail.markerAt(0, "TAG")) audioBytes -= ID3V1_FOOTER
        if (audioBytes <= 0) return null
        return audioBytes * 8 * 1000 / (kbps.toLong() * 1000)
    }

    // ── WAV ──────────────────────────────────────────────────────────────────

    private fun wavDurationMs(head: ByteArray, total: Long): Long? {
        if (head.size < 12 || !head.markerAt(0, "RIFF") || !head.markerAt(8, "WAVE")) return null
        var pos = 12
        var byteRate = 0L
        var dataSize = -1L
        while (pos + 8 <= head.size) {
            val size = head.le32(pos + 4)
            when {
                head.markerAt(pos, "fmt ") -> {
                    // Only uncompressed PCM is timed here; compressed WAVs keep MMR's verdict.
                    if (pos + 20 > head.size || head.le16(pos + 8) != 1) return null
                    byteRate = head.le32(pos + 16)
                }
                head.markerAt(pos, "data") -> {
                    // 0xFFFFFFFF means "size unknown" — fall back to everything after the header.
                    dataSize = if (size == 0xFFFFFFFFL) total - pos - 8 else size
                }
            }
            // Chunks are word-aligned; a hostile size (e.g. 0xFFFFFFFF for "unknown") ends the
            // walk rather than overflowing it.
            val chunkTotal = 8L + size + (size and 1L)
            if (chunkTotal > head.size - pos) break
            pos += chunkTotal.toInt()
        }
        if (dataSize <= 0 || byteRate <= 0) return null
        return dataSize * 1000 / byteRate
    }

    // ── OGG (Vorbis / Opus) ──────────────────────────────────────────────────

    /** Opus granule positions always count 48 kHz samples, whatever the stream's input rate. */
    private const val OPUS_GRANULE_RATE = 48_000L

    private fun oggDurationMs(file: File, total: Long): Long? {
        val head = readAt(file, 0, HEAD_BYTES)
        if (head.size < 27 || !head.markerAt(0, "OggS")) return null
        val packet = 27 + (head[26].toInt() and 0xFF) // first packet follows the segment table
        val isOpus = head.markerAt(packet, "OpusHead")
        val rate: Long
        val preSkip: Long
        when {
            isOpus -> {
                if (packet + 12 > head.size) return null
                rate = OPUS_GRANULE_RATE
                preSkip = head.le16(packet + 10).toLong()
            }
            packet + 16 <= head.size && head[packet] == 1.toByte() && head.markerAt(packet + 1, "vorbis") -> {
                rate = head.le32(packet + 12)
                preSkip = 0L
            }
            else -> return null
        }
        if (rate <= 0) return null

        // The stream's length is the granule position of its final page (granule -1 = "no packet ends here").
        val tail = readAt(file, maxOf(0L, total - TAIL_BYTES), TAIL_BYTES)
        var i = tail.size - 27
        while (i >= 0) {
            if (tail.markerAt(i, "OggS") && tail[i + 4] == 0.toByte()) {
                val granule = tail.le64(i + 6)
                if (granule >= 0) {
                    val samples = granule - preSkip
                    if (samples <= 0 || samples > Long.MAX_VALUE / 1000) return null
                    return samples * 1000 / rate
                }
            }
            i--
        }
        return null
    }

    // ── MP4 / M4A ────────────────────────────────────────────────────────────

    private fun mp4DurationMs(file: File, total: Long): Long? = RandomAccessFile(file, "r").use { raf ->
        val moov = findBox(raf, 0, total, "moov") ?: return null
        val mvhd = findBox(raf, moov.first, moov.second, "mvhd") ?: return null
        // mvhd payload: version/flags(4), then v0: ctime(4) mtime(4) timescale(4) duration(4);
        // v1: ctime(8) mtime(8) timescale(4) duration(8).
        val start = mvhd.first
        raf.seek(start)
        val version = raf.read()
        val timescale: Long
        val duration: Long
        when (version) {
            0 -> {
                if (mvhd.second - start < 20) return null
                raf.seek(start + 12)
                timescale = raf.readUInt32()
                duration = raf.readUInt32()
            }
            1 -> {
                if (mvhd.second - start < 32) return null
                raf.seek(start + 20)
                timescale = raf.readUInt32()
                duration = raf.readLong()
            }
            else -> return null
        }
        if (timescale <= 0 || duration <= 0 || duration / timescale > Long.MAX_VALUE / 1000) return null
        (duration / timescale) * 1000 + (duration % timescale) * 1000 / timescale
    }

    /**
     * Walks the sibling boxes in [from, to) looking for [type]; returns the box's payload range
     * (start, end) or null. Reads only box headers, never payloads, and gives up after [MAX_BOXES].
     */
    private fun findBox(raf: RandomAccessFile, from: Long, to: Long, type: String): Pair<Long, Long>? {
        var pos = from
        repeat(MAX_BOXES) {
            if (pos + 8 > to) return null
            raf.seek(pos)
            var size = raf.readUInt32()
            val name = ByteArray(4).also { raf.readFully(it) }
            var header = 8L
            when (size) {
                1L -> { size = raf.readLong(); header = 16L } // 64-bit size follows the type
                0L -> size = to - pos                         // runs to the end of the enclosing box
            }
            if (size < header || size > to - pos) return null
            if (String(name, Charsets.ISO_8859_1) == type) return (pos + header) to (pos + size)
            pos += size
        }
        return null
    }

    private fun RandomAccessFile.readUInt32(): Long = readInt().toLong() and 0xFFFFFFFFL

    // ── byte helpers ─────────────────────────────────────────────────────────

    private fun ByteArray.markerAt(offset: Int, marker: String): Boolean =
        offset >= 0 && offset + marker.length <= size &&
            String(this, offset, marker.length, Charsets.ISO_8859_1) == marker

    private fun ByteArray.be32(offset: Int): Long =
        ((this[offset].toInt() and 0xFF).toLong() shl 24) or
            ((this[offset + 1].toInt() and 0xFF).toLong() shl 16) or
            ((this[offset + 2].toInt() and 0xFF).toLong() shl 8) or
            (this[offset + 3].toInt() and 0xFF).toLong()

    private fun ByteArray.le32(offset: Int): Long =
        (this[offset].toInt() and 0xFF).toLong() or
            ((this[offset + 1].toInt() and 0xFF).toLong() shl 8) or
            ((this[offset + 2].toInt() and 0xFF).toLong() shl 16) or
            ((this[offset + 3].toInt() and 0xFF).toLong() shl 24)

    private fun ByteArray.le16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.le64(offset: Int): Long = le32(offset) or (le32(offset + 4) shl 32)
}
