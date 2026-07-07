package com.playfieldportal.studio.io

import java.io.File
import java.io.InputStream

/**
 * Bounded reads for untrusted files. theme-kit's parsers cap what they *decode* (zip
 * entries, zlib inflation, BMP dimensions); this caps what we're willing to hold in
 * memory at all, so a mispicked multi-GB file fails fast instead of OOMing the app.
 */
object SafeIo {

    /** Theme files are a few MB; 64 MB is generous headroom, not a target. */
    const val MAX_THEME_FILE_BYTES = 64L * 1024 * 1024

    /**
     * Reads [file] fully, or null when it exceeds [cap] — checked up front via length
     * AND enforced while streaming (file length can change or lie under us).
     */
    fun readBytesCapped(file: File, cap: Long = MAX_THEME_FILE_BYTES): ByteArray? {
        if (!file.isFile || file.length() > cap) return null
        return file.inputStream().use { it.readCapped(cap) }
    }

    /** Reads [this] fully, or null once more than [cap] bytes arrive. */
    fun InputStream.readCapped(cap: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
