package com.playfieldportal.studio

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Header-only audio fixtures for the media gates. `MediaDurationProbe` times a file from its
 * container header alone, so a fixture only needs a truthful header — [padTo] stretches the file
 * (sparse) when a test needs a byte count without writing the payload.
 */
object MediaTestFiles {

    /** 8 kHz mono 8-bit PCM WAV that claims [ms] of audio (byte rate 8000 → 8 bytes per ms). */
    fun writeWav(file: File, ms: Long, padTo: Long = 0L) {
        val dataBytes = ms * 8
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeBytes("RIFF"); writeIntLe((36 + dataBytes).toInt()); writeBytes("WAVE")
            writeBytes("fmt "); writeIntLe(16)
            writeShortLe(1); writeShortLe(1); writeIntLe(8000); writeIntLe(8000); writeShortLe(1); writeShortLe(8)
            writeBytes("data"); writeIntLe(dataBytes.toInt())
        }
        file.writeBytes(out.toByteArray())
        if (padTo > file.length()) RandomAccessFile(file, "rw").use { it.setLength(padTo) }
    }

    /** Minimal M4A: `ftyp` + `moov{mvhd}` with a 1000 Hz timescale claiming [ms]. */
    fun writeM4a(file: File, ms: Long) {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(16); writeBytes("ftyp"); writeBytes("M4A "); writeInt(0)
            val mvhdPayload = 100
            writeInt(8 + 8 + mvhdPayload); writeBytes("moov")
            writeInt(8 + mvhdPayload); writeBytes("mvhd")
            writeInt(0)            // version + flags
            writeInt(0); writeInt(0) // ctime, mtime
            writeInt(1000)         // timescale
            writeInt(ms.toInt())   // duration
            write(ByteArray(mvhdPayload - 20))
        }
        file.writeBytes(out.toByteArray())
    }

    private fun DataOutputStream.writeIntLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}
