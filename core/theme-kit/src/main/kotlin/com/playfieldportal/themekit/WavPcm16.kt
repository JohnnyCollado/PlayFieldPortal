package com.playfieldportal.themekit

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/**
 * Rewrites a WAV whose samples are not plain 8/16-bit integer PCM — 32/64-bit IEEE float (what DAWs
 * and game-audio tools export), 24/32-bit integer, or any WAVE_FORMAT_EXTENSIBLE file — as plain
 * 16-bit PCM at the same rate and channel count.
 *
 * Plain 16-bit PCM is the WAV every player handles: the launcher's SoundPool and MediaPlayer, the
 * Studio's own `javax.sound` audition, and the duration probe both gates time a sound with
 * ([MediaDurationProbe] reads PCM only, so a float WAV had no length and was rejected). It is also
 * half the size of float. Shared like [MediaDurationProbe]: the Studio converts at import, the
 * launcher at import and when a theme's media is installed.
 *
 * Float samples are clipped to full scale, never wrapped: exports often peak past 1.0 (that is what
 * their `PEAK` chunk records), and `javax.sound`'s own float converter wraps those to the opposite
 * rail — a loud click. Pure JVM, streaming, bounded chunk walk; anything it cannot read is left
 * untouched for the gate to reject.
 */
object WavPcm16 {

    private const val PCM = 1
    private const val IEEE_FLOAT = 3
    private const val EXTENSIBLE = 0xFFFE
    private const val MAX_CHUNKS = 64
    private const val HEADER_BYTES = 44

    private class Layout(
        val formatTag: Int,
        val extensible: Boolean,
        val channels: Int,
        val sampleRate: Int,
        val bits: Int,
        val blockAlign: Int,
        val dataOffset: Long,
        val dataSize: Long,
    ) {
        val convertible: Boolean
            get() = when (formatTag) {
                PCM -> bits == 8 || bits == 16 || bits == 24 || bits == 32
                IEEE_FLOAT -> bits == 32 || bits == 64
                else -> false
            }

        /** Already what every player reads. */
        val plain: Boolean get() = formatTag == PCM && !extensible && (bits == 8 || bits == 16)
    }

    /** Whether [file] is a WAV this can read and its samples are not already plain 8/16-bit PCM. */
    fun needsConversion(file: File): Boolean = layoutOf(file)?.let { it.convertible && !it.plain } ?: false

    /**
     * Writes [src] to [dst] as 16-bit PCM. False — and no [dst] left behind — when [src] is not a WAV
     * this can read.
     */
    fun convert(src: File, dst: File): Boolean {
        val layout = layoutOf(src)?.takeIf { it.convertible } ?: return false
        return try {
            write(src, dst, layout)
            true
        } catch (e: Exception) {
            dst.delete()
            false
        }
    }

    private fun write(src: File, dst: File, layout: Layout) {
        val frames = layout.dataSize / layout.blockAlign
        val outData = frames * layout.channels * 2
        require(outData + HEADER_BYTES - 8 <= 0xFFFFFFFFL) { "too long for a WAV" }
        val bytesPerSample = layout.bits / 8
        BufferedOutputStream(dst.outputStream()).use { out ->
            writeHeader(out, layout.channels, layout.sampleRate, outData)
            BufferedInputStream(src.inputStream()).use { input ->
                skipFully(input, layout.dataOffset)
                val sample = ByteArray(bytesPerSample)
                repeat((frames * layout.channels).toInt()) {
                    readFully(input, sample)
                    val v = toPcm16(sample, layout.formatTag, layout.bits)
                    out.write(v and 0xFF)
                    out.write((v shr 8) and 0xFF)
                }
            }
        }
    }

    /** One stored sample, little-endian, as a 16-bit value. */
    private fun toPcm16(b: ByteArray, formatTag: Int, bits: Int): Int = when {
        formatTag == IEEE_FLOAT && bits == 32 -> clip(Float.fromBits(le32(b, 0)).toDouble())
        formatTag == IEEE_FLOAT -> clip(Double.fromBits(le32(b, 0).toLong() and 0xFFFFFFFFL or (le32(b, 4).toLong() shl 32)))
        bits == 8 -> ((b[0].toInt() and 0xFF) - 128) shl 8 // 8-bit WAV is unsigned
        bits == 16 -> (b[0].toInt() and 0xFF) or (b[1].toInt() shl 8)
        bits == 24 -> (b[1].toInt() and 0xFF) or (b[2].toInt() shl 8)
        else -> (b[2].toInt() and 0xFF) or (b[3].toInt() shl 8)
    }

    private fun clip(x: Double): Int = if (x.isNaN()) 0 else (x * 32768.0).coerceIn(-32768.0, 32767.0).roundToInt()

    private fun writeHeader(out: BufferedOutputStream, channels: Int, rate: Int, dataBytes: Long) {
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun u32(v: Long) = repeat(4) { out.write(((v shr (8 * it)) and 0xFF).toInt()) }
        fun u16(v: Int) = repeat(2) { out.write((v shr (8 * it)) and 0xFF) }
        ascii("RIFF"); u32(HEADER_BYTES - 8 + dataBytes); ascii("WAVE")
        ascii("fmt "); u32(16)
        u16(PCM); u16(channels); u32(rate.toLong()); u32(rate.toLong() * channels * 2); u16(channels * 2); u16(16)
        ascii("data"); u32(dataBytes)
    }

    /** The WAV's sample layout and where its samples are, or null when it is not a WAV this reads. */
    private fun layoutOf(file: File): Layout? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < 12 || raf.ascii() != "RIFF") return@use null
            raf.skipBytes(4)
            if (raf.ascii() != "WAVE") return@use null
            var fmt: IntArray? = null // tag, channels, rate, blockAlign, bits, extensible
            var dataOffset = -1L
            var dataSize = 0L
            var chunks = 0
            while (raf.filePointer + 8 <= length && chunks++ < MAX_CHUNKS && (fmt == null || dataOffset < 0)) {
                val id = raf.ascii()
                val size = raf.u32()
                val start = raf.filePointer
                when (id) {
                    "fmt " -> {
                        if (size < 16 || start + size > length) return@use null
                        val tag = raf.u16()
                        val channels = raf.u16()
                        val rate = raf.u32()
                        raf.skipBytes(4) // byte rate: derived, not trusted
                        val blockAlign = raf.u16()
                        val bits = raf.u16()
                        var effective = tag
                        if (tag == EXTENSIBLE) {
                            if (size < 40) return@use null
                            raf.skipBytes(8) // cbSize, valid bits, channel mask
                            effective = raf.u16() // the sub-format GUID's leading field is the format tag
                        }
                        fmt = intArrayOf(effective, channels, rate.toInt(), blockAlign, bits, if (tag == EXTENSIBLE) 1 else 0)
                    }
                    "data" -> {
                        dataOffset = start
                        // 0xFFFFFFFF ("unknown") or a size past the end: the samples run to the end of the file.
                        dataSize = if (size == 0xFFFFFFFFL || start + size > length) length - start else size
                    }
                }
                val next = start + size + (size and 1L)
                if (next > length) break
                raf.seek(next)
            }
            val f = fmt ?: return@use null
            if (dataOffset < 0) return@use null
            val (tag, channels, rate, blockAlign, bits) = f
            if (channels !in 1..8 || rate <= 0 || bits <= 0 || bits % 8 != 0 || blockAlign != channels * bits / 8) return@use null
            Layout(tag, f[5] == 1, channels, rate, bits, blockAlign, dataOffset, dataSize - dataSize % blockAlign)
        }
    }.getOrNull()

    private fun RandomAccessFile.ascii(): String = ByteArray(4).also { readFully(it) }.toString(Charsets.US_ASCII)
    private fun RandomAccessFile.u16(): Int = read() or (read() shl 8)
    private fun RandomAccessFile.u32(): Long = (u16().toLong() or (u16().toLong() shl 16))

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or (b[at + 3].toInt() shl 24)

    private fun readFully(input: BufferedInputStream, into: ByteArray) {
        var got = 0
        while (got < into.size) {
            val n = input.read(into, got, into.size - got)
            if (n < 0) throw java.io.EOFException()
            got += n
        }
    }

    private fun skipFully(input: BufferedInputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) throw java.io.EOFException()
            left -= n
        }
    }
}
