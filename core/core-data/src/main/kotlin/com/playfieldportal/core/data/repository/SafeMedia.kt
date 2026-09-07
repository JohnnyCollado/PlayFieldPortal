package com.playfieldportal.core.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import java.io.OutputStream

/**
 * Bounded reads/decodes for untrusted theme media (SAF picks, extracted bundle entries).
 * theme-kit's parsers cap what they decode; this caps what we hold in memory at all, and
 * keeps BitmapFactory from allocating pixel buffers for crafted headers claiming absurd
 * dimensions.
 */
object SafeMedia {

    /** Theme files/bundles are a few MB; 64 MB is generous headroom, not a target. */
    const val MAX_THEME_FILE_BYTES = 64L * 1024 * 1024

    /** Matches theme-kit Bmp.kt's dimension cap. */
    const val MAX_IMAGE_DIMENSION = 8192

    /**
     * Streams [this] to [out], or returns null once more than [cap] bytes arrive.
     *
     * The counterpart to [readCapped] for content that has no business being on the heap. A
     * theme bundle carrying a motion wallpaper is routinely 50 MB; [readCapped] would hold that
     * plus the doubling buffer that inflated it, against a 256 MB heap shared with a live UI.
     * Callers that only need the bytes to land somewhere should land them here instead.
     */
    fun InputStream.copyCappedTo(out: OutputStream, cap: Long = MAX_THEME_FILE_BYTES): Long? {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buffer, 0, n)
        }
        return total
    }

    /** Reads [this] fully, or null once more than [cap] bytes arrive. */
    fun InputStream.readCapped(cap: Long = MAX_THEME_FILE_BYTES): ByteArray? {
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

    /**
     * Decodes an image byte array with an `inJustDecodeBounds` pre-pass: headers claiming
     * dimensions past [maxDimension] never reach a pixel allocation; anything above
     * [targetDimension] decodes sampled down near it.
     */
    fun decodeBitmapCapped(
        bytes: ByteArray,
        maxDimension: Int = MAX_IMAGE_DIMENSION,
        targetDimension: Int = 1920,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w > maxDimension || h > maxDimension) return null

        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= targetDimension) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    /** [decodeBitmapCapped] for a file path (extracted theme icons and the like). */
    fun decodeFileCapped(path: String, maxDimension: Int, targetDimension: Int = maxDimension): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w > maxDimension || h > maxDimension) return null
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= targetDimension) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
    }
}
