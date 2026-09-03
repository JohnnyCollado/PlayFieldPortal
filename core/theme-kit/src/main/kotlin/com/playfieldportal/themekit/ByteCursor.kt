package com.playfieldportal.themekit

/**
 * Bounds-checked little-endian reads over an untrusted byte array.
 *
 * The parsers in this module read PSP theme containers, which are attacker-supplied files chosen
 * through SAF. They used to read through private `ByteArray.u16/u32` helpers that indexed the array
 * directly and returned a *signed* `Int`. That made safety a convention rather than a property:
 * `0xFFFFFFFF` decodes to `-1`, so a guard like `if (ptr + 12 > bytes.size) break` passes at `11`
 * and the next read throws `ArrayIndexOutOfBoundsException`. Most call sites remembered to reject
 * negatives. Two did not.
 *
 * Every accessor here returns null instead of throwing, and [u32At] returns a `Long` so a 32-bit
 * unsigned value simply cannot come back negative. A malformed pointer becomes a null the caller
 * has to handle, rather than a crash it has to remember to prevent.
 */
@JvmInline
value class ByteCursor(private val bytes: ByteArray) {

    val size: Int get() = bytes.size

    /** True when [offset] is a usable index into this array. */
    fun holds(offset: Int, length: Int): Boolean =
        offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size

    fun u8At(offset: Int): Int? =
        if (holds(offset, 1)) bytes[offset].toInt() and 0xFF else null

    fun u16At(offset: Int): Int? {
        if (!holds(offset, 2)) return null
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    /**
     * Unsigned 32-bit little-endian. Returns `Long` deliberately: the whole class of defect this
     * type exists for came from squeezing a u32 into a signed `Int`.
     */
    fun u32At(offset: Int): Long? {
        if (!holds(offset, 4)) return null
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    /**
     * Signed 32-bit little-endian, for fields that genuinely are signed (GIM chunk sizes).
     */
    fun i32At(offset: Int): Int? = u32At(offset)?.toInt()

    /**
     * Reads a u32 at [offset] and returns it as an index only if it addresses at least [needs]
     * bytes of this array. This is the operation the resource-table loop actually wanted: "give me
     * a pointer I can safely read a descriptor from".
     */
    fun pointerAt(offset: Int, needs: Int): Int? {
        val raw = u32At(offset) ?: return null
        if (raw <= 0 || raw + needs > bytes.size) return null
        return raw.toInt()
    }

    /** A copy of [length] bytes at [offset], or null if that range is not fully present. */
    fun sliceAt(offset: Int, length: Int): ByteArray? {
        if (!holds(offset, length)) return null
        return bytes.copyOfRange(offset, offset + length)
    }

    /** NUL-terminated ASCII, clamped to what is actually present. Empty rather than null. */
    fun asciiAt(offset: Int, maxLength: Int): String {
        if (offset < 0 || offset >= bytes.size) return ""
        val end = (offset.toLong() + maxLength).coerceAtMost(bytes.size.toLong()).toInt()
        val nul = (offset until end).firstOrNull { bytes[it] == 0.toByte() } ?: end
        return String(bytes, offset, nul - offset, Charsets.ISO_8859_1).trim()
    }

    /** The backing array, for the decoders that still need bulk access. */
    fun raw(): ByteArray = bytes
}

/** Reads [this] through bounds-checked accessors. */
internal fun ByteArray.cursor(): ByteCursor = ByteCursor(this)
