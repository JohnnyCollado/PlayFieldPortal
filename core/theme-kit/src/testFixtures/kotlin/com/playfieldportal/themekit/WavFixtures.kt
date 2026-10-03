package com.playfieldportal.themekit

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WAV files with real samples in every layout [WavPcm16] handles, and a reader for what it writes. */
object WavFixtures {

    /**
     * A WAV with real sample bytes: [formatTag] 1 (integer PCM) or 3 (IEEE float), [bits] per sample,
     * [data] the interleaved samples as stored. [extensible] writes it as WAVE_FORMAT_EXTENSIBLE with
     * [formatTag] as the sub-format. [extraChunks] adds the `fact` and `PEAK` chunks float exporters
     * write between `fmt ` and `data`, and a `LIST` chunk after `data`.
     */
    fun sampleWav(
        formatTag: Int,
        bits: Int,
        channels: Int,
        sampleRate: Int,
        data: ByteArray,
        extensible: Boolean = false,
        extraChunks: Boolean = false,
    ): ByteArray {
        val blockAlign = channels * bits / 8
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply {
            writeBytes("WAVE")
            writeBytes("fmt "); writeIntLe(if (extensible) 40 else 16)
            writeShortLe(if (extensible) 0xFFFE else formatTag); writeShortLe(channels)
            writeIntLe(sampleRate); writeIntLe(sampleRate * blockAlign); writeShortLe(blockAlign); writeShortLe(bits)
            if (extensible) {
                writeShortLe(22); writeShortLe(bits); writeIntLe(if (channels == 2) 3 else 4)
                // KSDATAFORMAT_SUBTYPE_*: the format tag, then the fixed GUID tail.
                writeShortLe(formatTag)
                write(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71))
            }
            if (extraChunks) {
                writeBytes("fact"); writeIntLe(4); writeIntLe(data.size / blockAlign)
                writeBytes("PEAK"); writeIntLe(8 + 8 * channels); writeIntLe(1); writeIntLe(0)
                repeat(channels) { writeIntLe(0); writeIntLe(0) }
            }
            writeBytes("data"); writeIntLe(data.size); write(data)
            if (data.size % 2 == 1) write(0)
            if (extraChunks) {
                writeBytes("LIST"); writeIntLe(4); writeBytes("INFO")
            }
        }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeBytes("RIFF"); writeIntLe(body.size()); write(body.toByteArray())
        }
        return out.toByteArray()
    }

    /** [sampleWav], written to [file]. */
    fun writeSampleWav(
        file: File,
        formatTag: Int,
        bits: Int,
        channels: Int,
        sampleRate: Int,
        data: ByteArray,
        extensible: Boolean = false,
        extraChunks: Boolean = false,
    ) = file.writeBytes(sampleWav(formatTag, bits, channels, sampleRate, data, extensible, extraChunks))

    /** Little-endian bytes of 32-bit floats. */
    fun floats32(vararg values: Float): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putFloat(it) } }.array()

    /** Little-endian bytes of 64-bit floats. */
    fun floats64(vararg values: Double): ByteArray =
        ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putDouble(it) } }.array()

    /** A plain PCM WAV as [WavPcm16] writes it: its header fields and its 16-bit samples. */
    data class Pcm16(
        val formatTag: Int,
        val channels: Int,
        val sampleRate: Int,
        val bits: Int,
        val blockAlign: Int,
        val byteRate: Int,
        val samples: ShortArray,
    )

    fun readPcm16(file: File): Pcm16 {
        val bytes = file.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        check(String(bytes, 36, 4, Charsets.US_ASCII) == "data")
        val size = b.getInt(40)
        return Pcm16(
            formatTag = b.getShort(20).toInt() and 0xFFFF,
            channels = b.getShort(22).toInt(),
            sampleRate = b.getInt(24),
            bits = b.getShort(34).toInt(),
            blockAlign = b.getShort(32).toInt(),
            byteRate = b.getInt(28),
            samples = ShortArray(size / 2) { b.getShort(44 + it * 2) },
        )
    }

    private fun DataOutputStream.writeIntLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}
